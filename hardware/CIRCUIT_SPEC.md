# Circuit specification for schematic capture

This file is the electrical source of truth until the KiCad schematic has
passed ERC. Net names are written exactly as they should appear in KiCad.

## External connections

The board has six 1.0 mm finished plated holes with 1.9 mm pads. The three
pairs sit on one long board edge and are labelled on both copper and
silkscreen.

| Pad | Net | Function |
|---|---|---|
| J2.1 | `12V_IN` | Bosch auxiliary positive supply |
| J2.2 | `GND` | Bosch auxiliary return |
| J3.1 | `WHEEL_RAW` | Dry magnetic reed contact |
| J3.2 | `GND` | Reed-contact return |
| J4.1 | `MOTOR_A` | Isolated output; either motor sensor wire |
| J4.2 | `MOTOR_B` | Isolated output; the other motor sensor wire |

The motor output has no electrical connection to board ground.

## 12 V input and buck regulator

1. `12V_IN` -> F1 0.25 A hold resettable fuse -> D1 B140-13-F series
   Schottky -> `12V_PROTECTED`.
2. D2 SMF18A goes from `12V_PROTECTED` to `GND`, cathode to the protected
   positive rail.
3. C1 10 uF 35 V X7R 1206 and C2 100 nF 50 V 0603 bypass
   `12V_PROTECTED` at U2.
4. U2 is AP63203WU-7. VIN and EN connect to `12V_PROTECTED`; GND to `GND`.
   BS connects through C3 100 nF 50 V to LX. FB connects directly
   to `3V3_AUX`, as required for the fixed 3.3 V version.
5. LX -> L1 3.9 uH -> `3V3_AUX`. C5 and C6 are 22 uF 25 V X7R 1206 from
   `3V3_AUX` to `GND`.

L1 candidate is APV ANR5040T3R9M, 3.7 A rated and 4.2 A saturation. Check
the minimum saturation current at temperature and source availability. The
previous 2.8 A candidate lacks margin against U2's 3.1 A maximum peak limit.
The hot loop C1/U2/L1/C5 must follow the AP63203 reference placement.
Keep the LX copper short and away from the crystal and antenna.

## USB-C, USB power and source selection

1. J1 is HRO TYPE-C-31-M-12. All VBUS pins join `USB_VBUS`; all ground and
   shield pins join `GND`. R2 and R3 are separate 5.1 kohm 1% resistors from
   CC1 and CC2 to `GND`.
2. D3 is ST USBLC6-2SC6 at the connector. Route the USB pair through D3 and
   then R4/R5, 22 ohm, to GPIO19 (`USB_DN`) and GPIO20 (`USB_DP`). R4/R5 sit
   close to U1. Reserve C7/C8 footprints, DNP, 0402, from the chip side of
   each resistor to `GND`.
3. U3 is AP2112K-3.3TRG1. IN and EN connect to `USB_VBUS`; OUT is
   `3V3_USB`; use C9 and C10, 1 uF X7R, at input and output.
4. U4 is TPS2116DRLR. `3V3_USB` connects to VIN1 and `3V3_AUX` to VIN2.
   Connect both MODE and PR1 to `GND`: according to TI's truth table this
   selects the higher input voltage. There is no fixed USB priority. Both
   VOUT pins form `3V3`; ST is not connected. Bypass VIN1, VIN2 and VOUT
   with C11/C12/C13, 1 uF X7R.

This arrangement is intended to permit programming from USB with the 12 V
supply absent and to block reverse current between the 3.3 V sources. It does
not guarantee uninterrupted operation during USB insertion/removal: the
TPS2116 can interrupt the output during switching, and the output can dip or
the ESP32 can reset. Selecting the higher input avoids the former fixed 2.8 V
nominal priority threshold, but does not guarantee a minimum rail voltage. A
poorly regulated input, unequal input voltages, current transients or repeated
switching near equal input voltages must be tested. Check both transitions
under load and at temperature, including the ESP32 3.0 V minimum supply. Both
backfeed and uninterrupted operation remain unverified on hardware.

Source: [TI TPS2116 datasheet, sections 6.5, 7.3 and 7.6](https://www.ti.com/lit/ds/symlink/tps2116.pdf).

Provision C30, a 22 uF 10 V X5R 0805 candidate, between `3V3` and `GND`
close to the load and mux output. Reserve a parallel C31 footprint for an
optional second 22 uF, initially DNP. C13 (1 uF) and C29 (10 uF) remain;
these are nominal values, not measured effective capacitance. Verify DC bias,
temperature, tolerance, starting inrush, and layout; do not claim the added
capacitor guarantees operation through a transition. Unlike the old fixed
priority divider, this mode has no guaranteed absolute 3.0 V switchover
threshold; source voltage tolerances and transient behavior still require
analysis and measurement before schematic release.

## ESP32-S3FH4R2 minimum circuit

U1 is the ESP32-S3FH4R2 QFN-56 with in-package 4 MB flash and 2 MB PSRAM.
Follow the latest Espressif reference schematic for every supply pin and do
not use GPIO26 through GPIO37 externally.

- All 3.3 V digital supply pins receive local 100 nF 0402 capacitors and the
  rail receives at least one 10 uF bulk capacitor beside U1.
- Feed VDD3P3 pins 2 and 3 through the LC filter recommended by Espressif;
  connect VDDA pins 55 and 56 and their local bypass capacitors per the
  reference schematic. Decouple VDD_SPI separately with 100 nF and 1 uF.
- CHIP_PU has 10 kohm to `3V3`, 1 uF to `GND`, and SW2 momentarily to `GND`.
- GPIO0 has 10 kohm to `3V3` and SW1 momentarily to `GND` for download mode.
- GPIO46 has 10 kohm to `GND`, ensuring the required low level when GPIO0 is
  held low for joint USB download boot. GPIO45 has 10 kohm to `GND` so the
  in-package flash/PSRAM supply starts in its 3.3 V configuration.
- Y1 is XRCGB40M000F1S1CR0, 40 MHz, 10 pF, +/-10 ppm. Start with two 11 pF
  C0G load capacitors and a 24 nH series footprint on XTAL_P, then validate
  frequency and adjust the values on prototypes.
- LNA_IN feeds a three-element 0201 pi matching network. Initial population
  is series 2.4 nH with two shunt 1.5 pF capacitors. AE1 is a Johanson
  2450AT18A0100001E ceramic antenna. These are tuning starting values only.
- GPIO19 is USB D-; GPIO20 is USB D+.
- GPIO5 is `WHEEL_GPIO`; GPIO6 is `PULSE_CMD`; GPIO21 is `LED_BLUE`.
- Add labelled test pads for `3V3`, `GND`, `CHIP_PU`, GPIO0, U0TXD and U0RXD.

## Wheel input and red indicator

1. `WHEEL_RAW` is clamped to `GND` by D4, a unidirectional 5 V ESD diode
   placed at J3.
2. R8 1 kohm connects `WHEEL_RAW` to `WHEEL_GPIO` (GPIO5). R9 10 kohm pulls
   `WHEEL_GPIO` to `3V3`; C14 100 nF from `WHEEL_GPIO` to `GND` provides
   hardware filtering in addition to firmware debouncing.
3. `3V3` -> R10 680 ohm -> LED1 red anode; LED1 cathode -> `WHEEL_RAW`.
   Closing the reed contact therefore lights LED1 without firmware.

## Isolated output and green indicator

U5 is Panasonic AQY222R1SX, an optically isolated bidirectional MOS switch
in a small SOP-4 package. It has no mechanical moving parts. Either motor wire
may be connected to either output pad.

- `3V3` -> R11 240 ohm 1% -> U5 pin 1 (LED anode); U5 pin 2 (LED cathode)
  -> Q1 collector. Q1 is MMBT3904 with emitter to `GND`.
- `PULSE_CMD` (GPIO6) -> R15 1.3 kohm 1% -> Q1 base; R16 100 kohm from
  base to `GND`. Check worst-case GPIO high level, base drive and Q1
  saturation. With 3.0 V rail, 1.5 V maximum LED drop, 0.2 V transistor
  drop and 1% resistor tolerance, the estimated LED current is 5.36 mA.
- `PULSE_CMD` -> R12 680 ohm -> LED2 green anode; LED2 cathode -> `GND`.
- U5 pin 4 -> `MOTOR_A`; pin 3 -> `MOTOR_B`.
- R13 100 kohm pulls `PULSE_CMD` to `GND` so the output remains open during
  reset and before GPIO initialization. The output must be measured during
  power-up and reset, including any GPIO glitches.

The user's expected motor sensor voltage of 3-12 V is below the manufacturer's
30 V recommended maximum. The 0.5 ms maximum on-time and 0.2 ms maximum
off-time are specified at 25 C and 5 mA input. They are not guarantees over
the entire temperature range. Verify the output level and pulse width with the
real motor input and expected temperature range. A 2 ms green flash may be
difficult to see.

## Bluetooth indicator

`LED_BLUE` (GPIO21) -> R14 680 ohm -> LED3 blue anode; LED3 cathode ->
`GND`. The discrete-LED firmware in this repository drives this net high
while Bluetooth is connected and flashes it during a fatal fault.

## PCB constraints

- Hard outline limit: 30.0 mm x 20.0 mm; target 29.5 mm x 19.5 mm.
- Four layers, 0.8 mm FR-4, ENIG, components on top only if placement permits.
- L2 is an uninterrupted ground plane. L3 is ground/power with no split or
  trace below USB, RF or crystal routing.
- USB is a 90 ohm differential pair. RF is a 50 ohm controlled-impedance
  trace. Calculate actual widths from each manufacturer's chosen stackup.
- AISLER's published 0.8 mm four-layer stackup gives 0.295 mm width for 50 ohm
  and 0.260 mm width with 0.135 mm gap for 90 ohm differential on TOP over IN1.
  These dimensions cannot be assumed to yield the same impedances at JLCPCB;
  calculate and approve the JLC-specific stackup before manufacturing.
- No copper on any layer under or in front of the chip antenna, following the
  antenna manufacturer's keep-out drawing.
- Separate the buck converter from the crystal/RF end of the board. Put USB
  at one short edge and the antenna at the opposite short edge.
- Maximum assembled height is 10 mm. The current tallest candidate is the
  3.0 mm power inductor; soldered wires must leave parallel to the PCB.

## Prototype validation gates

Do not call the design production-ready until all five checks pass:

1. no 12 V-to-USB backfeed and no USB-to-12 V backfeed in every power state;
2. USB flashing with 12 V absent, including manual BOOT/RESET recovery;
3. isolated output pulse measured at 2 ms command over temperature and load;
4. input transient/ESD tests representative of the installed cable;
5. 2.4 GHz matching and radiated Bluetooth performance measured in the final
   enclosure and wiring arrangement.
