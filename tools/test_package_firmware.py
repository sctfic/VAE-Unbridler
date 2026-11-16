import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("package_firmware", Path(__file__).with_name("package-firmware.py"))
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


def image():
    header = bytearray(24)
    header[0], header[1], header[23] = 0xE9, 1, 1
    struct.pack_into("<H", header, 12, 9)
    desc = bytearray(256)
    struct.pack_into("<I", desc, 0, 0xABCD5432)
    desc[16:21] = b"0.4.1"
    desc[48:68] = b"ebike_firmware_hw_v1"
    payload = desc + b"EBIKE-OTA-CODE:2:END\0"
    data = header + struct.pack("<II", 0x3C000020, len(payload)) + payload
    checksum = 0xEF
    for byte in payload:
        checksum ^= byte
    while len(data) % 16 != 15:
        data.append(0)
    data.append(checksum)
    return bytes(data + hashlib.sha256(data).digest())


class PackageFirmwareTest(unittest.TestCase):
    def test_manifest_matches_actual_image_identity_and_hash(self):
        data = image()
        self.assertEqual(packager.inspect_image(data), ("0.4.1", 2))
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "app.bin"
            source.write_bytes(data)
            result = packager.package(source, Path(temp) / "ota")
            self.assertEqual(result["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(result["size"], len(data))
            self.assertEqual(result["version_code"], 2)
            self.assertEqual(result["hardware_revision"], "V1.0")
            self.assertTrue(result["download_url"].endswith("/esp32-hw-v1-v0.4.1/ebike-esp32s3-hw-v1.bin"))

    def test_rejects_truncated_corrupt_wrong_chip_and_merged_images(self):
        valid = image()
        variants = [valid[:-1], b"\xff" * 32 + valid, valid + b"extra"]
        for offset in (0, 12, 32, 80, 130, len(valid) - 1):
            changed = bytearray(valid)
            changed[offset] ^= 1
            variants.append(bytes(changed))
        for data in variants:
            with self.subTest(size=len(data)):
                with self.assertRaises((ValueError, UnicodeDecodeError)):
                    packager.inspect_image(data)


if __name__ == "__main__":
    unittest.main()
