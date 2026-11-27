#!/usr/bin/env bash
# Rebuild Flyoff's bundled offline world map:
#   app/src/main/assets/basemap/world.pmtiles   zoom 0..MAXZOOM extract of OpenFreeMap's planet,
#                                                cut down to the layers and names our style uses
#   app/src/main/assets/fonts/<font>/<range>.pbf MapLibre glyphs, Unicode ranges below U+0600
#   app/src/main/assets/basemap/tile_sizes.bin  per zoom-5 tile, mean and max tile size at zooms 6-9
#                                                (read from the planet's directories, no tiles)
#
# Usage:  tools/basemap/build_basemap.sh            (run from anywhere)
#         MAXZOOM=4 tools/basemap/build_basemap.sh  (override the zoom, e.g. to measure)
#
# Needs bash, curl, python3, tar/unzip and sha256sum (or shasum). Downloads the pinned
# go-pmtiles release into ~/.cache/flyoff (outside the repo) and checks its SHA-256.
# The planet is never downloaded whole: `pmtiles extract` reads only the byte
# ranges it needs over HTTP.
#
# Licences: map data © OpenStreetMap contributors (ODbL), schema © OpenMapTiles
# (CC-BY 4.0), tiles built by OpenFreeMap; Noto Sans glyphs are SIL OFL 1.1.
# Attribution shown in the app: "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors".
set -euo pipefail

# ---- Pinned inputs (bump these together, then re-run and review the diff) ----

# OpenFreeMap planet build. OpenFreeMap keeps the last 4 weekly runs plus the
# first run of each of the last 6 months, so pin a first-of-month run.
# Index of builds: https://btrfs.openfreemap.com/files.txt
OFM_BUILD="20261101_080013_pt"   # OSM data date 2026-10-26
OFM_URL="https://btrfs.openfreemap.com/areas/planet/${OFM_BUILD}/tiles.pmtiles"
# Size of that file in bytes (HTTP Content-Length). The script stops if the
# remote file differs, so a replaced build can't slip in unnoticed.
OFM_BYTES="87352499305"

MAXZOOM="${MAXZOOM:-5}"

# go-pmtiles (BSD-3-Clause), https://github.com/protomaps/go-pmtiles
# Upstream publishes no checksum file, so these SHA-256 values were recorded
# when the archives were first downloaded (2026-11-26).
PMTILES_VERSION="1.31.2"

# Fonts: protomaps/basemaps-assets at a pinned commit (glyphs made with
# maplibre/font-maker from Noto Sans, SIL OFL 1.1).
FONTS_COMMIT="028c18f713baecad011301ff7a69acc39bcc2ae7"
FONTS_BASE="https://raw.githubusercontent.com/protomaps/basemaps-assets/${FONTS_COMMIT}/fonts"
FONTS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic")
# Latin, Latin Extended, IPA, Greek, Cyrillic, Armenian, Hebrew (below U+0600).
RANGES=("0-255" "256-511" "512-767" "768-1023" "1024-1279" "1280-1535")

# ---- Paths ----

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ASSETS="${REPO_ROOT}/app/src/main/assets"
CACHE="${XDG_CACHE_HOME:-${HOME}/.cache}/flyoff"
FONTS_SHA="${SCRIPT_DIR}/fonts.sha256"

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# ---- 1. go-pmtiles ----

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)  PM_ARCHIVE="go-pmtiles_${PMTILES_VERSION}_Linux_x86_64.tar.gz"
                 PM_SHA="3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f" ;;
  Linux-aarch64) PM_ARCHIVE="go-pmtiles_${PMTILES_VERSION}_Linux_arm64.tar.gz"
                 PM_SHA="f8bd47e7ea866863489cad588fbaf2f31f42e5821f7a03f009b3769f05801cb1" ;;
  Darwin-arm64)  PM_ARCHIVE="go-pmtiles-${PMTILES_VERSION}_Darwin_arm64.zip"
                 PM_SHA="40528f7f616fcbf91207cd48c8fc023d213f6d86c0cbf1f748732803d1880f3d" ;;
  Darwin-x86_64) PM_ARCHIVE="go-pmtiles-${PMTILES_VERSION}_Darwin_x86_64.zip"
                 PM_SHA="1f0dc02eee6c58312dd6c509faee1b5c32f0596568af1bf51f1b034e7a88a65b" ;;
  *) echo "Unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

PM_DIR="${CACHE}/go-pmtiles-${PMTILES_VERSION}"
PMTILES="${PM_DIR}/pmtiles"
if [[ ! -x "${PMTILES}" ]]; then
  mkdir -p "${PM_DIR}"
  echo "Downloading ${PM_ARCHIVE}"
  curl -fsSL -o "${PM_DIR}/${PM_ARCHIVE}" \
    "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/${PM_ARCHIVE}"
  got="$(sha256 "${PM_DIR}/${PM_ARCHIVE}")"
  if [[ "${got}" != "${PM_SHA}" ]]; then
    echo "SHA-256 mismatch for ${PM_ARCHIVE}: got ${got}, want ${PM_SHA}" >&2
    rm -f "${PM_DIR}/${PM_ARCHIVE}"; exit 1
  fi
  case "${PM_ARCHIVE}" in
    *.tar.gz) tar -xzf "${PM_DIR}/${PM_ARCHIVE}" -C "${PM_DIR}" pmtiles ;;
    *.zip)    unzip -o -q "${PM_DIR}/${PM_ARCHIVE}" pmtiles -d "${PM_DIR}" ;;
  esac
  chmod +x "${PMTILES}"
fi
"${PMTILES}" version

# ---- 2. World map extract ----

remote_bytes="$(curl -fsSI "${OFM_URL}" | tr -d '\r' | awk 'tolower($1)=="content-length:" {print $2}' | tail -n1)"
if [[ "${remote_bytes}" != "${OFM_BYTES}" ]]; then
  echo "Remote planet size is '${remote_bytes}', expected ${OFM_BYTES}." >&2
  echo "The pinned build changed or was deleted; pick a new one from https://btrfs.openfreemap.com/files.txt" >&2
  exit 1
fi

mkdir -p "${ASSETS}/basemap"
OUT="${ASSETS}/basemap/world.pmtiles"
EXTRACT="${CACHE}/world.z${MAXZOOM}.extract.pmtiles"
SHRUNK="${CACHE}/world.z${MAXZOOM}.shrunk.mbtiles"
TMP="${CACHE}/world.z${MAXZOOM}.pmtiles.tmp"
rm -f "${EXTRACT}" "${SHRUNK}" "${TMP}"
# 4 download threads: the same limit OpenFreeMap sets for app downloads.
"${PMTILES}" extract "${OFM_URL}" "${EXTRACT}" --maxzoom="${MAXZOOM}" --download-threads=4
"${PMTILES}" verify "${EXTRACT}"

# Keep only the layers flyoff.json uses and the names in mvt.KEEP_NAMES
# (the planet's ~80 name translations are most of the bytes at low zoom).
# This also writes metadata with the real zoom range: the extract keeps the
# planet's "maxzoom": "14", and MapLibre Native takes the zoom range from that
# metadata before the header (pmtiles_file_source.cpp), so it would ask for
# zoom 6+ tiles, get nothing, and draw an empty map instead of overzooming.
python3 -I "${SCRIPT_DIR}/shrink_tiles.py" "${EXTRACT}" "${ASSETS}/style/flyoff.json" "${SHRUNK}"
"${PMTILES}" convert "${SHRUNK}" "${TMP}" --tmpdir="${CACHE}"
"${PMTILES}" verify "${TMP}"
mv "${TMP}" "${OUT}"
rm -f "${EXTRACT}" "${SHRUNK}"
"${PMTILES}" show "${OUT}"

# ---- 3. Tile size table (for route download size estimates) ----

python3 -I "${SCRIPT_DIR}/tile_size_table.py" "${OFM_URL}" "${OFM_BUILD}" "${ASSETS}/basemap/tile_sizes.bin"

# ---- 4. Glyphs ----

for font in "${FONTS[@]}"; do
  mkdir -p "${ASSETS}/fonts/${font}"
  for range in "${RANGES[@]}"; do
    curl -fsSL -o "${ASSETS}/fonts/${font}/${range}.pbf" \
      "${FONTS_BASE}/${font// /%20}/${range}.pbf"
  done
done
curl -fsSL -o "${ASSETS}/fonts/OFL.txt" "${FONTS_BASE}/OFL.txt"

# Check the glyphs against the committed list (written on first run).
( cd "${ASSETS}/fonts"
  if [[ -f "${FONTS_SHA}" ]]; then
    if command -v sha256sum >/dev/null; then sha256sum -c --quiet "${FONTS_SHA}"
    else shasum -a 256 -c --quiet "${FONTS_SHA}"; fi
  else
    find . -type f | LC_ALL=C sort | while read -r f; do echo "$(sha256 "$f")  $f"; done > "${FONTS_SHA}"
    echo "Wrote ${FONTS_SHA}; review and commit it."
  fi )

echo
echo "Done. Asset sizes:"
du -sh "${OUT}" "${ASSETS}/fonts" "${ASSETS}/basemap/tile_sizes.bin"
