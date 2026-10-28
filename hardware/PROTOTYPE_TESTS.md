# Prototype acceptance tests — draft

Perform initial electrical tests on a bench before connecting the bicycle.
Use a current-limited 12 V supply and a USB supply or computer. Measure the
actual current budget and choose limits before energizing. None of these tests
has yet been performed.

| Test | Method | Required observation |
|---|---|---|
| Inspection | Check component orientation and resistance between power rails and ground | No unintended short; review QFN solder joints with the assembler |
| 12 V only | Apply 12 V to the supply pads | 3.3 V rail starts; output contact is open until commanded |
| USB only | Connect a USB-C data cable with 12 V absent | 3.3 V rail starts; ESP32 can enter boot mode and flash |
| Both sources | Apply 12 V and USB together | No reverse current to either source; both rails stay within ratings |
| Source switching | Connect and disconnect USB while powered from 12 V; also remove 12 V with USB present | Record rail minimum and whether ESP32 reboots; no damage or backfeed |
| Reset | Press RESET; repeat while holding BOOT | Normal restart; BOOT+RESET enters USB download mode |
| Wheel contact | Short and open the two wheel pads using a reed contact | Input is detected and red LED follows closure |
| Output idle | Measure isolated output at power-up, reset and before firmware initializes | No unintended sustained closure |
| Output timing | Drive a representative 3–12 V sensor load in both wire orientations; scope command and optical-switch output | Compare output width and switching delays with actual motor input requirements |
| LEDs | Trigger wheel, output and BLE connection separately | Correct red, green and blue behavior; assess visibility of 2 ms green flashes |
| Radio | Measure antenna match and BLE performance in final enclosure with wiring | Record S11 and operating range; tune RF matching before production |
| Environment | Check transient, ESD, vibration, water protection and temperature relevant to installation | No unsafe behavior or damage within defined test limits |

The optical switch is non-polarized and has voltage, current, leakage,
on-resistance and switching-time limits. The reported 3–12 V sensor voltage
is below the recommended operating limit; current and pulse duration still
need verification against the real motor input.
