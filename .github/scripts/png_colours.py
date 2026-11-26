"""Count distinct colours in a PNG screenshot (stdlib only).

Usage: python3 -I png_colours.py <file.png> [min-colours]
Prints the count. With min-colours, exits 1 if the image has fewer, which
catches a blank or single-colour map. Supports 8-bit RGB/RGBA, non-interlaced
PNGs, which is what `adb exec-out screencap -p` produces.
"""
import struct
import sys
import zlib


def pixels(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        sys.exit(f"{path}: not a PNG")
    pos, idat = 8, b""
    width = height = channels = None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or colour not in (2, 6) or interlace:
                sys.exit(f"{path}: unsupported PNG (depth {depth}, colour type {colour}, interlace {interlace})")
            channels = 3 if colour == 2 else 4
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = width * channels
    prev = bytearray(stride)
    i = 0
    for _ in range(height):
        f, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b, c = prev[x], (prev[x - channels] if x >= channels else 0)
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        yield line, channels
        prev = line


colours = set()
for row, ch in pixels(sys.argv[1]):
    for x in range(0, len(row), ch * 4):  # sample every 4th pixel
        colours.add(bytes(row[x:x + 3]))
print(len(colours))
if len(sys.argv) > 2 and len(colours) < int(sys.argv[2]):
    sys.exit(f"{sys.argv[1]}: only {len(colours)} colours, expected at least {sys.argv[2]}")
