# Five-board quotation comparison

Use identical electrical design data and the same quantity for both suppliers.

## Common configuration

- Quantity: 5 assembled boards.
- Board: 30 mm x 20 mm maximum, four layers, FR-4, 0.8 mm thickness.
- RF/USB routing must be checked against each supplier's actual 0.8 mm
  stackup; equivalent impedance is not guaranteed from identical Gerbers.
- Copper: 1 oz outer and inner layers unless the selected controlled-impedance stack requires otherwise.
- Finish: ENIG.
- Solder mask: green.
- Assembly: both sides only if the final placement cannot remain single-sided.
- Inspection: AOI plus X-ray for ESP32-S3FH4R2 exposed-pad QFN.
- Controlled impedance: 90 ohm differential USB and 50 ohm RF.
- Files: Gerber, drill, BOM and CPL generated from the same tagged revision.

## Price fields to record

| Field | JLCPCB | AISLER |
|---|---:|---:|
| Bare PCB | pending | pending |
| Components | pending | pending |
| Assembly/setup | pending | pending |
| X-ray/inspection | pending | pending |
| Shipping to France | pending | pending |
| VAT/duties/fees | pending | pending |
| Total delivered | pending | pending |
| Total per usable board | pending | pending |
| Manufacturing lead time | pending | pending |
| Shipping lead time | pending | pending |
| Parts unavailable or substituted | pending | pending |

No supplier substitution is accepted for U1, U2, U4, U5, the crystal, the RF components or the USB ESD device without an engineering review. An optical switch alternate must retain a non-polarized output and match pinout, input current, leakage, on-resistance, voltage ratings and switching behavior.
