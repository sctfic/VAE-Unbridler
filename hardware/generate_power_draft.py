"""Generate an explicitly incomplete KiCad 10 electrical study.

This is a capture aid, not an assembly-ready schematic. Pin numbers are
included for review, but the library symbols have no production footprints.
"""
from pathlib import Path
from uuid import uuid5, NAMESPACE_URL

OUT = Path(__file__).parent / "kicad" / "power_study.kicad_sch"


def uid(name):
    return str(uuid5(NAMESPACE_URL, "ebike-power-study/" + name))


def quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'


# Every entry is (reference, value, x, y, [(pin number, pin name, net), ...]).
# U2/U3/U4 pin numbers are preliminary and must be checked against datasheets.
parts = [
    ("J2", "12V input pads", 35, 35, [("1", "+12V", "12V_IN"), ("2", "GND", "GND")]),
    ("F1", "PTC 0.25A", 95, 35, [("1", "1", "12V_IN"), ("2", "2", "FUSED_12V")]),
    ("D1", "B140-13-F", 155, 35, [("1", "K", "12V_PROTECTED"), ("2", "A", "FUSED_12V")]),
    ("D2", "SMF18A", 215, 35, [("1", "K", "12V_PROTECTED"), ("2", "A", "GND")]),
    ("C1", "10uF 35V", 275, 35, [("1", "+", "12V_PROTECTED"), ("2", "-", "GND")]),
    ("C2", "100nF 50V", 335, 35, [("1", "+", "12V_PROTECTED"), ("2", "-", "GND")]),
    ("U2", "AP63203WU-7", 55, 80, [("1", "FB", "3V3_AUX"), ("2", "EN", "12V_PROTECTED"), ("3", "VIN", "12V_PROTECTED"), ("4", "GND", "GND"), ("5", "SW", "BUCK_SW"), ("6", "BST", "BUCK_BST")]),
    ("L1", "3.9uH", 115, 80, [("1", "1", "BUCK_SW"), ("2", "2", "3V3_AUX")]),
    ("C3", "100nF BST", 175, 80, [("1", "1", "BUCK_BST"), ("2", "2", "BUCK_SW")]),
    ("C5", "22uF 25V", 235, 80, [("1", "+", "3V3_AUX"), ("2", "-", "GND")]),
    ("C6", "22uF 25V", 295, 80, [("1", "+", "3V3_AUX"), ("2", "-", "GND")]),
    ("J1", "USB-C TYPE-C-31-M-12", 35, 130, [
        ("A1", "GND", "GND"), ("A4", "VBUS", "USB_VBUS"), ("A5", "CC1", "USB_CC1"),
        ("A6", "D+", "USB_DP_CONN"), ("A7", "D-", "USB_DN_CONN"), ("A8", "SBU1", None),
        ("A9", "VBUS", "USB_VBUS"), ("A12", "GND", "GND"),
        ("B1", "GND", "GND"), ("B4", "VBUS", "USB_VBUS"), ("B5", "CC2", "USB_CC2"),
        ("B6", "D+", "USB_DP_CONN"), ("B7", "D-", "USB_DN_CONN"), ("B8", "SBU2", None),
        ("B9", "VBUS", "USB_VBUS"), ("B12", "GND", "GND"), ("SH", "SHIELD", "GND")]),
    ("U3", "AP2112K-3.3TRG1", 95, 130, [("1", "VIN", "USB_VBUS"), ("2", "GND", "GND"), ("3", "EN", "USB_VBUS"), ("4", "NC", None), ("5", "VOUT", "3V3_USB")]),
    ("C9", "1uF", 155, 130, [("1", "+", "USB_VBUS"), ("2", "-", "GND")]),
    ("C10", "1uF", 215, 130, [("1", "+", "3V3_USB"), ("2", "-", "GND")]),
    ("U4", "TPS2116DRLR", 275, 130, [("1", "GND", "GND"), ("2", "VOUT", "3V3"), ("3", "VIN1", "3V3_USB"), ("4", "PR1", "MUX_PR1"), ("5", "MODE", "3V3_USB"), ("6", "VIN2", "3V3_AUX"), ("7", "VOUT", "3V3"), ("8", "ST", None)]),
    ("R6", "180k 1%", 35, 180, [("1", "1", "3V3_USB"), ("2", "2", "MUX_PR1")]),
    ("R7", "100k 1%", 95, 180, [("1", "1", "MUX_PR1"), ("2", "2", "GND")]),
    ("C11", "1uF", 155, 180, [("1", "+", "3V3_USB"), ("2", "-", "GND")]),
    ("C12", "1uF", 215, 180, [("1", "+", "3V3_AUX"), ("2", "-", "GND")]),
    ("C13", "1uF", 275, 180, [("1", "+", "3V3"), ("2", "-", "GND")]),
    ("J4", "MOTOR: non polarized", 35, 225, [("1", "A", "MOTOR_A"), ("2", "B", "MOTOR_B")]),
    ("U5", "AQY222R1SX", 95, 225, [("1", "LED A", "OPTO_A"), ("2", "LED K", "OPTO_K"), ("3", "OUT", "MOTOR_B"), ("4", "OUT", "MOTOR_A")]),
    ("R11", "240R 1%", 155, 225, [("1", "1", "3V3"), ("2", "2", "OPTO_A")]),
    ("R13", "100k", 215, 225, [("1", "1", "PULSE_CMD"), ("2", "2", "GND")]),
    ("R12", "680R", 275, 225, [("1", "1", "PULSE_CMD"), ("2", "2", "GREEN_A")]),
    ("LED2", "GREEN", 335, 225, [("1", "K", "GND"), ("2", "A", "GREEN_A")]),
    ("J3", "WHEEL reed", 35, 260, [("1", "WHEEL", "WHEEL_RAW"), ("2", "GND", "GND")]),
    ("R8", "1k", 95, 260, [("1", "1", "WHEEL_RAW"), ("2", "2", "WHEEL_GPIO")]),
    ("R9", "10k", 155, 260, [("1", "1", "3V3"), ("2", "2", "WHEEL_GPIO")]),
    ("C14", "100nF", 215, 260, [("1", "1", "WHEEL_GPIO"), ("2", "2", "GND")]),
    ("R10", "680R", 275, 260, [("1", "1", "3V3"), ("2", "2", "RED_A")]),
    ("LED1", "RED", 335, 260, [("1", "K", "WHEEL_RAW"), ("2", "A", "RED_A")]),
    ("Q1", "MMBT3904", 35, 300, [("1", "BASE", "OPTO_BASE"), ("2", "EMITTER", "GND"), ("3", "COLLECTOR", "OPTO_K")]),
    ("R15", "1.3k 1%", 95, 300, [("1", "1", "PULSE_CMD"), ("2", "2", "OPTO_BASE")]),
    ("R16", "100k", 155, 300, [("1", "1", "OPTO_BASE"), ("2", "2", "GND")]),
    ("R17", "10k BOOT", 215, 300, [("1", "1", "3V3"), ("2", "2", "BOOT")]),
    ("SW1", "BOOT", 275, 300, [("1", "1", "BOOT"), ("2", "2", "GND")]),
    ("R18", "10k EN", 335, 300, [("1", "1", "3V3"), ("2", "2", "CHIP_PU")]),
    ("C15", "1uF EN", 35, 340, [("1", "1", "CHIP_PU"), ("2", "2", "GND")]),
    ("SW2", "RESET", 95, 340, [("1", "1", "CHIP_PU"), ("2", "2", "GND")]),
    ("R19", "10k GPIO45", 155, 340, [("1", "1", "GPIO45"), ("2", "2", "GND")]),
    ("R20", "10k GPIO46", 215, 340, [("1", "1", "GPIO46"), ("2", "2", "GND")]),
    ("R14", "680R BLUE", 275, 340, [("1", "1", "LED_BLUE"), ("2", "2", "BLUE_A")]),
    ("LED3", "BLUE", 335, 340, [("1", "K", "GND"), ("2", "A", "BLUE_A")]),
    ("Y1", "40MHz CRYSTAL", 35, 380, [("1", "XTAL", "XTAL_P_CRYSTAL"), ("2", "GND", "GND"), ("3", "XTAL", "XTAL_N"), ("4", "GND", "GND")]),
    ("L3", "24nH XTAL", 95, 380, [("1", "1", "XTAL_P"), ("2", "2", "XTAL_P_CRYSTAL")]),
    ("C16", "11pF XTAL", 155, 380, [("1", "1", "XTAL_P_CRYSTAL"), ("2", "2", "GND")]),
    ("C17", "11pF XTAL", 215, 380, [("1", "1", "XTAL_N"), ("2", "2", "GND")]),
    ("C18", "100nF SPI", 275, 380, [("1", "1", "VDD_SPI"), ("2", "2", "GND")]),
    ("C19", "1uF SPI", 335, 380, [("1", "1", "VDD_SPI"), ("2", "2", "GND")]),
    ("L4", "RF supply filter TBD", 395, 300, [("1", "1", "3V3"), ("2", "2", "RF_3V3")]),
    ("C20", "10uF RF", 395, 340, [("1", "1", "RF_3V3"), ("2", "2", "GND")]),
    ("C21", "100nF RF", 395, 380, [("1", "1", "RF_3V3"), ("2", "2", "GND")]),
    ("R2", "5.1k CC1", 540, 45, [("1", "1", "USB_CC1"), ("2", "2", "GND")]),
    ("R3", "5.1k CC2", 600, 45, [("1", "1", "USB_CC2"), ("2", "2", "GND")]),
    ("D3", "USBLC6-2SC6", 660, 45, [("1", "IO1", "USB_DN_CONN"), ("2", "GND", "GND"), ("3", "IO2", "USB_DP_CONN"), ("4", "IO2", "USB_DP_CONN"), ("5", "VBUS", "USB_VBUS"), ("6", "IO1", "USB_DN_CONN")]),
    ("R4", "22R USB-", 540, 85, [("1", "1", "USB_DN_CONN"), ("2", "2", "USB_DN")]),
    ("R5", "22R USB+", 600, 85, [("1", "1", "USB_DP_CONN"), ("2", "2", "USB_DP")]),
    ("C7", "USB- DNP", 660, 85, [("1", "1", "USB_DN"), ("2", "2", "GND")]),
    ("C8", "USB+ DNP", 720, 85, [("1", "1", "USB_DP"), ("2", "2", "GND")]),
    ("D4", "ESD 5V TBD", 540, 130, [("1", "K", "WHEEL_RAW"), ("2", "A", "GND")]),
    ("C26", "100nF RTC", 600, 130, [("1", "1", "3V3"), ("2", "2", "GND")]),
    ("C27", "100nF CPU", 660, 130, [("1", "1", "3V3"), ("2", "2", "GND")]),
    ("C28", "100nF VDDA", 720, 130, [("1", "1", "3V3"), ("2", "2", "GND")]),
    ("C29", "10uF bulk", 780, 130, [("1", "1", "3V3"), ("2", "2", "GND")]),
    ("C22", "1.5pF RF chip", 540, 175, [("1", "1", "RF_LNA"), ("2", "2", "GND")]),
    ("L2", "2.4nH RF chip", 600, 175, [("1", "1", "RF_LNA"), ("2", "2", "RF_MATCHED")]),
    ("C23", "1.5pF RF chip", 660, 175, [("1", "1", "RF_MATCHED"), ("2", "2", "GND")]),
    ("C24", "RF antenna DNP", 540, 220, [("1", "1", "RF_MATCHED"), ("2", "2", "GND")]),
    ("R21", "0R antenna", 600, 220, [("1", "1", "RF_MATCHED"), ("2", "2", "RF_ANT")]),
    ("C25", "RF antenna DNP", 660, 220, [("1", "1", "RF_ANT"), ("2", "2", "GND")]),
    ("AE1", "2450AT18A0100001E", 720, 220, [("1", "FEED", "RF_ANT"), ("2", "ANCHOR_NC", None)]),
    ("U1", "ESP32-S3FH4R2 - provisional pinout", 490, 190, [
        ("1", "LNA_IN", "RF_LNA"), ("2", "VDD3P3", "RF_3V3"), ("3", "VDD3P3", "RF_3V3"),
        ("4", "CHIP_PU", "CHIP_PU"), ("5", "GPIO0", "BOOT"), ("6", "GPIO1", None),
        ("7", "GPIO2", None), ("8", "GPIO3", None), ("9", "GPIO4", None),
        ("10", "GPIO5", "WHEEL_GPIO"), ("11", "GPIO6", "PULSE_CMD"), ("12", "GPIO7", None),
        ("13", "GPIO8", None), ("14", "GPIO9", None), ("15", "GPIO10", None),
        ("16", "GPIO11", None), ("17", "GPIO12", None), ("18", "GPIO13", None),
        ("19", "GPIO14", None), ("20", "VDD3P3_RTC", "3V3"), ("21", "GPIO15", None),
        ("22", "GPIO16", None), ("23", "GPIO17", None), ("24", "GPIO18", None),
        ("25", "GPIO19_USB_D-", "USB_DN"), ("26", "GPIO20_USB_D+", "USB_DP"), ("27", "GPIO21", "LED_BLUE"),
        ("28", "SPICS1_INTERNAL", None), ("29", "VDD_SPI", "VDD_SPI"), ("30", "SPIHD_INTERNAL", None),
        ("31", "SPIWP_INTERNAL", None), ("32", "SPICS0_INTERNAL", None), ("33", "SPICLK_INTERNAL", None),
        ("34", "SPIQ_INTERNAL", None), ("35", "SPID_INTERNAL", None), ("36", "SPICLK_N", None),
        ("37", "SPICLK_P", None), ("38", "GPIO33", None), ("39", "GPIO34", None),
        ("40", "GPIO35", None), ("41", "GPIO36", None), ("42", "GPIO37", None),
        ("43", "GPIO38", None), ("44", "GPIO39", None), ("45", "GPIO40", None),
        ("46", "VDD3P3_CPU", "3V3"), ("47", "GPIO41", None), ("48", "GPIO42", None),
        ("49", "U0TXD", "U0TXD"), ("50", "U0RXD", "U0RXD"), ("51", "GPIO45", "GPIO45"),
        ("52", "GPIO46", "GPIO46"), ("53", "XTAL_N", "XTAL_N"), ("54", "XTAL_P", "XTAL_P"),
        ("55", "VDDA", "3V3"), ("56", "VDDA", "3V3"), ("57", "GND_PAD", "GND"),
    ]),
]


def geometry(pins):
    # Pins face right and leave the left side of the block. Local Y is positive up.
    return [(n, name, net, 2.54 * (len(pins) - 1) / 2 - 2.54 * i)
            for i, (n, name, net) in enumerate(pins)]


def main():
    root = uid("root")
    s = ['(kicad_sch (version 20250114) (generator "eeschema")',
         f' (uuid "{root}") (paper "A1")',
         ' (title_block (title "E-Bike - electrical study - INCOMPLETE") (rev "DRAFT"))',
         ' (lib_symbols']
    for ref, value, _, _, pins in parts:
        half = max(5.08, 2.54 * (len(pins) - 1) / 2 + 2.54)
        s += [f'  (symbol "draft:{ref}" (pin_names (offset 0.5)) (in_bom yes) (on_board yes)',
              f'   (property "Reference" "{ref}" (at 0 {half + 2} 0) (effects (font (size 1 1))))',
              f'   (property "Value" {quote(value)} (at 0 {-half - 2} 0) (effects (font (size 1 1))))',
              f'   (symbol "{ref}_0_1" (rectangle (start -7.62 {-half}) (end 7.62 {half}) (stroke (width 0.25) (type default)) (fill (type background))))',
              f'   (symbol "{ref}_1_1"']
        for n, name, _, y in geometry(pins):
            s.append(f'    (pin passive line (at -12.7 {y:g} 0) (length 5.08) (name {quote(name)} (effects (font (size 0.9 0.9)))) (number {quote(n)} (effects (font (size 0.9 0.9)))))')
        s += ['   )', '  )']
    s.append(' )')
    for ref, value, x, y, pins in parts:
        x, y = round(x / 1.27) * 1.27, round(y / 1.27) * 1.27
        half = max(5.08, 2.54 * (len(pins) - 1) / 2 + 2.54)
        dnp = "yes" if "DNP" in value else "no"
        s += [f' (symbol (lib_id "draft:{ref}") (at {x} {y} 0) (unit 1) (in_bom yes) (on_board yes) (dnp {dnp}) (uuid "{uid(ref)}")',
              f'  (property "Reference" "{ref}" (at {x} {y-half-2:g} 0) (effects (font (size 1 1))))',
              f'  (property "Value" {quote(value)} (at {x} {y+half+2:g} 0) (effects (font (size 1 1))))']
        for n, _, _, _ in geometry(pins):
            s.append(f'  (pin {quote(n)} (uuid "{uid(ref + "/" + n)}"))')
        s += [f'  (instances (project "power_study" (path "/{root}" (reference "{ref}") (unit 1))))', ' )']
        for n, _, net, py in geometry(pins):
            px, ay = x - 12.7, y - py
            if net:
                s.append(f' (label {quote(net)} (at {px:g} {ay:g} 0) (effects (font (size 0.8 0.8)) (justify right bottom)) (uuid "{uid(ref + "/label/" + n)}"))')
            else:
                s.append(f' (no_connect (at {px:g} {ay:g}) (uuid "{uid(ref + "/nc/" + n)}"))')
    s += [' (text "ETUDE PRELIMINAIRE - NON FABRICABLE - SYMBOLES ET EMPREINTES A VERIFIER" (at 165 405 0) (effects (font (size 2 2))))', ')']
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text('\n'.join(s) + '\n', encoding='utf-8')
    print(OUT)


if __name__ == '__main__':
    main()
