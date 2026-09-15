# E-Bike telemetry and pulse simulator

This repository contains two independent subprojects:

- `firmware-esp32/`: ESP-IDF firmware for an ESP32-S3 Zero. It measures the wheel reed switch on GPIO 5 and drives an **isolated dry-contact interface** from GPIO 6.
- `android-app/`: native Android dashboard and companion service.

Read [`BLE_PROTOCOL.md`](BLE_PROTOCOL.md) before changing either side of the link.

## Electrical safety

GPIO 6 is a 3.3 V logic output. It must drive the LED input of a PhotoMOS/solid-state relay (or another appropriately rated isolated dry-contact circuit), **not the motor-controller wires directly**. The isolated output is connected across the two contacts normally closed by the original magnetic switch. Never short an unknown controller VCC rail to GND.

## Firmware build

Install ESP-IDF 5.3 or newer, open `firmware-esp32/`, then run `idf.py set-target esp32s3`, `idf.py build`, and `idf.py flash monitor`.

## Android build

Open `android-app/` in Android Studio and let Gradle sync. Pair the ESP32 once from the dashboard; after that, the companion service reconnects whenever the board advertises.
