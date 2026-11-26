// BilPark yük üreteci (Node 24, bağımlılık yok). Rehber: loadtest/README.md
//
// Neden k6 değil: saha uygulaması SSE'ye TEPKİ veriyor (bölgedeki her araç olayında her görevli
// /parking/filter + /parking/history + /vehicle-types çeker, HEARTBEAT'te geride kalınca /sync?since= çeker).
// Bu yükü gerçekçi üretmek için her sanal görevlinin gerçek bir SSE akışı tutması ve olaylara uygulamadaki
// gibi tepki vermesi gerekiyor; k6 çekirdeği SSE okuyamıyor (xk6 derlemesi gerekirdi).
//
// Davranışın kaynağı (mobile/lib ve web/): live_sync.dart, entry_queue.dart, api_client.dart, main.dart,
// parking_map_screen.dart, dashboard_screen.dart, vehicle_detail_screen.dart, admin-panel.html, customer-payment.html.
//
// Ayarlar ortam değişkeniyle (varsayılanlar gerçekçi temel senaryodur):
//   OFFICERS=10 DURATION=900 ADMINS=2 CITIZEN_MIN_RPM=1 CITIZEN_MAX_RPM=10 BURST_RPM=30 BURST_EVERY=300
//   ACTION_MIN=30 ACTION_MAX=90 RAMP=60 DEV=1 SCENARIO=normal|spike OFFICER_STEPS=60,120 STEP_SEC=180
//   SPIKE_AT=180 OUTAGE=120 QUEUE=20 RUN_ID=1 OUT=/results/x.json LABEL=temel

import { randomBytes, randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const env = (k, d) => (process.env[k] ?? d);
const num = (k, d) => Number(env(k, d));
const CFG = {
  base: env('BASE', 'http://app:8080'),
  label: env('LABEL', 'temel'),
  scenario: env('SCENARIO', 'normal'),
  officers: num('OFFICERS', 10),
  steps: env('OFFICER_STEPS', '') ? env('OFFICER_STEPS', '').split(',').map(Number) : null,
  stepSec: num('STEP_SEC', 180),
  duration: num('DURATION', 900),
  ramp: num('RAMP', 60),
  admins: num('ADMINS', 2),
  citizenMin: num('CITIZEN_MIN_RPM', 1),
  citizenMax: num('CITIZEN_MAX_RPM', 10),
  burstRpm: num('BURST_RPM', 30),
  burstEvery: num('BURST_EVERY', 300),
  actionMin: num('ACTION_MIN', 30),
  actionMax: num('ACTION_MAX', 90),
  dev: env('DEV', '1') === '1',
  adminReload: num('ADMIN_RELOAD', 240),
  reportEvery: num('REPORT_EVERY', 600),
  spikeAt: num('SPIKE_AT', 180),
  outage: num('OUTAGE', 120),
  queue: num('QUEUE', 20),
  runId: num('RUN_ID', 1),
  out: env('OUT', '/results/sonuc.json'),
  officerPassword: env('OFFICER_PASSWORD', 'test-secret-officer'),
  adminPassword: env('ADMIN_PASSWORD', 'test-secret-admin'),
  devPassword: env('DEVELOPER_PASSWORD', 'test-secret-developer'),
};
if (CFG.steps) CFG.officers = Math.max(...CFG.steps);

const T0 = Date.now();
const END = T0 + CFG.duration * 1000;
const now = () => Date.now();
const sec = () => ((now() - T0) / 1000).toFixed(0);
const sleep = (ms) => new Promise((r) => setTimeout(r, Math.max(0, ms)));
const rand = (a, b) => a + Math.random() * (b - a);
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const running = () => now() < END && !STOP;
let STOP = false;

// ---------------------------------------------------------------- metrics
const M = new Map(); // endpoint -> { lat: [], codes: {}, bad: 0 }
const BUCKET_MS = 10_000;
const buckets = []; // { n, bad, lat: [], officers, sse }
const corr = { dupSame: 0, dupConflict: 0, dupWrong: 0, raceSame: 0, raceWrong: 0, queued: 0, flushed: 0, flushFail: 0 };
let activeOfficers = 0;

function bucket() {
  const i = Math.floor((now() - T0) / BUCKET_MS);
  while (buckets.length <= i) buckets.push({ n: 0, bad: 0, lat: [], officers: activeOfficers, sse: 0 });
  return buckets[i];
}

function rec(name, ms, status, ok) {
  let m = M.get(name);
  if (!m) M.set(name, (m = { lat: [], codes: {}, bad: 0 }));
  m.lat.push(ms);
  m.codes[status] = (m.codes[status] || 0) + 1;
  if (!ok) m.bad++;
  const b = bucket();
  b.n++;
  b.lat.push(ms);
  if (!ok) b.bad++;
  b.officers = activeOfficers;
}

const sse = { delays: [], perEvent: new Map(), connects: 0, drops: 0, helloMs: [], resets: 0 };

function pct(sorted, p) {
  if (!sorted.length) return null;
  const i = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, i)];
}

// ---------------------------------------------------------------- http
const APP_HEADERS = { 'X-App-Version': '1.0.0+yuk', 'X-Device': 'yuk-testi' };

async function req(name, method, path, o = {}) {
  const url = new URL(CFG.base + path);
  if (o.query) for (const [k, v] of Object.entries(o.query)) if (v != null) url.searchParams.set(k, String(v));
  const h = { ...(o.app ? APP_HEADERS : {}), ...(o.headers || {}) };
  if (o.token) h.Authorization = 'Bearer ' + o.token;
  if (o.device) h['X-Device-Id'] = o.device;
  if (o.body !== undefined) h['Content-Type'] = 'application/json';
  const ok = o.ok || [200];
  const t0 = performance.now();
  try {
    const res = await fetch(url, {
      method, headers: h, body: o.body !== undefined ? JSON.stringify(o.body) : undefined,
      signal: AbortSignal.timeout(o.timeout || 15000),
    });
    const buf = await res.arrayBuffer();
    const ms = performance.now() - t0;
    rec(name, ms, res.status, ok.includes(res.status));
    let json = null;
    if (!o.binary) {
      try { json = JSON.parse(Buffer.from(buf).toString('utf8')); } catch { /* boş / metin */ }
    }
    return { status: res.status, json, ms, bytes: buf.byteLength };
  } catch (e) {
    const ms = performance.now() - t0;
    const code = e?.name === 'TimeoutError' ? 'timeout' : (e?.cause?.code || e?.name || 'error');
    rec(name, ms, code, false);
    return { status: 0, json: null, ms, error: code };
  }
}

// ---------------------------------------------------------------- shared state
const activePlates = new Set(); // vatandaş sorguları için
const zoneMembers = new Map(); // zoneId -> bağlı görevli sayısı (beklenen alıcı)
let adminsOnline = 0;

const istanbulMs = (at) => {
  if (!at) return null;
  const s = at.length > 23 ? at.slice(0, 23) : at;
  const t = Date.parse(s + '+03:00');
  return Number.isFinite(t) ? t : null;
};

function noteDelivery(ev, kind) {
  const at = istanbulMs(ev.at);
  if (at == null) return;
  const d = now() - at;
  sse.delays.push(d);
  let pe = sse.perEvent.get(ev.version);
  if (!pe) {
    const expected = (ev.zoneId != null ? (zoneMembers.get(ev.zoneId) || 0) : 0) + adminsOnline;
    sse.perEvent.set(ev.version, (pe = { max: d, n: 0, expected, kind }));
  }
  pe.n++;
  if (d > pe.max) pe.max = d;
}

// ---------------------------------------------------------------- SSE client (live_sync.dart / admin-panel.html)
const backoff = (attempt) => [1, 2, 4, 8, 16, 30][Math.min(Math.max(attempt - 1, 0), 5)] * 1000;

async function readSse(res, onFrame, abort, watchdogMs) {
  let timer;
  const arm = () => { clearTimeout(timer); timer = setTimeout(() => abort.abort(new Error('watchdog')), watchdogMs); };
  arm();
  const dec = new TextDecoder();
  let buf = '', event = null, data = [];
  try {
    for await (const chunk of res.body) {
      arm();
      buf += dec.decode(chunk, { stream: true });
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        let line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        if (line === '') {
          if (event || data.length) onFrame(event || 'message', data.join('\n'));
          event = null; data = [];
        } else if (line.startsWith(':')) {
          // yorum
        } else {
          const c = line.indexOf(':');
          const f = c < 0 ? line : line.slice(0, c);
          let v = c < 0 ? '' : line.slice(c + 1);
          if (v.startsWith(' ')) v = v.slice(1);
          if (f === 'event') event = v; else if (f === 'data') data.push(v);
        }
      }
    }
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------- officer
const LETTERS = 'ABCDEFGHJKLMNPRSTUVYZ';
function officerLetters(idx) {
  const L = LETTERS.length;
  return LETTERS[Math.floor(idx / (L * L)) % L] + LETTERS[Math.floor(idx / L) % L] + LETTERS[idx % L];
}

class Officer {
  constructor(idx) {
    this.idx = idx;
    this.username = 'yuk' + String(idx + 1).padStart(3, '0');
    this.device = randomBytes(16).toString('hex');
    this.token = null;
    this.version = null;
    this.failures = 0;
    this.online = false;
    this.plates = [];
    this.counter = 100;
    this.queue = [];
    this.sent = []; // { requestId, query, spotId }
    this.netDown = false;
    this.abort = null;
    this.mapTimer = null;
    this.dashTimer = null;
    this.flushing = null;
    this.catching = false;
  }

  plate() {
    const il = String(1 + (CFG.runId % 80)).padStart(2, '0');
    return il + officerLetters(this.idx) + String(this.counter++ % 1000).padStart(3, '0');
  }

  o(extra = {}) { return { token: this.token, device: this.device, app: true, ...extra }; }

  async login() {
    while (running() && !this.netDown) {
      const r = await req('POST /api/auth/login', 'POST', '/api/auth/login', {
        device: this.device, app: true, body: { username: this.username, password: CFG.officerPassword },
      });
      if (r.status === 200 && r.json?.token) {
        this.token = r.json.token;
        this.zoneId = r.json.assignedZone?.id;
        this.street = r.json.assignedZone?.street;
        return true;
      }
      await sleep(rand(4000, 8000)); // görevli tekrar dener
    }
    return false;
  }

  // ---- ekran yenilemeleri (debounce'lar uygulamadaki gibi)
  scheduleMap() {
    clearTimeout(this.mapTimer);
    this.mapTimer = setTimeout(() => { if (running() && !this.netDown) this.fetchMap(); }, 300);
  }
  scheduleDash() {
    clearTimeout(this.dashTimer);
    this.dashTimer = setTimeout(() => { if (running() && !this.netDown) this.dashRefresh(); }, 500);
  }
  fetchMap() { return req('GET /api/parking/filter', 'GET', '/api/parking/filter', this.o({ query: { street: this.street } })); }
  dashRefresh() {
    return Promise.all([
      req('GET /api/parking/filter', 'GET', '/api/parking/filter', this.o({ query: { street: this.street } })),
      req('GET /api/parking/history', 'GET', '/api/parking/history', this.o({ query: { street: this.street } })),
      req('GET /api/vehicle-types', 'GET', '/api/vehicle-types', { app: true, device: this.device }),
    ]);
  }
  reloadAll() { // liveSync.reloads: main.dart + parking_map_screen.dart
    req('GET /api/tariff/current', 'GET', '/api/tariff/current', { app: true, device: this.device });
    req('GET /api/vehicle-types', 'GET', '/api/vehicle-types', { app: true, device: this.device });
    req('GET /api/license/status', 'GET', '/api/license/status', this.o());
    this.scheduleDash();
    this.scheduleMap();
  }

  onEvent(e) {
    if (['VEHICLE_ENTERED', 'VEHICLE_EXITED', 'VEHICLE_RUNAWAY'].includes(e.type) && e.zoneId === this.zoneId) this.scheduleMap();
    if (['VEHICLE_ENTERED', 'VEHICLE_EXITED', 'VEHICLE_RUNAWAY', 'VEHICLE_CANCELLED', 'RECORD_CANCELLED'].includes(e.type)
      && (e.zoneId == null || e.zoneId === this.zoneId)) this.scheduleDash();
  }

  deliver(e) {
    if (e.version <= (this.version ?? 0)) return;
    this.version = e.version;
    const dropped = e.scope === 'ZONE' && this.zoneId != null && e.zoneId !== this.zoneId;
    if (!dropped) this.onEvent(e);
  }

  // ---- LiveSync döngüsü
  async syncLoop() {
    while (running() && !this.netDown && this.version == null) {
      const r = await req('GET /api/sync', 'GET', '/api/sync', this.o());
      if (r.status === 401) { await this.login(); continue; }
      if (r.status === 200) { this.version = r.json?.version ?? 0; this.reloadAll(); break; }
      this.failures++;
      await sleep(backoff(this.failures));
    }
    let needCatchUp = false;
    while (running() && !this.netDown) {
      if (needCatchUp) {
        await sleep(backoff(this.failures));
        if (!running() || this.netDown) break;
        try { await this.catchUp(); } catch { this.failures++; continue; }
      }
      try { await this.stream(); } catch { /* aşağıda */ }
      if (this.online) { this.online = false; zoneMembers.set(this.zoneId, (zoneMembers.get(this.zoneId) || 1) - 1); }
      if (!running() || this.netDown) break;
      sse.drops++;
      this.failures++;
      needCatchUp = true;
    }
  }

  async catchUp() {
    if (this.catching) return;
    this.catching = true;
    try {
      while (running() && !this.netDown) {
        const r = await req('GET /api/sync?since', 'GET', '/api/sync', this.o({ query: { since: this.version } }));
        if (r.status !== 200) throw new Error('sync ' + r.status);
        const j = r.json || {};
        if (j.resetRequired) { this.version = j.version; this.reloadAll(); return; }
        let got = 0;
        for (const e of j.events || []) { got++; this.deliver(e); }
        if (j.hasMore && got > 0) continue;
        if (j.version != null && j.version > (this.version ?? 0)) this.version = j.version;
        return;
      }
    } finally {
      this.catching = false;
    }
  }

  async stream() {
    this.abort = new AbortController();
    const url = new URL(CFG.base + '/api/events');
    url.searchParams.set('since', String(this.version ?? 0));
    const t0 = performance.now();
    let res;
    try {
      res = await fetch(url, {
        headers: { Authorization: 'Bearer ' + this.token, 'X-Device-Id': this.device, Accept: 'text/event-stream',
          'Cache-Control': 'no-cache', ...APP_HEADERS },
        signal: this.abort.signal,
      });
    } catch (e) {
      rec('GET /api/events (bağlantı)', performance.now() - t0, e?.cause?.code || e?.name || 'error', false);
      throw e;
    }
    rec('GET /api/events (bağlantı)', performance.now() - t0, res.status, res.status === 200);
    if (res.status === 401) { await this.login(); throw new Error('401'); }
    if (res.status !== 200) { await res.body?.cancel(); throw new Error('events ' + res.status); }
    sse.connects++;
    await readSse(res, (name, data) => {
      let j = {};
      try { j = data ? JSON.parse(data) : {}; } catch { return; }
      if (name === 'HELLO') {
        sse.helloMs.push(performance.now() - t0);
        this.failures = 0;
        if (!this.online) { this.online = true; zoneMembers.set(this.zoneId, (zoneMembers.get(this.zoneId) || 0) + 1); }
        this.flush(); // main.dart: online olunca kuyruk boşaltılır
      } else if (name === 'HEARTBEAT') {
        if (j.version > (this.version ?? 0)) this.catchUp().catch(() => {});
      } else if (name === 'RESET') {
        sse.resets++;
        this.version = j.version;
        this.reloadAll();
      } else {
        if (!j.type) j.type = name;
        if (j.version > (this.version ?? 0) && j.type?.startsWith('VEHICLE_')) noteDelivery(j, 'officer');
        this.deliver(j);
      }
    }, this.abort, 60_000);
  }

  // ---- giriş kuyruğu (entry_queue.dart)
  flush() { return (this.flushing ??= this.doFlush().finally(() => { this.flushing = null; })); }
  async doFlush() {
    while (this.queue.length && running() && !this.netDown) {
      const e = this.queue[0];
      const q = { ...e.query, clientEntryTime: e.createdAt };
      let r;
      if (e.race) {
        const [a, b] = await Promise.all([this.checkInReq(q), this.checkInReq(q)]);
        const ids = [a, b].filter((x) => x.status === 200).map((x) => x.json?.id);
        const conflicts = [a, b].filter((x) => x.status === 409).length;
        if (ids.length === 2 && ids[0] !== ids[1]) corr.raceWrong++;
        else if (ids.length + conflicts === 2 && ids.length >= 1) corr.raceSame++;
        else if (a.status === 0 || a.status >= 500) { corr.flushFail++; return; }
        r = a.status === 200 ? a : b;
      } else {
        r = await this.checkInReq(q);
      }
      if (r.status === 0 || r.status >= 500 || r.status === 401) { corr.flushFail++; return; } // kayıt kalır
      this.queue.shift();
      corr.flushed++;
      if (r.status === 200) {
        this.plates.push(q.plate);
        activePlates.add(q.plate);
        this.sent.push({ query: e.query, spotId: r.json?.id });
      }
    }
  }
  checkInReq(q) {
    return req('POST /api/parking/check-in', 'POST', '/api/parking/check-in', this.o({ query: q, ok: [200, 409] }));
  }

  // ---- saha işlemleri (parking_map_screen.dart / vehicle_detail_screen.dart)
  async checkIn() {
    const plate = this.plate();
    const query = { plate, street: this.street, type: Math.random() < 0.85 ? 'SMALL' : 'LARGE',
      side: Math.random() < 0.5 ? 'LEFT' : 'RIGHT', zoneId: this.zoneId, checkInRequestId: randomUUID() };
    if (this.netDown) {
      this.queue.push({ query, createdAt: new Date().toISOString(), race: false });
      corr.queued++;
      return;
    }
    const r = await this.checkInReq(query);
    if (r.status === 200) { this.plates.push(plate); activePlates.add(plate); }
    else if (r.status === 0) { this.queue.push({ query, createdAt: new Date().toISOString() }); corr.queued++; this.flush(); }
    this.scheduleMap();
  }

  async checkOutLike(kind) {
    if (!this.plates.length) return this.checkIn();
    const i = Math.floor(Math.random() * this.plates.length);
    const plate = this.plates[i];
    await req('GET /api/parking/fee-preview', 'GET', '/api/parking/fee-preview', this.o({ query: { plate } }));
    await sleep(rand(3000, 8000) + 5000); // ekrana bakma + 5 sn geri alma penceresi
    if (!running() || this.netDown) return;
    let r;
    if (kind === 'out') {
      r = await req('POST /api/parking/check-out', 'POST', '/api/parking/check-out',
        this.o({ query: { plate, paymentMethod: Math.random() < 0.6 ? 'CASH' : 'POS' } }));
    } else if (kind === 'cancel') {
      r = await req('POST /api/parking/spots/cancel', 'POST', '/api/parking/spots/cancel',
        this.o({ query: { plate }, body: { reason: 'Yanlis giris (yuk testi)' } }));
    } else {
      r = await req('POST /api/parking/runaway', 'POST', '/api/parking/runaway', this.o({ query: { plate } }));
    }
    if (r.status === 200 || r.status === 404 || r.status === 409) {
      this.plates.splice(this.plates.indexOf(plate), 1);
      activePlates.delete(plate);
    }
    this.scheduleMap();
  }

  async act() {
    const n = this.plates.length;
    const r = Math.random();
    if (n < 4 || (n < 20 && r < 0.52)) return this.checkIn();
    if (r < 0.90) return this.checkOutLike('out');
    if (r < 0.96) return this.checkOutLike('cancel');
    return this.checkOutLike('runaway');
  }

  async run(startDelay) {
    await sleep(startDelay);
    if (!running()) return;
    activeOfficers++;
    if (!(await this.login())) return;
    // initState'ler: TariffStore/VehicleTypeStore/LicenseStore.load, /me, gösterge ve harita yüklemesi
    req('GET /api/me', 'GET', '/api/me', this.o());
    req('GET /api/tariff/current', 'GET', '/api/tariff/current', { app: true, device: this.device });
    req('GET /api/vehicle-types', 'GET', '/api/vehicle-types', { app: true, device: this.device });
    req('GET /api/license/status', 'GET', '/api/license/status', this.o());
    this.dashRefresh();
    this.fetchMap();
    this.loop = this.syncLoop();
    while (running()) {
      await sleep(rand(CFG.actionMin, CFG.actionMax) * 1000);
      if (!running()) break;
      try { await this.act(); } catch { /* sayıldı */ }
    }
  }

  // ---- kesinti senaryosu
  goOffline() {
    this.netDown = true;
    this.abort?.abort(new Error('outage'));
  }
  async comeOnline() {
    this.netDown = false;
    // her kuyruk kaydından biri eşzamanlı ikili gönderilir (yanıt kaybı yarışı)
    if (this.queue.length) this.queue[Math.floor(this.queue.length / 2)].race = true;
    await this.loop?.catch(() => {});
    this.failures = 0;
    this.loop = this.syncLoop(); // catch-up + akış; HELLO'da flush
    while (this.flushing || this.queue.length) {
      if (!running()) return;
      if (!this.flushing && this.queue.length) this.flush();
      await sleep(500);
    }
    // yanıtı kaybolmuş sayılan 3 kaydı aynı requestId ile tekrar gönder
    for (const s of this.sent.slice(0, 3)) {
      const r = await this.checkInReq({ ...s.query, clientEntryTime: new Date().toISOString() });
      if (r.status === 200 && r.json?.id === s.spotId) corr.dupSame++;
      else if (r.status === 409 && r.json?.code === 'DUPLICATE_REQUEST') corr.dupConflict++;
      else if (r.status === 409 && r.json?.code === 'ALREADY_PARKED') corr.dupWrong++;
      else if (r.status === 200) corr.dupWrong++;
    }
  }
}

// ---------------------------------------------------------------- admin (admin-panel.html)
class Admin {
  constructor(i) { this.i = i; this.token = null; this.version = 0; this.timer = null; this.retry = 0; }
  o(extra = {}) { return { token: this.token, timeout: 60000, ...extra }; }
  async login() {
    while (running()) {
      const r = await req('POST /api/auth/login (admin)', 'POST', '/api/auth/login',
        { body: { username: 'admin', password: CFG.adminPassword }, timeout: 60000 });
      if (r.status === 200) { this.token = r.json.token; return true; }
      await sleep(5000);
    }
    return false;
  }
  async syncVersion() {
    const r = await req('GET /api/sync', 'GET', '/api/sync', this.o());
    if (r.status === 200 && r.json?.version != null) this.version = r.json.version;
  }
  vehicleRefresh() {
    return Promise.all([
      req('GET /api/parking/spots', 'GET', '/api/parking/spots', this.o()),
      req('GET /api/parking/bi/income-by-type/details', 'GET', '/api/parking/bi/income-by-type/details', this.o()),
      req('GET /api/parking/bi/income-summary', 'GET', '/api/parking/bi/income-summary', this.o()),
      req('GET /api/parking/bi/last-records', 'GET', '/api/parking/bi/last-records', this.o()),
      req('GET /api/parking/bi/shifts', 'GET', '/api/parking/bi/shifts', this.o()),
    ]);
  }
  updateDashboard() {
    return Promise.all([
      this.vehicleRefresh(),
      req('GET /api/admin/fiscal-years', 'GET', '/api/admin/fiscal-years', this.o()),
      req('GET /api/tariff/current', 'GET', '/api/tariff/current', { timeout: 60000 }),
      req('GET /api/admin/tariffs', 'GET', '/api/admin/tariffs', this.o()),
      req('GET /api/license/status', 'GET', '/api/license/status', this.o()),
      req('GET /api/admin/records/cancelled', 'GET', '/api/admin/records/cancelled', this.o({ query: { page: 0, size: 20 } })),
      req('GET /api/admin/notifications', 'GET', '/api/admin/notifications', this.o({ query: { limit: 30 } })),
    ]);
  }
  async fullReload() {
    await this.syncVersion();
    await req('GET /api/admin/vehicle-types', 'GET', '/api/admin/vehicle-types', this.o());
    await this.updateDashboard();
  }
  scheduleVehicleRefresh() {
    if (this.timer) return;
    this.timer = setTimeout(async () => { this.timer = null; if (running()) await this.vehicleRefresh(); }, 800);
  }
  async live() {
    while (running()) {
      const t = await req('POST /api/events/ticket', 'POST', '/api/events/ticket', this.o());
      if (t.status === 401) { await this.login(); continue; }
      if (t.status === 200) {
        const url = new URL(CFG.base + '/api/events');
        url.searchParams.set('ticket', t.json.ticket);
        if (this.version > 0) url.searchParams.set('since', String(this.version));
        this.abort = new AbortController();
        const t0 = performance.now();
        let counted = false;
        try {
          const res = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal: this.abort.signal });
          rec('GET /api/events (bağlantı)', performance.now() - t0, res.status, res.status === 200);
          if (res.status === 200) {
            await readSse(res, (name, data) => {
              this.retry = 0;
              let d = {};
              try { d = data ? JSON.parse(data) : {}; } catch { return; }
              if (name === 'HELLO') { if (!counted) { counted = true; adminsOnline++; } if (!this.version && d.version) this.version = d.version; }
              else if (name === 'HEARTBEAT') { if (d.version > this.version) this.catchUp(); }
              else if (name === 'RESET') this.fullReload();
              else {
                if (d.version != null) { if (d.version <= this.version) return; this.version = d.version; }
                if (d.type?.startsWith('VEHICLE_')) { noteDelivery(d, 'admin'); this.scheduleVehicleRefresh(); }
              }
            }, this.abort, 60_000);
          } else await res.body?.cancel();
        } catch { /* yeniden bağlan */ }
        if (counted) adminsOnline--;
      }
      if (!running()) return;
      sse.drops++;
      await sleep(Math.min(30000, 1000 * 2 ** this.retry++));
    }
  }
  async catchUp() {
    const r = await req('GET /api/sync?since', 'GET', '/api/sync', this.o({ query: { since: this.version } }));
    for (const e of r.json?.events || []) {
      if (e.version > this.version) { this.version = e.version; if (e.type?.startsWith('VEHICLE_')) this.scheduleVehicleRefresh(); }
    }
    if (r.json?.version > this.version) this.version = r.json.version;
  }
  async reports() {
    const d = new Date();
    const month = d.toISOString().slice(0, 7);
    const today = d.toISOString().slice(0, 10);
    const from = new Date(d.getTime() - 30 * 86400_000).toISOString().slice(0, 10);
    let k = 0;
    while (running()) {
      await sleep(rand(0.6, 1.4) * CFG.reportEvery * 1000);
      if (!running()) return;
      const which = k++ % 3;
      if (which === 0) await req('GET /api/admin/reports/period (xlsx)', 'GET', '/api/admin/reports/period', this.o({ binary: true, query: { from: month + '-01', to: today } }));
      else if (which === 1) await req('GET /api/admin/reports/records (xlsx)', 'GET', '/api/admin/reports/records', this.o({ binary: true, query: { from, to: today } }));
      else await req('GET /api/admin/reports/personnel (xlsx)', 'GET', '/api/admin/reports/personnel', this.o({ binary: true, query: { month } }));
    }
  }
  async run(delay) {
    await sleep(delay);
    if (!(await this.login())) return;
    await this.syncVersion();
    await req('GET /api/streets', 'GET', '/api/streets', { timeout: 60000 });
    await req('GET /api/admin/vehicle-types', 'GET', '/api/admin/vehicle-types', this.o());
    await this.updateDashboard();
    this.live();
    this.reports();
    while (running()) {
      await sleep(rand(0.7, 1.3) * CFG.adminReload * 1000); // elle "Yenile" / sekme dönüşü
      if (running()) await this.fullReload();
    }
  }
}

// ---------------------------------------------------------------- citizens (customer-payment.html)
async function citizens() {
  let rpm = rand(CFG.citizenMin, CFG.citizenMax);
  let lastPick = now();
  while (running()) {
    const t = (now() - T0) / 1000;
    const inBurst = CFG.burstEvery > 0 && (t % CFG.burstEvery) > CFG.burstEvery - 60; // her pencerenin son dakikası
    if (now() - lastPick > 60_000) { rpm = rand(CFG.citizenMin, CFG.citizenMax); lastPick = now(); }
    const rate = inBurst ? CFG.burstRpm : rpm;
    await sleep(-Math.log(1 - Math.random()) * 60_000 / rate);
    if (!running()) return;
    (async () => {
      await req('GET /api/tariff/current', 'GET', '/api/tariff/current', { timeout: 30000 });
      const known = activePlates.size && Math.random() < 0.6;
      const plate = known ? pick([...activePlates]) : '34ZZ' + Math.floor(rand(100, 999));
      await req('GET /api/parking/debt', 'GET', '/api/parking/debt', { query: { plate }, ok: [200, 404], timeout: 30000 });
    })();
  }
}

// Eşzamanlı vatandaş dalgası: N kişi BURST_WINDOW sn içinde QR sayfasını açar (sayfa GitHub Pages'ten gelir,
// backend'e yalnızca /api/tariff/current + /api/parking/debt düşer). main'de /debt hız sınırı yok; tek IP'den gelinir.
async function citizenWave(n, windowSec, tag) {
  const t1 = now();
  console.log(`[${sec()}s] VATANDAŞ DALGASI ${tag}: ${n} kişi / ${windowSec} sn`);
  await Promise.all(Array.from({ length: n }, async () => {
    await sleep(Math.random() * windowSec * 1000);
    if (!running()) return;
    await req(`GET /api/tariff/current [${tag}]`, 'GET', '/api/tariff/current', { timeout: 30000 });
    const known = activePlates.size && Math.random() < 0.6;
    const plate = known ? pick([...activePlates]) : '34ZZ' + Math.floor(rand(100, 999));
    await req(`GET /api/parking/debt [${tag}]`, 'GET', '/api/parking/debt', { query: { plate }, ok: [200, 404], timeout: 30000 });
  }));
  console.log(`[${sec()}s] dalga ${tag} bitti: ${((now() - t1) / 1000).toFixed(0)} sn`);
}

// ---------------------------------------------------------------- developer page
async function developer() {
  await sleep(rand(30, 90) * 1000);
  let token = null;
  while (running() && !token) {
    const r = await req('POST /api/auth/login (gelistirici)', 'POST', '/api/auth/login',
      { body: { username: 'gelistirici', password: CFG.devPassword }, timeout: 60000 });
    if (r.status === 200) token = r.json.token; else await sleep(10000);
  }
  let k = 0;
  while (running()) {
    const o = { token, timeout: 60000 };
    if (k % 5 === 0) {
      await req('GET /api/developer/health', 'GET', '/api/developer/health', o);
      await req('GET /api/developer/errors', 'GET', '/api/developer/errors', { ...o, query: { limit: 50 } });
    }
    await req('GET /api/developer/activity', 'GET', '/api/developer/activity', { ...o, query: { hours: 24 } });
    k++;
    await sleep(60_000);
  }
}

// ---------------------------------------------------------------- main
console.log(`[${sec()}s] başla: ${JSON.stringify({ ...CFG, officerPassword: undefined, adminPassword: undefined, devPassword: undefined })}`);

const officers = Array.from({ length: CFG.officers }, (_, i) => new Officer(i));
const tasks = [];
officers.forEach((o, i) => {
  let delay;
  if (CFG.steps) {
    const step = CFG.steps.findIndex((s) => i < s);
    const prev = step > 0 ? CFG.steps[step - 1] : 0;
    delay = step * CFG.stepSec * 1000 + ((i - prev) / Math.max(1, CFG.steps[step] - prev)) * 30_000;
  } else {
    delay = (i / Math.max(1, CFG.officers)) * CFG.ramp * 1000 + rand(0, 1000);
  }
  tasks.push(o.run(delay));
});
for (let i = 0; i < CFG.admins; i++) tasks.push(new Admin(i).run(i * 5000));
tasks.push(citizens());
if (CFG.dev) tasks.push(developer());

// WAVES="100,200,300,400" WAVE_START=120 WAVE_GAP=180 WAVE_WINDOW=60
if (env('WAVES', '')) {
  (async () => {
    const waves = env('WAVES', '').split(',').map(Number);
    await sleep(num('WAVE_START', 120) * 1000);
    for (const n of waves) {
      if (!running()) return;
      const t1 = now();
      await citizenWave(n, num('WAVE_WINDOW', 60), 'dalga ' + n);
      await sleep(num('WAVE_GAP', 180) * 1000 - (now() - t1));
    }
  })();
}

if (CFG.scenario === 'spike') {
  (async () => {
    await sleep(CFG.spikeAt * 1000);
    console.log(`[${sec()}s] KESİNTİ: ${officers.length} görevlinin ağı gitti, her biri ${CFG.queue} giriş kuyruklayacak`);
    officers.forEach((o) => o.goOffline());
    // kesinti boyunca kuyruğa giriş (createdAt kesinti içinde dağılır)
    await Promise.all(officers.map(async (o) => {
      for (let k = 0; k < CFG.queue; k++) {
        await sleep((CFG.outage * 1000) / CFG.queue * rand(0.8, 1.0));
        await o.checkIn();
      }
    }));
    const t1 = now();
    console.log(`[${sec()}s] AĞ GERİ GELDİ: kuyruk toplamı ${officers.reduce((a, o) => a + o.queue.length, 0)}`);
    await Promise.all(officers.map(async (o) => { await sleep(rand(0, 2000)); await o.comeOnline(); }));
    corr.replaySeconds = (now() - t1) / 1000;
    console.log(`[${sec()}s] kuyruklar boşaldı: ${corr.replaySeconds.toFixed(1)} sn`);
  })();
}

// SSE bağlı sayısını kovaya yaz, ilerleme göster
const ticker = setInterval(() => {
  const b = bucket();
  b.sse = officers.filter((o) => o.online).length + adminsOnline;
  const last = buckets[buckets.length - 2];
  if (last && Math.floor((now() - T0) / 1000) % 60 < 10) {
    const s = [...last.lat].sort((a, b) => a - b);
    console.log(`[${sec()}s] görevli=${activeOfficers} sse=${b.sse} 10sn: istek=${last.n} hata=${last.bad} p95=${(pct(s, 95) ?? 0).toFixed(0)}ms`);
  }
}, BUCKET_MS);

await sleep(END - now());
STOP = true;
clearInterval(ticker);
officers.forEach((o) => { o.abort?.abort(new Error('son')); clearTimeout(o.mapTimer); clearTimeout(o.dashTimer); });
await sleep(3000);

// ---------------------------------------------------------------- report
const endpoints = [...M.entries()].map(([name, m]) => {
  const s = m.lat.sort((a, b) => a - b);
  return { name, n: s.length, bad: m.bad, errPct: +(100 * m.bad / s.length).toFixed(2),
    p50: +pct(s, 50).toFixed(0), p95: +pct(s, 95).toFixed(0), p99: +pct(s, 99).toFixed(0), max: +s[s.length - 1].toFixed(0),
    codes: m.codes };
}).sort((a, b) => b.n - a.n);
const all = endpoints.reduce((a, e) => ({ n: a.n + e.n, bad: a.bad + e.bad }), { n: 0, bad: 0 });
const allLat = [...M.values()].flatMap((m) => m.lat).sort((a, b) => a - b);
const d = sse.delays.sort((a, b) => a - b);
const perEv = [...sse.perEvent.values()];
const evMax = perEv.map((e) => e.max).sort((a, b) => a - b);
const complete = perEv.filter((e) => e.n >= e.expected).length;
const series = buckets.map((b, i) => {
  const s = b.lat.sort((x, y) => x - y);
  return { t: i * BUCKET_MS / 1000, officers: b.officers, sse: b.sse, rps: +(b.n / (BUCKET_MS / 1000)).toFixed(1),
    errPct: b.n ? +(100 * b.bad / b.n).toFixed(1) : 0, p95: +(pct(s, 95) ?? 0).toFixed(0) };
});
const result = {
  label: CFG.label, config: { ...CFG, officerPassword: undefined, adminPassword: undefined, devPassword: undefined },
  startedAt: new Date(T0).toISOString(), seconds: (now() - T0) / 1000,
  total: { n: all.n, bad: all.bad, errPct: +(100 * all.bad / Math.max(1, all.n)).toFixed(2),
    rps: +(all.n / CFG.duration).toFixed(2), p50: pct(allLat, 50), p95: pct(allLat, 95), p99: pct(allLat, 99) },
  sse: { deliveries: d.length, p50: pct(d, 50), p95: pct(d, 95), p99: pct(d, 99), max: d[d.length - 1] ?? null,
    events: perEv.length, eventMaxP95: pct(evMax, 95), eventMaxMax: evMax[evMax.length - 1] ?? null,
    eventsReachedAllExpected: complete, connects: sse.connects, drops: sse.drops, resets: sse.resets,
    helloP95: pct(sse.helloMs.sort((a, b) => a - b), 95) },
  correctness: corr, endpoints, series,
};
writeFileSync(CFG.out, JSON.stringify(result, null, 1));
console.log(`\n=== ${CFG.label}: ${all.n} istek, hata %${result.total.errPct}, ${result.total.rps} istek/sn, p95 ${result.total.p95?.toFixed(0)} ms ===`);
console.log('| uç nokta | n | hata % | p50 | p95 | p99 | max |');
for (const e of endpoints) console.log(`| ${e.name} | ${e.n} | ${e.errPct} | ${e.p50} | ${e.p95} | ${e.p99} | ${e.max} |`);
console.log(`SSE: teslim=${d.length} p50=${result.sse.p50} p95=${result.sse.p95} max=${result.sse.max} ms; olay=${perEv.length}, tüm beklenenlere ulaşan=${complete}; kopma=${sse.drops} reset=${sse.resets}`);
console.log('doğruluk:', JSON.stringify(corr));
process.exit(0);
