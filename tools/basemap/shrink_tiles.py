"""Shrink a PMTiles extract to what Flyoff's style uses, written as MBTiles
(then turned back into PMTiles with `pmtiles convert`, which deduplicates).

- Keeps only the vector layers the style references (read from the style
  JSON, so style and data can't drift apart).
- In every feature keeps only the name attributes in mvt.KEEP_NAMES; all
  non-name attributes stay.
- Writes JSON metadata whose minzoom/maxzoom match the archive and whose
  vector_layers list only the kept layers and fields. MapLibre Native reads
  the zoom range from this metadata before the header.

Usage: python3 -I tools/basemap/shrink_tiles.py IN.pmtiles STYLE.json OUT.mbtiles
"""
import gzip
import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mvt  # noqa: E402
from pmtiles_read import Archive, id_to_zxy  # noqa: E402


def style_usage(style):
    """(source-layers, attribute names read with "get") of the style's vector layers."""
    vector_sources = {k for k, s in style["sources"].items() if s.get("type") == "vector"}
    layers, fields = set(), set()

    def walk(x):
        if isinstance(x, list):
            if len(x) >= 2 and x[0] == "get" and isinstance(x[1], str):
                fields.add(x[1])
            for y in x:
                walk(y)
        elif isinstance(x, dict):
            for y in x.values():
                walk(y)

    for layer in style["layers"]:
        if layer.get("source") in vector_sources:
            layers.add(layer["source-layer"])
            for part in ("filter", "layout", "paint"):
                walk(layer.get(part))
    return layers, fields


def gzip_deterministic(data):
    return gzip.compress(data, compresslevel=9, mtime=0)


def main(src, style_path, out):
    with open(style_path, encoding="utf-8") as f:
        keep_layers, used_fields = style_usage(json.load(f))
    dropped_names = sorted(k for k in used_fields if mvt.is_name_key(k) and k not in mvt.KEEP_NAMES)
    if dropped_names:
        sys.exit(f"The style reads name attributes this step drops: {dropped_names}")

    archive = Archive(src)
    meta = archive.metadata()
    known = {v["id"] for v in meta["vector_layers"]}
    missing = sorted(keep_layers - known)
    if missing:
        sys.exit(f"The style uses source layers the tiles don't have: {missing}")

    vector_layers = []
    for v in meta["vector_layers"]:
        if v["id"] not in keep_layers:
            continue
        v = dict(v)
        v["fields"] = {k: t for k, t in sorted(v["fields"].items())
                       if not mvt.is_name_key(k) or k in mvt.KEEP_NAMES}
        v["maxzoom"] = min(int(v.get("maxzoom", archive.max_zoom)), archive.max_zoom)
        vector_layers.append(v)

    rows = [("format", "pbf"), ("compression", "gzip")]  # format first: pmtiles convert needs it before compression
    for k in sorted(meta):
        if k not in ("vector_layers", "format", "compression", "minzoom", "maxzoom", "bounds", "center"):
            rows.append((k, str(meta[k])))
    w, s, e, n = archive.bounds
    rows += [
        ("minzoom", str(archive.min_zoom)),
        ("maxzoom", str(archive.max_zoom)),
        ("bounds", f"{w},{s},{e},{n}"),
        ("center", f"{archive.center[0]},{archive.center[1]},{archive.center_zoom}"),
        ("json", json.dumps({"vector_layers": vector_layers}, ensure_ascii=False, sort_keys=True)),
    ]

    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    db.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    db.executemany("INSERT INTO metadata VALUES (?, ?)", rows)

    rewritten = {}  # offset in the source archive -> new tile bytes
    before = after = 0
    for tile_id, run, offset, length in archive.entries():
        if offset not in rewritten:
            layers = mvt.strip(mvt.decode(archive.tile(offset, length)), keep_layers)
            rewritten[offset] = gzip_deterministic(mvt.encode(layers))
            before += length
            after += len(rewritten[offset])
        for t in range(tile_id, tile_id + run):
            z, x, y = id_to_zxy(t)
            db.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (z, x, (1 << z) - 1 - y, rewritten[offset]))
    db.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
    db.commit()
    db.close()
    print(f"Kept layers: {', '.join(sorted(keep_layers))}")
    print(f"Unique tiles: {len(rewritten)}, {before / 1e6:.1f} MB -> {after / 1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
