"""Prepare public OTA assets from an ESP-IDF application binary (never a merged flash image)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct

REPO = "sctfic/VAE-Unbridler"
BINARY = "ebike-esp32s3-hw-v1.bin"
MANIFEST = "ebike-esp32s3-hw-v1-ota.json"
MAX_SIZE = 0x1E0000


def inspect_image(data: bytes) -> tuple[str, int]:
    if not 288 <= len(data) <= MAX_SIZE or data[0] != 0xE9:
        raise ValueError("Expected an ESP application image fitting a 1920 KiB OTA slot")
    if struct.unpack_from("<H", data, 12)[0] != 9:
        raise ValueError("Expected ESP32-S3 chip ID 9")
    if struct.unpack_from("<I", data, 32)[0] != 0xABCD5432:
        raise ValueError("Application description missing (do not use a merged flash binary)")
    version = data[48:80].split(b"\0", 1)[0].decode("ascii")
    project = data[80:112].split(b"\0", 1)[0].decode("ascii")
    if project != "ebike_firmware_hw_v1" or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or len(version) > 23:
        raise ValueError("Unexpected project/version in the application image")
    codes = set(re.findall(rb"EBIKE-OTA-CODE:([0-9]+):END", data))
    if len(codes) != 1 or not 1 <= int(next(iter(codes))) <= 0x7FFFFFFF:
        raise ValueError("OTA version code missing or ambiguous in the compiled image")
    # Verify segment boundaries, ROM checksum and the image's appended SHA-256.
    position, checksum = 24, 0xEF
    for _ in range(data[1]):
        if position + 8 > len(data):
            raise ValueError("Truncated image segment header")
        length = struct.unpack_from("<I", data, position + 4)[0]
        position += 8
        if position + length > len(data):
            raise ValueError("Truncated image segment")
        for byte in data[position:position + length]:
            checksum ^= byte
        position += length
    checksum_at = (position // 16 + 1) * 16 - 1
    image_end = checksum_at + 1
    if checksum_at >= len(data) or data[checksum_at] != checksum:
        raise ValueError("Invalid image checksum")
    if data[23] != 1 or len(data) != image_end + 32 or hashlib.sha256(data[:image_end]).digest() != data[image_end:]:
        raise ValueError("Invalid or missing appended image SHA-256")
    return version, int(next(iter(codes)))


def package(binary: Path, output: Path) -> dict:
    data = binary.read_bytes()
    version, code = inspect_image(data)
    manifest = {
        "schema": 1, "target": "ebike-esp32s3-hw-v1", "hardware_revision": "V1.0", "ota_protocol": 1,
        "version": version, "version_code": code, "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "download_url": f"https://github.com/{REPO}/releases/download/esp32-hw-v1-v{version}/{BINARY}",
    }
    output.mkdir(parents=True, exist_ok=True)
    if binary.resolve() != (output / BINARY).resolve():
        shutil.copyfile(binary, output / BINARY)
    (output / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=Path("firmware-esp32/build/ebike_firmware_hw_v1.bin"))
    parser.add_argument("--output", type=Path, default=Path("firmware-esp32/build/ota"))
    args = parser.parse_args()
    manifest = package(args.binary, args.output)
    print(f"Prepared esp32-hw-v1-v{manifest['version']} (code {manifest['version_code']}, {manifest['size']} bytes): {args.output.resolve()}")
