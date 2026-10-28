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
ESP GPIO 6 ─ resistor ─ NPN driver ─ optical MOS switch LED

Motor wire A ─ isolated bidirectional MOS switch ─ Motor wire B
```

The output is isolated and non-polarized, so either motor wire can connect to
either pad. The switch is a solid-state component with no moving parts; unlike
a mechanical contact, it has finite on-resistance and current limits.

The logic is active-high: GPIO 6 high closes the isolated contact for 2 ms; GPIO 6 low opens it.

## Compact custom board indicators

- The red LED is connected from 3.3 V to the protected GPIO 5 input through
  its own resistor. It lights while the dry reed contact is closed.
- The green LED has its own resistor and follows GPIO 6 in parallel with the
  optical switch input. It follows the commanded output pulse.
- GPIO 21 drives a discrete blue LED. It is steady while Bluetooth is
  connected and flashes on a fatal firmware initialization error.

Only the custom board with a discrete blue LED is supported by this firmware.

The green LED is on for the commanded 2 ms pulse and may be difficult to see.
