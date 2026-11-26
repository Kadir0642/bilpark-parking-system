// Ölçüm koşusu: yerel sunucu + başsız Chrome (puppeteer-core, sistemdeki Chrome) ile harness.html'i
// her motor/ön işleme için çalıştırır, sonuçları results/ altına yazar ve özet tabloyu basar.
// Kullanım: node run_measure.mjs [--limit N] [--only tesseract:band,paddle-en:band] [--cdn]
// Ağ: varsayılan local mod (node_modules); --cdn verilirse kütüphaneler jsDelivr'den gelir.
import { mkdir, writeFile, readFile, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { brotliCompressSync, gzipSync, constants } from 'node:zlib';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';
import { startServer } from './serve.mjs';

const ROOT = dirname(fileURLToPath(import.meta.url));
const arg = (k, d) => {
  const i = process.argv.indexOf(k);
  return i > 0 ? process.argv[i + 1] : d;
};
const limit = Number(arg('--limit', 100000));
const only = arg('--only', 'tesseract:band,tesseract:none,paddle-en:band,paddle-en:none,paddle-ch:band');
const local = !process.argv.includes('--cdn');
const PORT = Number(arg('--port', 8765));
const dump = process.argv.includes('--dump');
const skipSizes = process.argv.includes('--no-sizes');

const CHROME = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].find((p) => p && existsSync(p));

// ------------------------------------------------------------------ metrikler

function lev(a, b) {
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
  for (let j = 1; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++)
    for (let j = 1; j <= b.length; j++)
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  return d[a.length][b.length];
}
const alnum = (s) => (s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const pct = (x) => (100 * x).toFixed(1);
const quant = (arr, p) => {
  const s = [...arr].sort((a, b) => a - b);
  return s.length ? s[Math.min(s.length - 1, Math.floor(p * (s.length - 1)))] : NaN;
};

function metrics(run) {
  const R = run.results;
  const pred = (r) => (r.kind === 'TR' ? r.plateTR : r.plateForeign);
  const ok = (r) => pred(r) === r.truth;
  const charAcc = (r) => {
    const p = pred(r) || alnum(r.text);
    return Math.max(0, 1 - lev(p, r.truth) / r.truth.length);
  };
  const rawCharAcc = (r) => Math.max(0, 1 - lev(alnum(r.text), r.truth) / r.truth.length);
  const group = (key) => {
    const g = {};
    for (const r of R) (g[r[key]] ||= []).push(r);
    return Object.fromEntries(Object.entries(g).map(([k, v]) => [k, { n: v.length, exact: v.filter(ok).length / v.length }]));
  };
  const tr = R.filter((r) => r.kind === 'TR');
  const eu = R.filter((r) => r.kind !== 'TR');
  // Güven eşiği: UI "Plakanız X mi?" yalnızca geçerli TR plakası ve güven >= t ise sorar
  const confOf = (r) => r.minConf ?? r.conf ?? 0;
  const thresholds = [0, 0.5, 0.7, 0.8, 0.9, 0.95].map((t) => {
    const shown = tr.filter((r) => r.plateTR && confOf(r) >= t);
    const wrong = shown.filter((r) => r.plateTR !== r.truth);
    return { t, shown: shown.length / tr.length, precision: shown.length ? 1 - wrong.length / shown.length : NaN, wrongShown: wrong.length / tr.length, wrongN: wrong.length };
  });
  const confident = 0.9;
  const wrongConfident = tr.filter((r) => r.plateTR && r.plateTR !== r.truth && confOf(r) >= confident);
  return {
    engine: run.engine,
    pre: run.pre,
    n: R.length,
    exact: R.filter(ok).length / R.length,
    exactTR: tr.filter(ok).length / tr.length,
    exactTRGrouped: tr.filter((r) => r.plateTRGrouped === r.truth).length / tr.length,
    trValidWrongGrouped: tr.filter((r) => r.plateTRGrouped && r.plateTRGrouped !== r.truth).length / tr.length,
    exactEU: eu.length ? eu.filter(ok).length / eu.length : NaN,
    charAcc: R.reduce((a, r) => a + charAcc(r), 0) / R.length,
    rawCharAcc: R.reduce((a, r) => a + rawCharAcc(r), 0) / R.length,
    trNoPlate: tr.filter((r) => !r.plateTR).length / tr.length,
    trValidWrong: tr.filter((r) => r.plateTR && r.plateTR !== r.truth).length / tr.length,
    euFalseTR: eu.length ? eu.filter((r) => r.plateTR).length / eu.length : NaN,
    wrongConfident: wrongConfident.length,
    wrongConfidentExamples: wrongConfident.slice(0, 8).map((r) => `${r.truth}->${r.plateTR} (${confOf(r).toFixed(2)}, ${r.category})`),
    thresholds,
    byCategory: group('category'),
    byKind: group('kind'),
    msOcrMedian: quant(R.map((r) => r.msOcr), 0.5),
    msOcrP95: quant(R.map((r) => r.msOcr), 0.95),
    msPreMedian: quant(R.map((r) => r.msPre), 0.5),
    initMs: run.initMs,
  };
}

// ------------------------------------------------------------------ indirme boyutları

async function sizeOf(rel) {
  const buf = await readFile(join(ROOT, 'node_modules', rel));
  return {
    file: rel,
    raw: buf.length,
    gzip: gzipSync(buf, { level: 9 }).length,
    br: brotliCompressSync(buf, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).length,
  };
}

async function sizes() {
  const sets = {
    tesseract: [
      'tesseract.js/dist/tesseract.esm.min.js',
      'tesseract.js/dist/worker.min.js',
      'tesseract.js-core/tesseract-core-simd-lstm.wasm.js',
      '@tesseract.js-data/eng/4.0.0_best_int/eng.traineddata.gz',
    ],
    'paddle-en': [
      'onnxruntime-web/dist/ort.wasm.min.mjs',
      'onnxruntime-web/dist/ort-wasm-simd-threaded.mjs',
      'onnxruntime-web/dist/ort-wasm-simd-threaded.wasm',
      'paddle-ocr-onnx-models/models/en_PP-OCRv4_rec.onnx',
    ],
    'paddle-ch': [
      'onnxruntime-web/dist/ort.wasm.min.mjs',
      'onnxruntime-web/dist/ort-wasm-simd-threaded.mjs',
      'onnxruntime-web/dist/ort-wasm-simd-threaded.wasm',
      '@gutenye/ocr-models/assets/ch_PP-OCRv4_rec_infer.onnx',
      '@gutenye/ocr-models/assets/ppocr_keys_v1.txt',
    ],
  };
  const out = {};
  for (const [k, files] of Object.entries(sets)) {
    const list = [];
    for (const f of files) list.push(await sizeOf(f));
    out[k] = { files: list, raw: list.reduce((a, x) => a + x.raw, 0), gzip: list.reduce((a, x) => a + x.gzip, 0), br: list.reduce((a, x) => a + x.br, 0) };
  }
  return out;
}

// ------------------------------------------------------------------ koşu

const mb = (b) => (b / 1048576).toFixed(1);
await mkdir(join(ROOT, 'results'), { recursive: true });
if (!existsSync(join(ROOT, 'testset', 'labels.json'))) {
  console.error('testset yok: önce  python gen_plates.py --out testset --n 200');
  process.exit(1);
}
const server = await startServer(PORT);
const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
const all = [];
try {
  for (const spec of only.split(',')) {
    const [engine, pre] = spec.split(':');
    const page = await browser.newPage();
    page.on('pageerror', (e) => console.error('pageerror', e.message));
    const url = `http://127.0.0.1:${PORT}/harness.html?engine=${engine}&pre=${pre}&limit=${limit}${local ? '&local=1' : ''}${dump ? '&dump=1' : ''}`;
    const t = Date.now();
    await page.goto(url);
    await page.waitForFunction('window.__done === true', { timeout: 60 * 60 * 1000, polling: 1000 });
    const err = await page.evaluate('window.__error || null');
    if (err) throw new Error(`${spec}: ${err}`);
    const run = await page.evaluate('window.__results');
    await page.close();
    if (dump) {
      await mkdir(join(ROOT, 'results', 'crops'), { recursive: true });
      for (const r of run.results) {
        if (r.crop) await writeFile(join(ROOT, 'results', 'crops', `${engine}_${pre}_${r.file.replace('.jpg', '.png')}`), Buffer.from(r.crop.split(',')[1], 'base64'));
        delete r.crop;
      }
    }
    await writeFile(join(ROOT, 'results', `${engine}_${pre}.json`), JSON.stringify(run, null, 1));
    const m = metrics(run);
    m.textDetector = run.textDetector;
    m.ua = run.ua;
    m.hw = run.hw;
    all.push(m);
    console.log(`${spec}: tam=${pct(m.exact)}% (TR ${pct(m.exactTR)}%, AB ${pct(m.exactEU)}%) TR+grup=${pct(m.exactTRGrouped)}% karakter=${pct(m.charAcc)}% medyan=${m.msOcrMedian.toFixed(0)}ms p95=${m.msOcrP95.toFixed(0)}ms yanlış+güvenli=${m.wrongConfident} (${((Date.now() - t) / 1000).toFixed(0)} s)`);
  }
} finally {
  await browser.close();
  server.close();
}
const sz = skipSizes ? {} : await sizes();
for (const [k, v] of Object.entries(sz)) console.log(`boyut ${k}: ham ${mb(v.raw)} MB, gzip ${mb(v.gzip)} MB, brotli ${mb(v.br)} MB`);
await writeFile(join(ROOT, 'results', 'summary.json'), JSON.stringify({ when: new Date().toISOString(), local, metrics: all, sizes: sz }, null, 1));
console.log('results/summary.json yazıldı');
