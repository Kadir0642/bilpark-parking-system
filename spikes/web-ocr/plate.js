// mobile/lib/core/plate.dart kurallarının JS karşılığı (yalnızca ölçüm denemesi için).
// Kaynak değişirse burası da güncellenmeli; ürün koduna geçerse tek kaynak yine Dart/Java tarafıdır.

export const plateExample = '34 ABC 123';

// İl kodu 01-81, ardından: 1 harf + 4-5 rakam | 2 harf + 3-4 rakam | 3 harf + 2-3 rakam
const TR_PLATE = /^(0[1-9]|[1-7][0-9]|8[01])([A-Z][0-9]{4,5}|[A-Z]{2}[0-9]{3,4}|[A-Z]{3}[0-9]{2,3})$/;

/** Boşluk ve tireleri atar, Türkçe i/ı/İ harflerini I yapar, büyütür. Doğrulamaz. */
export function cleanPlate(raw) {
  return raw.replace(/[\s-]+/g, '').replace(/İ/g, 'I').replace(/ı/g, 'I').replace(/i/g, 'I').toUpperCase();
}

export const isValidTrPlate = (raw) => TR_PLATE.test(cleanPlate(raw));

// Konuma göre OCR karışıklıkları
const TO_DIGIT = { O: '0', Q: '0', D: '0', I: '1', L: '1', T: '1', Z: '2', S: '5', G: '6', B: '8' };
const TO_LETTER = { 0: 'O', 1: 'I', 2: 'Z', 5: 'S', 8: 'B', 6: 'G' };
const isDigit = (ch) => ch >= '0' && ch <= '9';
const isLetter = (ch) => ch >= 'A' && ch <= 'Z';

// (harf sayısı, rakam sayısı); il kodu ayrıca 2 rakamdır
const SHAPES = [[1, 4], [1, 5], [2, 3], [2, 4], [3, 2], [3, 3]];
const MAX_FIXES = 2;

function tryShape(s, [l, d]) {
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
 * Dart sürümüyle aynı seçim: önce en az düzeltme, sonra en uzun aday. Ölçüm için düzeltme sayısını da döner.
 */
export function extractPlateWithFixes(ocrText) {
  const tokens = ocrText
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

/**
 * ÖNERİ (mobilde yok): OCR grup boşluklarını koruyorsa ("24 OZK 112") önce sondan üç grubu
 * il (2) / harf / rakam olarak dener. "24OZK112" bitişik okununca mobil kural O'yu 0 sayıp
 * "40ZK112" (düzeltmesiz, geçerli) seçiyordu; grup bilgisi bu belirsizliği çözer.
 */
export function extractPlateGrouped(spacedText) {
  const tokens = spacedText.toUpperCase().split(/[^A-Z0-9]+/).filter((t) => t.length > 0);
  for (let end = tokens.length; end >= 3; end--) {
    const [a, b, c] = tokens.slice(end - 3, end);
    if (a.length !== 2) continue;
    const shape = SHAPES.find(([l, d]) => l === b.length && d === c.length);
    if (!shape) continue;
    const m = tryShape(a + b + c, shape);
    if (m && m.fixes <= MAX_FIXES) return m;
  }
  return null;
}

export const extractPlate = (ocrText) => extractPlateWithFixes(ocrText)?.plate ?? null;

// --- Yabancı / özel plaka ---
export const foreignPlateMin = 2;
export const foreignPlateMax = 12;
const FOREIGN_PLATE = new RegExp(`^[A-Z0-9]{${foreignPlateMin},${foreignPlateMax}}$`);
export const isValidForeignPlateInput = (raw) => FOREIGN_PLATE.test(cleanPlate(raw));

/** Yabancı modda düzeltmesiz satır seçimi (Dart extractRawPlate). */
export function extractRawPlate(ocrText) {
  let best = null;
  let bestScore = -1;
  for (const line of ocrText.split(/[\r\n]+/)) {
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
