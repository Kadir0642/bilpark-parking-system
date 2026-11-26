// mobile/lib/core/foreign_ocr.dart kurallarının JS karşılığı (yalnızca ölçüm denemesi için).
// Kutu: { text, left, top, right, bottom, charHeight? } (piksel, sol-üst başlangıç).

import { cleanPlate, foreignPlateMin, foreignPlateMax } from './plate.js';

const ROW_OVERLAP = 0.5;
const TINY_RATIO = 0.6;
const COUNTRY_RATIO = 0.85;
const COUNTRY_RATIO_KNOWN = 0.9;
const SINGLE_RATIO = 0.8;
const SINGLE_SMALL = 0.7;
const SINGLE_LOW = 0.1;
const STACK_GAP = 0.6;
const STACK_HEIGHT = 0.75;
const GAP_SPLIT = 2.0;
const HEIGHT_TIE = 0.1;
const COUNTRY_REACH = 2.0;
const COUNTRY_MIN_RATIO = 0.25;

export const foreignCountryCodes = new Set([
  'A', 'AL', 'AND', 'AZ', 'B', 'BG', 'BIH', 'BY', 'CH', 'CY', 'CZ', 'D', 'DK', 'E', 'EST', 'F', 'FIN',
  'GB', 'GE', 'GR', 'H', 'HR', 'I', 'IR', 'IRL', 'IRQ', 'IS', 'L', 'LT', 'LV', 'M', 'MD', 'MK', 'MNE',
  'N', 'NL', 'P', 'PL', 'RKS', 'RO', 'RSM', 'RUS', 'S', 'SK', 'SLO', 'SRB', 'SYR', 'TR', 'UA', 'UK', 'V',
]);

export const foreignAlnum = (raw) =>
  cleanPlate(raw).replace(/Ä/g, 'A').replace(/Ö/g, 'O').replace(/Ü/g, 'U').replace(/[^A-Z0-9]/g, '');

const hasLetter = (s) => /[A-Z]/.test(s);
const hasDigit = (s) => /[0-9]/.test(s);
export const isMixedPlate = (p) => hasLetter(p) && hasDigit(p);

const H = (b) => b.bottom - b.top;
const W = (b) => b.right - b.left;
const maxH = (ws) => Math.max(...ws.map((w) => H(w.box)));
const cx = (w) => (w.box.left + w.box.right) / 2;
const cy = (w) => (w.box.top + w.box.bottom) / 2;

function rows(words) {
  const sorted = [...words].sort((a, b) => H(b.box) - H(a.box));
  const rs = [];
  const bands = [];
  for (const w of sorted) {
    let placed = false;
    for (let i = 0; i < rs.length; i++) {
      const [top, bottom] = bands[i];
      const minH = Math.min(H(w.box), bottom - top);
      const overlap = Math.min(w.box.bottom, bottom) - Math.max(w.box.top, top);
      if (minH > 0 && overlap / minH >= ROW_OVERLAP) {
        rs[i].push(w);
        placed = true;
        break;
      }
    }
    if (!placed) {
      rs.push([w]);
      bands.push([w.box.top, w.box.bottom]);
    }
  }
  return rs;
}

function* segments(row) {
  const words = [...row].sort((a, b) => a.box.left - b.box.left);
  const parts = [];
  let seg = [];
  for (const w of words) {
    if (seg.length) {
      const h = Math.max(maxH(seg), H(w.box));
      const gap = w.box.left - Math.max(...seg.map((s) => s.box.right));
      if (gap > h * GAP_SPLIT) {
        parts.push(seg);
        seg = [];
      }
    }
    seg.push(w);
  }
  if (seg.length) parts.push(seg);
  for (const p of parts) {
    const mainH = maxH(p);
    const kept = p.filter((w) => H(w.box) >= mainH * TINY_RATIO);
    if (kept.length) yield kept;
  }
}

function baseline(ws) {
  const n = ws.length;
  const mx = ws.reduce((a, w) => a + cx(w), 0) / n;
  const my = ws.reduce((a, w) => a + cy(w), 0) / n;
  let sxx = 0;
  let sxy = 0;
  for (const w of ws) {
    sxx += (cx(w) - mx) ** 2;
    sxy += (cx(w) - mx) * (cy(w) - my);
  }
  const slope = sxx > 0 ? sxy / sxx : 0;
  return [slope, my - slope * mx];
}

function trueH(w, slope) {
  const ch = w.box.charHeight;
  if (ch != null && ch > 0) return ch;
  const h = H(w.box);
  if (slope === 0) return h;
  const cos = 1 / Math.sqrt(1 + slope * slope);
  const sin = Math.abs(slope) * cos;
  const cos2 = cos * cos - sin * sin;
  if (cos2 <= 0.5) return h;
  return Math.min(h, Math.max(h * 0.3, (h * cos - W(w.box) * sin) / cos2));
}

function stripCountryCode(seg) {
  if (seg.length < 2) return [seg, null];
  const first = seg[0];
  const a = first.alnum;
  if (a.length > 3 || hasDigit(a)) return [seg, null];
  const rest = seg.slice(1);
  const [slope, icept] = baseline(rest);
  const mainH = Math.max(...rest.map((w) => trueH(w, slope)));
  if (mainH <= 0) return [seg, null];
  const ratio = trueH(first, slope) / mainH;
  let strip;
  if (a.length === 1) {
    const below = (cy(first) - (slope * cx(first) + icept)) / mainH;
    strip = ratio < SINGLE_SMALL || (ratio < SINGLE_RATIO && below >= SINGLE_LOW);
  } else {
    strip = ratio < COUNTRY_RATIO;
  }
  return strip ? [rest, a] : [seg, null];
}

function nearbyCountryCode(seg, all) {
  const h = maxH(seg);
  const left = seg[0].box.left;
  const top = Math.min(...seg.map((w) => w.box.top)) - h * 0.25;
  const bottom = Math.max(...seg.map((w) => w.box.bottom)) + h * 0.25;
  let best = null;
  let bestGap = Infinity;
  for (const w of all) {
    if (seg.includes(w) || !foreignCountryCodes.has(w.alnum)) continue;
    const bh = H(w.box);
    if (bh >= h * COUNTRY_RATIO_KNOWN || bh < h * COUNTRY_MIN_RATIO) continue;
    const gap = left - w.box.right;
    if (gap < -h * 0.1 || gap > h * COUNTRY_REACH) continue;
    const c = (w.box.top + w.box.bottom) / 2;
    if (c < top || c > bottom) continue;
    if (gap < bestGap) {
      best = w.alnum;
      bestGap = gap;
    }
  }
  return best;
}

class Cand {
  constructor(words, countryCode) {
    this.words = words;
    this.countryCode = countryCode;
    this.absorbed = false;
  }
  get plate() { return this.words.map((w) => w.alnum).join(''); }
  get left() { return Math.min(...this.words.map((w) => w.box.left)); }
  get right() { return Math.max(...this.words.map((w) => w.box.right)); }
  get top() { return Math.min(...this.words.map((w) => w.box.top)); }
  get bottom() { return Math.max(...this.words.map((w) => w.box.bottom)); }
  get height() { return maxH(this.words); }
}

function stackRows(cands) {
  const merged = [];
  const byTop = [...cands].sort((a, b) => a.top - b.top);
  for (const up of byTop) {
    if (up.absorbed) continue;
    let pick = null;
    for (const down of byTop) {
      if (up === down || down.absorbed || down.top <= up.top) continue;
      const h = Math.max(up.height, down.height);
      if (Math.min(up.height, down.height) / h < STACK_HEIGHT) continue;
      const gap = down.top - up.bottom;
      if (gap < -0.2 * h || gap > STACK_GAP * h) continue;
      const overlap = Math.min(up.right, down.right) - Math.max(up.left, down.left);
      const narrow = Math.min(up.right - up.left, down.right - down.left);
      if (narrow <= 0 || overlap < 0.5 * narrow) continue;
      if (up.plate.length + down.plate.length > foreignPlateMax) continue;
      if (!pick || down.top < pick.top) pick = down;
    }
    if (pick) {
      up.absorbed = true;
      pick.absorbed = true;
      merged.push(new Cand([...up.words, ...pick.words], up.countryCode ?? pick.countryCode));
    }
  }
  cands.push(...merged);
}

function better(a, b) {
  if (a.mixed !== b.mixed) return a.mixed;
  const aLen = a.plate.length >= 4 && a.plate.length <= 10;
  const bLen = b.plate.length >= 4 && b.plate.length <= 10;
  if (aLen !== bLen) return aLen;
  const m = Math.max(a.height, b.height);
  if (Math.abs(a.height - b.height) > m * HEIGHT_TIE) return a.height > b.height;
  return a.centerDistance < b.centerDistance;
}

/** Kelime kutularından yabancı / özel plaka adayını bulur; yoksa null. */
export function readForeignPlate(boxes, frameWidth, frameHeight) {
  const words = [];
  for (const b of boxes) {
    if (H(b) <= 0 || W(b) <= 0) continue;
    const a = foreignAlnum(b.text);
    if (!a) continue;
    words.push({ box: b, alnum: a });
  }
  if (!words.length) return null;
  let fx;
  let fy;
  let halfDiag;
  if (frameWidth > 0 && frameHeight > 0) {
    fx = frameWidth / 2;
    fy = frameHeight / 2;
    halfDiag = Math.sqrt(fx * fx + fy * fy);
  } else {
    const l = Math.min(...words.map((w) => w.box.left));
    const t = Math.min(...words.map((w) => w.box.top));
    const r = Math.max(...words.map((w) => w.box.right));
    const bt = Math.max(...words.map((w) => w.box.bottom));
    fx = (l + r) / 2;
    fy = (t + bt) / 2;
    halfDiag = Math.sqrt((r - l) ** 2 + (bt - t) ** 2) / 2;
  }
  if (halfDiag <= 0) halfDiag = 1;
  const cands = [];
  for (const row of rows(words)) {
    for (const raw of segments(row)) {
      const [seg, stripped] = stripCountryCode(raw);
      cands.push(new Cand(seg, stripped ?? nearbyCountryCode(seg, words)));
    }
  }
  stackRows(cands);
  let best = null;
  for (const k of cands) {
    if (k.absorbed) continue;
    const plate = k.plate;
    if (plate.length < foreignPlateMin || plate.length > foreignPlateMax) continue;
    const dx = (k.left + k.right) / 2 - fx;
    const dy = (k.top + k.bottom) / 2 - fy;
    const c = {
      plate,
      mixed: isMixedPlate(plate),
      height: k.height,
      centerDistance: Math.sqrt(dx * dx + dy * dy) / halfDiag,
      countryCode: k.countryCode,
    };
    if (!best || better(c, best)) best = c;
  }
  return best;
}
