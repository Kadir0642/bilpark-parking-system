// Yönetim paneli yük azaltma testi: sahte API + sahte SSE'ye karşı, sistemdeki Chrome ile. Canlı API'ye gidilmez.
// Kapsam: olay yığını tek tazelemeye iner, gizli sekmede istek yok (görününce tek tazeleme), 503 oturumu kapatmaz.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const WEB = resolve(fileURLToPath(new URL('..', import.meta.url)));
const CHROME = process.env.CHROME_PATH || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].find((p) => existsSync(p));
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };

let srv, base, browser;
const hits = {};            // yol -> istek sayısı
let streams = [];           // açık SSE bağlantıları
let version = 1;
const mode = { spots503: 0, login: null }; // /spots için kalan 503 sayısı; login: sahte giriş yanıtı { status, body, headers }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const count = (re) => Object.entries(hits).filter(([k]) => re.test(k)).reduce((a, [, v]) => a + v, 0);
const reset = () => { for (const k of Object.keys(hits)) delete hits[k]; };

const json = (res, body, status = 200, headers = {}) => {
  res.writeHead(status, {
    'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*',
    'Access-Control-Expose-Headers': 'Retry-After', ...headers,
  });
  res.end(status === 204 ? undefined : JSON.stringify(body));
};

function push(type, payload = {}) {
  version++;
  const data = JSON.stringify({ type, version, payload });
  streams.forEach((r) => r.write(`event: ${type}\ndata: ${data}\n\n`));
}

before(async () => {
  srv = http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    if (req.method === 'OPTIONS') return json(res, {}, 204);
    const api = /^\/(api|spots|bi|parking|vehicle-types|streets|tariff)/.test(u.pathname);
    if (!api) {
      try {
        const body = await readFile(join(WEB, normalize(u.pathname === '/' ? '/admin-panel.html' : u.pathname)));
        res.writeHead(200, { 'Content-Type': TYPES[extname(u.pathname)] || 'application/octet-stream' });
        return res.end(body);
      } catch { res.writeHead(404); return res.end(); }
    }
    u.pathname = u.pathname.replace(/^\/api\/parking/, '');
    hits[u.pathname] = (hits[u.pathname] || 0) + 1;
    if (u.pathname === '/api/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-cache' });
      res.write(`event: HELLO\ndata: ${JSON.stringify({ userId: 1, version })}\n\n`);
      streams.push(res);
      req.on('close', () => { streams = streams.filter((x) => x !== res); });
      return;
    }
    if (u.pathname === '/api/auth/login' && req.method === 'POST') {
      const l = mode.login || { status: 401, body: { error: 'Kullanıcı adı veya şifre hatalı.' } };
      return json(res, l.body, l.status, l.headers || {});
    }
    if (u.pathname === '/api/events/ticket') return json(res, { ticket: 't' });
    if (u.pathname === '/api/sync') return json(res, { version, events: [], hasMore: false });
    if (u.pathname === '/spots' && mode.spots503 > 0) { mode.spots503--; return json(res, { error: 'Yoğun' }, 503, { 'Retry-After': '1' }); }
    if (/(\/spots|\/last-records|\/shifts|\/vehicle-types|\/streets|\/fiscal-years|\/tariffs)$/.test(u.pathname)) return json(res, []);
    if (u.pathname === '/api/admin/notifications') return json(res, { items: [], unread: 0 });
    return json(res, {});
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${srv.address().port}`;
  browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-first-run'] });
});
after(async () => {
  streams.forEach((r) => r.end());
  if (browser) await browser.close();
  srv?.closeAllConnections?.();
  srv?.close();
});

async function openPanel() {
  const page = await browser.newPage();
  await page.evaluateOnNewDocument(() => {
    localStorage.setItem('token', 'test-secret-token'); localStorage.setItem('role', 'ADMIN'); localStorage.setItem('username', 'yonetici');
    // Gizli sekme taklidi: window.__hide(true/false)
    let hidden = false;
    Object.defineProperty(document, 'visibilityState', { get: () => (hidden ? 'hidden' : 'visible') });
    window.__hide = (h) => { hidden = h; document.dispatchEvent(new Event('visibilitychange')); };
  });
  await page.goto(`${base}/admin-panel.html?api=${base}`);
  for (let i = 0; i < 50 && !(streams.length && count(/^\/bi\/shifts$/)); i++) await sleep(200);
  await sleep(1000);
  return page;
}

test('arka arkaya araç olayları tek tazelemeye iner, yalnızca etkilenen bileşenler çekilir', async () => {
  const page = await openPanel();
  assert.ok(streams.length > 0, 'SSE açılmalı');
  await sleep(4500); // açılış penceresi dolsun
  reset();
  for (let i = 0; i < 20; i++) push('VEHICLE_ENTERED', { plate: 'X' + i });
  await sleep(5500);
  assert.equal(count(/^\/spots$/), 1, 'spots tek kez');
  assert.equal(count(/^\/bi\/shifts$/), 1, 'shifts tek kez');
  assert.equal(count(/^\/bi\/income-by-type/), 0, 'giriş gelir bileşenini çekmemeli');
  assert.equal(count(/^\/bi\/income-summary$/), 0);
  reset();
  for (let i = 0; i < 10; i++) push('VEHICLE_EXITED', { plate: 'X' + i });
  await sleep(5500);
  assert.equal(count(/^\/spots$/), 1);
  assert.equal(count(/^\/bi\/income-summary$/), 1, 'çıkış gelir bileşenini tazeler');
  await page.close();
});

test('gizli sekmede istek atılmaz, görünür olunca tek tazeleme yapılır', async () => {
  const page = await openPanel();
  await sleep(4500);
  await page.evaluate(() => window.__hide(true));
  reset();
  for (let i = 0; i < 10; i++) push('VEHICLE_EXITED', { plate: 'G' + i });
  await sleep(6000);
  assert.equal(count(/^\/(spots|bi\/)/), 0, 'gizliyken veri isteği olmamalı');
  await page.evaluate(() => window.__hide(false));
  await sleep(2500);
  assert.equal(count(/^\/spots$/), 1, 'görünür olunca tek tazeleme');
  assert.equal(count(/^\/bi\/income-summary$/), 1);
  await page.close();
});

test('503 geçicidir: yeniden denenir, oturum kapanmaz', async () => {
  mode.spots503 = 1;
  const page = await openPanel();
  await sleep(2000);
  assert.ok(count(/^\/spots$/) >= 2, '503 sonrası yeniden denenmeli');
  const state = await page.evaluate(() => ({ token: localStorage.getItem('token'), loginVisible: getComputedStyle(document.getElementById('loginScreen')).display !== 'none' }));
  assert.equal(state.token, 'test-secret-token');
  assert.equal(state.loginVisible, false, 'giriş ekranına dönülmemeli');
  await page.close();
});

// ---- Giriş ekranı: sakin hata ve bekleme mesajları (sahte API, canlıya gidilmez) ----
async function openLogin() {
  const page = await browser.newPage();
  await page.goto(`${base}/admin-panel.html?api=${base}`);
  await page.waitForSelector('#loginUsername', { visible: true });
  return page;
}
async function tryLogin(page, user = 'yonetici1') {
  await page.$eval('#loginUsername', (el) => { el.value = ''; });
  await page.type('#loginUsername', user);
  await page.type('#loginPassword', 'test-secret-yanlis');
  await page.click('#btnLogin');
}
const loginState = (page) => page.evaluate(() => ({
  box: !document.getElementById('loginError').hidden,
  text: document.getElementById('loginErrorText').textContent,
  hint: document.getElementById('loginHint').hidden ? '' : document.getElementById('loginHint').textContent,
  countdown: document.getElementById('loginCountdown').hidden ? '' : document.getElementById('loginCountdown').textContent,
  disabled: document.getElementById('btnLogin').disabled,
  label: document.getElementById('btnLogin').textContent,
  user: document.getElementById('loginUsername').value,
  fontPx: parseFloat(getComputedStyle(document.getElementById('loginError')).fontSize),
  cls: document.getElementById('loginError').className,
}));
const waitLoginText = async (page, re) => {
  for (let i = 0; i < 40; i++) { if (re.test((await loginState(page)).text)) return; await sleep(100); }
};

test('giriş 401 (hak bilgisi yok): sunucu metni, ipucu yok, kullanıcı adı silinmez, kırmızı değil', async () => {
  mode.login = { status: 401, body: { error: 'Kullanıcı adı veya şifre hatalı.', code: 'UNAUTHORIZED' } };
  const page = await openLogin();
  await tryLogin(page);
  await waitLoginText(page, /hatalı/);
  const st = await loginState(page);
  assert.equal(st.text, 'Kullanıcı adı veya şifre hatalı.');
  assert.equal(st.hint, '');
  assert.equal(st.user, 'yonetici1');
  assert.equal(st.disabled, false);
  assert.ok(st.fontPx >= 17, 'yazı büyük olmalı');
  assert.ok(!/alert-danger/.test(st.cls), 'kırmızı uyarı kullanılmamalı');
  await page.close();
});

test('giriş 401 remainingAttempts: sunucu metni + nazik ipucu', async () => {
  mode.login = { status: 401, body: { error: 'Kullanıcı adı veya şifre hatalı. 2 deneme hakkınız kaldı.', code: 'UNAUTHORIZED', remainingAttempts: 2 } };
  const page = await openLogin();
  await tryLogin(page);
  await waitLoginText(page, /hakkınız kaldı/);
  const st = await loginState(page);
  assert.match(st.text, /2 deneme hakkınız kaldı/);
  assert.equal(st.hint, 'Şifrenizi hatırlamıyorsanız yöneticinize başvurun.');
  assert.equal(st.user, 'yonetici1');
  await page.close();
});

test('giriş 401 gövdesiz: yerel sakin metin', async () => {
  mode.login = { status: 401, body: {} };
  const page = await openLogin();
  await tryLogin(page);
  await waitLoginText(page, /hatalı/);
  assert.equal((await loginState(page)).text, 'Kullanıcı adı veya şifre hatalı.');
  await page.close();
});

test('giriş 429 LOGIN_LOCKED: düğme kapanır, geri sayım akar, süre dolunca açılır, otomatik yeniden deneme yok', async () => {
  mode.login = { status: 429, headers: { 'Retry-After': '3' }, body: { error: 'Çok fazla başarısız giriş denemesi yapıldı. Lütfen 1 dakika sonra tekrar deneyin.', code: 'LOGIN_LOCKED', retryAfterSeconds: 3 } };
  reset();
  const page = await openLogin();
  await tryLogin(page);
  await waitLoginText(page, /1 dakika/);
  let st = await loginState(page);
  assert.match(st.text, /Lütfen 1 dakika sonra tekrar deneyin\./);
  assert.match(st.countdown, /^Kalan süre: 0:0[1-3]$/);
  assert.equal(st.disabled, true);
  assert.match(st.label, /^Lütfen bekleyin · 0:0\d$/);
  assert.equal(st.user, 'yonetici1');
  // Kilitliyken Enter ile gönderme de istek atmaz
  await page.focus('#loginPassword');
  await page.keyboard.press('Enter');
  await sleep(300);
  assert.equal(count(/^\/api\/auth\/login$/), 1, 'kilitliyken yeni istek atılmamalı');
  for (let i = 0; i < 50 && (await loginState(page)).disabled; i++) await sleep(100);
  st = await loginState(page);
  assert.equal(st.disabled, false, 'süre dolunca düğme açılmalı');
  assert.equal(st.label, 'Giriş yap');
  assert.equal(st.countdown, '');
  assert.match(st.text, /Bekleme süresi doldu/);
  await sleep(1500);
  assert.equal(count(/^\/api\/auth\/login$/), 1, 'kendiliğinden yeniden denenmemeli');
  await page.close();
});

test('giriş 429 RATE_LIMITED: sunucu metni gösterilir, "sunucu yoğun" denmez', async () => {
  mode.login = { status: 429, headers: { 'Retry-After': '2' }, body: { error: 'Bu bağlantıdan kısa sürede çok sayıda giriş denemesi yapıldı. Lütfen 1 dakika sonra tekrar deneyin.', code: 'RATE_LIMITED', retryAfterSeconds: 2 } };
  const page = await openLogin();
  await tryLogin(page);
  await waitLoginText(page, /Bu bağlantıdan/);
  const st = await loginState(page);
  assert.match(st.text, /^Bu bağlantıdan kısa sürede/);
  assert.ok(!/yoğun/.test(st.text));
  assert.equal(st.disabled, true, 'Retry-After varsa kısa süre beklenir');
  await page.close();
});

test('giriş 503: sakin "sunucu yoğun" mesajı, düğme açık, oturum yok', async () => {
  mode.login = { status: 503, headers: { 'Retry-After': '5' }, body: { error: 'x', code: 'SERVICE_UNAVAILABLE' } };
  const page = await openLogin();
  await tryLogin(page);
  await waitLoginText(page, /yoğun/);
  const st = await loginState(page);
  assert.match(st.text, /Sunucu şu an yoğun/);
  assert.equal(st.disabled, false);
  assert.equal(await page.evaluate(() => localStorage.getItem('token')), null);
  mode.login = null;
  await page.close();
});
