"""Regression checks for the draft's declared nets, not electrical approval.

Run: python -m unittest discover -s hardware -p test_electrical_study.py
The checks inspect generator input only; KiCad ERC/DRC, footprints, component
ratings, PCB routing and physical validation are still required.
"""
import unittest

from generate_power_draft import parts


class StudyConnectivity(unittest.TestCase):
    def setUp(self):
        self.pins = {}
        for ref, _, _, _, pins in parts:
            for number, _, net in pins:
                key = (ref, number)
                self.assertNotIn(key, self.pins, f"duplicate pin {key}")
                self.pins[key] = net

    def check_nets(self, expected):
        for pin, net in expected.items():
            self.assertEqual(self.pins.get(pin), net, f"wrong net on {pin}")

    def test_usb_and_power_mux(self):
        self.check_nets({
            ("J1", "A5"): "USB_CC1", ("J1", "B5"): "USB_CC2",
            ("R2", "1"): "USB_CC1", ("R2", "2"): "GND",
            ("R3", "1"): "USB_CC2", ("R3", "2"): "GND",
            ("U3", "1"): "USB_VBUS", ("U3", "5"): "3V3_USB",
            ("U4", "3"): "3V3_USB", ("U4", "5"): "GND",
            ("U4", "6"): "3V3_AUX", ("U4", "2"): "3V3",
            ("U4", "7"): "3V3", ("U4", "1"): "GND",
            ("U4", "4"): "GND",
            ("U1", "25"): "USB_DN", ("U1", "26"): "USB_DP",
        })
        for pin in ("A1", "A12", "B1", "B12", "SH"):
            self.assertEqual(self.pins[("J1", pin)], "GND")
        for pin in ("A4", "A9", "B4", "B9"):
            self.assertEqual(self.pins[("J1", pin)], "USB_VBUS")

    def test_motor_output_is_a_separate_two_terminal_net(self):
        self.check_nets({
            ("J4", "1"): "MOTOR_A", ("J4", "2"): "MOTOR_B",
            ("U5", "4"): "MOTOR_A", ("U5", "3"): "MOTOR_B",
            ("U5", "1"): "OPTO_A", ("U5", "2"): "OPTO_K",
            ("Q1", "3"): "OPTO_K", ("Q1", "2"): "GND",
        })
        for net, allowed in (("MOTOR_A", {("J4", "1"), ("U5", "4")}),
                             ("MOTOR_B", {("J4", "2"), ("U5", "3")})):
            self.assertEqual({pin for pin, value in self.pins.items() if value == net}, allowed)

    def test_gpio_assignment_and_reset(self):
        self.check_nets({
            ("U1", "4"): "CHIP_PU", ("R18", "1"): "3V3",
            ("R18", "2"): "CHIP_PU", ("C15", "1"): "CHIP_PU",
            ("C15", "2"): "GND", ("SW2", "1"): "CHIP_PU",
            ("SW2", "2"): "GND", ("U1", "5"): "BOOT",
            ("U1", "10"): "WHEEL_GPIO", ("U1", "11"): "PULSE_CMD",
            ("U1", "27"): "LED_BLUE", ("R13", "1"): "PULSE_CMD",
            ("R13", "2"): "GND", ("R15", "1"): "PULSE_CMD",
            ("R15", "2"): "OPTO_BASE", ("R16", "1"): "OPTO_BASE",
            ("R16", "2"): "GND", ("Q1", "1"): "OPTO_BASE",
        })

    def test_hold_up_capacitors_connect_to_common_output(self):
        for ref in ("C13", "C29", "C30", "C31"):
            self.assertEqual(self.pins[(ref, "1")], "3V3")
            self.assertEqual(self.pins[(ref, "2")], "GND")
        c31 = next(part for part in parts if part[0] == "C31")
        self.assertIn("DNP", c31[1])

    def test_debug_test_points(self):
        for ref, net in (("TP1", "3V3"), ("TP2", "GND"),
                         ("TP3", "CHIP_PU"), ("TP4", "BOOT"),
                         ("TP5", "U0TXD"), ("TP6", "U0RXD")):
            self.assertEqual(self.pins[(ref, "1")], net)


if __name__ == "__main__":
    unittest.main()
