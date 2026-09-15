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
ESP GPIO 6 ─ resistor ─ PhotoMOS LED ─ ESP GND

Motor sensor contact A ─ PhotoMOS output ─ Motor sensor contact B
```

The PhotoMOS output must be rated for the measured controller contact voltage and current. It is a two-wire dry-contact replacement for the original reed switch. It is not a connection between an unspecified VCC rail and GND.

The logic is active-high: GPIO 6 high closes the isolated contact for 2 ms; GPIO 6 low opens it.
