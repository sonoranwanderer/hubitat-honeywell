# hubitat-honeywell
Hubitat device driver for Honeywell / Ademco 345MHz wireless security system
sensors

Currently only Contact and Motion Sensors are supported.

This driver loads Honeywell or Ademco 345MHz alerts from an MQTT topic, parses
and feeds them into Virtual Contact or Motion Sensor child devices. Sensor
events are loaded into the MQTT topic by rtl_433 attached to a RTL-SDR device.

This driver does not require the use of Home Assitant as an intermediary.

Honeywell / Ademco sensors output sensor events unencrypted on the air and 
therefore this driver does not require not use your security system's main 
controller. In the author's specific case, the secuirty systemn controller 
is powered off but that is not necessary. This driver is passive, simply 
listening via a RTL-SDR device for broadcast signals, and will not interfer 
with the normal use of you full Honeywell or Ademco secuity system.

This driver does NOT support wired Honeywell / Ademco sensors, nor does it 
support any integration with your security system controller. 

## Data Flow
Data / Signal flow looks like:

    <sensor> --wireless--> <antenna> --> <rtl-sdr device> <--USB--> <rtl_433> --> <MQTT Broker> <--> <Hubitat / Honeywell Wireless Sensors driver> --> <Virtual Motion / Contact Sensor>

The rtl-sdr and MQTT broker can exist on the same or different hosts using any OS that supports rtl_433 and MQTT software.

## Setup
The recommended setup sequence when starting from scratch is RTL, MQTT, then the
Hubitat Honeywell driver.

A few key shared detials need to be decided up front:
* IP address of the MQTT broker
* Usernames and passwords for MQTT clients
* MQTT broker topic to pass sensor events

RTL-SDR and MQTT can be on the same or different hosts but are separate of the
Hubitat system. 

### Honeywell Wireless Sensors Driver initial setup
For a new setup, on your Hubitat: 

* Navigate to the Drivers Code section in the left-hand menu
* Click "+ Add driver"
* Copy the driver code from drivers/honeywell_wireless_sensors.groovy
    and pastes into the empty "New driver" window.
* Click Save
* Naviogate to the Devices section in the left-hand menu
* Click "+ Add device"
* Click "</> Virtual"
* Start typing "Honeywell"
* Select "Honeywell Wireless Sensors Driver"
* Click "Next"
* Name your new driver (e.g., "Honeywell Sensors")
* Click "Next"
* Select a room and click "Next" or click "Skip"
* Click "View Device Details"
* Go to the Preferences tab
* Fill in:
  * MQTT Broker IP Address
  * MQTT Broker Port
  * MQTT Broker Topic
  * MQTT Broker Username
  * MQTT Broker Password
* Click "Save"

Optional but recommended while getting through the initial setup is to set the
Logging Level to "debug". You can move it back to "info" once you have verfied
your full setup works as expected. Less logging will lower your hub load and 
spare you some log space. I do not recommend trace unless you are helping to 
debug the driver. Trace is very verbose and may create a heavy load on your 
Hubitat system.

* On the Commands tab, click "Connect"

At this point the driver will beging polling your MQTT broker on the set topic
for sensor events. Each event has as sensor ID contained within, and as the 
driver hears new sensors it will update the list of "Known Sensors" located on
the Device Info tab under the "Device Data" section in the lower right.

Refresh the driver page to see updates to the Known Sensors list. 

### Honeywell sesnors setup
With the driver up, running, and collecting new events, you can add child 
devices that represent each Honeywell sensor you want to monitor in Hubitat.

Due to limitations in the Hubitat driver UI design, sensor setup may seem a 
little convoluted.

* On the Device Info tab, verify a sensor you want to add as a device is in the 
    list of Known Sensors
* Go to the Preferences tab
* Click "Load Known Sensors" and click "Save". This will cause a new preference
  to appear, "List of known sensor IDs that can be added..."
* In the "List of known sensors...", select the sensor you want to add as a 
  device in Hubitat, then click "Save" (again).
* Go to the Commands tab and in the "Add Discovered Sensor Device":
  * Select the device type, Contact or Motion sensor
  * Enter a Device Lable, a convinient indentifying name (e.g. "Front Door")
  * Click "Run"

In the Devices setion of Hubitat, you can now expand the Honeywell device to see
child devices underneath.

Alterntively, if you have a list of your sensor IDs, you can skip the known list
and directly add them. On the Commands tab
* Use the "Add Sensor Device"
* Enter a Sensor ID
* Select the Device type (Contact or Motion)
* Give the new device a friendly name
* Click "Run"

Regardless of which way you add the sensor device, once a child device exists,
sensor events with a macthing sensor ID will be routed to the child device as
a device event. (E.g. open or close for contact sensors; active or inactive for
motion sensors.)

Repeat this process for each sensor you want to use in Hubitat. 

Notes:

1) Since sensor events are unencrypted and broadcast over open radios waves,
your RTL-SDR may pick up sensor signals and give you events from nearby houses 
or other buildings. There really isn't much you can do about this. These will
show up in Known Sensors, you do not have to add them as devices in Hubitat.

2) Sensors themselves do not know what they are used for. Yo will have to look 
at each sensor ion your house individually to identify what sensor ID goes with
what door, window, or motion detector. Many sensors have a stick on the outside 
with the sensor ID. If that sticker is missing or hidden due to the way the 
sensor is physically installed, another sticker with the sensor ID is located 
inside the sensor case.

3) This honeywell driver assmues that wireless contact sensors use "Loop 2" 
which security system installer parlance for the reed_open bit. It also assumes 
that motion sensors use Loop 1 or the contact_open bit. You can change the loop 
used to indicate activity for any sensor by:
* On the Preferences tab, enable "Load Child Devices"
* Click "Save"
* In the new "List of child devices that can be managed" select a device
* Click "Save" (again)
* On the Commands Tab, go to the "Set Child Device Loop"
* Select the Loop option you want the sensor to act on
* Click "Set"

You can see the active Loop for any child device on that Child Device's Device 
Info tab under Device Data.

4) Also in Device Data are the: 
* Most recent raw event data pulled off the MQTT broker, "Sensor Last Event"
* The last known state of the sensor tamper indicator.

5) Sensors send up to 10 signals for each physical event or heartbeat check-in.
This is a sensor design to get around using weak radio signals. This driver 
processes all recieved events as there is no easy way to disinguise duplicates.
Child devices however will only register a Hubitat event if the device state 
changes. See the child device's Events tab.

### RTL-SDR
Receiving Honeywell sensor events requires:
* RTL-SDR device
* Antenna tuned for 345MHz
* rtl_433 binary / program
* Host system with an available USB port and ability to run rtl_433

The host system can run any OS so long as you can get rtl_433 up and running.

rtl_433 can run on command line arguments, a config file or both.

rtl_433 can be obtained here: https://github.com/merbanan/rtl_433

If you are running Linux you may be able to install rtl_433 using the OS' 
package manager. E.g. in Ubuntu or Debian:

    apt install rtl-433

With the RTL-SDR device plugged in and attached to an antenna, you can run a 
simple test with: 

    rtl_433 -f 344975000 -M time:unix -R 70 -F json

Try activating one or more of your sensors (e.g. open and close a door) and see 
events on the command line that look like this:

    {"time" : "1743724331", "model" : "Honeywell-Security", "id" : 39809, "channel" : 8, "event" : 132, "state" : "open", "contact_open" : 1, "reed_open" : 0, "alarm" : 0, "tamper" : 0, "battery_ok" : 1, "heartbeat" : 1, "mic" : "CRC"}

How many events you see may have a lot to do with how well your antenna is tuned
for 345MHz. You may find some sensors don't show up at all, some come through 
strong (single physical events show multiple, upwards of ten repeats) and others 
have an occasional event show upi as a single report.

Some notes on antenna tuning. Short of purchasing off Alibaba, I could not find
a ready made 345MHz antenna. However the RTL-SDR I have came with two sets of 
rabbit ears. Using an antenna analyzer, I was able to tune the larger set by 
adjusting both poles (ears) length to 22.875 inches. Combined as 45.75 inches 
this comes out to a 1.25 wavelength for 345MHz and drastically increase the 
number of sensor signals being picking up. 

I purchased a kit very similar to this: https://a.co/d/gUSarWu
(This is not an endorsement and I receive no commissions on that link)

Once you are satisfied with the reception of sensor signals, the next step is to
get rtl_433 pushing events to your MTTQ broker.

Pick s directory from which you will start rtl_433 and in that directory, create
a config file similar to the one below, replacing the username and password as 
appropriate for your MQTT setup.

    === Begin sample rtl_433 config file
    frequency      344975000
    protocol       70
    report_meta    time:unix
    output         mqtt://192.168.50.232:1883,user=MQTT_user,pass=MQTT_password,retain=0,events=hubitat/honeywell/events
    output         json
    output         log
    === End sample rtl_433 config file

The second and third output lines (json, log) are not necessary, and just serve
to help to debug the process. Once you are satified everything works, you can 
comment them out if desired.

Not necessary, but I recommend getting "MQTT Explorer" as a UI program to 
explore your MQTT broker and see if events are being deilivered to the topic as 
expected.

At this point, if the Honeywell driver has been setup correctly, you should 
start seeing the Known Sensor list populate on the Device Info tab under 
Device Data.

### MQTT Setup
Setup of the MQTT broker is beyond the scope of this document and there are many 
tutorials online. The important aspects are:
* The broker IP address and port must be open and reachable by both rtl_433 and 
    Hubitat
* You need to have a valid username and password with access to the same shared 
    Topic. This setup uses a single topic structure, suggested:
    /hubitat/honeywell/events

If you run Linuix, you may find MQTT is in the distribution's package system. 
E.g. in Ubuntu:

    apt install mosquitto

Here is a handy guide for setting up MQTT on Ubuntu:
https://docs.litmus.io/litmusedge/how-to-guides/integration-guides/install-mosquitto-mqtt-broker-ubuntu
