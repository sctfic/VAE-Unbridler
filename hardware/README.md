# E-Bike controller board

Compact ESP32-S3 controller for the E-BikeCockpit firmware.

## Frozen prototype requirements

- Maximum PCB outline: 30 mm x 20 mm.
- Maximum assembled height: 10 mm.
- Supply: Bosch eBike 12 V power port, only when that port is not already used by Bosch ABS.
- Programming: USB-C, including when the 12 V supply is absent.
- Field wiring: six plated through holes, arranged as three pairs:
  - `12V+`, `GND`
  - `WHEEL`, `GND`
  - `MOTOR_A`, `MOTOR_B`
- `WHEEL` is a local, dry reed contact to ground.
- `MOTOR_A` / `MOTOR_B` form a non-polarized isolated semiconductor contact.
- Indicators:
  - red: wheel input contact closed;
  - green: isolated output is being commanded closed;
  - blue: Bluetooth connection active.
- Prototype quantity: five assembled boards.
- Manufacturers to quote: JLCPCB and AISLER.

## Electrical architecture

1. `12V+` passes through a resettable fuse, reverse-polarity protection and a TVS clamp.
2. A fixed-output AP63203 buck regulator creates `3V3_AUX`.
3. USB VBUS feeds a 600 mA 3.3 V LDO and creates `3V3_USB`.
4. A TPS2116 power multiplexer selects USB power first when present and blocks reverse current between supplies.
5. The ESP32-S3FH4R2 is powered from the selected 3.3 V rail.
6. USB D- and D+ connect to GPIO19 and GPIO20 through 22 ohm series resistors and low-capacitance ESD protection.
7. GPIO5 reads the protected wheel input. The red LED and its resistor are connected from 3.3 V to the input, so the LED lights when the dry contact closes.
8. GPIO6 drives the isolated MOS switch through a transistor and the green
   indicator through a separate current-limiting resistor.
9. GPIO21 drives the blue Bluetooth indicator using the firmware in this repository.

## Manufacturing outputs

The release directory must contain, after design validation:

- native KiCad schematic and PCB files;
- schematic PDF;
- Gerber and NC drill ZIP;
- BOM with manufacturer and supplier part numbers;
- CPL / pick-and-place file;
- top and bottom assembly drawings;
- STEP model;
- ERC, DRC and Gerber review reports;
- JLCPCB and AISLER quotation notes for five boards.

The detailed circuit capture specification is in `CIRCUIT_SPEC.md`. The live
parts and quotation procedure are in `SOURCING.md`.

`kicad/power_study.kicad_sch` is an incomplete study of the power section,
ESP32 pinout, reset, clock and wheel/isolated output interfaces, not a manufacturing schematic. Its
generic symbols have no verified footprints and all pins are passive, so an
ERC pass cannot validate the design. The USB data connector, decoupling,
wheel ESD protection and RF matching are captured provisionally; the RF
supply filter value, ESD protection and crystal pinout remain to be verified.
Do not use its netlist or preview to order a PCB.

`kicad/placement_study_NOT_FOR_FABRICATION.kicad_pcb` is a mechanical
placement experiment on a 30 x 20 mm four-layer 0.8 mm board. Only major
parts and six unconnected wire holes are placed; some footprints are only
approximations, the output switch and driver are on the bottom, and no tracks
are routed. Only the USB connector's GND and VBUS nets are assigned. The DRC
report covers placed objects only: it says nothing about missing components,
radio keep-out, circuit connectivity or production readiness.

## Prototype limitations

This revision integrates the bare ESP32-S3FH4R2 to meet the 30 mm x 20 mm outline. RF matching and antenna performance must be measured on the first assembled prototypes. The first order is therefore an engineering prototype, not a validated production design.

The Bosch 12 V power port must not be shared with a Bosch ABS unit. Verify polarity before soldering the two supply wires.
