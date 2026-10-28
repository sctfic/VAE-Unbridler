# Sourcing and quotation method

Snapshot date: 2026-10-28. Stock and prices are volatile and must be refreshed
immediately before ordering.

## Selected manufacturers

### JLCPCB

JLCPCB accepts Gerber, BOM and CPL files, matches the BOM against its assembly
library and calculates component, setup, stencil, placement and shipping costs
online. Delivery to France is available; VAT, customs and carrier fees must be
checked at checkout rather than inferred from the PCB price.

For this design, use Standard PCBA if any selected part is marked Standard-only.
Do not accept automatic substitutions for U1, U2, U4, U5, Y1, D3 or the RF
network. The six wire holes are intentionally left empty and soldered locally.

### AISLER

AISLER accepts the native KiCad project and extracts assembly data and MPN
fields from it. Its Amazing Assembly service is made in Europe. The BOM tool
can price parts from Würth, TI, Digi-Key, Mouser, Arrow, Farnell, TTI, TME and
LCSC. Prices shown by AISLER include the applicable VAT unless indicated
otherwise, which makes the delivered-price comparison clearer for France.

Every KiCad symbol will carry an `MPN` field so AISLER can auto-assign the
exact part. If a component is unavailable, use AISLER's customer-supplied
option only after including its per-MPN service fee in the comparison.

## Preliminary key-part snapshot

These are component prices, not a finished-board quote. Currency, packaging,
minimums, feeder fees, attrition quantities and VAT differ between suppliers.

| Function | Approved MPN | JLC/LCSC reference | JLC/LCSC snapshot | European snapshot |
|---|---|---|---:|---:|
| MCU | ESP32-S3FH4R2 | C3013940 | about US$3.46, 566 JLC stock | price pending in AISLER BOM |
| 12 V buck | AP63203WU-7 | C780769 | extended part, live quote required | distributor price pending |
| USB LDO | AP2112K-3.3TRG1 | C51118 | about US$0.17, 68k+ JLC stock | about EUR0.22 each at Mouser |
| power mux | TPS2116DRLR | C3235557 | about US$0.40 at LCSC, 55k+ stock | live AISLER/TI price pending |
| bidirectional optical MOS switch | AQY222R1SX | C719598 | listed by JLC; stock and price need a live quote | about EUR4.87-5.02 each in a prior distributor snapshot |
| candidate cheaper alternative | GAQY212GS | C7435107 | about US$0.61 each in JLC listing | live AISLER price pending |
| 40 MHz crystal | XRCGB40M000F1S1CR0 | C1870448 | live quote required | about US$0.32 each at Mouser global |
| 2.4 GHz antenna | 2450AT18A0100001E | legacy C89334 footprint | live quote required | about EUR0.44 each at Mouser |

The optically isolated MOS switch is bidirectional and small, but costs more
than the previously considered polarized phototransistor. The full assembly
cost cannot be inferred from the part price.

The lower-cost SUPSiC GAQY212GS is only a candidate alternate: its published
datasheet specifies 0.5 ms maximum turn-on and 0.3 ms maximum turn-off at
25 C. The manufacturer web listing and distributor datasheet differ on load
rating; confirm the exact datasheet, pinout, mounting dimensions and stock
before authorizing substitution or using the part in a quote.

## Apples-to-apples quotation procedure

1. Freeze one KiCad revision after ERC, DRC and Gerber review.
2. Generate Gerber/drill, BOM and CPL from that exact revision.
3. Request five assembled boards from each manufacturer using four layers,
   0.8 mm thickness, ENIG and the same approved MPN list. Confirm each
   supplier's stackup and controlled-impedance widths before routing.
4. Ask for X-ray inspection of the ESP32 exposed-pad QFN and record whether it
   is included or charged separately.
5. At checkout, use the same French delivery address and record PCB, parts,
   assembly/setup, inspection, shipping, VAT and import charges separately.
6. Record every unavailable part or proposed substitution. Reject a quote that
   silently leaves parts unpopulated.
7. Compare total delivered cost and total delivered cost per usable board, not
   the advertised bare-PCB price.

The final values belong in `manufacturing-comparison.md`; they cannot be known
reliably until both services have processed the actual production files.

## Tools used

- KiCad 10: native schematic, PCB, footprint assignment, 3D clearance review,
  ERC/DRC, Gerber, drill, BOM, CPL and STEP generation.
- `kicad-cli`: repeatable command-line ERC/DRC and manufacturing exports, so
  the quote files can be regenerated without manual omissions.
- Espressif, TI, Diodes, Panasonic, Murata and Johanson datasheets: electrical,
  timing, footprint and layout limits; distributor descriptions are not used
  as the engineering authority.
- JLCPCB Parts Library/LCSC: current C-codes, assembly classification, stock
  and Chinese assembly pricing.
- AISLER BOM/Simple Supply: current European distributor matching, stock and
  consolidated pricing for the exact KiCad `MPN` fields.
- Manufacturer upload previews: final independent verification of layer,
  drill, BOM match and component orientation before payment.

No production order should be placed from the preliminary CSV alone.
