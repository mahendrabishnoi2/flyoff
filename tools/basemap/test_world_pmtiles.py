"""Checks on the bundled world map, app/src/main/assets/basemap/world.pmtiles.

Run: python3 -I -m unittest discover -s tools/basemap -p 'test_*.py'

MapLibre Native takes the zoom range from the archive's JSON metadata before
the header. If the metadata says more than the tiles hold (the planet extract
says "maxzoom": "14"), Native asks for zoom 6+ tiles, gets nothing and draws
an empty map instead of overzooming the zoom-5 tiles.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pmtiles_read import Archive  # noqa: E402

WORLD = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "app", "src", "main", "assets", "basemap", "world.pmtiles")


def zoom_problems(header_min, header_max, meta):
    """Ways the metadata's zoom range disagrees with the header's; empty if none."""
    problems = []
    for key, header in (("minzoom", header_min), ("maxzoom", header_max)):
        if key not in meta:
            problems.append(f"metadata has no {key}")
        elif int(meta[key]) != header:
            problems.append(f"metadata {key} is {meta[key]!r}, header says {header}")
    for layer in meta.get("vector_layers", []):
        if "maxzoom" in layer and int(layer["maxzoom"]) > header_max:
            problems.append(f"vector layer {layer['id']} maxzoom {layer['maxzoom']} > header maxzoom {header_max}")
    return problems


class WorldMapZooms(unittest.TestCase):
    def test_metadata_zooms_match_header(self):
        archive = Archive(WORLD)
        self.assertEqual(zoom_problems(archive.min_zoom, archive.max_zoom, archive.metadata()), [])

    def test_planet_metadata_is_caught(self):
        meta = {"minzoom": "0", "maxzoom": "14", "vector_layers": [{"id": "water", "maxzoom": 14}]}
        self.assertEqual(len(zoom_problems(0, 5, meta)), 2)
        self.assertEqual(zoom_problems(0, 5, {"vector_layers": []}),
                         ["metadata has no minzoom", "metadata has no maxzoom"])


if __name__ == "__main__":
    unittest.main()
