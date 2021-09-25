/**
 *  Venstar ColorTouch Thermostat Driver for Hubitat Elevation
 *
 *  (C) 2021 Patrick H. Rigney (toggledbits), All Rights Reserved
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a
 *  copy of this software and associated documentation files (the “Software”),
 *  to deal in the Software without restriction, including without limitation
 *  the rights to use, copy, modify, merge, publish, distribute, sublicense,
 *  and/or sell copies of the Software, and to permit persons to whom the
 *  Software is furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 *  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 *  IN THE SOFTWARE.
 *
 *  Note: this is my first project in Groovy. If I've done some things "the long
 *  way" or "the wrong way," I'd appreciate a tip, but don't judge me. :o)
 *
 *  Humidity is supported (with humidification and dehumidification setpoints)
 *  for thermostats that have it (e.g. T7900). Currently, there is no deadband
 *  enforced between the settings, so the thermostat will decide what to do with
 *  whatever values you send it.
 *
 *  TO-DO: /query/sensors, /query/runtimes
 *
 *  Revision History
 *  Stamp By           Description
 *  ----- ------------ ---------------------------------------------------------
 *  21265 toggledbits  Rebirth.
 *
 */

metadata {
    definition (name: "Venstar ColorTouch Thermostat", namespace: "toggledbits.com", author: "Patrick Rigney", filename: "toggledbits-venstar-colortouch") {
        capability "Refresh"
        capability "Thermostat"
        capability "Sensor"
        capability "Actuator"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "PresenceSensor"

        attribute "name", "string"
        attribute "online", "boolean"
        attribute "override", "enum", [ "off", "on", "unknown" ]
        attribute "schedule", "enum", [ "off", "on", "unknown" ]
        attribute "schedulePeriod", "enum", [ "morning", "day", "evening", "night", "n/a", "unknown" ]
        attribute "thermostatFanOperatingState", "enum", [ "off", "on" ]
        attribute "humidificationSetpoint", "number"
        attribute "dehumidifcationSetpoint", "number"
        attribute "lastUpdate", "date"

        command "home"
        command "away"
        command "setHumidificationSetpoint", [[name:"setpoint", type:"NUMBER", description:"New humidification setpoint"]]
        command "setDehumidificationSetpoint", [[ name: "setpoint", type: "NUMBER" ]]
        command "setProgram", [[ name: "action", type: "ENUM", constraints: ["run","stop"] ]]
        command "programRun"
        command "programStop"
        command "setPollingInterval", [[ name: "interval", type: "NUMBER" ]]
    }

    preferences {
        section {
            input (
                type: "enum",
                name: "requestProto",
                title: "Local API Protocol",
                options: [ "http", "https" ],
                required: true,
                defaultValue: "http"
            )
            input (
                type: "string",
                name: "thermostatIp",
                title: "Thermostat IP Address",
                required: true
            )
            input (
                type: "string",
                name: "authUser",
                title: "Basic Auth User",
                required: false
            )
            input (
                type: "string",
                name: "authPass",
                title: "Basic Auth Password",
                required: false
            )
            input (
                type: "number",
                name: "pollInterval",
                title: "Polling Interval",
                description: "Interval, in seconds, between queries to the thermostate for update (0=no poll/disable)",
                range: "-1..86400",
                required: false,
                defaultValue: 60
            )
            input (
                type: "bool",
                name: "enableDebugLogging",
                title: "Enable debug logging",
                required: true,
                defaultValue: true
            )
        }
    }
}

private def D( msg ) {
    if ( enableDebugLogging ) {
        log.debug "${device.displayName}: ${msg}"
    }
}

private def W( msg ) {
    log.warn "${device.displayName}: ${msg}"
}

private def E( msg ) {
    log.error "${device.displayName}: ${msg}"
}

def installed() {
    D( "installed()" )
    state.pollInterval = 60
    initialize()
}

def initialize() {
    D( "initialize()" )
    unschedule()
    state.version = 21265
    state.failCount = 0
    state.lastpoll = 0
    updated()
}

def reinitialize() {
    D("reinitialize()")
    initialize()
}

def configure() {
    D("configure()")
    updated()
}

def updated() {
    D( "updated()" )

    unschedule()

    state.pollInterval = pollInterval
    state.lastpoll = 0

    _request( [ uri: "${requestProto}://${thermostatIp}/", contentType: "application/json", timeout: 15 ], { r->handleBasicResponse(r) } )
    
    updateChanged( "supportedThermostatModes",
                  coalesce( state.availablemodes ?: 0, { val->[
                      [ "off","heat","cool","auto" ], /* 0 */
                      [ "off","heat","cool" ],        /* 1 */
                      [ "off","heat" ],               /* 2 */
                      [ "off","cool" ]                /* 3 */
                  ][val] } ), "Supported operating modes have changed" )
    updateChanged( "supportedThermostatFanModes", [ "auto", "on" ], "Supported fan modes have changed" )

    refresh()
}

def refresh() {
    D( "refresh()" )

	state.driver_version = 21267
    state.driver_name = "Venstar ColorTouch Thermostat for Hubitat; https://"

    if ( "" != thermostatIp ) {
        /* Schedule next query immediately */
        unschedule()
        runIn( state.pollInterval, refresh )
        D("refresh() armed for next poll in ${state.pollInterval} secs")

        def req = [
            uri: "${requestProto}://${thermostatIp}/query/info",
            contentType: "application/json",
            timeout: 10
        ]
        _request( req, { r->httpGetCallback(r) } )
    }
}

/*
{
    "name": "Thermostat",
    "mode": 1,
    "state": 0,
    "fan": 0,
    "fanstate": 0,
    "tempunits": 0,
    "schedulepart": 0,
    "away": 0,
    "holiday": 0,
    "override": 0,
    "overridetime": 0,
    "forceunocc": 0,
    "spacetemp": 71,
    "heattemp": 63,
    "cooltemp": 66,
    "cooltempmin": 65,
    "cooltempmax": 99,
    "heattempmin": 35,
    "heattempmax": 80,
    "setpointdelta": 2,
    "hum": 43,
    "hum_setpoint": 45,
    "dehum_setpoint": 60,
    "availablemodes": 0
}
*/

private def coalesce( v, cl, df=null ) {
    if ( v != null ) {
        v = cl.call( v )
    }
    return ( v == null && df != null ) ? df : v
}

private def updateChanged( key, val, desctext=null, unit=null ) {
    def oldval = device.currentValue( key )
    // D("Current value of ${key} is (${oldval==null?"null":oldval.class})${oldval} new is (${val==null?"null":val.class})${val}")
    if ( ! ( val instanceof Number || val instanceof String ) ) {
        val = val.toString();
    }
    D("key ${key} oldval=${oldval} val=${val}")
    if ( oldval != val ) {
        sendEvent( name: key, value: val, unit: unit, linkText: device.displayName, descriptionText: desctext )
        if ( desctext != null && "" != desctext ) {
            log.info( "${device.displayName}: ${desctext}" );
        }
        return true
    }
    return false
}

private def handleBasicResponse( response ) {
	D("handleBasicResponse(${response.class})")
    D("response status ${response.getStatus()}")
	if ( 200 == response.getStatus() ) {
		def data = response.getData()
        D("data is ${data}")
		state.api_ver = data.api_ver
		state.type = data.type
		state.model = data.model
		state.firmware = data.firmware
	}
}

private def httpGetCallback(response) {
    D("httpGetcallback(${response.class})")
    if (response == null) {
        return
    }

    // D("httpGetCallback(response) status ${response.getStatus()} headers ${response.getHeaders()}")

    state.lastpoll = now()

    if ( 200 == response.getStatus() ) {
        def scale = getTemperatureScale()
        def data = response.getData()      /* or maybe parseJson( response.data ) ? */

        // D("state is ${state}")
        D("data is ${data}")

        state.failCount = 0
        state.mode = data.mode
        state.fan = data.fan
        state.tempunits = data.tempunits
        state.spacetemp = data.spacetemp
        state.heattemp = data.heattemp
        state.heattempmin = data.heattempmin
        state.heattempmax = data.heattempmax
        state.cooltemp = data.cooltemp
        state.cooltempmin = data.cooltempmin
        state.cooltempmax = data.cooltempmax
        state.setpointdelta = data.setpointdelta
        state.hum = data.hum
        state.hum_setpoint = data.hum_setpoint
        state.dehum_setpoint = data.dehum_setpoint
        state.lastupdate = new Date()
        state.lastdata = data

        updateChanged( "online", true, "Thermostat is now ONLINE" )
        updateChanged( "name", data.name, "Thermostat name changed to ${data.name}" )

        def t = ['off','heat','cool','auto'][ data.mode ]
        updateChanged( "thermostatMode", t, "Thermostat mode changed to ${t}" )

        /* We add our own HE operating state "fan only" when thermostat is idle but fan is on */
        t = ['idle','heating','cooling'][ data.state ]
        if ( t == "idle" && data.fanstate != 0 ) {
            t = "fan only"
        }
        updateChanged( "thermostatOperatingState", t, "Thermostat state now ${t}" )

        t = [ 'auto', 'on' ][ data.fan ]
        updateChanged( "thermostatFanMode", t, "Thermostat fan mode is now ${t}" )
        if ( data.fanstate != 0 ) {
            updateChanged( "thermostatFanOperatingState", 'on', "Fan is now RUNNING" )
        } else {
            updateChanged( "thermostatFanOperatingState", 'off', "Fan is now STOPPED" )
        }

        /* Convert thermostat's temperature units to hub's units and store */
        if ( scale == "F" && data.tempunits == 1 ) {
            /* Thermostat is C and Hubitat is F */
            data.spacetemp = coalesce( data.spacetemp, { temp->celsiusToFahrenheit( temp ) } )
            data.cooltemp = coalesce( data.cooltemp, { temp->celsiusToFahrenheit( temp ) } )
            data.heattemp = coalesce( data.heattemp, { temp->celsiusToFahrenheit( temp ) } )
        } else if ( scale == "C" && data.tempunits == 0 ) {
            data.spacetemp = coalesce( data.spacetemp, { temp->fahrenheitToCelsius( temp ) } )
            data.cooltemp = coalesce( data.cooltemp, { temp->fahrenheitToCelsius( temp ) } )
            data.heattemp = coalesce( data.heattemp, { temp->fahrenheitToCelsius( temp ) } )
        }
        updateChanged( "temperature", data.spacetemp, "Ambient is now ${data.spacetemp}", scale );
        updateChanged( "coolingSetpoint", data.cooltemp, "Cooling setpoint is now ${data.cooltemp}", scale );
        updateChanged( "heatingSetpoint", data.heattemp, "Heating setpoint is now ${data.heattemp}", scale );

        /* Generic setpoint. If thermostat mode is heating, or currently in heating state, set to heating setpoint.
         * Apply same logic for cooling.
         */
        if ( data.mode == 1 || data.state == 1 ) {
            updateChanged( "thermostatSetpoint", data.heattemp, "Setpoint is now ${data.heattemp}", scale );
        } else if ( data.mode == 2 || data.state == 2 ) {
            updateChanged( "thermostatSetpoint", data.cooltemp, "Setpoint is now ${data.cooltemp}", scale );
        }

        /* T7900 supports humidity */
        if ( null != data.hum ) {
            updateChanged( "humidity", data.hum, "Ambient relative humidity is now ${data.hum}", "%" );
            updateChanged( "humidificationSetpoint", data.hum_setpoint, "Humidification setpoint is now ${data.hum_setpoint}", "%" );
            updateChanged( "dehumidifcationSetpoint", data.dehum_setpoint, "Dehumidification setpoint is now ${data.dehum_setpoint}", "%" );
        }

        t = coalesce( data.schedule, { val->['stopped','running'][val] }, 'unknown' )
        updateChanged( "program", t, "Program is now ${t}" )
        t = coalesce( data.schedulepart, { val->val==255?'n/a':(['morning','day','evening','night'][val]) }, 'unknown' );
        updateChanged( "schedulePeriod", t, "Schedule period is now ${t}" )

        t = coalesce( data.away, { val->['present','not present'][val] }, 'unknown' )
        updateChanged( "presence", t, "Thermostat now in ${t == 'unknown' ? t : ['HOME','AWAY'][data.away]} mode (presence)" );

		if ( state.type != 'residential' ) {
			t = coalesce( data.override, { val->['off','on'][val] }, 'unknown' )
			updateChanged( "override", t, "Override now ${t}" );
		}

        sendEvent( name: "lastUpdate", value: state.lastupdate )
    } else {
        E( "failed ${requestProto}://${thermostatIp}: ${response.getStatus()}, ${response.getErrorMessage()}}" )
        if ( ++state.failCount >= 3 ) {
            updateChanged( "online", false, "Thermostat is now OFF-LINE" )
        }
    }
}

private def basicAuth() {
    /* RFC7617: user:pass -- Aladdin:open sesame --> QWxhZGRpbjpvcGVuIHNlc2FtZQ== */
    return "Basic ${authUser}:${authPass}".bytes.encodeBase64().toString()
}

private def digestAuth( path, authReq, method="GET" ) {
    /* Algorithm determined by server; defaults to MD5 */
    def algMap = [ 'MD5':'MD5', 'SHA-256':'SHA-256' ] /* supported algorithm and java..MessageDigest equivalent name */
    def respalg = ( authReq?.algorithm ?: "MD5" ).trim().toUpperCase()
    if ( algMap[ respalg ] == null ) {
        respalg = 'MD5' /* fallback */
    }
    def H = { s->java.security.MessageDigest.getInstance(algMap[respalg]).digest(s.getBytes("UTF-8")).encodeHex().toString() }
    def HA1 = H("${authUser.trim()}:${authReq.realm}:${authPass}")
    def HA2 = H("${method.toUpperCase()}:${path}")
	def cnonce = java.util.UUID.randomUUID().toString().replaceAll('-', '').substring(0, 8)
    /* Reset NC per nonce. In a perfect world, we'd store the original www-authenticate response values and just build a new
     * header for each subsequent request, until we get a 401 from the server, when we toss it and start over with the new
     * data the server provides and that response. That would eliminate the double-querying that is going on now for every
     * request. But, I haven't figure out how HE does persistent storage for that kind of thing that isn't in the 'state'
     * structure, which is kind of public, so not a great choice for storage of this data. 
     */
    if ( state.nonce == authReq.nonce ) {
        state.nc = state.nc + 1
    } else {
        state.nc = 1
        state.nonce = authReq.nonce
    }
    def response = H("${HA1}:${authReq.nonce}:${state.nc}:${cnonce}:${authReq.qop}:${HA2}")
    return "Digest username=\"${authUser.trim()}\", realm=\"${authReq.realm}\", qop=\"${authReq.qop}\", algorithm=\"${respalg}\"" +
        ", uri=\"${path}\", nonce=\"${authReq.nonce}\", cnonce=\"${cnonce}\", opaque=\"${authReq.opaque == null ? "" : authReq.opaque}\"" +
        ", nc=${state.nc}, response=\"${response}\""
}

private def _request( rp, callback ) {
    D("_request(${rp}, callback")
    def tries = 0
    while ( tries++ < 3 ) {
        try {
            D("_request() sending request")
            if ( "https" == requestProto ) {
                rp.ignoreSSLIssues = true
            }
            httpGet( rp ) { resp->callback.call( resp ) }
            return
        } catch ( groovyx.net.http.HttpResponseException e ) {
            D("exception caught ${e.class} ${e}")
            if ( e.getResponse().getStatus() == 401 && "" != authUser ) {
                if ( rp.headers?.Authorization != null ) {
                    D("Attempted auth failed with ${rp.headers.Authorization}")
                    state.nc = 0
                    callback.call( e.getResponse() );
                    return;
                }
                /* Do auth if we can */
                def authstring = e.getResponse().headers.'www-authenticate'
                // ??? What if multiple www-authenticate headers are returned? We need to pick a supported algorithm...
                def ah
                if ( authstring.startsWith( 'Digest ' ) ) {
                    def authmap = authstring.replaceAll("Digest ", "").replaceAll(", ", ",").findAll(/([^,=]+)=([^,]+)/) { full, name, value -> [name, value.replaceAll("\"", "")] }.collectEntries( { it })
                    ah = digestAuth( new java.net.URI( rp.uri ).getPath(), authmap );
                } else {
                    ah = basicAuth();
                }
                if ( rp.headers == null ) {
                    rp.headers = [ 'Authorization': ah ]
                } else {
                    rp.headers.Authorization = ah
                }
                D("_request resubmitting with ${rp.headers}")
                madeWithAuth = true;
            } else {
                callback.call( e.getResponse() );
            }
        }
    }
    D("_request bailing out")
}

private def postCallback( resp ) {
    // def result = resp.data
    D("command response ${resp.getStatus()}: ${resp.getData()}")
    unschedule();
    runInMillis( 2000, refresh )
}

private def sendCommand( command, reqparams ) {
    D("sendCommand(${command},${reqparams.class} ${reqparams})")
    def params = [
        uri: "${requestProto}://${thermostatIp}/${command}",
        contentType: "application/json",
        // requestContentType: "application/x-www-form-urlencoded",
        query: reqparams,
        timeout: 15
    ]
    D("sending request to ${params.uri}?${reqparams}")
    _request( params, { r->postCallback(r) } )
}

private def control() {
    /** mode=%s&heattemp=%.1f&cooltemp=%.1f
     *  mode=0-3 for off, heat, cool, auto
     *  units in temp units of thermostat (ref state.tempunits)
     */
    sendCommand( "control", [ mode: state.mode, heattemp: state.heattemp, cooltemp: state.cooltemp ] )
}

private def set_humidity_setpoints() {
    def params = [ hum_setpoint: state.hum_setpoint, dehum_setpoint: state.dehum_setpoint ]
    sendCommand( 'settings', params )
}

def auto() {
    state.mode = 3
    control()
}

def cool() {
    state.mode = 2
    control()
}

def emergencyHeat() {
    W("mode 'emergency heat' is not supported; setting mode to HEAT")
    state.mode = 1
    control()
}

def fanAuto() {
    sendCommand( 'control', [ fan: 0 ] )
}

def fanCirculate() {
    W("fan 'circulate' mode is not supported; setting fan mode to AUTO")
    fanAuto()
}

def fanOn() {
    sendCommand( 'control', [ fan: 1 ] )
}

def heat() {
    state.mode = 1
    control()
}

def off() {
    state.mode = 0
    control()
}

def setCoolingSetpoint( temperature ) {
    def ttemp = temperature
    def scale = getTemperatureScale()
    D("setCoolingSetpoint(${temperature}): scale=${scale}, tempunits=${state.tempunits}")
    if ( scale == "C" && state.tempunits == 0 ) {
        ttemp = celsiusToFahrenheit( ttemp )
    } else if ( scale == "F" && state.tempunits == 1 ) {
        ttemp = fahrenheitToCelsius( ttemp )
    }
    if ( ttemp < state.cooltempmin ) {
        W( "requested cooling setpoint ${ttemp} is below minumum (${state.cooltempmin}); limiting to minimum" )
        ttemp = state.cooltempmin
    } else if ( ttemp > state.cooltempmax ) {
        W( "requested cooling setpoint ${ttemp} exceeds maximum (${state.cooltempmax}); limiting to maximum" )
        ttemp = state.cooltempmax
    }
    if ( state.heattemp > ( ttemp - state.setpointdelta ) ) {
        /* Move heat setp down to honor differential; if we bump heat min, everything has to move up */
        state.heattemp = ttemp - state.setpointdelta
        if ( state.heattemp < state.heattempmin ) {
            state.heattemp = state.heattempmin
            state.cooltemp = state.heattempmin + state.setpointdelta
            W( "adjusting heating and cooling setpoints to honor limits and differential; cooling now ${state.cooltemp}, heating ${state.heattemp}" )
        } else {
            W( "adjusting heating setpoint to ${state.heattemp} to preserve differential (${state.setpointdelta})" )
        }
    }
    state.cooltemp = ttemp
    control()
}

def setHeatingSetpoint( temperature ) {
    def ttemp = temperature
    def scale = getTemperatureScale()
    D("setHeatingSetpoint(${temperature}): scale=${scale}, tempunits=${state.tempunits}")
    if ( scale == "C" && state.tempunits == 0 ) {
        ttemp = celsiusToFahrenheit( ttemp )
    } else if ( scale == "F" && state.tempunits == 1 ) {
        ttemp = fahrenheitToCelsius( ttemp )
    }
    if ( ttemp < state.heattempmin ) {
        W( "requested heating setpoint ${ttemp} is below minumum (${state.heattempmin}); limiting to minimum" )
        ttemp = state.heattempmin
    } else if ( ttemp > state.heattempmax ) {
        W( "requested heating setpoint ${ttemp} exceeds maximum (${state.heattempmax}); limiting to maximum" )
        ttemp = state.heattempmax
    }
    if ( state.cooltemp < ( ttemp + state.setpointdelta ) ) {
        /* Move cool setp up to honor differential; if we bump cool max, everything has to move down */
        state.cooltemp = ttemp + state.setpointdelta
        if ( state.cooltemp > state.cooltempmax ) {
            state.cooltemp = state.cooltempmax
            state.heattemp = state.cooltempmax - state.setpointdelta
            W( "adjusting heating and cooling setpoints to honor limits and differential; heating now ${state.heattemp}, cooling ${state.cooltemp}" )
        } else {
            W( "adjusting cooling setpoint to ${state.cooltemp} to preserve differential (${state.setpointdelta})" )
        }
    }
    state.heattemp = ttemp
    control()
}

def setSchedule( sched ) {
    /* not implemented -- deprecated method */
}

def setThermostatFanMode( fanmode ) {
    D("setThermostatFanMode(${fanmode})")
    if ( fanmode == 'on' ) {
        state.fan = 1; /* on */
    } else if ( fanmode == 'auto' ) {
        state.fan = 0 /* auto */
    }
    sendCommand( 'control', [ fan: state.fan ] )
}

def setThermostatMode( mode ) {
    if ( mode == 'auto' ) {
        state.mode = 3
    } else if ( mode == 'cool' ) {
        state.mode = 2
    } else if ( mode == 'heat' ) {
        state.mode = 1
    } else if ( mode == 'off' ) {
        state.mode = 0
    }
    control()
}

def setPollingInterval( num ) {
    if ( num < 0 ) {
        num = pollInterval
    }
    if ( num >= 0 && num <= 86400 ) {
        unschedule()
        state.pollInterval = num;
        if ( num > 0 ) {
            def nextpoll = state.lastpoll + ( num * 1000 ) - ( new Date() ).getTime()
            if ( nextpoll < 10 ) {
                nextpoll = 10
            }
            D("nextpoll ${nextpoll}ms")
            runInMillis( nextpoll as Integer, refresh )
        }
    }
}

def home() {
    sendCommand( 'settings', [ away: 0 ] )
}

def away() {
    sendCommand( 'settings', [ away: 1 ] )
}

def setHumidificationSetpoint( rel ) {
    if ( state.hum_setpoint != null ) {
        /* ??? enforce deadband? */
        if ( rel >= 0 && rel < 100 ) {
            state.hum_setpoint = rel
            set_humidity_setpoints()
        }
    } else {
        D( "humidification setpoint is not supported by the thermostat" )
    }
}

def setDehumidificationSetpoint( rel ) {
    if ( state.dehum_setpoint != null ) {
        /* ??? enforce deadband? */
        if ( rel >= 0 && rel < 100 ) {
            state.dehum_setpoint = rel
            set_humidity_setpoints()
        }
    } else {
        D( "dehumidification setpoint is not supported by the thermostat" )
    }
}

def setProgram( action ) {
    sendCommand( 'settings', [ schedule: action == "run" ? 1 : 0 ] )
}

def programRun() {
    sendCommand( 'settings', [ schedule: 1 ] )
}

def programStop() {
    sendCommand( 'settings', [ schedule: 0 ] )
}