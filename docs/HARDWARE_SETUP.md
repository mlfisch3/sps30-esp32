# Wiring the SPS30 to an ESP32-WROOM-32E

What to connect and what to have ready, before any firmware exists.

## What you need

- ESP32-WROOM-32E dev board (the usual 30- or 38-pin DevKitC style)
- The SPS30 and its 5-pin cable (JST ZH 1.5 mm)
- A USB cable and a **supply that can actually deliver current** - see the
  warning at the bottom, this is the one thing that has already cost this
  project hours
- Jumper wires; a breadboard helps

## Wiring

The SPS30's connector is on the underside. **Pin 1 is the one nearest the
corner of the housing**, and the SPS30 datasheet numbers them 1-5 from there.

| SPS30 pin | Signal | Connect to | Note |
|---|---|---|---|
| 1 | VDD | **5V** (`VIN`/`5V` on the dev board) | 5 V ±10%, **not** 3V3 |
| 2 | RX | ESP32 **TX** (GPIO17 suggested) | sensor's input |
| 3 | TX | ESP32 **RX** (GPIO16 suggested) | sensor's output |
| 4 | SEL | **leave unconnected** | floating selects UART |
| 5 | GND | GND | common ground |

Three things that are easy to get wrong:

**TX and RX cross exactly once.** The sensor's RX goes to the ESP32's TX. If it
is silent, this is the first thing to re-check.

**SEL floating means UART; tying it to GND selects I2C.** Leave it unconnected.
The interface is latched at power-up, so if it was ever grounded it needs a full
power cycle after you free it - a reset is not enough.

**No level shifter is needed.** The datasheet specifies both UART pins as "TTL
5V and LVTTL 3.3V compatible", so the SPS30's 3.3 V output is safe for the
ESP32's input and the ESP32's 3.3 V output is a valid high for the SPS30. This
is why it worked directly on the Pi, which is also 3.3 V. Only VDD needs 5 V.

**Do not use GPIO1/GPIO3** (`TX0`/`RX0`) - they are the USB serial console. Use
UART2 on GPIO16/17, so flashing and logging stay independent of the sensor.

Link settings, for reference: **115200 baud, 8N1, no flow control.**

## Power, and why this section exists

The Pi that has been running this sensor was **under-voltage and throttled**:
`vcgencmd get_throttled` returned `0x50005` - not merely "since boot", but live.
That is what caused the dropped serial reads which used to end a recording
session and truncate the app's history.

The SPS30's fan draws **55 mA typical, 80 mA peak at 5 V**, and it draws it in
bursts as the fan spins up. An ESP32 transmitting on Wi-Fi draws **200-300 mA**
in its own bursts. Those two peaks can coincide.

So: use a supply and cable good for **at least 1 A**, and prefer a decent USB
cable - thin charge-only cables drop enough voltage to cause exactly this
failure. If the sensor goes silent rather than returning garbled bytes, suspect
power or wiring before suspecting code. Garbled bytes mean baud or levels;
**silence means interface, wiring, or power.**

A 100 µF electrolytic across the SPS30's 5 V and GND, close to the sensor, is
cheap insurance against the fan's inrush.

## Before the first firmware run

Have these ready and it will go quickly:

1. **The board enumerates.** Plug it in and confirm a serial device appears
   (`ls /dev/ttyUSB*` or `/dev/ttyACM*`). Some WROOM boards need the CP210x or
   CH340 driver.
2. **Your Wi-Fi SSID and password.** The first firmware will have them compiled
   in; proper provisioning comes later.
3. **Which USB port it is on**, so the flash command can be given directly.

Nothing needs installing on your side - the toolchain goes on this machine.

## What happens next

The first milestone is deliberately small: bring up the SHDLC driver on the
ESP32 and read the sensor's serial number and firmware version over UART2. That
one exchange proves wiring, levels, baud rate, framing and checksums all at
once, and it is the same handshake the Pi does at startup. Only once that
answers do acquisition, Wi-Fi and the JSONL protocol follow.

Expect the serial number to read **E3164D2544338487** and the firmware **2.1**,
the same as on the Pi - it is the same physical sensor.
