# Byte statistics for a local PMTiles v3 vector archive: per zoom, and per
# vector-tile layer (split into geometry and attribute bytes).
# Each distinct tile is counted once, as the archive stores it.
#
# Usage: python3 -I tools/basemap/tile_stats.py app/src/main/assets/basemap/world.pmtiles
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mvt  # noqa: E402
from pmtiles_read import Archive, zoom_of  # noqa: E402


def main(path):
    archive = Archive(path)
    seen = set()
    per_zoom = defaultdict(lambda: [0, 0])      # zoom -> [tiles, stored bytes]
    per_layer = defaultdict(lambda: [0, 0])     # (zoom, layer) -> [geometry, attributes]
    for tile_id, run, offset, length in archive.entries():
        z = zoom_of(tile_id)
        per_zoom[z][0] += run
        if offset in seen:
            continue
        seen.add(offset)
        per_zoom[z][1] += length
        for layer in mvt.decode(archive.tile(offset, length)):
            s = per_layer[(z, layer.name)]
            s[0] += sum(len(f.geometry) for f in layer.features)
            s[1] += (sum(len(k) for k in layer.keys) + sum(len(v) for v in layer.values)
                     + sum(len(f.tags) for f in layer.features))

    mb = 1e6
    print("zoom  tiles  stored MB  cumulative MB")
    total = 0
    for z in sorted(per_zoom):
        total += per_zoom[z][1]
        print(f"{z:>4} {per_zoom[z][0]:>6} {per_zoom[z][1] / mb:>10.2f} {total / mb:>14.2f}")

    print("\nUncompressed MB per layer and zoom (geometry / attributes, approximate):")
    zooms = sorted({k[0] for k in per_layer})
    print(f"{'layer':<20}" + "".join(f"{'z' + str(z):>14}" for z in zooms))
    for name in sorted({k[1] for k in per_layer}):
        row = f"{name:<20}"
        for z in zooms:
            s = per_layer.get((z, name))
            row += f"{'':>14}" if not s else f"{s[0] / mb:>7.2f}/{s[1] / mb:<6.2f}"
        print(row)


if __name__ == "__main__":
    main(sys.argv[1])
