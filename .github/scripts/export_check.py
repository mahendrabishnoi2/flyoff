"""Check a track exported by Flyoff (stdlib only): a GPX 1.1 or KML 2.2 file.

Usage: python3 -I export_check.py <file.gpx|file.kml>

Parses the file as XML and checks what the app promises: the right namespace
(and version="1.1" for GPX), at least one track point, every point with a
latitude and longitude in range and an ISO-8601 UTC time ("...Z"), elevations
that are numbers, and the two airports (GPX waypoints, KML point placemarks).
For KML, gx:Track's <when> and <gx:coord> counts must match and each coord is
"lon lat [alt]". Prints "<n> track points, <w> waypoints, <first time> to
<last time>"; exits 1 with a message on any problem.
"""
import re
import sys
import xml.etree.ElementTree as ET

GPX = "{http://www.topografix.com/GPX/1/1}"
KML = "{http://www.opengis.net/kml/2.2}"
GX = "{http://www.google.com/kml/ext/2.2}"
UTC = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z")


class Problem(Exception):
    pass


def number(text, what):
    try:
        return float(text)
    except (TypeError, ValueError):
        raise Problem(f"{what} isn't a number: {text!r}")


def lat_lon(lat, lon, what):
    lat, lon = number(lat, f"{what} latitude"), number(lon, f"{what} longitude")
    if not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise Problem(f"{what} is out of range: {lat}, {lon}")


def time(text, what):
    if not text or not UTC.fullmatch(text):
        raise Problem(f"{what} time isn't ISO-8601 UTC: {text!r}")
    return text


def check_gpx(root):
    if root.tag != GPX + "gpx":
        raise Problem(f"root is {root.tag}, not GPX 1.1's gpx")
    if root.get("version") != "1.1":
        raise Problem(f"version is {root.get('version')!r}, not 1.1")
    points = root.findall(f"{GPX}trk/{GPX}trkseg/{GPX}trkpt")
    times = []
    for i, p in enumerate(points):
        lat_lon(p.get("lat"), p.get("lon"), f"trkpt {i}")
        ele = p.find(GPX + "ele")
        if ele is not None:
            number(ele.text, f"trkpt {i} ele")
        times.append(time(getattr(p.find(GPX + "time"), "text", None), f"trkpt {i}"))
    waypoints = root.findall(GPX + "wpt")
    for i, w in enumerate(waypoints):
        lat_lon(w.get("lat"), w.get("lon"), f"wpt {i}")
    return len(points), len(waypoints), times


def check_kml(root):
    if root.tag != KML + "kml":
        raise Problem(f"root is {root.tag}, not KML 2.2's kml")
    whens, coords = [], []
    for track in root.iter(GX + "Track"):
        whens += [w.text for w in track.findall(KML + "when")]
        coords += [c.text for c in track.findall(GX + "coord")]
    if len(whens) != len(coords):
        raise Problem(f"{len(whens)} times but {len(coords)} coordinates")
    times = [time(w, f"when {i}") for i, w in enumerate(whens)]
    for i, c in enumerate(coords):
        parts = (c or "").split()
        if len(parts) not in (2, 3):
            raise Problem(f"gx:coord {i} isn't 'lon lat [alt]': {c!r}")
        lat_lon(parts[1], parts[0], f"gx:coord {i}")
        if len(parts) == 3:
            number(parts[2], f"gx:coord {i} altitude")
    waypoints = 0
    for point in root.iter(KML + "Point"):
        lon, lat = (getattr(point.find(KML + "coordinates"), "text", "") or "").strip().split(",")[:2]
        lat_lon(lat, lon, "placemark")
        waypoints += 1
    return len(coords), waypoints, times


def check(path):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as e:
        raise Problem(f"not well-formed XML ({e})")
    points, waypoints, times = check_kml(root) if root.tag.endswith("}kml") else check_gpx(root)
    if points < 1:
        raise Problem("no track points")
    if waypoints != 2:
        raise Problem(f"{waypoints} airports, not 2")
    return f"{points} track points, {waypoints} waypoints, {times[0]} to {times[-1]}"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    try:
        print(check(sys.argv[1]))
    except Problem as e:
        print(f"{sys.argv[1]}: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
