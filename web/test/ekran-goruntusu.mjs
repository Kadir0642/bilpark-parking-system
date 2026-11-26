// Vatandaş sayfasının ekran görüntülerini sahte API'ye karşı üretir (sistemdeki Chrome, dış ağ yok).
// Kullanım: node ekran-goruntusu.mjs <çıktı-klasörü> [sahte-kamera.y4m]
// .y4m verilirse kamera akışı o videoyla (ör. kurgusal bir plaka) uçtan uca çalıştırılır.
import { mkdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';
import { start } from './sunucu.mjs';

const OUT = process.argv[2];
const Y4M = process.argv[3];
if (!OUT) { console.error('Kullanım: node ekran-goruntusu.mjs <klasör> [video.y4m]'); process.exit(2); }
await mkdir(OUT, { recursive: true });

const NM = fileURLToPath(new URL('./node_modules/', import.meta.url));
const CDN_MAP = [
  ['https://cdn.jsdelivr.net/npm/onnxruntime-web@1.31.1/', NM + 'onnxruntime-web/'],
  ['https://cdn.jsdelivr.net/npm/paddle-ocr-onnx-models@0.2.0/', NM + 'paddle-ocr-onnx-models/'],
];
const { server, base } = await start(0);
const args = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--lang=tr-TR'];
if (Y4M) args.push('--use-file-for-fake-video-capture=' + Y4M);
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, args });

async function page(query, { w = 375, h = 812, dark = false } = {}) {
  const p = await browser.newPage();
  await p.setViewport({ width: w, height: h, deviceScaleFactor: 2 });
  await p.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: dark ? 'dark' : 'light' }]);
  await p.setRequestInterception(true);
  p.on('request', async (req) => {
    const u = req.url();
    if (u.startsWith(base) || u.startsWith('data:')) return req.continue();
    const m = CDN_MAP.find(([a]) => u.startsWith(a));
    if (m) {
      const f = m[1] + u.slice(m[0].length).split('?')[0];
      return req.respond({ status: 200, contentType: f.endsWith('.wasm') ? 'application/wasm' : f.endsWith('.mjs') ? 'text/javascript' : 'application/octet-stream', headers: { 'Access-Control-Allow-Origin': '*' }, body: await readFile(f) });
    }
    // Yalnızca sayfanın gerçekte kullandığı yazı tipi (Inter, Google Fonts) gerçek adresinden gelir; başka dış istek yok
    if (u.startsWith('https://fonts.googleapis.com/') || u.startsWith('https://fonts.gstatic.com/')) return req.continue();
    return req.abort();
  });
  await p.goto(base + '/customer-payment.html?api=' + encodeURIComponent(base) + (query ? '&' + query : ''), { waitUntil: 'networkidle0' });
  return p;
}
// SONEK ortam değişkeni dosya adlarına eklenir (ör. SONEK=-v2), eski görüntülerin üzerine yazılmaz
const SONEK = process.env.SONEK || '';
const shot = (p, name, full = true) => p.screenshot({ path: join(OUT, name + SONEK + '.png'), fullPage: full });
const settle = (p) => p.evaluate(() => new Promise((r) => setTimeout(r, 350)));

for (const dark of [false, true]) {
  const t = dark ? 'koyu' : 'acik';
  for (const [w, h, d] of [[375, 812, 'mobil'], [1280, 900, 'masaustu']]) {
    let p = await page('', { w, h, dark });
    await shot(p, `${d}-${t}-1-giris`);
    await p.close();
    p = await page('plate=34ABC123&zone=%C3%96rnek%20B%C3%B6lge', { w, h, dark });
    await p.waitForSelector('#view-result.active'); await settle(p);
    await shot(p, `${d}-${t}-2-sonuc-borclu`);
    await p.close();
  }
}

let p = await page('kamera=1');
await shot(p, 'mobil-acik-3-kamera-bayragi');
await p.close();

p = await page('plate=06AB1234');
await p.waitForSelector('#view-result.active'); await settle(p);
await shot(p, 'mobil-acik-4-sonuc-abone');
await p.close();

p = await page('');
await p.type('#plateInput', '35XY999'); await p.click('#btnQuery');
await p.waitForSelector('#notFoundCard:not([hidden])'); await settle(p);
await shot(p, 'mobil-acik-5-kayit-yok');
await p.close();

p = await page('');
await p.type('#plateInput', '99RATE99'); await p.click('#btnQuery');
await p.waitForSelector('#rateMsg:not([hidden])'); await settle(p);
await shot(p, 'mobil-acik-6-hiz-siniri-429');
await p.close();

p = await page('plate=34ABC123', { dark: true });
await p.waitForSelector('#view-result.active');
await p.evaluate(() => { const b = document.getElementById('btnRefresh'); b.setAttribute('aria-busy', 'true'); b.disabled = true; document.getElementById('txtRefresh').textContent = 'Yenileniyor…'; });
await shot(p, 'mobil-koyu-7-yenileniyor', false);
await p.close();

if (Y4M) {
  for (const dark of [false, true]) {
    const t = dark ? 'koyu' : 'acik';
    p = await page('kamera=1', { dark });
    await p.click('#btnCamera');
    await p.waitForSelector('#camOverlay');
    await settle(p);
    await shot(p, `mobil-${t}-8-kamera-hazirlaniyor`, false);
    try {
      await p.waitForFunction(() => { const x = document.getElementById('camShot'); return !document.getElementById('camOverlay') || (x && !x.disabled); }, { timeout: 60000 });
      if (await p.$('#camOverlay')) {
        await settle(p);
        await shot(p, `mobil-${t}-9-kamera-hazir`, false);
        await p.click('#camShot');
        await p.waitForFunction(() => !document.getElementById('camOverlay'), { timeout: 30000 });
        await settle(p);
        await shot(p, `mobil-${t}-10-kamera-sonucu`, false);
        console.log('kamera sonucu:', await p.$eval('#plateInput', (e) => e.value), await p.$eval('#camConfirm', (e) => !e.hidden));
      } else {
        console.log('sahte kamera akışı bitti (bkz. sayfa.test.mjs notu)');
      }
    } catch (e) { console.log('kamera adımı atlandı:', e.message); }
    await p.close();
  }
}

// Aylık abonelik kartı açık hali
for (const dark of [false, true]) {
  p = await page('', { dark });
  await p.click('#subToggle'); await settle(p);
  await shot(p, `mobil-${dark ? 'koyu' : 'acik'}-11-abonelik-acik`);
  await p.close();
}

await browser.close();
server.close();
console.log('bitti:', OUT);
