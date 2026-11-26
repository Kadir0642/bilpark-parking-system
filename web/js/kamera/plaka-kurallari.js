// Plaka kuralları: mobile/lib/core/plate.dart ve foreign_ocr.dart'taki kuralların JS karşılığı.
// Tek kaynak Dart/Java tarafıdır; orada değişen kural burada da güncellenir (testler: web/test/kurallar.test.mjs).
// Yalnızca kamera okumasında kullanılır; klavye girişini sunucu doğrular (PlateNormalizer.normalizeAny).

// İl kodu 01-81, ardından: 1 harf + 4-5 rakam | 2 harf + 3-4 rakam | 3 harf + 2-3 rakam
const TR_PLATE = /^(0[1-9]|[1-7][0-9]|8[01])([A-Z][0-9]{4,5}|[A-Z]{2}[0-9]{3,4}|[A-Z]{3}[0-9]{2,3})$/;

export const foreignPlateMin = 2;
export const foreignPlateMax = 12;
const FOREIGN_PLATE = new RegExp('^[A-Z0-9]{' + foreignPlateMin + ',' + foreignPlateMax + '}$');

/** Boşluk ve tireleri atar, Türkçe i/ı/İ harflerini I yapar, büyütür. Doğrulamaz. */
export function cleanPlate(raw) {
  return String(raw || '').replace(/[\s-]+/g, '').replace(/İ/g, 'I').replace(/ı/g, 'I').replace(/i/g, 'I').toUpperCase();
}

export const isValidTrPlate = (raw) => TR_PLATE.test(cleanPlate(raw));
export const isValidForeignPlate = (raw) => FOREIGN_PLATE.test(cleanPlate(raw));

/** Yabancı plakada yalnızca A-Z0-9 kalır (Ä/Ö/Ü sadeleşir). */
export const foreignAlnum = (raw) =>
  cleanPlate(raw).replace(/Ä/g, 'A').replace(/Ö/g, 'O').replace(/Ü/g, 'U').replace(/[^A-Z0-9]/g, '');

// Konuma göre OCR karışıklıkları
const TO_DIGIT = { O: '0', Q: '0', D: '0', I: '1', L: '1', T: '1', Z: '2', S: '5', G: '6', B: '8' };
const TO_LETTER = { 0: 'O', 1: 'I', 2: 'Z', 5: 'S', 8: 'B', 6: 'G' };
const isDigit = (ch) => ch >= '0' && ch <= '9';
const isLetter = (ch) => ch >= 'A' && ch <= 'Z';

// (harf sayısı, rakam sayısı); il kodu ayrıca 2 rakamdır
const SHAPES = [[1, 4], [1, 5], [2, 3], [2, 4], [3, 2], [3, 3]];
const MAX_FIXES = 2;

function tryShape(s, shape) {
  const l = shape[0];
  const d = shape[1];
  if (s.length !== 2 + l + d) return null;
  let out = '';
  let fixes = 0;
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    const wantDigit = i < 2 || i >= 2 + l;
    if (wantDigit) {
      if (isDigit(ch)) out += ch;
      else {
        const f = TO_DIGIT[ch];
        if (f === undefined) return null;
        out += f;
        fixes++;
      }
    } else if (isLetter(ch)) out += ch;
    else {
      const f = TO_LETTER[ch];
      if (f === undefined) return null;
      out += f;
      fixes++;
    }
  }
  if (!TR_PLATE.test(out)) return null;
  return { plate: out, fixes };
}

/**
 * Gürültülü OCR metninden en iyi geçerli TR plakasını bulur (yoksa null).
 * Dart extractPlate ile aynı seçim: önce en az düzeltme, sonra en uzun aday.
 */
export function extractPlateWithFixes(ocrText) {
  const tokens = String(ocrText || '')
    .replace(/İ/g, 'I').replace(/ı/g, 'I').replace(/i/g, 'I')
    .toUpperCase()
    .split(/[^A-Z0-9]+/)
    .filter((t) => t.length > 0);
  if (tokens.length === 0) return null;
  let best = null;
  for (let start = 0; start < tokens.length; start++) {
    let joined = '';
    for (let end = start; end < tokens.length && end < start + 4; end++) {
      joined += tokens[end];
      for (let from = 0; from < joined.length; from++) {
        for (const shape of SHAPES) {
          const len = 2 + shape[0] + shape[1];
          if (from + len > joined.length) continue;
          const c = tryShape(joined.substring(from, from + len), shape);
          if (!c || c.fixes > MAX_FIXES) continue;
          if (!best || c.fixes < best.fixes || (c.fixes === best.fixes && c.plate.length > best.plate.length)) best = c;
        }
      }
    }
  }
  return best;
}

export const extractPlate = (ocrText) => {
  const m = extractPlateWithFixes(ocrText);
  return m ? m.plate : null;
};

/** Yabancı modda düzeltmesiz satır seçimi (Dart extractRawPlate). */
export function extractRawPlate(ocrText) {
  let best = null;
  let bestScore = -1;
  for (const line of String(ocrText || '').split(/[\r\n]+/)) {
    const p = cleanPlate(line).replace(/[^A-Z0-9]/g, '');
    if (p.length < foreignPlateMin || p.length > foreignPlateMax) continue;
    const mixed = /[A-Z]/.test(p) && /[0-9]/.test(p);
    const score = (mixed ? 100 : 0) + p.length;
    if (score > bestScore) {
      best = p;
      bestScore = score;
    }
  }
  return best;
}

/** 34ABC123 -> "34 ABC 123" (yabancı plaka olduğu gibi kalır). */
export function formatPlate(p, foreign) {
  const s = cleanPlate(p);
  if (foreign) return s;
  const m = s.match(/^(\d{2})([A-Z]{1,3})(\d{2,5})$/);
  return m ? m[1] + ' ' + m[2] + ' ' + m[3] : s;
}

export const CONFIDENCE_THRESHOLD = 0.8;

/**
 * OCR sonucundan sayfanın vereceği kararı üretir.
 * r: { text, minConf }. foreign: yabancı plaka modu (TR kuralı ve düzeltmeler kapalı).
 * Dönüş: { kind: 'confirm' | 'edit' | 'none', plate, guess }
 *  - confirm: geçerli plaka ve en düşük karakter güveni >= eşik → "Plakanız X mi?"
 *  - edit: bir tahmin var ama güven düşük ya da biçim geçersiz → klavye alanı tahminle açılır
 *  - none: okunacak bir şey yok → klavye alanı boş açılır
 */
export function decide(r, foreign) {
  const text = (r && r.text) || '';
  const conf = r && typeof r.minConf === 'number' ? r.minConf : 0;
  if (foreign) {
    const p = extractRawPlate(text) || foreignAlnum(text).slice(0, foreignPlateMax);
    if (p.length < foreignPlateMin) return { kind: 'none', plate: null, guess: '' };
    if (isValidForeignPlate(p) && conf >= CONFIDENCE_THRESHOLD) return { kind: 'confirm', plate: p, guess: p };
    return { kind: 'edit', plate: null, guess: p };
  }
  const m = extractPlateWithFixes(text);
  if (m && conf >= CONFIDENCE_THRESHOLD) return { kind: 'confirm', plate: m.plate, guess: m.plate };
  const guess = m ? m.plate : foreignAlnum(text).slice(0, foreignPlateMax);
  // Tek karakterlik gürültü tahmin sayılmaz
  if (guess.length < foreignPlateMin) return { kind: 'none', plate: null, guess: '' };
  return { kind: 'edit', plate: null, guess };
}
