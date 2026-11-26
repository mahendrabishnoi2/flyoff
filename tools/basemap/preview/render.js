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
'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');
const { chromium } = require('playwright');

const ASSETS = path.resolve(__dirname, '../../../app/src/main/assets');
const PAGE = path.join(__dirname, 'index.html');
const OUT = path.resolve(process.argv[2] || path.join(__dirname, 'screenshots'));

const VIEWS = [
  { name: 'world-z1', lng: 0, lat: 20, zoom: 1 },
  { name: 'north-atlantic-z3', lng: -40, lat: 52, zoom: 3 },
  { name: 'europe-z4', lng: 12, lat: 50, zoom: 4 },
  { name: 'india-z5', lng: 79, lat: 22, zoom: 5 },
  // Past the data's zoom 5: overzoomed tiles and the smaller cities.
  { name: 'uk-z6', lng: -3, lat: 54, zoom: 6 },
  { name: 'uk-z8', lng: -2, lat: 53, zoom: 8 },
];

const TYPES = {
  '.html': 'text/html', '.json': 'application/json',
  '.pbf': 'application/x-protobuf', '.pmtiles': 'application/octet-stream',
};

// Static server for index.html and /assets/, with HTTP Range support for PMTiles.
function serve(req, res) {
  const url = new URL(req.url, 'http://localhost');
  let file;
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

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const server = http.createServer(serve);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;

  const browser = await chromium.launch();
  let problems = 0;
  try {
    for (const v of VIEWS) {
      const context = await browser.newContext({ viewport: { width: 1024, height: 768 }, deviceScaleFactor: 1 });
      const page = await context.newPage();
      const log = [];
      await page.route('**/*', (route) => {
        const u = new URL(route.request().url());
        if (u.origin === origin || u.hostname === 'cdn.jsdelivr.net') return route.continue();
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

      await page.goto(`${origin}/?lng=${v.lng}&lat=${v.lat}&zoom=${v.zoom}`);
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
  process.exitCode = problems ? 1 : 0;
}

main().catch((e) => { console.error(e); process.exitCode = 1; });
