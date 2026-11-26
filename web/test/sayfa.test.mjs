// Vatandaş sayfası tarayıcı testi: sahte API'ye karşı (sunucu.mjs), sistemdeki Chrome ile (puppeteer-core).
// Dış ağa çıkılmaz: jsDelivr istekleri yerel node_modules'tan karşılanır, diğer dış istekler engellenir ve kaydedilir.
// Kullanım: npm install && node --test sayfa.test.mjs   (CHROME_PATH ile Chrome yolu değiştirilebilir)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';
import { start } from './sunucu.mjs';

const CHROME = process.env.CHROME_PATH || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].find((p) => existsSync(p));

const NM = fileURLToPath(new URL('./node_modules/', import.meta.url));
const CDN_MAP = [
  ['https://cdn.jsdelivr.net/npm/onnxruntime-web@1.31.1/', NM + 'onnxruntime-web/'],
  ['https://cdn.jsdelivr.net/npm/paddle-ocr-onnx-models@0.2.0/', NM + 'paddle-ocr-onnx-models/'],
];

let srv, base, browser;

const launch = () => puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--no-first-run', '--lang=tr-TR'],
});

before(async () => {
  ({ server: srv, base } = await start(0));
  browser = await launch();
});
after(async () => {
  if (browser) await browser.close();
  if (srv) srv.close();
});

async function openPage(query, { width = 375, height = 812, b = browser, tamper = null } = {}) {
  const page = await b.newPage();
  await page.setViewport({ width, height, deviceScaleFactor: 1 });
  const log = { requests: [], blocked: [], cdn: [], nonGet: [] };
  await page.setRequestInterception(true);
  page.on('request', async (req) => {
    const u = req.url();
    log.requests.push(u);
    if (req.method() !== 'GET' && req.method() !== 'OPTIONS') log.nonGet.push(req.method() + ' ' + u);
    if (u.startsWith(base) || u.startsWith('data:') || u.startsWith('blob:')) return req.continue();
    const m = CDN_MAP.find(([p]) => u.startsWith(p));
    if (m) {
      log.cdn.push(u);
      const file = m[1] + u.slice(m[0].length).split('?')[0];
      const type = file.endsWith('.wasm') ? 'application/wasm' : file.endsWith('.mjs') ? 'text/javascript' : 'application/octet-stream';
      try {
        let body = await readFile(file);
        if (tamper && u.endsWith(tamper)) { body = Buffer.from(body); body[body.length - 1] ^= 0xff; } // bozulmuş dosya
        return req.respond({ status: 200, contentType: type, headers: { 'Access-Control-Allow-Origin': '*' }, body });
      } catch {
        return req.respond({ status: 404, body: '' });
      }
    }
    log.blocked.push(u);
    if (u.startsWith('https://fonts.googleapis.com/')) return req.respond({ status: 200, contentType: 'text/css', body: '' });
    return req.abort();
  });
  const sep = query ? '&' : '';
  await page.goto(base + '/customer-payment.html?api=' + encodeURIComponent(base) + sep + (query || ''), { waitUntil: 'networkidle0' });
  return { page, log };
}

const visible = (page, sel) => page.$eval(sel, (e) => !e.hidden && !e.closest('[hidden]') && e.offsetParent !== null).catch(() => false);
const text = (page, sel) => page.$eval(sel, (e) => e.textContent.trim());

test('bayrak yok: kamera düğmesi görünmez, kamera kodu ve model hiç yüklenmez', async () => {
  const { page, log } = await openPage('');
  assert.equal(await visible(page, '#btnCamera'), false);
  assert.equal(await visible(page, '#plateInput'), true);
  assert.deepEqual(log.requests.filter((u) => u.includes('/js/kamera/') || u.includes('jsdelivr') || u.endsWith('.onnx') || u.endsWith('.wasm')), []);
  await page.close();
});

test('sorgu: borçlu plaka sonuç ekranı, tutar, döküm ve tarife', async () => {
  const { page } = await openPage('');
  await page.type('#plateInput', '34 abc 123');
  await page.click('#btnQuery');
  await page.waitForSelector('#view-result.active');
  assert.equal(await text(page, '#resPlate'), '34 ABC 123');
  assert.equal(await text(page, '#resFee'), '₺60');
  assert.equal(await text(page, '#resStreet'), 'Örnek Cadde');
  assert.equal(await text(page, '#resDuration'), '2 sa 15 dk');
  assert.equal(await visible(page, '#tariffCard'), true);
  const rows = await page.$$eval('#tariffBody .tariff-row', (r) => r.map((x) => x.textContent));
  assert.equal(rows.length, 3); // başlık + 2 etkin araç tipi (kapalı motosiklet yok)
  assert.match(rows[1], /Otomobil₺30₺15/);
  assert.ok((await page.$$('#breakdown .breakdown-row')).length >= 3);
  // 375 px'te döküm hücreleri tek satırda kalır ve süre sütunları taşmaz
  const cells = await page.$$eval('#breakdown .breakdown-row > span', (cs) => cs.map((c) => ({
    t: c.textContent, h: c.getBoundingClientRect().height, lh: parseFloat(getComputedStyle(c).lineHeight), over: c.scrollWidth > c.clientWidth + 1,
  })));
  assert.deepEqual(cells.filter((c) => c.h > c.lh * 1.5).map((c) => c.t), [], 'alt satıra kayan hücre');
  assert.deepEqual(cells.filter((c) => c.over).map((c) => c.t), [], 'taşan hücre');
  // Yenile: aynı plaka yeniden sorgulanır, sonuç ekranı kalır
  await page.click('#btnRefresh');
  await page.waitForFunction(() => document.getElementById('btnRefresh').getAttribute('aria-busy') === 'false');
  assert.equal(await visible(page, '#view-result'), true);
  // Başka plaka sorgula: giriş ekranına döner
  await page.click('#btnBack');
  assert.equal(await visible(page, '#view-search'), true);
  await page.close();
});

test('URL ?plate= ile otomatik sorgu ve abone durumu; ?zone= başlıkta', async () => {
  const { page } = await openPage('plate=06AB1234&zone=Test%20B%C3%B6lgesi');
  await page.waitForSelector('#view-result.active');
  assert.equal(await text(page, '#zoneLine'), 'Test Bölgesi');
  assert.equal(await visible(page, '#resSub'), true);
  assert.equal(await visible(page, '#resFee'), false);
  await page.close();
});

test('yabancı plaka ülke koduyla gösterilir', async () => {
  const { page } = await openPage('plate=MAB1234');
  await page.waitForSelector('#view-result.active');
  assert.equal(await text(page, '#resPlate'), 'MAB1234 · D');
  assert.equal(await text(page, '#resFee'), '₺45,5');
  await page.close();
});

test('aktif kayıt yok (404): bilgi kartı sunucu mesajıyla', async () => {
  const { page } = await openPage('');
  await page.type('#plateInput', '35XY999');
  await page.click('#btnQuery');
  await page.waitForSelector('#notFoundCard:not([hidden])');
  assert.match(await text(page, '#notFoundText'), /35XY999 plakalı araç aktif otoparkta bulunamadı/);
  assert.equal(await visible(page, '#errorMsg'), false);
  await page.close();
});

test('429 RATE_LIMITED: sunucu mesajı, Retry-After geri sayımı, düğme bekler', async () => {
  const { page } = await openPage('');
  await page.type('#plateInput', '99RATE99');
  await page.click('#btnQuery');
  await page.waitForSelector('#rateMsg:not([hidden])');
  const msg = await text(page, '#rateMsg');
  assert.match(msg, /Çok fazla plaka sorgusu yapıldı/);
  assert.match(msg, /saniye sonra tekrar deneyebilirsiniz/);
  assert.equal(await page.$eval('#btnQuery', (b) => b.disabled), true);
  await page.waitForFunction(() => !document.getElementById('btnQuery').disabled, { timeout: 6000 });
  assert.match(await text(page, '#rateMsg'), /Şimdi tekrar deneyebilirsiniz/);
  await page.close();
});

test('aylık abonelik kartı: metni açar/kapatır, aria-expanded güncellenir', async () => {
  const { page } = await openPage('');
  assert.equal(await visible(page, '#subText'), false);
  await page.click('#subToggle');
  assert.equal(await visible(page, '#subText'), true);
  assert.equal(await page.$eval('#subToggle', (b) => b.getAttribute('aria-expanded')), 'true');
  assert.equal(await text(page, '#subText'), 'Aylık abonelik için saha görevlisine veya işletmeye başvurun.');
  await page.click('#subToggle');
  assert.equal(await visible(page, '#subText'), false);
  assert.equal(await page.$eval('#subToggle', (b) => b.getAttribute('aria-expanded')), 'false');
  await page.close();
});

test('geçersiz giriş ve bağlantı hatası', async () => {
  const { page } = await openPage('');
  await page.type('#plateInput', 'A');
  await page.click('#btnQuery');
  assert.equal(await visible(page, '#plateError'), true);
  await page.close();
  // Kapalı porta yönlendirilmiş API: bağlantı hatası mesajı
  const p2 = await browser.newPage();
  await p2.goto(base + '/customer-payment.html?api=' + encodeURIComponent('http://127.0.0.1:9'), { waitUntil: 'networkidle0' });
  await p2.type('#plateInput', '34ABC123');
  await p2.click('#btnQuery');
  await p2.waitForSelector('#errorMsg:not([hidden])');
  assert.match(await text(p2, '#errorMsg'), /Bağlantı kurulamadı/);
  await p2.close();
});

// Not: başsız Chrome'un sahte kamera aygıtı (Windows) ara sıra akışı kendiliğinden bitirip aygıtı kaybediyor
// (sayfadan bağımsız; yalın getUserMedia denemesinde de görüldü). Bu durumda sayfa "Kamera açılamadı" deyip
// klavyeye döner; test bunu doğrular ve her seferinde yeni bir tarayıcıyla (taze aygıt) en fazla 5 kez dener.
// CI bu web testlerini koşmaz (maven.yml / flutter.yml); yerelde npm test ile çalıştırılır.
async function cameraFlow(b) {
  const { page, log } = await openPage('kamera=1', { b });
  try {
    assert.equal(await visible(page, '#btnCamera'), true);
    assert.deepEqual(log.requests.filter((u) => u.includes('/js/kamera/') || u.includes('jsdelivr')), []);
    await page.click('#btnCamera');
    await page.waitForSelector('#camOverlay');
    await page.waitForFunction(() => { const x = document.getElementById('camShot'); return !document.getElementById('camOverlay') || (x && !x.disabled); }, { timeout: 60000 });
    if (!(await page.$('#camOverlay'))) {
      assert.match(await text(page, '#camNotice'), /Kamera açılamadı/);
      return { flaky: true };
    }
    assert.ok(log.cdn.some((u) => u.endsWith('/models/en_PP-OCRv4_rec.onnx')), 'model jsDelivr adresinden istendi');
    assert.ok(log.cdn.some((u) => u.includes('onnxruntime-web@1.31.1/dist/ort.wasm.min.mjs')));
    const tracks = await page.evaluate(() => { window.__tracks = document.querySelector('.cam-video').srcObject.getTracks(); return window.__tracks.map((t) => t.readyState); });
    if (!tracks.includes('live')) return { flaky: true };
    // Sahte kamera görüntüsünde plaka yok: klavyeye (ya da onaya) düşülür
    await page.click('#camShot');
    await page.waitForFunction(() => !document.getElementById('camOverlay'), { timeout: 30000 });
    assert.equal(await page.evaluate(() => window.__tracks.every((t) => t.readyState === 'ended')), true, 'kamera durduruldu');
    assert.ok((await visible(page, '#camConfirm')) || (await visible(page, '#camNotice')), 'onay ya da klavye uyarısı gösterildi');
    // Görüntü hiçbir yere gönderilmedi: GET dışında istek yok, API'ye borç sorgusu yapılmadı
    assert.deepEqual(log.nonGet, []);
    assert.deepEqual(log.requests.filter((u) => u.includes('/api/parking/debt')), []);
    // Vazgeç: katman kapanır ve kamera durur
    await page.click('#btnCamera');
    await page.waitForSelector('#camOverlay');
    await page.waitForFunction(() => { const v = document.querySelector('.cam-video'); return !document.getElementById('camOverlay') || (v && v.srcObject); }, { timeout: 20000 });
    if (await page.$('#camOverlay')) {
      await page.evaluate(() => { window.__tracks = document.querySelector('.cam-video').srcObject.getTracks(); });
      await page.click('#camCancel');
      await page.waitForFunction(() => !document.getElementById('camOverlay'));
      assert.equal(await page.evaluate(() => window.__tracks.every((t) => t.readyState === 'ended')), true);
    }
    assert.equal(log.blocked.filter((u) => !u.startsWith('https://fonts.')).length, 0, 'beklenmeyen dış istek: ' + log.blocked.join(', '));
    return { flaky: false };
  } finally {
    await page.close();
  }
}

test('?kamera=1: düğme görünür, basılana kadar hiçbir şey inmez; katman açılır, model iner, Çek sonrası kamera kapanır', async () => {
  let done = false;
  for (let attempt = 1; attempt <= 5 && !done; attempt++) {
    const b = attempt === 1 ? browser : await launch();
    try {
      done = !(await cameraFlow(b)).flaky;
      if (!done) console.log('sahte kamera akışı kendiliğinden bitti; deneme ' + attempt + ' tekrarlanacak');
    } finally {
      if (b !== browser) await b.close();
    }
  }
  assert.ok(done, 'kamera akışı 5 denemede tamamlanamadı');
});

test('bütünlük: CDN dosyası değişmişse motor yüklenmez, sayfa klavyeye döner', async () => {
  for (const tamper of ['ort.wasm.min.mjs', 'ort-wasm-simd-threaded.wasm', 'en_PP-OCRv4_rec.onnx']) {
    const { page } = await openPage('kamera=1', { tamper });
    const r = await page.evaluate(async () => {
      const m = await import('./js/kamera/okuyucu.js');
      try { await m.loadEngine(); return 'yuklendi'; } catch (e) { return 'reddedildi'; }
    });
    assert.equal(r, 'reddedildi', tamper);
    await page.close();
  }
});

test('okuma motoru: çizilmiş bir plaka görüntüsü doğru okunur ve onaya gider', async () => {
  const { page } = await openPage('kamera=1');
  const out = await page.evaluate(async () => {
    const ok = await import('./js/kamera/okuyucu.js');
    const rules = await import('./js/kamera/plaka-kurallari.js');
    const c = document.createElement('canvas');
    c.width = 640; c.height = 200;
    const g = c.getContext('2d');
    g.fillStyle = '#ffffff'; g.fillRect(0, 0, 640, 200);
    g.strokeStyle = '#111111'; g.lineWidth = 8; g.strokeRect(4, 4, 632, 192);
    g.fillStyle = '#1747c6'; g.fillRect(8, 8, 52, 184);
    g.fillStyle = '#ffffff'; g.font = 'bold 22px Arial'; g.fillText('TR', 16, 180);
    g.fillStyle = '#111111'; g.font = 'bold 96px Arial'; g.textBaseline = 'middle';
    g.fillText('34 ABC 123', 78, 104);
    const r = await ok.readPlateImage(c);
    return { r, d: rules.decide(r, false) };
  });
  assert.equal(out.d.guess, '34ABC123', JSON.stringify(out));
  assert.equal(out.d.kind, 'confirm', JSON.stringify(out));
  await page.close();
});
