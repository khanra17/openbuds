# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the original OpenBuds ring/signal mark (no product art or dependencies)."""
from pathlib import Path
import math
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / "apps/compose/desktopApp"


def png(size):
    rows = bytearray()
    for y in range(size):
        rows.append(0)  # PNG filter: None
        for x in range(size):
            samples = []
            for sy in range(4):
                for sx in range(4):
                    px = (x + (sx + 0.5) / 4) * 48 / size
                    py = (y + (sy + 0.5) / 4) * 48 / size
                    ring = math.hypot(px - 24, py - 24)
                    signal = math.hypot(px - 35, py - 13)
                    samples.append((232, 74, 60, 255) if signal <= 4 else
                                   (244, 242, 236, 255) if 11.5 <= ring <= 16.5 else
                                   (8, 10, 9, 255))
            rows.extend(round(sum(s[i] for s in samples) / len(samples)) for i in range(4))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def main():
    resources = ROOT / "src/main/resources"
    packaging = ROOT / "packaging"
    resources.mkdir(parents=True, exist_ok=True)
    packaging.mkdir(parents=True, exist_ok=True)
    (resources / "openbuds.png").write_bytes(png(256))
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = [png(size) for size in sizes]
    header = bytearray(struct.pack("<HHH", 0, 1, len(images)))
    offset = 6 + 16 * len(images)
    for size, data in zip(sizes, images):
        header.extend(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(data), offset))
        offset += len(data)
    (packaging / "openbuds.ico").write_bytes(header + b"".join(images))


if __name__ == "__main__":
    main()
