// Screenshots of the bundled world map with Flyoff's style, rendered by
// MapLibre GL JS in headless Chromium (Playwright). GL JS stands in for
// MapLibre Native: a good check of the style and data, not proof of the app.
//
// Usage: node tools/basemap/preview/render.js [output dir]
//   Default output dir: tools/basemap/preview/screenshots
//   Needs Node and the `playwright` package with Chromium (not a repo dependency).
//
// The page may only reach this script's local server and cdn.jsdelivr.net
// (for the pinned GL JS and pmtiles scripts); every other request is
// blocked and reported, so the map is shown to need no other network.
//
// Corridor mode (Milestone 4): node tools/basemap/preview/render.js --corridor [output dir]
//   Default output dir: tools/basemap/preview/screenshots/corridor
//   Draws style/corridor.json over the world map along a London-New York strip
//   (175 km each side of the great circle), with the route line the app draws,
//   and the same views without the corridor (*-world.png) to compare.
//   Corridor tiles come from OpenFreeMap (tiles.openfreemap.org), but only
//   the ones prepareCorridor() lists as downloaded: requests for others fail,
//   as offline in the app.
//   Tiles are cached in ~/.cache/flyoff/corridor-tiles, fetched at most 4 at a
//   time with a User-Agent naming Flyoff, and only for the views rendered.
'use strict';

const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');
const { chromium } = require('playwright');
const corridor = require('./corridor');

const ASSETS = path.resolve(__dirname, '../../../app/src/main/assets');
const PAGE = path.join(__dirname, 'index.html');
const CORRIDOR = process.argv.includes('--corridor');
// --only=a,b renders just the named views (corridor mode), e.g. while changing the style.
const ONLY = process.argv.find((a) => a.startsWith('--only='))?.slice('--only='.length).split(',');
const OUT = path.resolve(process.argv.slice(2).find((a) => !a.startsWith('--'))
  || path.join(__dirname, 'screenshots', CORRIDOR ? 'corridor' : ''));

const VIEWS = [
  { name: 'world-z1', lng: 0, lat: 20, zoom: 1 },
  { name: 'north-atlantic-z3', lng: -40, lat: 52, zoom: 3 },
  { name: 'europe-z4', lng: 12, lat: 50, zoom: 4 },
  { name: 'india-z5', lng: 79, lat: 22, zoom: 5 },
  // Past the data's zoom 5: overzoomed tiles and the smaller cities.
  { name: 'uk-z6', lng: -3, lat: 54, zoom: 6 },
  { name: 'uk-z8', lng: -2, lat: 53, zoom: 8 },
];

// LHR to JFK. Each view is rendered with the corridor and without it (-world).
const ROUTE = { from: [-0.4543, 51.47], to: [-73.7781, 40.6413], halfWidthMeters: 175000 };
const CORRIDOR_MINZOOM = 6;
const CORRIDOR_VIEWS = [
  { name: 'uk-z6', lng: -3.5, lat: 53.6, zoom: 6 },
  { name: 'uk-z7', lng: -1.2, lat: 51.8, zoom: 7 },
  { name: 'uk-z8', lng: -0.6, lat: 51.6, zoom: 8 },
  // The optional detailed download: zoom-9 tiles, shown at zoom 9.
  { name: 'uk-z9', lng: -0.45, lat: 51.5, zoom: 9, maxzoom: 9 },
  // The strip's north edge (about 54.3N here): past it no zoom-8 tiles, so the zoom-7 ones are drawn.
  { name: 'ireland-edge-z8', lng: -6.6, lat: 54.3, zoom: 8 },
  // Where the downloaded zoom-6 tiles (and the mask) end: 55.78N across Scotland, 61.88W across Quebec.
  { name: 'scotland-edge-z7', lng: -4.6, lat: 55.8, zoom: 7 },
  { name: 'quebec-edge-z7', lng: -62.2, lat: 50.2, zoom: 7 },
  { name: 'newfoundland-z7', lng: -56, lat: 48.7, zoom: 7 },
  { name: 'mid-atlantic-z7', lng: -30, lat: 53.4, zoom: 7 },
  // A corner of the mask in open ocean (rows of zoom-6 tiles start at 50.6W and 61.9W).
  { name: 'atlantic-edge-z6', lng: -51, lat: 53.5, zoom: 6 },
  { name: 'new-york-z8', lng: -73.6, lat: 40.9, zoom: 8 },
];
const TILEJSON = 'https://tiles.openfreemap.org/planet';
const USER_AGENT = 'Flyoff-preview/0.1 (+https://github.com/mahendrabishnoi2/flyoff)';
const TILE_CACHE = path.join(process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'), 'flyoff', 'corridor-tiles');
const MAX_PARALLEL = 4;

const TYPES = {
  '.html': 'text/html', '.json': 'application/json',
  '.pbf': 'application/x-protobuf', '.pmtiles': 'application/octet-stream',
};

let corridorData = null; // from prepareCorridor(), served as /corridor-data.json

// Static server for index.html and /assets/, with HTTP Range support for PMTiles.
function serve(req, res) {
  const url = new URL(req.url, 'http://localhost');
  let file;
  if (url.pathname === '/corridor-data.json' && corridorData) {
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(corridorData));
    return;
  }
  if (url.pathname === '/' || url.pathname === '/index.html') {
    file = PAGE;
  } else if (url.pathname.startsWith('/assets/')) {
    file = path.join(ASSETS, decodeURIComponent(url.pathname.slice('/assets/'.length)));
    if (!file.startsWith(ASSETS + path.sep)) { res.writeHead(403).end(); return; }
  }
  if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404).end();
    return;
  }
  const size = fs.statSync(file).size;
  const headers = { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Accept-Ranges': 'bytes' };
  const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || '');
  if (range) {
    const start = Number(range[1]);
    const end = Math.min(range[2] ? Number(range[2]) : size - 1, size - 1);
    res.writeHead(206, { ...headers, 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else {
    res.writeHead(200, { ...headers, 'Content-Length': size });
    fs.createReadStream(file).pipe(res);
  }
}

// At most MAX_PARALLEL tile fetches at once.
let running = 0;
const waiting = [];
async function limited(fn) {
  while (running >= MAX_PARALLEL) await new Promise((resolve) => waiting.push(resolve));
  running++;
  try { return await fn(); } finally { running--; waiting.shift()?.(); }
}

const stats = { cached: 0, fetched: 0, notDownloaded: 0 };

/** Serves an OpenFreeMap tile from the disk cache, fetching it first if needed; fails it if not downloaded. */
async function corridorTile(route, u, allowed, log) {
  const m = /^\/planet\/([\w.-]+)\/(\d+)\/(\d+)\/(\d+)\.pbf$/.exec(u.pathname);
  if (!m || m[1] !== corridorData.build) {
    log.push(`blocked request: ${u.href}`);
    return route.abort();
  }
  const key = `${m[2]}/${m[3]}/${m[4]}`;
  if (!allowed.has(key)) {
    // Fail like an offline request (index.html ignores these errors). A 404
    // would make GL JS draw an empty tile instead of the downloaded parent.
    stats.notDownloaded++;
    return route.abort('internetdisconnected');
  }
  const file = path.join(TILE_CACHE, m[1], `${key}.pbf`);
  if (fs.existsSync(file)) {
    stats.cached++;
  } else {
    const res = await limited(() => route.fetch({ headers: { ...route.request().headers(), 'user-agent': USER_AGENT } }));
    if (res.status() !== 200) {
      log.push(`HTTP ${res.status()}: ${u.href}`);
      return route.fulfill({ response: res });
    }
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, await res.body());
    stats.fetched++;
  }
  return route.fulfill({ status: 200, contentType: 'application/x-protobuf', body: fs.readFileSync(file) });
}

/** The strip, the tiles downloaded at each zoom, and the mask; fetches OpenFreeMap's TileJSON for the tile URL. */
async function prepareCorridor(request) {
  const res = await request.get(TILEJSON, { headers: { 'user-agent': USER_AGENT } });
  if (!res.ok()) throw new Error(`${TILEJSON}: HTTP ${res.status()}`);
  const tiles = (await res.json()).tiles[0];
  const build = /\/planet\/([\w.-]+)\//.exec(tiles)[1];
  const line = corridor.greatCircle(ROUTE.from, ROUTE.to, 100);
  const ring = corridor.strip(line, ROUTE.halfWidthMeters);
  // What the app downloads (a suggestion for the downloader): zoom 6 and 7
  // over every zoom-6 tile the strip touches, zoom 8 (and 9) over the strip
  // only. So from zoom 7 the whole masked area has landcover, roads and towns
  // (zoom-6 tiles have no landcover), and only finer detail stops at the strip.
  const z6 = corridor.tileCover(ring, CORRIDOR_MINZOOM);
  const downloaded = { 6: z6, 7: corridor.children(z6), 8: corridor.tileCover(ring, 8), 9: corridor.tileCover(ring, 9) };
  console.log(`OpenFreeMap build ${build}; corridor tiles: ${Object.entries(downloaded).map(([z, t]) => `z${z} ${t.length}`).join(', ')}`);
  return {
    build,
    tiles,
    route: line,
    // The app's mask: the downloaded zoom-6 tiles (see corridor.json).
    mask: corridor.maskGeoJson(z6),
    downloaded,
  };
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const server = http.createServer(serve);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;

  const browser = await chromium.launch();
  let problems = 0;
  try {
    let views = VIEWS;
    if (CORRIDOR) {
      const setup = await browser.newContext({ userAgent: USER_AGENT });
      corridorData = await prepareCorridor(setup.request);
      await setup.close();
      views = CORRIDOR_VIEWS.filter((v) => !ONLY || ONLY.includes(v.name)).flatMap((v) => [
        { ...v, query: `&corridor=1&maxzoom=${v.maxzoom ?? 8}` },
        { ...v, name: `${v.name}-world`, query: '&corridor=0' },
      ]);
    }
    for (const v of views) {
      const context = await browser.newContext({
        viewport: { width: 1024, height: 768 },
        deviceScaleFactor: 1,
        ...(CORRIDOR && { userAgent: USER_AGENT }),
      });
      const page = await context.newPage();
      const log = [];
      const allowed = new Set();
      if (CORRIDOR) {
        for (let z = CORRIDOR_MINZOOM; z <= (v.maxzoom ?? 8); z++) corridorData.downloaded[z].forEach((t) => allowed.add(t));
      }
      await page.route('**/*', (route) => {
        const u = new URL(route.request().url());
        if (u.origin === origin || u.hostname === 'cdn.jsdelivr.net') return route.continue();
        if (CORRIDOR && u.hostname === 'tiles.openfreemap.org') return corridorTile(route, u, allowed, log);
        log.push(`blocked request: ${u.href}`);
        return route.abort();
      });
      page.on('console', (m) => {
        // Chromium's software GL reports "GPU stall due to ReadPixels"; harmless here.
        if (m.text().includes('GL Driver Message')) return;
        if (m.type() === 'error' || m.type() === 'warning') log.push(`console ${m.type()}: ${m.text()}`);
      });
      page.on('pageerror', (e) => log.push(`page error: ${e.message}`));
      page.on('response', (r) => { if (r.status() >= 400) log.push(`HTTP ${r.status()}: ${r.url()}`); });

      await page.goto(`${origin}/?lng=${v.lng}&lat=${v.lat}&zoom=${v.zoom}${v.query ?? ''}`);
      await page.waitForFunction(() => window.previewReady === true, null, { timeout: 60000 });
      const errors = await page.evaluate(() => window.previewErrors);
      errors.forEach((e) => log.push(`map error: ${e}`));

      const file = path.join(OUT, `${v.name}.png`);
      await page.screenshot({ path: file });
      console.log(`${file}${log.length ? '' : '  (no errors)'}`);
      log.forEach((l) => console.log(`  ${l}`));
      problems += log.length;
      await context.close();
    }
  } finally {
    await browser.close();
    server.close();
  }
  if (CORRIDOR) {
    console.log(`Corridor tiles: ${stats.fetched} fetched from OpenFreeMap, ${stats.cached} from the cache, ${stats.notDownloaded} refused (not downloaded).`);
  }
  process.exitCode = problems ? 1 : 0;
}

main().catch((e) => { console.error(e); process.exitCode = 1; });
