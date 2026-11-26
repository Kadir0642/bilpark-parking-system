// Test sunucusu: web/ klasörünü durağan olarak sunar ve aynı portta sahte bir API verir.
// Canlı API'ye hiç gidilmez. Kullanım: node sunucu.mjs [port]  →  http://127.0.0.1:<port>/customer-payment.html?api=http://127.0.0.1:<port>
// Sahte plakalar (kurgusal): 34ABC123 borçlu, 06AB1234 abone, MAB1234 yabancı, 99RATE99 hız sınırı (429), diğerleri 404.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = resolve(fileURLToPath(new URL('..', import.meta.url)));
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.md': 'text/plain; charset=utf-8',
};

export const TARIFF = {
  id: 7,
  freeMinutes: 5,
  paidStart: '08:00',
  paidEnd: '22:00',
  vehicleTypes: [
    { code: 'SMALL', displayName: 'Otomobil', enabled: true, sortOrder: 1, firstHour: 30, extraHour: 15 },
    { code: 'LARGE', displayName: 'Kamyonet', enabled: true, sortOrder: 2, firstHour: 45, extraHour: 20 },
    { code: 'MOTO', displayName: 'Motosiklet', enabled: false, sortOrder: 3, firstHour: 10, extraHour: 5 },
  ],
  upcoming: null,
};

function debtOf(plate) {
  const base = {
    streetName: 'Örnek Cadde', entryTime: '2026-11-26T09:10:00', durationMinutes: 135, tariffId: 7,
    paidMinutes: 135, freeMinutes: 0,
    segments: [{ date: '2026-11-26', start: '2026-11-26T09:10:00', end: '2026-11-26T11:25:00', minutes: 135, paid: true }],
  };
  if (plate === '34ABC123') return { ...base, plate, foreignPlate: false, fee: 60, hasSubscription: false };
  if (plate === '06AB1234') return { ...base, plate, foreignPlate: false, fee: 0, hasSubscription: true };
  if (plate === 'MAB1234') return { ...base, plate, foreignPlate: true, foreignCountryCode: 'D', fee: 45.5, hasSubscription: false };
  return null;
}

export const calls = [];

function json(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', ...headers });
  res.end(JSON.stringify(body));
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  calls.push(req.method + ' ' + url.pathname + url.search);
  if (url.pathname === '/api/tariff/current') return json(res, 200, TARIFF);
  if (url.pathname === '/api/parking/debt') {
    const plate = String(url.searchParams.get('plate') || '').replace(/[\s-]+/g, '').toUpperCase();
    if (plate === '99RATE99') {
      return json(res, 429, { error: 'Çok fazla plaka sorgusu yapıldı. Lütfen biraz bekleyip tekrar deneyin.', code: 'RATE_LIMITED' }, { 'Retry-After': '3' });
    }
    if (plate === '500ERR') return json(res, 500, { error: 'Sunucu hatası (test).', code: 'INTERNAL' });
    const d = debtOf(plate);
    if (!d) return json(res, 404, { error: plate + ' plakalı araç aktif otoparkta bulunamadı.', code: 'VEHICLE_NOT_FOUND' });
    return json(res, 200, d);
  }
  if (url.pathname.startsWith('/api/')) return json(res, 404, { error: 'yok', code: 'NOT_FOUND' });

  const rel = normalize(decodeURIComponent(url.pathname)).replace(/^([/\\])+/, '');
  const file = resolve(join(WEB, rel || 'customer-payment.html'));
  if (!file.startsWith(WEB + sep) || file.includes(sep + 'node_modules' + sep)) {
    res.writeHead(403); return res.end();
  }
  try {
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end();
  }
}

export function start(port = 0, host = '127.0.0.1') {
  return new Promise((ok) => {
    const server = http.createServer((req, res) => { handle(req, res).catch(() => { res.writeHead(500); res.end(); }); });
    server.listen(port, host, () => ok({ server, base: 'http://' + host + ':' + server.address().port }));
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { base } = await start(Number(process.argv[2]) || 8795);
  console.log('Hazır: ' + base + '/customer-payment.html?api=' + base);
}
