# Hardware wiring

## Wheel reed switch, GPIO 5

```text
GPIO 5 ───── reed switch ───── ESP32 GND
   │
  10 kΩ pull-up to 3.3 V (the internal pull-up is enabled too)
```

The firmware captures the falling edge: the instant the reed switch closes. For a long cable, add a small series resistor and protection appropriate to the bicycle environment; do not feed a controller-voltage signal into GPIO 5.

## Motor sensor simulation, GPIO 6

```text
Controller sensor signal (external 3.3 V pull-up) ─ ESP GPIO 6
Controller sensor ground ──────────────────────── ESP GND
```

Current ESP32-S3 Zero Bluetooth Standard/Turbo firmware: GPIO_MODE_OUTPUT_OD,
with both internal pulls disabled. The controller supplies the pull-up.
Writing 0 sinks the signal to ground (closed); writing 1 releases it (open).
The released latch value is set before enabling the output to avoid an initial
false pulse. Pulse timing and the Standard/Turbo algorithms are unchanged.

This is NOT an isolated dry contact. Only use this direct wiring after checking
that the original reed connects signal to signal ground, that grounds are common,
and that signal voltage AND sink current are within ESP32-S3 ratings. The reported
3.3 V open-circuit measurement does not establish pull-up resistance, transient
protection, or safety when the ESP32 is unpowered. Avoid powering the controller
signal into an unpowered ESP32; use a properly rated external transistor or
isolated interface if these conditions cannot be guaranteed. Never short VCC to GND.

This firmware is not compatible unchanged with the former active-high PhotoMOS
LED wiring. A new hardware design must remain on a separate development branch.

Before connecting the controller, bench-test GPIO 6 with an external 10 kohm
pull-up to ESP 3.3 V and an oscilloscope: idle high, pulses low for 2 ms,
no pulse during initialization, return high on stop, in both Standard and Turbo.
Confirm the controller-side electrical conditions separately before a stationary
test on the bicycle. A multimeter alone cannot validate pulse timing/transients.
