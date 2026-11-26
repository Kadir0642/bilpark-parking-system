// Tarayıcıda plaka okuma motoru: hafif ön işleme + PaddleOCR PP-OCRv4 "en" tanıma modeli (onnxruntime-web, WASM).
// Ölçüm ve gerekçe: docs/web-ocr-deneme.md (dal claude/web-ocr-deneme). Her şey cihazda çalışır; görüntü hiçbir yere gönderilmez.
// Kütüphane ve model yalnızca bu modül ilk kez kullanıldığında jsDelivr'den sabit sürümle iner. Lisanslar: web/THIRD_PARTY.md.

const ORT_VERSION = '1.31.1';
const MODEL_PKG = 'paddle-ocr-onnx-models@0.2.0';
const CDN = 'https://cdn.jsdelivr.net/npm/';

// Her dosya SHA-384 özetiyle doğrulanır (npm paketindeki dosyalarla ve jsDelivr kopyasıyla aynı olduğu denetlendi).
// ORT kodu fetch(integrity) ile indirilip blob adresinden yüklenir; WASM ikilisi de aynı biçimde doğrulanıp verilir.
const ORT_DIST = CDN + 'onnxruntime-web@' + ORT_VERSION + '/dist/';
export const SOURCES = {
  ortEsm: ORT_DIST + 'ort.wasm.min.mjs',
  ortEsmSri: 'sha384-Vo83xJ0b4xRW+799QzqOViZ2AdoasAnbfsI9UCyxkEGv/8dtIqcHTZ4EJOlDMcfc',
  ortGlue: ORT_DIST + 'ort-wasm-simd-threaded.mjs',
  ortGlueSri: 'sha384-L9olMNCg9w3FaNhr+m7EAeeiUpM5zy2Nlh6APnoogtFXjsL4ZZZRXlYhSVJc1r3j',
  ortWasm: ORT_DIST + 'ort-wasm-simd-threaded.wasm',
  ortWasmSri: 'sha384-bQJgW/bZhNOlc9KU45Zu0dyd0EFfNpkOZjfHyH/90dkZQSBBGkFTsAJaabSyYA4Z',
  model: CDN + MODEL_PKG + '/models/en_PP-OCRv4_rec.onnx',
  // npm paketindeki dosyanın SHA-384 özeti; inen dosya bununla karşılaştırılır (bozuk ya da değişmiş dosya kullanılmaz)
  modelSha384: 'lUpTmBEBmu5rozteF7LEA/YPVtHI7eUjdCZbKMsUmePq9zbX+AyNTCTK3JMCOeXv',
  modelBytes: 7666529,
};

const ALNUM = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

// ------------------------------------------------------------------ yardımcılar

function canvasOf(w, h) {
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w));
  c.height = Math.max(1, Math.round(h));
  return c;
}
const ctx = (c) => c.getContext('2d', { willReadFrequently: true });

function scaled(src, w, h) {
  const c = canvasOf(w, h);
  const g = ctx(c);
  g.imageSmoothingQuality = 'high';
  g.drawImage(src, 0, 0, c.width, c.height);
  return c;
}

function grayOf(data, n) {
  const g = new Float32Array(n);
  for (let i = 0; i < n; i++) g[i] = 0.299 * data[4 * i] + 0.587 * data[4 * i + 1] + 0.114 * data[4 * i + 2];
  return g;
}

function percentile(arr, p) {
  const s = Float32Array.from(arr).sort();
  return s[Math.min(s.length - 1, Math.max(0, Math.floor(p * (s.length - 1))))];
}

/** Parlaklık/kontrast germe (yüzde 1-99): karanlık ve parlamalı karelerde modeli rahatlatır. */
function autoContrast(canvas) {
  const g = ctx(canvas);
  const im = g.getImageData(0, 0, canvas.width, canvas.height);
  const n = canvas.width * canvas.height;
  const gray = grayOf(im.data, n);
  const lo = percentile(gray, 0.01);
  const hi = percentile(gray, 0.99);
  const k = hi - lo > 10 ? 255 / (hi - lo) : 1;
  for (let i = 0; i < n * 4; i++) {
    if (i % 4 === 3) continue;
    im.data[i] = Math.max(0, Math.min(255, (im.data[i] - lo) * k));
  }
  g.putImageData(im, 0, 0);
  return canvas;
}

// ------------------------------------------------------------------ ön işleme (eğim, yazı bandı, mavi bant)

const AN_W = 400; // analiz genişliği

function edgePoints(canvas) {
  const w = canvas.width;
  const h = canvas.height;
  const im = ctx(canvas).getImageData(0, 0, w, h).data;
  const gray = grayOf(im, w * h);
  const lo = percentile(gray, 0.02);
  const hi = percentile(gray, 0.98);
  const k = hi - lo > 5 ? 255 / (hi - lo) : 1;
  const blue = new Uint8Array(w * h);
  for (let i = 0; i < w * h; i++) {
    const r = im[4 * i];
    const gg = im[4 * i + 1];
    const b = im[4 * i + 2];
    blue[i] = b > 35 && b > r * 1.6 && b > gg * 1.25 && b - r > 25 ? 1 : 0;
  }
  const pts = [];
  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const gx = Math.abs(gray[y * w + x + 1] - gray[y * w + x - 1]) * k;
      if (gx > 60) pts.push(x, y);
    }
  }
  return { pts, blue, w, h };
}

/** Projeksiyon profiliyle eğim tahmini (derece): yazı satırı yatayken satır histogramı en keskindir. */
function estimateAngle({ pts, w, h }) {
  if (pts.length < 40) return 0;
  let best = 0;
  let bestScore = -1;
  const cx = w / 2;
  const cy = h / 2;
  for (let a = -12; a <= 12; a += 1) {
    const t = (a * Math.PI) / 180;
    const sin = Math.sin(t);
    const cos = Math.cos(t);
    const hist = new Float32Array(h * 2 + 2);
    for (let i = 0; i < pts.length; i += 2) {
      const y = -(pts[i] - cx) * sin + (pts[i + 1] - cy) * cos + h;
      hist[Math.max(0, Math.min(hist.length - 1, Math.round(y)))] += 1;
    }
    let score = 0;
    for (let j = 0; j < hist.length; j++) score += hist[j] * hist[j];
    if (score > bestScore) {
      bestScore = score;
      best = a;
    }
  }
  return best;
}

function rotate(canvas, deg) {
  if (!deg) return canvas;
  const c = canvasOf(canvas.width, canvas.height);
  const g = ctx(c);
  g.fillStyle = '#808080';
  g.fillRect(0, 0, c.width, c.height);
  g.translate(c.width / 2, c.height / 2);
  g.rotate((deg * Math.PI) / 180);
  g.drawImage(canvas, -canvas.width / 2, -canvas.height / 2);
  return c;
}

function smooth(arr, r) {
  const out = new Float32Array(arr.length);
  for (let i = 0; i < arr.length; i++) {
    let s = 0;
    let n = 0;
    for (let j = Math.max(0, i - r); j <= Math.min(arr.length - 1, i + r); j++) {
      s += arr[j];
      n++;
    }
    out[i] = s / n;
  }
  return out;
}

/** Yazı bandı: dikey kenar yoğunluğunun en yüksek olduğu satırlar, sonra o banttaki en güçlü sütun kümesi. */
function findTextRect({ pts, blue, w, h }) {
  const rowP = new Float32Array(h);
  for (let i = 0; i < pts.length; i += 2) rowP[pts[i + 1]]++;
  const rs = smooth(rowP, Math.max(1, Math.round(h / 30)));
  let peak = 0;
  for (let y = 1; y < h; y++) if (rs[y] > rs[peak]) peak = y;
  if (rs[peak] < 3) return null;
  let y0 = peak;
  let y1 = peak;
  while (y0 > 0 && rs[y0 - 1] > rs[peak] * 0.3) y0--;
  while (y1 < h - 1 && rs[y1 + 1] > rs[peak] * 0.3) y1++;
  const th = Math.max(4, y1 - y0);

  // Mavi bant (TR / AB ülke kodu) sütunları
  const blueCol = new Uint8Array(w);
  for (let x = 0; x < w; x++) {
    let b = 0;
    for (let y = y0; y <= y1; y++) b += blue[y * w + x];
    blueCol[x] = b / (y1 - y0 + 1) > 0.3 ? 1 : 0;
  }
  const colP = new Float32Array(w);
  for (let i = 0; i < pts.length; i += 2) {
    const y = pts[i + 1];
    if (y >= y0 && y <= y1 && !blueCol[pts[i]]) colP[pts[i]]++;
  }
  const cs = smooth(colP, 2);
  let cmax = 0;
  for (let x = 0; x < w; x++) if (cs[x] > cmax) cmax = cs[x];
  const clusters = [];
  let cur = null;
  for (let x = 0; x < w; x++) {
    if (cs[x] > cmax * 0.08) {
      if (cur && x - cur.x1 <= th * 1.2) {
        cur.x1 = x;
        cur.sum += cs[x];
      } else {
        cur = { x0: x, x1: x, sum: cs[x] };
        clusters.push(cur);
      }
    }
  }
  if (!clusters.length) return null;
  const best = clusters.reduce((a, b) => (b.sum > a.sum ? b : a));
  let x0 = best.x0;
  for (let x = best.x0; x < best.x0 + (best.x1 - best.x0) * 0.3; x++) if (blueCol[x]) x0 = x + 1;
  const padY = th * 0.25;
  const padX = th * 0.3;
  return {
    x: Math.max(0, x0 - padX),
    y: Math.max(0, y0 - padY),
    x1: Math.min(w, best.x1 + padX),
    y1: Math.min(h, y1 + padY),
  };
}

/** Kılavuz çerçeve kırpımından modele verilecek tek satırlık görüntüyü üretir. */
export function preprocess(full) {
  const s = Math.min(1, AN_W / full.width);
  const small = scaled(full, full.width * s, full.height * s);
  const angle = estimateAngle(edgePoints(small));
  const rotSmall = rotate(small, -angle);
  const rect = findTextRect(edgePoints(rotSmall));
  const rotFull = rotate(full, -angle);
  if (!rect) return autoContrast(rotFull);
  const r = { x: rect.x / s, y: rect.y / s, w: (rect.x1 - rect.x) / s, h: (rect.y1 - rect.y) / s };
  const c = canvasOf(Math.max(8, r.w), Math.max(8, r.h));
  ctx(c).drawImage(rotFull, r.x, r.y, r.w, r.h, 0, 0, c.width, c.height);
  return autoContrast(c);
}

// ------------------------------------------------------------------ model indirme ve oturum

// PaddleOCR en_dict.txt sırası: ASCII 48-126, sonra 33-47; sonda boşluk; 0. sınıf CTC boşluğu (blank)
function enDict() {
  const chars = [];
  for (let c = 48; c <= 126; c++) chars.push(String.fromCharCode(c));
  for (let c = 33; c <= 47; c++) chars.push(String.fromCharCode(c));
  chars.push(' ');
  return chars;
}

function toBase64(buf) {
  const b = new Uint8Array(buf);
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
  return btoa(s);
}

/** Modeli ilerleme bilgisiyle indirir ve SHA-384 özetini doğrular. onProgress(0..1). */
async function fetchModel(onProgress, signal) {
  const res = await fetch(SOURCES.model, { signal, credentials: 'omit', referrerPolicy: 'no-referrer' });
  if (!res.ok || !res.body) throw new Error('model indirilemedi (' + res.status + ')');
  const reader = res.body.getReader();
  const out = new Uint8Array(SOURCES.modelBytes);
  let got = 0;
  const parts = [];
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (got + value.length <= out.length) out.set(value, got);
    else parts.push(value);
    got += value.length;
    if (onProgress) onProgress(Math.min(1, got / SOURCES.modelBytes));
  }
  if (got !== SOURCES.modelBytes || parts.length) throw new Error('model boyutu beklenenden farklı');
  const digest = await crypto.subtle.digest('SHA-384', out);
  if (toBase64(digest) !== SOURCES.modelSha384) throw new Error('model özeti tutmadı');
  return out;
}

/** Dosyayı bütünlük denetimiyle indirir (özet tutmazsa tarayıcı yanıtı reddeder). */
async function fetchChecked(url, integrity) {
  const res = await fetch(url, { integrity, credentials: 'omit', referrerPolicy: 'no-referrer' });
  if (!res.ok) throw new Error('indirilemedi (' + res.status + ')');
  return res.arrayBuffer();
}
const jsBlobUrl = (buf) => URL.createObjectURL(new Blob([buf], { type: 'text/javascript' }));

let enginePromise = null;

/**
 * Motoru bir kez hazırlar (sonraki çağrılar aynı sözü döner). Hata olursa bir sonraki çağrı yeniden dener.
 * onProgress(0..1): model indirme ilerlemesi.
 */
export function loadEngine(onProgress) {
  if (!enginePromise) {
    enginePromise = (async () => {
      const [ortJs, glueJs, wasm, bytes] = await Promise.all([
        fetchChecked(SOURCES.ortEsm, SOURCES.ortEsmSri),
        fetchChecked(SOURCES.ortGlue, SOURCES.ortGlueSri),
        fetchChecked(SOURCES.ortWasm, SOURCES.ortWasmSri),
        fetchModel(onProgress),
      ]);
      const ort = await import(jsBlobUrl(ortJs));
      ort.env.wasm.wasmPaths = { mjs: jsBlobUrl(glueJs) };
      ort.env.wasm.wasmBinary = wasm;
      ort.env.wasm.numThreads = 1; // GitHub Pages COOP/COEP başlığı vermez: tek iş parçacığı
      const session = await ort.InferenceSession.create(bytes, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' });
      const dict = enDict();
      const groups = new Int16Array(dict.length + 1).fill(-1);
      for (let i = 0; i < dict.length; i++) {
        const ch = dict[i].toUpperCase();
        groups[i + 1] = ch.length === 1 ? ALNUM.indexOf(ch) : -1;
      }
      return { ort, session, groups };
    })();
    enginePromise.catch(() => { enginePromise = null; });
  }
  return enginePromise;
}

/**
 * Bir satır görüntüsünü okur. Dönüş: { text, minConf } — text yalnızca A-Z0-9, minConf en düşük karakter güveni (0..1).
 * Çözüm A-Z0-9'a kısıtlı açgözlü CTC: küçük harf olasılığı büyüğe eklenir, noktalama/boşluk "blank" grubuna gider.
 */
export async function recognizeLine(canvas) {
  const { ort, session, groups } = await loadEngine();
  const H = 48;
  const W = Math.max(32, Math.min(800, Math.round((canvas.width * H) / canvas.height / 8) * 8));
  const img = scaled(canvas, W, H);
  const px = ctx(img).getImageData(0, 0, W, H).data;
  const t = new Float32Array(3 * H * W);
  for (let i = 0; i < H * W; i++) {
    // PaddleOCR BGR sırası, (x/255 - 0.5) / 0.5
    t[i] = (px[4 * i + 2] / 255 - 0.5) / 0.5;
    t[H * W + i] = (px[4 * i + 1] / 255 - 0.5) / 0.5;
    t[2 * H * W + i] = (px[4 * i] / 255 - 0.5) / 0.5;
  }
  const feeds = { [session.inputNames[0]]: new ort.Tensor('float32', t, [1, 3, H, W]) };
  const out = (await session.run(feeds))[session.outputNames[0]];
  const T = out.dims[1];
  const C = out.dims[2];
  const p = out.data;
  let text = '';
  let prev = -1;
  let runMax = 0;
  const confs = [];
  const g = new Float32Array(ALNUM.length);
  for (let ti = 0; ti < T; ti++) {
    const row = ti * C;
    g.fill(0);
    let blank = 0;
    for (let c = 0; c < C; c++) {
      const k = c < groups.length ? groups[c] : -1;
      if (c === 0 || k < 0) blank += p[row + c];
      else g[k] += p[row + c];
    }
    let bk = -1;
    let bv = blank;
    for (let k = 0; k < g.length; k++) if (g[k] > bv) { bv = g[k]; bk = k; }
    if (bk >= 0 && bk !== prev) {
      if (text.length) confs.push(runMax);
      text += ALNUM[bk];
      runMax = bv;
    } else if (bk >= 0) runMax = Math.max(runMax, bv);
    prev = bk;
  }
  if (text.length) confs.push(runMax);
  return { text, minConf: confs.length ? Math.min(...confs) : 0 };
}

/** Kırpılmış kamera karesini (çerçevenin içi) okur. */
export async function readPlateImage(frameCanvas) {
  return recognizeLine(preprocess(frameCanvas));
}
