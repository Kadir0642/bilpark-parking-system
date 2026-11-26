// Tarayıcıda plaka okuma denemesi: ön işleme + iki OCR motoru (tesseract.js, PaddleOCR ONNX).
// Her şey cihazda çalışır; görüntü hiçbir yere gönderilmez. Kütüphaneler yalnızca ilk kullanımda yüklenir.

import { extractPlateWithFixes, extractPlateGrouped, extractRawPlate } from './plate.js';
import { readForeignPlate, foreignAlnum } from './foreign_ocr.js';

// Sürümler sabit (CDN'de tam sürüm). local=true ise aynı dosyalar /node_modules altından gelir.
export const VERSIONS = {
  tesseract: '7.0.0',
  tesseractCore: '7.0.0',
  tesseractEng: '1.0.0',
  ort: '1.31.1',
  paddleModels: '0.2.0',
  gutenyeModels: '1.4.2',
};

export function urls(local) {
  const npm = local ? '/node_modules/' : 'https://cdn.jsdelivr.net/npm/';
  const v = (name, ver) => (local ? `${npm}${name}` : `${npm}${name}@${ver}`);
  return {
    tesseractEsm: `${v('tesseract.js', VERSIONS.tesseract)}/dist/tesseract.esm.min.js`,
    tesseractWorker: `${v('tesseract.js', VERSIONS.tesseract)}/dist/worker.min.js`,
    tesseractCore: v('tesseract.js-core', VERSIONS.tesseractCore),
    tesseractLang: `${v('@tesseract.js-data/eng', VERSIONS.tesseractEng)}/4.0.0_best_int`,
    ortEsm: `${v('onnxruntime-web', VERSIONS.ort)}/dist/ort.wasm.min.mjs`,
    ortWasmDir: `${v('onnxruntime-web', VERSIONS.ort)}/dist/`,
    paddleEn: `${v('paddle-ocr-onnx-models', VERSIONS.paddleModels)}/models/en_PP-OCRv4_rec.onnx`,
    paddleCh: `${v('@gutenye/ocr-models', VERSIONS.gutenyeModels)}/assets/ch_PP-OCRv4_rec_infer.onnx`,
    paddleChKeys: `${v('@gutenye/ocr-models', VERSIONS.gutenyeModels)}/assets/ppocr_keys_v1.txt`,
  };
}

// ------------------------------------------------------------------ yardımcılar

function canvasOf(w, h) {
  if (typeof document !== 'undefined') return Object.assign(document.createElement('canvas'), { width: w, height: h });
  return new OffscreenCanvas(w, h);
}

function toCanvas(src) {
  const w = src.naturalWidth || src.videoWidth || src.width;
  const h = src.naturalHeight || src.videoHeight || src.height;
  const c = canvasOf(w, h);
  c.getContext('2d', { willReadFrequently: true }).drawImage(src, 0, 0, w, h);
  return c;
}

function scaled(src, w, h) {
  const c = canvasOf(Math.max(1, Math.round(w)), Math.max(1, Math.round(h)));
  const g = c.getContext('2d', { willReadFrequently: true });
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

/** Parlaklık/kontrast germe (yüzde 1-99). Karanlık ve parlamalı karelerde iki motoru da rahatlatır. */
function autoContrast(canvas) {
  const g = canvas.getContext('2d', { willReadFrequently: true });
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

// ------------------------------------------------------------------ ön işleme (hafif plaka bulma)

const AN_W = 400; // analiz genişliği

function edgePoints(canvas) {
  const w = canvas.width;
  const h = canvas.height;
  const im = canvas.getContext('2d', { willReadFrequently: true }).getImageData(0, 0, w, h).data;
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

/** Projeksiyon profili ile eğim tahmini (derece): yazı satırları yatayken satır histogramı en keskindir. */
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
  const g = c.getContext('2d', { willReadFrequently: true });
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

/** Yazı bandını bulur: dikey kenar yoğunluğunun en yüksek olduğu satırlar, sonra o banttaki sütun kümesi. */
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

  // Mavi bant (TR / AB ülke kodu) sütunları: bant satırlarında mavi oranı yüksek olan sütunlar
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
  const cmax = Math.max(...cs);
  // Etkin sütun kümeleri; boşluk < 1.2 * yazı yüksekliği ise birleşir
  const clusters = [];
  let cur = null;
  for (let x = 0; x < w; x++) {
    const on = cs[x] > cmax * 0.08;
    if (on) {
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
  // Kümenin içine düşen mavi bandı soldan kes
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

/**
 * Kılavuz çerçeve kırpımından OCR'a verilecek satır görüntüsünü üretir.
 * mode='band': eğim düzeltme + yazı bandı + mavi bant kesme; mode='none': kareyi olduğu gibi verir.
 */
export function preprocess(src, mode = 'band') {
  const full = toCanvas(src);
  if (mode === 'none') return { canvas: autoContrast(full), angle: 0, rect: null };
  const s = Math.min(1, AN_W / full.width);
  const small = scaled(full, full.width * s, full.height * s);
  const angle = estimateAngle(edgePoints(small));
  // estimateAngle: yazı satırı y = x * tan(a) eğiminde (canvas y aşağı). Düzeltmek için -a döndür.
  const rotSmall = rotate(small, -angle);
  const rect = findTextRect(edgePoints(rotSmall));
  const rotFull = rotate(full, -angle);
  if (!rect) return { canvas: autoContrast(rotFull), angle, rect: null };
  const r = { x: rect.x / s, y: rect.y / s, w: (rect.x1 - rect.x) / s, h: (rect.y1 - rect.y) / s };
  const c = canvasOf(Math.max(8, Math.round(r.w)), Math.max(8, Math.round(r.h)));
  c.getContext('2d', { willReadFrequently: true }).drawImage(rotFull, r.x, r.y, r.w, r.h, 0, 0, c.width, c.height);
  return { canvas: autoContrast(c), angle, rect: r };
}

// ------------------------------------------------------------------ motor 1: tesseract.js

const WHITELIST = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

export function createTesseract({ local = false } = {}) {
  const u = urls(local);
  let worker = null;
  return {
    name: 'tesseract',
    async init() {
      const mod = await import(u.tesseractEsm);
      const T = mod.default || mod;
      worker = await T.createWorker('eng', 1, {
        workerPath: u.tesseractWorker,
        corePath: u.tesseractCore,
        langPath: u.tesseractLang,
        cacheMethod: 'none',
      });
      await worker.setParameters({
        tessedit_pageseg_mode: '7', // tek satır
        tessedit_char_whitelist: WHITELIST,
        user_defined_dpi: '300',
      });
    },
    async recognize(canvas) {
      // Tesseract ~30-40 px yazı yüksekliğini sever: satırı 96 px yüksekliğe getir
      const h = 96;
      const img = scaled(canvas, (canvas.width * h) / canvas.height, h);
      const { data } = await worker.recognize(img, {}, { text: true, blocks: true });
      const words = [];
      const sym = [];
      for (const b of data.blocks || []) {
        for (const p of b.paragraphs) {
          for (const l of p.lines) {
            for (const wd of l.words) {
              words.push({ text: wd.text, left: wd.bbox.x0, top: wd.bbox.y0, right: wd.bbox.x1, bottom: wd.bbox.y1 });
              for (const sy of wd.symbols || []) sym.push(sy.confidence);
            }
          }
        }
      }
      // Güven: sembol güvenlerinin ortalaması ve en düşüğü (beyaz listeyle kelime güveni 0 dönüyor)
      const conf = sym.length ? sym.reduce((a, b) => a + b, 0) / sym.length / 100 : 0;
      const minConf = sym.length ? Math.min(...sym) / 100 : 0;
      return { text: (data.text || '').trim(), words, conf, minConf, frame: [img.width, img.height] };
    },
    async dispose() {
      if (worker) await worker.terminate();
      worker = null;
    },
  };
}

// ------------------------------------------------------------------ motor 2: PaddleOCR tanıma modeli (onnxruntime-web)

// PaddleOCR en_dict.txt sırası: ASCII 48-126, sonra 33-47; sonda boşluk; 0. sınıf CTC boşluğu (blank)
function enDict() {
  const chars = [];
  for (let c = 48; c <= 126; c++) chars.push(String.fromCharCode(c));
  for (let c = 33; c <= 47; c++) chars.push(String.fromCharCode(c));
  chars.push(' ');
  return chars;
}

export function createPaddle({ local = false, model = 'en' } = {}) {
  const u = urls(local);
  let session = null;
  let ort = null;
  let dict = null;
  let groups = null; // her sınıfın A-Z0-9 karşılığı (yoksa -1 = boşluk grubuna)
  return {
    name: `paddle-${model}`,
    async init() {
      ort = await import(u.ortEsm);
      ort.env.wasm.wasmPaths = u.ortWasmDir;
      ort.env.wasm.numThreads = 1; // GitHub Pages'te COOP/COEP yok: tek iş parçacığı gerçekçi
      if (model === 'en') dict = enDict();
      else {
        const txt = await (await fetch(u.paddleChKeys)).text();
        dict = txt.split(/\r?\n/).filter((l, i, a) => i < a.length - 1 || l.length);
        dict.push(' ');
      }
      session = await ort.InferenceSession.create(model === 'en' ? u.paddleEn : u.paddleCh, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' });
      const alnum = WHITELIST;
      groups = new Int16Array(dict.length + 1).fill(-1);
      for (let i = 0; i < dict.length; i++) {
        const ch = dict[i].toUpperCase();
        const k = ch.length === 1 ? alnum.indexOf(ch) : -1;
        groups[i + 1] = k;
      }
    },
    async recognize(canvas) {
      const H = 48;
      const W = Math.max(32, Math.min(800, Math.round((canvas.width * H) / canvas.height / 8) * 8));
      const img = scaled(canvas, W, H);
      const px = img.getContext('2d', { willReadFrequently: true }).getImageData(0, 0, W, H).data;
      const t = new Float32Array(3 * H * W);
      for (let i = 0; i < H * W; i++) {
        // PaddleOCR BGR sırası, (x/255 - 0.5) / 0.5
        t[i] = (px[4 * i + 2] / 255 - 0.5) / 0.5;
        t[H * W + i] = (px[4 * i + 1] / 255 - 0.5) / 0.5;
        t[2 * H * W + i] = (px[4 * i] / 255 - 0.5) / 0.5;
      }
      const feeds = { [session.inputNames[0]]: new ort.Tensor('float32', t, [1, 3, H, W]) };
      const out = (await session.run(feeds))[session.outputNames[0]];
      const [, T, C] = out.dims;
      const p = out.data;
      // (a) Ham açgözlü CTC çözümü, (b) A-Z0-9'a kısıtlı çözüm (küçük harf olasılığı büyüğe eklenir,
      // noktalama/boşluk olasılığı "blank" grubuna eklenir)
      let raw = '';
      let prevRaw = 0;
      let text = '';
      let prev = -1;
      const confs = [];
      const pos = []; // her karakterin başladığı zaman adımı (grup boşluğunu bulmak için)
      let runMax = 0;
      for (let ti = 0; ti < T; ti++) {
        const row = ti * C;
        let am = 0;
        for (let c = 1; c < C; c++) if (p[row + c] > p[row + am]) am = c;
        if (am !== 0 && am !== prevRaw && am - 1 < dict.length) raw += dict[am - 1];
        prevRaw = am;
        const g = new Float32Array(WHITELIST.length);
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
          text += WHITELIST[bk];
          pos.push(ti);
          runMax = bv;
        } else if (bk >= 0) runMax = Math.max(runMax, bv);
        prev = bk;
      }
      if (text.length) confs.push(runMax);
      const mean = confs.length ? confs.reduce((a, b) => a + b, 0) / confs.length : 0;
      const min = confs.length ? Math.min(...confs) : 0;
      // Karakter aralığı medyanın 1.5 katını aşıyorsa araya boşluk koy: "24 OZK 112" grupları korunur
      const steps = pos.slice(1).map((v, i) => v - pos[i]);
      const med = steps.length ? [...steps].sort((a, b) => a - b)[Math.floor(steps.length / 2)] : 0;
      let spaced = text[0] || '';
      for (let i = 1; i < text.length; i++) spaced += (med > 0 && steps[i - 1] > med * 1.5 ? ' ' : '') + text[i];
      return { text, spaced, rawText: raw, words: null, conf: mean, minConf: min, charConfs: confs, classes: C, frame: [W, H] };
    },
    async dispose() {
      if (session) await session.release?.();
      session = null;
    },
  };
}

// ------------------------------------------------------------------ son işleme

/**
 * mode='tr': plate.dart extractPlate (konuma göre O->0 vb. düzeltme, il kodu ve biçim denetimi).
 * mode='foreign': kutu varsa foreign_ocr.dart readForeignPlate, yoksa extractRawPlate (düzeltmesiz).
 */
export function postprocess(r, mode) {
  if (mode === 'tr') {
    const m = extractPlateWithFixes(r.text);
    return m ? { plate: m.plate, fixes: m.fixes } : { plate: null, fixes: null };
  }
  if (mode === 'tr-grouped') {
    // Öneri: önce okunan grup boşluklarına göre (il / harf / rakam) dene, olmazsa mobil kural
    const m = extractPlateGrouped(r.spaced ?? r.text) ?? extractPlateWithFixes(r.text);
    return m ? { plate: m.plate, fixes: m.fixes } : { plate: null, fixes: null };
  }
  if (r.words && r.words.length) {
    const f = readForeignPlate(r.words, r.frame?.[0], r.frame?.[1]);
    return { plate: f ? f.plate : null, countryCode: f?.countryCode ?? null };
  }
  return { plate: extractRawPlate(r.text) ?? (foreignAlnum(r.text) || null) };
}

export function textDetectorAvailable() {
  return typeof globalThis.TextDetector !== 'undefined';
}
