/**
 * HoneyWell Wireless Sensors Driver
 *
 * Driver polls a configured MQTT server and topic for JSON 
 * formatted Honeywell sensor messages recieved using an rtl_433
 * USB stick publishing to MQTT.
 *
 * Example receive and publish command line for 345mHz Honeywell / Ademco sensors:
 *  rtl_433 -f 344975000 -F json -M time:unix:utc mqtt://192.168.50.232:1883,user=USERNAME,pass=PASSWORD,retain=0,events=hubitat/honeywell/events
 *  rtl_433 -f 344975000 -F json -M time:unix:utc | mosquitto_pub -h MQTTbrokerHost -p 1883 -t -u USERNAME -P PASSWORD hubitat/honeywell/events -l -V mqttv311
 *
 *  NOTE: both rtl_433 and mosquitto_pub support putting 
 *  MQTT credentials in a restricted access config file. I 
 *  strongly encourage you to use that option to best 
 *  protect your user credentials.
 *
 * Events look like (in text JSON)
 *  {"time" : "1743220672", "model" : "Honeywell-Security", "id" : 980740, "channel" : 8, "event" : 128, "state" : "open", "contact_open" : 1, "reed_open" : 0, "alarm" : 0, "tamper" : 0, "battery_ok" : 1, "heartbeat" : 0, "mic" : "CRC"}
 *
 *  Note: state follows contact_open, which applies to sensors used 
 *  in physical contact mode (Loop 1). Sensors that are magnet 
 *  triggered are read with reed_open (Loop 2). You want one or the 
 *  other based on how the sensor is installed and used.
 *
 * Child devices are created based on the 'id' field contained 
 * in the rtl sensor JSON message. Child devces show current 
 * (last known) status only and cannot effect any change to the
 * sensor or monitored item.
 * 
 * The ID shown in the JSON message is the ID from the sticker on each 
 * sensor. Some sensors have an external sticker with the ID, all sensors 
 * have a sticker on the inside with the sensor ID. Sensor ID is the 
 * sticker ID striped of the leading letter (usually "A") and leading zeros.
 * 
 * Usefull links:
 *  https://github.com/securityguy/Honeywell5800
 *  https://www.advancedsecuritysolution.com/manuals/5816-Window-Door-Sensors.pdf
 *  https://triq.org/rtl_433/STARTING.html
 *  https://github.com/merbanan/rtl_433/blob/master/conf/rtl_433.example.conf
 * 
 * MIT License
 *
 * Copyright (c) 2025 gatewoodgreen@gmail.com
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String VERSION   = "202504030730"
@Field static final String DRIVER    = "HoneyWell Wireless Sensors Driver"
@Field static final String COMM_LINK = "https://github.com/sonoranwanderer/hubitat-honeywell"

public static String version()   { return "v${VERSION}" }

metadata {
    definition(
     	name: "HoneyWell Wireless Sensors Driver",
       	namespace: "sonoranwanderer",
       	author: "Gatewood Green",
       	description: "Monitor Honeywell and Ademco 345mHz wirless sensors as devices in Hubitat",
    ) {
        capability "Initialize" 
        
        // Provided for broker setup and troubleshooting
        command "connect",            [ [ name:"Connect to MQTT and subscribe to the event topic." ] ]
        command "disconnect",         [ [ name:"Disconnect from MQTT." ] ]
        command "subscribe",          [ [ name:"topic*", type:"STRING", description:"For debug use only<br>Overrides topic set on Preferences tab.<br>Use Connect to subscribe to the Preferences topic.<br>Topic" ] ]
        command "unsubscribe",        [ [ name:"topic*", type:"STRING", description:"For debug use only<br>Topic" ] ]
        command "publish",            [ [ name:"topic*", type:"STRING", title:"test", description:"For debug use only<br>Topic" ], [ name:"message", type:"STRING", description:"Message" ] ]
        command "wipeStateData",      [ [ name:"Clear the driver's parameters and state along with any stored data. Resets the Honeywell driver. Does not remove child devices." ] ]
        command "wipeChildStateData", [ [ name:"Clear Child device's (select a child device on the Preference Tab first) parameters and state along with any stored data. Does not remove the child device." ] ]
        command "addSensorDevice",    [
            [ name:"Create device with user supplied sensor ID" ],
            [ name:"sensor*", type: "STRING", description:"Sensor ID" ], 
            [ name:"devType*", type:"ENUM", description:"Device Type", constraints: [ "Virtual Contact Sensor", "Virtual Motion Sensor" ] ], 
            [ name:"devLable*", type: "STRING", description: "Device Label" ]
        ]
        command "addDiscoveredSensorDevice", [
            [ name:"Create Device from selected sensor ID in List of Known Sensors on the Preferences tab" ],
            [ name:"devType*", type:"ENUM", description:"Device Type", constraints: [ "Virtual Contact Sensor", "Virtual Motion Sensor" ] ], 
            [ name:"devLabel*", type: "STRING", description: "Device Label" ]
        ]
        command "deleteChildSensorDevice", [
            [ name:"Remove child device using selected device ID in List of Child Devices on the Preferences tab" ]
        ]
        command "setChildDeviceLoop", [
            [ name:"Set loop for child device using selected device ID in List of Child Devices on the Preferences tab" ],
            [ name:"devType*", type:"ENUM", description:"Device Type", constraints: [ "Loop 1 / Contact", "Loop 2 / Reed", "Loop 3 / Alarm" ] ]
        ]

        preferences {
            input (
                name: "helpInfo", 
                type: "hidden", 
                title: fmtHelpInfo("")
            )
            input(
                name: "brokerIp",
                type: "string",
                title: "MQTT Broker IP Address",
                description: "e.g. 192.168.1.234",
                required: true,
                displayDuringSetup: true
            )
            input(
                name: "brokerPort",
                type: "string",
                title: "MQTT Broker Port",
                description: "e.g. 1883",
                required: true,
                displayDuringSetup: true
            )
            input(
                name: "brokerUser",
                type: "string",
                title: "MQTT Broker Username",
                description: "e.g. mqtt_user",
                required: false,
                displayDuringSetup: true
            )
            input(
                name: "brokerPassword",
                type: "password",
                title: "MQTT Broker Password",
                description: "e.g. %dgdrT4J8f$d60",
                required: false,
                displayDuringSetup: true
            )
            input(
                name: "brokerTopic",
                type: "string",
                title: "MQTT Broker Topic",
                description: "e.g. hubitat/honeywell/events",
                required: true,
                displayDuringSetup: true
            )
            input (
                name: "loadKnownSensors", 
                type: "bool", 
                title: "Load Known Sensors", 
                defaultValue: false
            )
            input (
                name: "loadChildDevices", 
                type: "bool", 
                title: "Load Child Devices", 
                defaultValue: false
            )
            input (
                name: "logLevel",
                type: "enum",
                title: "Logging Level", 
                defaultValue: 3, 
                options: [3: "info", 2:"debug", 1:"trace"], 
                required: true
            )
            if ( loadKnownSensors ) {
                knownSensors = device.getDataValue( "knownSensors" )
                def slurper = new JsonSlurper()
	            def parsedSensors = slurper.parseText( knownSensors )
                def Map myOptions = [ "nosensor": "Pick Sensor" ]
                parsedSensors.each { key, value ->
                    myOptions[key] = key
                }
                input (
                    name: "createChild", 
                    type: "enum", 
                    options: myOptions, 
                    defaultValue: "nosensor",
                    title: "List of known sensor IDs that can be added as a child device."
                )
            }
            if ( loadChildDevices ) {
                childDevices = getChildDevices()
                def Map myOptions = [ "nodevice": "Pick Child Device" ]
                for ( child in childDevices ) {
                    myOptions[ child.deviceNetworkId ] = "${child.deviceNetworkId} ${child.properties.label}"
                }
                input (
                    name: "manageChild", 
                    type: "enum", 
                    options: myOptions, 
                    defaultValue: "nodevice",
                    title: "List of child devices that can be managed."
                )
            }
        }
    }
}

void logEvent ( message="", level="info" ) {
    Map     logLevels = [ error: 5, warn: 4, info: 3, debug: 2, trace: 1 ]
    Integer msgLevelN = logLevels[ level ].toInteger()
    String  name      = device.displayName.toString()
    Integer logLevelN = 0

    if ( device.getSetting( "logLevel" ) == null ) {
        device.updateSetting( "logLevel", "3" ) /* Send string to imitate what the preference dialog will do for enum numeric keys */
        log.info "${name}: logEvent(): set default log level to 3 (info)"
        logLevelN = 3
    } else {
        logLevelN = device.getSetting( "logLevel" ).toInteger()
        if ( logLevelN == null || logLevelN < 1 || logLevelN > 5 )
            logLevelN = 3 /* default to info on unexpected value */
    }
    if ( msgLevelN == null || msgLevelN < 1 || msgLevelN > 5 ) {
        msgLevelN = 3
        level     = "info"
    }
    if ( msgLevelN >= logLevelN )
        log."${level}" "${name}: ${message}"
}

String fmtHelpInfo( String title ) {
	String info = "${DRIVER}<br>Version: ${VERSION}".trim()
    String prefLink = "1"
    if ( title != "" ) {
	    prefLink = "<a href='${COMM_LINK}' target='_blank'>${title}<br><div style='font-size: 70%;'>${info}</div></a>"
    } else {
        prefLink = "<a href='${COMM_LINK}' target='_blank'><div style='font-size: 70%;'>${info}</div></a>"
    }
	return "<div style='font-size: 160%; font-style: bold; padding: 2px 0px; text-align: center;'>${prefLink}</div>"
}

void initialize() {
    logEvent( "initialize(): Initializing driver...", "debug" )
    
    try {   
        interfaces.mqtt.connect(getBrokerUri(),
                           "hubitat_${getHubId()}",
                           settings?.brokerUser,
                           settings?.brokerPassword,
                           lastWillTopic: "LWT",
                           lastWillQos: 0,
                           lastWillMessage: "offline",
                           lastWillRetain: true)
       
        // delay for connection
        pauseExecution(1000)
        
    } catch( Exception e ) {
        logEvent( "initialize(): error: ${e}", "error" )
        return
    }
    
    def topic = device.getSetting( "brokerTopic" )
    if ( topic != null && topic != "" ) {
        subscribe( topic )
    } else {
        logEvent( "initialize(): missing MQTT Topic to subscribe to for sensor events", "error" )
    }
}

void connect() {
    initialize()
    connected()
}

void disconnect() {
    try {
        interfaces.mqtt.disconnect()
        disconnected()
    } catch( e ) {
        logEvent( "disconnect(): Disconnection from broker failed, ${e.message}", "warn" )
        if ( interfaces.mqtt.isConnected() ) {
            connected()
        }
    }
}

def subscribe( topic ) {
    if ( notMqttConnected() ) {
        connect()
    }

    logEvent( "subscribe(): topic: ${topic}", "debug" )
    interfaces.mqtt.subscribe( "${topic}" )
}

def unsubscribe( topic ) {
    if ( notMqttConnected() ) {
        connect()
    }

    logEvent( "unsubscribe(): topic: ${topic}", "debug" )
    interfaces.mqtt.unsubscribe( "${topic}" )
}

void addDiscoveredSensorDevice( unused, devType, devLabel ) {
    def sensor = device.getSetting( "createChild" )

    if ( sensor == null || sensor.trim() == "" ) {
        logEvent( "addDiscoveredSensorDevice(): No child selected in Preferences in List of Known Sensors", "error" )
        return
    } else if ( sensor == "nosensor" ) {
        logEvent( "addDiscoveredSensorDevice(): \"nosensor\" selected in Preferences in List of Known Sensors", "error" )
    }

    if ( devLabel == null || devLabel.trim() == "" ) {
        logEvent( "addDiscoveredSensorDevice(): device creation failed. Device Label cannot be empty", "error" )
        return
    }

    logEvent( "addDiscoveredSensorDevice(): calling createChildDevice( '${sensor}', '${devType}', '${devLabel}' )...", "info" )
    createChildDevice( sensor, devType, devLabel )
    device.removeSetting( "createChild" )
    return
}

void addSensorDevice( unused, sensor, devType, devLabel ) {
    if ( sensor == null || sensor.trim() == "" ) {
        logEvent( "addSensorDevice(): device creation failed. Sensor ID cannot be empty", "error" )
        return
    } else if ( isNaN( sensor ) ) {
        logEvent( "addSensorDevice(): device creation failed. Sensor ID (${sensor}) must be a number", "error" )
        return
    } else if ( sensor < 1 || sensor > 9999999 ) {
        logEvent( "addSensorDevice(): device creation failed. Sensor ID (${sensor}) must be a number between 1 and 9999999 (inclusive)", "error" )
        return
    }

    if ( devLabel == null || devLabel.trim() == "" ) {
        logEvent( "addSensorDevice(): device creation failed. Device Label cannot be empty", "error" )
        return
    }
    
    logEvent( "addSensorDevice(): calling createChildDevice()...", "info" )
    createChildDevice( sensor, devType, devLabel )
    return
}

void createChildDevice( sensor, devType, devLabel ) {
    def devNetworkId = "Honeywell_${sensor}"
    def childDev
    def namespace = "hubitat"

    if ( devLabel == null ) {
        logEvent( "createChildDevice(): device creation failed. Device Label cannot be null", "error" )
        return
    } else if ( devLabel.trim() == "" ) {
        logEvent( "createChildDevice(): device creation failed. Device Label cannot be empty", "error" )
        return
    }
    
    if ( sensor == null || sensor.trim() == "" ) {
        logEvent( "createChildDevice(): No child selected in Preferences in List of Known Sensors", "warn" )
    } else if ( sensor == "nosensor" ) {
        logEvent( "createChildDevice(): \"nosensor\" selected in Preferences in List of Known Sensors", "warn" )
    } else {
        logEvent( "createChildDevice(): creating child (${sensor}) device.", "info" )
        try {
            childDev = addChildDevice( namespace, devType, devNetworkId, [ "name": devType, "label": devLabel, isComponent: false ] )
        } catch (error) {
            logEvent( "createChildDevice(): device creation for sensor ${sensor} failed. Error = ${error}", "error" )
            return
        }
        try { // update child Default Current State preference
            if ( devType.indexOf ( "Contact" > 0 ) ) {
                // Default contact sensors to loop 2 (reed)
                childDev.updateDataValue( "loop", 2 )
                // childDev.updateSetting( name: "defaultCurrentState", value: "contact" )
            } else {
                // Default all other sensors to loop 1 (contact)
                childDev.updateDataValue( "loop", 1 )
                // childDev.updateSetting( name: "defaultCurrentState", value: "motion" )
            }
        } catch ( error ) {
            logEvent( "createChildDevice(): setting defaultCurrentState for ${sensor} failed. Error = ${error}", "error" )
            // not fatal, carry on
        }
        logEvent( "createChildDevice(): device creation for sensor ${sensor} succeeded.", "info" )
    }

    def slurper = new JsonSlurper()
    registeredSensors = device.getDataValue( "registeredSensors" )
    logEvent( "createChildDevice(): Loaded registeredSensors data: '${registeredSensors}'", "trace" )
    def Map parsedSensors
    if ( registeredSensors != null && registeredSensors != "" ) {
        logEvent( "createChildDevice(): registeredSensors is not null or empty: '${registeredSensors}'", "trace" )
        registeredSensors.trim()
	    parsedSensors = slurper.parseText( registeredSensors )
        logEvent( "createChildDevice(): Got parsedSensors from knownSensors: '${parsedSensors}'", "trace" )
    }

    deviceId = slurper.parseText( "{\"deviceNetworkId\":\"${devNetworkId}\"}" )
    if ( registeredSensors == null ) {
        parsedSensors = [:]
        parsedSensors[sensor] = deviceId
        logEvent( "createChildDevice(): registeredSensors was null, adding first entry: '${parsedSensors}'", "debug" )
    } else if ( parsedSensors.containsKey( sensor.toString() ) ) {
        logEvent( "createChildDevice(): already have sensor data for ${sensor}", "trace" )
        return
    } else {
        parsedSensors[sensor] = deviceId
        logEvent( "createChildDevice(): found new sensor: '${parsedSensors}'", "debug" )
    }

    logEvent( "createChildDevice(): storing to registeredSensors from parsedSensors (${parsedSensors})", "trace" )
    def dumper = new JsonOutput()
    device.updateDataValue( "registeredSensors", dumper.toJson( parsedSensors ) )
    logEvent( "createChildDevice(): child device (${sensor}, ${devLabel}) creation complete", "info" )
    return
}

void deleteChildSensorDevice() {
    def devNetId = device.getSetting( "manageChild" )
    def devLabel = ""
    
    if ( devNetId == null || devNetId == "" || devNetId == "nodevice" ) {
        logEvent( "deleteChildSensorDevice(): Must select a device to remove in the List of Child Devices on the Preferences tab.", "warn" )
        return
    }

    logEvent( "deleteChildSensorDevice(): Removing child device: '${devNetId}'", "debug" )
    try {
        childDev = getChildDevice( devNetId )
        devLabel = childDev.properties.label
        deleteChildDevice( devNetId )
    } catch (error) {
        logEvent( "deleteChildSensorDevice(): device deletion for ${devNetId} failed. Error = ${error}", "error" )
        return
    }
    
    def slurper = new JsonSlurper()
    def registeredSensors = device.getDataValue( "registeredSensors" )
    def Map parsedSensors
    logEvent( "deleteChildSensorDevice(): Loaded registeredSensors data: '${registeredSensors}'", "trace" )
    if ( registeredSensors != null && registeredSensors != "" ) {
        logEvent( "deleteChildSensorDevice(): registeredSensors is not null or empty: '${registeredSensors}'", "trace" )
        registeredSensors.trim()
	    parsedSensors = slurper.parseText( registeredSensors )
        logEvent( "deleteChildSensorDevice(): Got parsedSensors from registeredSensors: '${parsedSensors}'", "trace" )
    } else {
        logEvent( "deleteChildSensorDevice(): registeredSensors was null or empty, nothing to do.", "warn" )
        return
    }

    def delKey = ""
    parsedSensors.each { key, val ->
        Integer index = val.indexOf( devNetId )
        if ( index > 0 ) {
            delKey = key
        }
    }
    if ( delKey == null || delKey =="" ) {
        logEvent( "deleteChildSensorDevice(): registeredSensors was was missing the selected sensor '${delKey}', nothing to do.", "warn" )
        return
    } else {
        parsedSensors.remove( delKey )
        device.updateDataValue( "registeredSensors", dumper.toJson( parsedSensors ) )
        logEvent( "deleteChildSensorDevice(): Removed ${delKey} from registeredSensors: '${parsedSensors}'", "trace" )
    }
    
    logEvent( "deleteChildSensorDevice(): Removing child device: ${devNetId}, ${devLabel} completed.", "info" )
    return
}

void setChildDeviceLoop( loop ) {
    def devNetId = device.getSetting( "manageChild" )
    def devLabel = ""
    
    if ( devNetId == null || devNetId == "" || devNetId == "nodevice" ) {
        logEvent( "setChildDeviceLoop(): Must select a device to update in the List of Child Devices on the Preferences tab.", "warn" )
        return
    }

    logEvent( "setChildDeviceLoop(): Updating sensor loop selection for device '${devNetId}'", "debug" )
    try {
        childDev = getChildDevice( devNetId )
        devLabel = childDev.properties.label
        if ( loop.indexOf( "1 /" ) > 0 )
            loopNum = "1"
        else if ( loop.indexOf( "2 /" ) > 0 )
            loopNum = "2"
        else if ( loop.indexOf( "3 /" ) > 0 )
            loopNum = "3"
        //else if ( loop.indexOf( "4 /" ) > 0 )
        //    loopNum = "4"
        childDev.updateDataValue( "loop", loopNum )
        logEvent( "setChildDeviceLoop(): Setting device sensor loop (${loopNum}): ${devNetId}, ${devLabel} completed.", "info" )
    } catch (error) {
        logEvent( "setChildDeviceLoop(): device selection for ${devNetId} failed. Error = ${error}", "error" )
        return
    }
       
}

Integer getChildDeviceLoop( childDevice ) {
    def loop = childDevice.getDataValue( "loop" )
    def typeName = ""

    if ( loop == null || loop == "" ) {
        typeName = childDevice.typeName
        if ( typeName.indexOf( "Contact" ) > 0 ) {
            // Default to 2
            logEvent( "getChildDeviceLoop(): Child device missing Loop setting, assuming loop 2 for Contact sensor", "warn" )
            childDevice.updateDataValue( "loop", "2" )
            return 2
        } else if ( typeName.indexOf( "Motion" ) > 0 ) {
            // Default to 1
            logEvent( "getChildDeviceLoop(): Child device missing Loop setting, assuming loop 1 for Motion sensor", "warn" )
            childDevice.updateDataValue( "loop", "1" )
            return 1
        } else {
            logEvent( "getChildDeviceLoop(): Could not determine child device type and set the default loop", "error" )
            return 1
        }
    }

    return loop.toInteger()
}

String loopToName ( int loop ) {
    def loopNames = [ 1 : "contact_open", 2 : "reed_open", 3 : "alarm", 4 : "tamper" ]
    return loopname[ loop ]
}

void parse( String mqttevent ) {
    def message      = interfaces.mqtt.parseMessage( mqttevent )

    logEvent( "parse(): Received MQTT message: ${message}", "trace" )
    def slurper = new JsonSlurper()
    sensorData = slurper.parseText( message.payload )
    logEvent( "parse(): Got sensor data: ${sensorData}", "trace" )

    // This is to show the system is (or is not) still getting data
    now = new Date()
    device.updateDataValue( "lastSesnorEvent", now.toString() )

    // Set child device states (if child exists)
    def sensorDev = getChildDevice( "Honeywell_${sensorData.id}" )
    if ( sensorDev != null ) {
        def loop = getChildDeviceLoop( sensorDev )
        def typeName = sensorDev.typeName
        if ( loop == 1 ) {
            if ( sensorData.contact_open.toInteger() == 0 ) {
                Integer motion = typeName.indexOf( "Motion" )
                if ( motion > 0 ) {
                    logEvent( "parse(): Calling inactive() for ${sensorData.id}", "debug" )
                    sensorDev.inactive()
                } else {
                    logEvent( "parse(): Calling close() for ${sensorData.id}", "debug" )
                    sensorDev.close()
                }
            } else {
                Integer motion = typeName.indexOf( "Motion" )
                if ( motion > 0 ) {
                    logEvent( "parse(): Calling inactive() for ${sensorData.id}", "debug" )
                    sensorDev.active()
                } else {
                    logEvent( "parse(): Calling close() for ${sensorData.id}", "debug" )
                    sensorDev.open()
                }
            }
        } else if ( loop == 2 ) {
            if ( sensorData.reed_open.toInteger() == 0 ) {
                Integer motion = typeName.indexOf( "Motion" )
                if ( motion > 0 ) {
                    logEvent( "parse(): Calling inactive() for ${sensorData.id}", "debug" )
                    sensorDev.inactive()
                } else {
                    logEvent( "parse(): Calling close() for ${sensorData.id}", "debug" )
                    sensorDev.close()
                }
            } else {
                Integer motion = typeName.indexOf( "Motion" )
                if ( motion > 0 ) {
                    logEvent( "parse(): Calling inactive() for ${sensorData.id}", "debug" )
                    sensorDev.active()
                } else {
                    logEvent( "parse(): Calling close() for ${sensorData.id}", "debug" )
                    sensorDev.open()
                }
            }
        } else if ( loop == 3 ) {
            if ( sensorData.alarm.toInteger() == 0 ) {
                Integer motion = typeName.indexOf( "Motion" )
                if ( motion > 0 ) {
                    logEvent( "parse(): Calling inactive() for ${sensorData.id}", "debug" )
                    sensorDev.inactive()
                } else {
                    logEvent( "parse(): Calling close() for ${sensorData.id}", "debug" )
                    sensorDev.close()
                }
            } else {
                Integer motion = typeName.indexOf( "Motion" )
                if ( motion > 0 ) {
                    logEvent( "parse(): Calling inactive() for ${sensorData.id}", "debug" )
                    sensorDev.active()
                } else {
                    logEvent( "parse(): Calling close() for ${sensorData.id}", "debug" )
                    sensorDev.open()
                }
            }
        }
        if ( sensorData.contact_open != null ) // Loop 1
            sensorDev.sendEvent( [ name: "contact_open", value: sensorData.contact_open.toInteger() ] )
        if ( sensorData.reed_open != null )    // Loop 2
            sensorDev.sendEvent( [ name: "reed_open",    value: sensorData.reed_open.toInteger()    ] )
        if ( sensorData.reed_open != null )    // Loop 3
            sensorDev.sendEvent( [ name: "alarm",        value: sensorData.alarm.toInteger()        ] )
        if ( sensorData.tamper != null ) {
            sensorDev.sendEvent( [ name: "tamper",       value: sensorData.tamper.toInteger()       ] )
            if ( sensorData.tamper ) {
                sensorDev.updateDataValue( "tamper", "Sensor case may be open" )
            } else {
                sensorDev.updateDataValue( "tamper", "Sensor case is closed / intact" )
            }
        }
        if ( sensorData.battery_ok != null )
            sensorDev.sendEvent( [ name: "battery_ok",   value: sensorData.battery_ok.toInteger()   ] )
        if ( sensorData.event != null )
            sensorDev.sendEvent( [ name: "lastEventType",   value: sensorData.event.toInteger()   ] )
        sensorDev.sendEvent( [ name: "lastEventTime", value: now.toString() ] )
        sensorDev.updateDataValue( "sensorLastEvent", message.payload )
        return
    }

    // Everything below here is to track discovered sensors not yet assigned to a child device
    knownSensors = device.getDataValue( "knownSensors" )
    logEvent( "parse(): Loaded knownSensors data: '${knownSensors}'", "trace" )

    def Map parsedSensors
    if ( knownSensors != null && knownSensors != "" ) {
        logEvent( "parse(): knownSensors is not null or empty: '${knownSensors}'", "trace" )
        knownSensors.trim()
        slurper = new JsonSlurper()
	    parsedSensors = slurper.parseText( knownSensors )
        logEvent( "parse(): Got parsedSensors from knownSensors: '${parsedSensors}'", "trace" )
    }

    if ( knownSensors == null ) {
        parsedSensors = [:]
        parsedSensors[sensorData.id] = sensorData
        logEvent( "parse(): knownSensors was null, adding first entry: '${parsedSensors}'", "debug" )
    } else if ( parsedSensors.containsKey( sensorData.id.toString() ) ) {
        logEvent( "parse(): already have sensor data for ${sensorData.id}", "trace" )
        return
    } else {
        parsedSensors[sensorData.id.toString()] = sensorData
        logEvent( "parse(): found new sensor: '${parsedSensors}'", "debug" )
    }
    
    logEvent( "parse(): storing to knownSensors from parsedSensors (${parsedSensors})", "trace" )
    def dumper = new JsonOutput()
    device.updateDataValue( "knownSensors", dumper.toJson( parsedSensors ) )
    logEvent( "parse(): parse complete", "trace" )
    return
}

void mqttClientStatus( status ) {
    logEvent( "mqttClientStatus(): status: ${status}", "debug" )
}

void publishMqtt( topic, payload, qos = 0, retained = false ) {
    if (notMqttConnected()) {
        logEvent( "publishMqtt(): not connected", "debug" )
        initialize()
    }
    
    def pubTopic = "${topic}"

    try {
        interfaces.mqtt.publish("${pubTopic}", payload, qos, retained)
        logEvent( "publishMqtt(): topic: ${pubTopic} payload: ${payload}", "debug" )
        
    } catch ( Exception e ) {
        logEvent( "publishMqtt(): Unable to publish message: ${e}", "error" )
    }
}

void connected() {
    logEvent( "connected(): Connected to broker", "debug" )
    sendEvent( name: "connectionState", value: "connected" )
    announceLwtStatus( "online" )
}

void disconnected() {
    logEvent( "disconnected(): Disconnected from broker", "debug" )
    sendEvent( name: "connectionState", value: "disconnected" )
    announceLwtStatus( "offline" )
}

void announceLwtStatus( String status ) {
    publishMqtt( "LWT", status )
    publishMqtt( "FW", "${location.hub.firmwareVersionString}" )
    publishMqtt( "IP", "${location.hub.localIP}" )
    publishMqtt( "UPTIME", "${location.hub.uptime}" )
}

void publish( topic, payload ) {
    publishMqtt( topic, payload )
}

String normalize( name ) {
    return name.replaceAll( "[^a-zA-Z0-9]+","-" ).toLowerCase()
}

String getBrokerUri() {        
    return "tcp://${settings?.brokerIp}:${settings?.brokerPort}"
}

String getHubId() {
    def hub = location.hubs[0]
    def hubNameNormalized = normalize( hub.name )
    return "${hubNameNormalized}-${hub.hardwareID}".toLowerCase()
}

def mqttConnected() {
    return interfaces.mqtt.isConnected()
}

def notMqttConnected() {
    return !mqttConnected()
}

void wipeStateData( int silent=0 ) {
    state.clear()
    
    //device.deleteCurrentState( "myCurentStateName" )

    device.removeSetting( "logLevel" )

    def dataValues = device.getData()
    String[] dvalues = []
    dataValues.each { key, val ->
        dvalues = dvalues + key
    }
    dvalues.each { val -> device.removeDataValue( val ) }
    logEvent( "wipeStateData(): Cleared state and device data", "info" )
    if ( silent < 1 ) {
        sendEvent(name:"queryStatus", value:"State and data wipe complete -<br>REFRESH the page.")
    }
}

void wipeChildStateData( childDevNetId, int silent=0 ) {
    childDev = getChildDevice( childDevNetId )
    if ( childDev == null ) {
        logEvent( "wipeChildStateData(): Child ${childDevNetId} not found", "error" )
        device.removeSetting( "manageChild" )
        return
    }
    childDev.state.clear()
    
    childDev.deleteCurrentState( "contact_open" )
    childDev.deleteCurrentState( "reed_open" )
    childDev.deleteCurrentState( "battery_ok" )
    childDev.deleteCurrentState( "tamper" )
    childDev.deleteCurrentState( "lastEvent" )
    childDev.deleteCurrentState( "lastEventTime" )
    childDev.deleteCurrentState( "lastEventType" )
    childDev.deleteCurrentState( "heartbeat" )
    childDev.deleteCurrentState( "alarm" )

    //childDev.removeSetting( "logLevel" )

    def dataValues = childDev.getData()
    String[] dvalues = []
    dataValues.each { key, val ->
        dvalues = dvalues + key
    }
    dvalues.each { val -> childDev.removeDataValue( val ) }
    logEvent( "wipeChildStateData(): Cleared state and device data for ${childDevNetId} (${childDev.properties.label})", "info" )
    device.removeSetting( "manageChild" )
    //if ( silent < 1 ) {
    //    sendEvent(name:"queryStatus", value:"State and data wipe complete -<br>REFRESH the page.")
    //}
}

