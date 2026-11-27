// Geometry for the corridor preview (render.js --corridor): a strip polygon
// along a great circle, the web-mercator tiles it touches at each zoom, and
// the mask GeoJSON (the union of the corridor's lowest-zoom tiles) that the
// app supplies as the `corridor-mask` source.
//
// Spherical earth (radius 6371 km), like the app's Geodesy.kt. Assumes the
// strip does not cross the antimeridian (true for the preview's LHR-JFK).
'use strict';

const R = 6371000;
const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;

/** Great-circle point at `fraction` (0..1) from a to b; points are [lng, lat]. */
function interpolate(a, b, fraction) {
  const [l1, p1] = [rad(a[0]), rad(a[1])];
  const [l2, p2] = [rad(b[0]), rad(b[1])];
  const d = 2 * Math.asin(Math.sqrt(Math.sin((p2 - p1) / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin((l2 - l1) / 2) ** 2));
  const A = Math.sin((1 - fraction) * d) / Math.sin(d);
  const B = Math.sin(fraction * d) / Math.sin(d);
  const x = A * Math.cos(p1) * Math.cos(l1) + B * Math.cos(p2) * Math.cos(l2);
  const y = A * Math.cos(p1) * Math.sin(l1) + B * Math.cos(p2) * Math.sin(l2);
  const z = A * Math.sin(p1) + B * Math.sin(p2);
  return [deg(Math.atan2(y, x)), deg(Math.atan2(z, Math.hypot(x, y)))];
}

function bearing(a, b) {
  const [l1, p1, l2, p2] = [rad(a[0]), rad(a[1]), rad(b[0]), rad(b[1])];
  const y = Math.sin(l2 - l1) * Math.cos(p2);
  const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(l2 - l1);
  return deg(Math.atan2(y, x));
}

function destination(from, bearingDeg, meters) {
  const [l1, p1, t, d] = [rad(from[0]), rad(from[1]), rad(bearingDeg), meters / R];
  const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(t));
  const l2 = l1 + Math.atan2(Math.sin(t) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2));
  return [deg(l2), deg(p2)];
}

/** The course arriving at b on the great circle from a. */
function finalCourse(a, b) {
  return (bearing(b, a) + 180) % 360;
}

/** The great circle from a to b as `segments` + 1 points. */
function greatCircle(a, b, segments = 100) {
  return Array.from({ length: segments + 1 }, (_, i) => interpolate(a, b, i / segments));
}

/**
 * A closed ring around the line `points` (at least 2), `halfWidth` metres to
 * each side, with half-round caps at both ends. Counter-clockwise in lng/lat.
 */
function strip(points, halfWidth) {
  const n = points.length;
  // Course along the line at point i (the last point uses the final course).
  const course = (i) => (i < n - 1 ? bearing(points[i], points[i + 1]) : finalCourse(points[i - 1], points[i]));
  const right = points.map((p, i) => destination(p, course(i) + 90, halfWidth));
  const left = points.map((p, i) => destination(p, course(i) - 90, halfWidth));
  // Seven points on a half circle, turning left from `from` (the right side's bearing).
  const cap = (p, from) => Array.from({ length: 7 }, (_, k) => destination(p, from - (k + 1) * (180 / 8), halfWidth));
  const ring = [
    ...right,
    ...cap(points[n - 1], course(n - 1) + 90),
    ...left.reverse(),
    ...cap(points[0], course(0) - 90),
  ];
  // Out on the right, back on the left: the line is on the left of the
  // ring's direction, so the ring is counter-clockwise, as GeoJSON wants.
  ring.push(ring[0]);
  return ring;
}

/** Web-mercator tile coordinates (fractional) of [lng, lat] at zoom z. */
function toTile([lng, lat], z) {
  const n = 2 ** z;
  const s = Math.sin(rad(lat));
  return [((lng + 180) / 360) * n, (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n];
}

function fromTile(x, y, z) {
  const n = 2 ** z;
  return [(x / n) * 360 - 180, deg(Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / n))))];
}

function pointInRing(pt, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if ((yi > pt[1]) !== (yj > pt[1]) && pt[0] < ((xj - xi) * (pt[1] - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

function segmentsCross(a, b, c, d) {
  const o = (p, q, r) => Math.sign((q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]));
  return o(a, b, c) !== o(a, b, d) && o(c, d, a) !== o(c, d, b);
}

/** "z/x/y" of every tile at zoom z that the ring touches. */
function tileCover(ring, z) {
  const t = ring.map((p) => toTile(p, z));
  const xs = t.map((p) => p[0]);
  const ys = t.map((p) => p[1]);
  const out = [];
  for (let x = Math.floor(Math.min(...xs)); x <= Math.floor(Math.max(...xs)); x++) {
    for (let y = Math.floor(Math.min(...ys)); y <= Math.floor(Math.max(...ys)); y++) {
      const corners = [[x, y], [x + 1, y], [x + 1, y + 1], [x, y + 1]];
      const hit = t.some((p) => p[0] >= x && p[0] <= x + 1 && p[1] >= y && p[1] <= y + 1)
        || corners.some((c) => pointInRing(c, t))
        || t.slice(1).some((p, i) => corners.some((c, k) => segmentsCross(t[i], p, c, corners[(k + 1) % 4])));
      if (hit) out.push(`${z}/${x}/${y}`);
    }
  }
  return out;
}

/** The four children of each "z/x/y" tile. */
function children(tiles) {
  return tiles.flatMap((t) => {
    const [z, x, y] = t.split('/').map(Number);
    return [[0, 0], [1, 0], [0, 1], [1, 1]].map(([dx, dy]) => `${z + 1}/${2 * x + dx}/${2 * y + dy}`);
  });
}

/**
 * The mask: one MultiPolygon covering the tiles, merged into one rectangle
 * per run of neighbouring tiles in a row. Tiles are "z/x/y" strings of one zoom.
 */
function maskGeoJson(tiles) {
  const rows = new Map();
  for (const t of tiles) {
    const [z, x, y] = t.split('/').map(Number);
    if (!rows.has(y)) rows.set(y, { z, xs: [] });
    rows.get(y).xs.push(x);
  }
  const polygons = [];
  for (const [y, { z, xs }] of rows) {
    xs.sort((a, b) => a - b);
    for (let i = 0; i < xs.length;) {
      let j = i;
      while (j + 1 < xs.length && xs[j + 1] === xs[j] + 1) j++;
      const [w, n] = fromTile(xs[i], y, z);
      const [e, s] = fromTile(xs[j] + 1, y + 1, z);
      polygons.push([[[w, s], [e, s], [e, n], [w, n], [w, s]]]);
      i = j + 1;
    }
  }
  return {
    type: 'FeatureCollection',
    features: [{ type: 'Feature', properties: {}, geometry: { type: 'MultiPolygon', coordinates: polygons } }],
  };
}

module.exports = { greatCircle, strip, tileCover, children, maskGeoJson, toTile, fromTile };
