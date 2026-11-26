// Yabancı / özel plaka modunda kamera OCR'ı: ML Kit'in kelime kutularını (metin + konum) kullanarak
// plakayı bulur. Saf Dart: ML Kit tipi içermez, birim testle sınanır. ML Kit uyarlayıcısı
// plate_scanner_screen.dart içindedir (ocrBoxesFrom).
//
// Neden konum? Düz metinde AB ülke kodu (D, NL), mühür / muayene etiketi parçaları ve yandaki ikinci
// plaka aynı satıra karışıyordu. Kutularla: aynı yatay sıradaki kelimeler birleştirilir, büyük boşlukta
// ayrı plakalara bölünür, küçük kutular (mühür, ülke kodu) atılır.
//
// TR yolu (plate.dart extractPlate) bundan etkilenmez.

import 'dart:math' as math;

import 'plate.dart';

/// OCR'ın bulduğu bir kelime ve görüntüdeki kutusu (piksel, sol-üst köşe başlangıç).
/// [charHeight]: döndürülmüş köşe noktalarından ölçülen gerçek yazı yüksekliği (ML Kit verirse).
/// Eğik plakada eksene hizalı kutu geniş kelimelerde yüksekliği şişirir; bu değer şişmez.
/// [symbols]: ML Kit'in karakter kutuları (verirse). Yüksekliği güvenilmez (hepsi satır boyu) ama
/// genişlik ve aralıkları, yan yana iki plakanın tek kelimeye birleştiği yeri (mavi bant) gösterir.
class OcrBox {
  final String text;
  final double left, top, right, bottom;
  final double? charHeight;
  final List<OcrBox> symbols;
  const OcrBox(
    this.text, {
    required this.left,
    required this.top,
    required this.right,
    required this.bottom,
    this.charHeight,
    this.symbols = const [],
  });

  double get width => right - left;
  double get height => bottom - top;

  @override
  String toString() => 'OcrBox($text @ $left,$top,$right,$bottom)';
}

/// Plakaya en çok benzeyen aday ve neden seçildiği (test ve tanı için).
class ForeignPlateRead {
  final String plate;
  final bool mixed; // harf ve rakam birlikte
  final double height; // adaydaki en büyük karakter yüksekliği
  final double centerDistance; // çerçeve merkezine uzaklık (0 = tam ortada, 1 = köşe)
  /// Plakanın solundaki ülke kodu (D, NL, GB ...; 1-3 harf), plakaya katılmaz; bulunamadıysa null.
  /// Şimdilik yalnızca tarama sonucunda taşınır; giriş formu / API alanı sonraki işte gelecek.
  final String? countryCode;
  /// Adayda küçük harf ya da Türkçeye özgü harf (Ç Ş Ğ) var: tabela, manşet, ülke adı yazısı.
  /// Plakalar büyük harftir; böyle aday temiz bir adaya karşı seçilmez.
  final bool dirty;
  const ForeignPlateRead(
    this.plate, {
    required this.mixed,
    required this.height,
    required this.centerDistance,
    this.countryCode,
    this.dirty = false,
  });

  @override
  String toString() =>
      'ForeignPlateRead($plate, cc=$countryCode, mixed=$mixed, dirty=$dirty, h=$height, d=$centerDistance)';
}

// Ayar değerleri (oranlar sıradaki en yüksek kutuya göre)
const _rowOverlap = 0.5; // dikey örtüşme / küçük kutunun yüksekliği bundan büyükse aynı sıra
const _tinyRatio = 0.6; // bundan küçük kutular mühür / etiket / bant yazısı: atılır
const _countryRatio = 0.85; // soldaki 2-3 harf (eğim düzeltmeli) bundan küçükse ülke kodu bandıdır
const _countryRatioKnown = 0.9; // ayrı sıraya düşen bant kodunu ararken üst sınır
const _singleRatio = 0.8; // tek harf için üst sınır; ayrıca yazı çizgisinin altında durmalı
const _singleSmall = 0.7; // tek harf bundan küçükse konuma bakmadan bant kodudur
const _singleLow = 0.1; // tek harfin merkezi yazı çizgisinden bu kadar (yükseklik katı) aşağıdaysa bant
const _stackGap = 0.6; // iki satırlı plaka: satırlar arası en çok bu kadar (yükseklik katı) boşluk
const _stackHeight = 0.75; // iki satırlı plaka: satır yükseklikleri oranı en az bu
const _gapSplit = 2.0; // kelimeler arası boşluk (yükseklik katı) bundan büyükse ayrı plaka
const _heightTie = 0.2; // yükseklik farkı %20'den azsa eşit sayılır, merkeze yakınlık belirler (ızgarada satırlar ±%15 oynar)
const _countryReach = 2.0; // ülke kodu plakanın en fazla bu kadar (yükseklik katı) solunda aranır
const _countryMinRatio = 0.25; // bundan küçük kutu ülke kodu sayılmaz (yıldız, nokta)
const _countryOverlap = 0.8; // ülke kodu plakanın ilk kelimesinin bu kadar (yükseklik katı) içine girebilir
const _markerWide = 1.6; // karakter, kelimedeki ortanca genişliğin bu katından genişse (yanına bant yapışmış)
const _markerGap = 0.45; // ... ve ardından bu kadar (yükseklik katı) boşluk varsa: yan yana iki plakanın sınırı
const _splitTarget = 10; // bundan uzun aday tek plaka değildir (ızgara / yan yana plakalar): en büyük boşluktan bölünür
const _markerBonus = 0.5; // bölme yeri seçerken bant izine eklenen pay (yükseklik katı)
const _junkReach = 0.3; // ülke kodunun sağ kenarından bu kadar (yükseklik katı) içerideki karakter bant artığıdır
const _pairGap = 4.0; // yalnız rakam + yalnız harf iki grup (Suudi Latin satırı) bu boşluğa kadar birleşir
const _pairMaxLen = 4;

/// Sık görülen Avrupa ve komşu ülke kodları (mavi bant ya da oval etiket).
const foreignCountryCodes = {
  'A', 'AL', 'AND', 'AZ', 'B', 'BG', 'BIH', 'BY', 'CH', 'CY', 'CZ', 'D', 'DK', 'E', 'EST', 'F', 'FIN', //
  'GB', 'GE', 'GR', 'H', 'HR', 'I', 'IR', 'IRL', 'IRQ', 'IS', 'L', 'LT', 'LV', 'M', 'MD', 'MK', 'MNE', //
  'N', 'NL', 'P', 'PL', 'RKS', 'RO', 'RSM', 'RUS', 'S', 'SK', 'SLO', 'SRB', 'SYR', 'TR', 'UA', 'UK', 'V',
};

/// Yalnızca A-Z0-9 bırakır: Türkçe i/ı/İ ve küçük harfler büyür, Ä/Ö/Ü harf karşılığına iner.
String foreignAlnum(String raw) => cleanPlate(raw)
    .replaceAll('Ä', 'A')
    .replaceAll('Ö', 'O')
    .replaceAll('Ü', 'U')
    .replaceAll(RegExp(r'[^A-Z0-9]'), '');

bool _hasLetter(String s) => RegExp(r'[A-Z]').hasMatch(s);
bool _hasDigit(String s) => RegExp(r'[0-9]').hasMatch(s);

/// Harf ve rakam birlikte mi? Yabancı modda yalnızca bunlar kendiliğinden onaylanır (düz yazı onaylanmaz).
bool isMixedPlate(String plate) => _hasLetter(plate) && _hasDigit(plate);

/// Kelime içinde iki rakamın arasındaki O / Q -> 0, I -> 1 ("8O11" -> "8011"). Yalnızca iki yanı da
/// rakamsa: Rus "O123OO", Polonya "WO..." gibi harf O'lar bozulmaz.
String fixDigitLookalikes(String alnum) {
  if (alnum.length < 3) return alnum;
  final c = alnum.split('');
  bool d(int i) => i >= 0 && i < c.length && RegExp(r'[0-9]').hasMatch(c[i]);
  for (var i = 1; i < c.length - 1; i++) {
    if (!d(i - 1) || !d(i + 1)) continue;
    if (c[i] == 'O' || c[i] == 'Q') c[i] = '0';
    if (c[i] == 'I') c[i] = '1';
  }
  return c.join();
}

/// Küçük harf ya da Ç Ş Ğ içeren yazı plaka değildir (tabela, manşet, "Austria" gibi ülke adı).
bool _isDirty(String raw) => RegExp(r'[a-zçşğıöüäà-ÿ]|[ÇŞĞ]').hasMatch(raw);

class _Word {
  final OcrBox box;
  final String alnum;
  final bool dirty;
  final bool markerBefore; // bant izinden (yan yana iki plaka) sonra başlıyor
  _Word(this.box, {this.markerBefore = false}) : alnum = fixDigitLookalikes(foreignAlnum(box.text)), dirty = _isDirty(box.text);

  /// Baştaki [k] karakteri (bant artığı) atılmış kelime; karakter kutusu yoksa ya da hepsi gidiyorsa null.
  _Word? dropLeading(int k) {
    final s = box.symbols;
    if (k <= 0) return this;
    if (k >= s.length) return null;
    final rest = s.sublist(k);
    return _Word(
      OcrBox(
        rest.map((x) => x.text).join(),
        left: rest.first.left,
        top: box.top,
        right: box.right,
        bottom: box.bottom,
        charHeight: box.charHeight,
        symbols: rest,
      ),
      markerBefore: markerBefore,
    );
  }
}

/// Kelimeyi bant izlerinden parçalara böler: kelimenin ortanca karakter genişliğinden belirgin geniş bir
/// karakter ve ardından büyük boşluk, ızgarada yan yana iki plakanın birleştiği yerdir ("1-EBY-514CA").
/// Parçalar normalde yine aynı adayda birleşir; yalnızca çok uzun adaylar bu izlerden bölünür.
List<_Word> _explode(OcrBox b) {
  final s = b.symbols;
  final whole = [_Word(b)];
  if (s.length < 3 || s.map((x) => x.text).join() != b.text.replaceAll(' ', '')) return whole;
  final widths = s.map((x) => x.width).toList()..sort();
  final med = widths[widths.length ~/ 2];
  final h = b.charHeight ?? b.height;
  if (med <= 0 || h <= 0) return whole;
  final cuts = <int>[];
  for (var i = 0; i < s.length - 1; i++) {
    if (s[i].width > med * _markerWide && s[i + 1].left - s[i].right > h * _markerGap) cuts.add(i + 1);
  }
  if (cuts.isEmpty) return whole;
  final out = <_Word>[];
  var from = 0;
  for (final cut in [...cuts, s.length]) {
    final part = s.sublist(from, cut);
    out.add(_Word(
      OcrBox(
        part.map((x) => x.text).join(),
        left: part.first.left,
        top: b.top,
        right: part.last.right,
        bottom: b.bottom,
        charHeight: b.charHeight,
        symbols: part,
      ),
      markerBefore: from > 0,
    ));
    from = cut;
  }
  return out;
}

double _maxH(Iterable<_Word> ws) => ws.map((w) => w.box.height).reduce(math.max);

/// Kutuları yatay sıralara ayırır: dikey örtüşmesi küçük kutunun yarısını geçen kelimeler aynı sıradır.
/// Sırayı en büyük kutu kurar; mühür gibi küçük kutular onun bandına düşer.
List<List<_Word>> _rows(List<_Word> words) {
  final sorted = [...words]..sort((a, b) => b.box.height.compareTo(a.box.height));
  final rows = <List<_Word>>[];
  final bands = <(double, double)>[];
  for (final w in sorted) {
    var placed = false;
    for (var i = 0; i < rows.length; i++) {
      final (top, bottom) = bands[i];
      final minH = math.min(w.box.height, bottom - top);
      final overlap = math.min(w.box.bottom, bottom) - math.max(w.box.top, top);
      if (minH > 0 && overlap / minH >= _rowOverlap) {
        rows[i].add(w);
        placed = true;
        break;
      }
    }
    if (!placed) {
      rows.add([w]);
      bands.add((w.box.top, w.box.bottom));
    }
  }
  return rows;
}

/// Bir sıradan plaka adayları: soldan sağa dizilir, büyük boşlukta bölünür (yan yana iki plaka),
/// sonra her parçada ana yazıya göre küçük kalan kutular (mühür, etiket, bant yazısı) atılır.
Iterable<List<_Word>> _segments(List<_Word> row) sync* {
  final words = [...row]..sort((a, b) => a.box.left.compareTo(b.box.left));
  final parts = <List<_Word>>[];
  var seg = <_Word>[];
  for (final w in words) {
    if (seg.isNotEmpty) {
      final h = math.max(_maxH(seg), w.box.height);
      final gap = w.box.left - seg.map((s) => s.box.right).reduce(math.max);
      if (gap > h * _gapSplit) {
        parts.add(seg);
        seg = <_Word>[];
      }
    }
    seg.add(w);
  }
  if (seg.isNotEmpty) parts.add(seg);
  final kept = <List<_Word>>[];
  for (final p in parts) {
    final mainH = _maxH(p);
    final k = p.where((w) => w.box.height >= mainH * _tinyRatio).toList();
    if (k.isNotEmpty) kept.add(k);
  }
  // Suudi Latin satırı "1234   ABC": yalnız rakam ve yalnız harf iki kısa grup, aynı boyda, geniş boşlukla
  for (var i = 0; i < kept.length; i++) {
    if (i + 1 < kept.length && _isPair(kept[i], kept[i + 1])) {
      yield [...kept[i], ...kept[i + 1]];
      i++;
    } else {
      yield kept[i];
    }
  }
}

bool _isPair(List<_Word> a, List<_Word> b) {
  final ta = a.map((w) => w.alnum).join(), tb = b.map((w) => w.alnum).join();
  if (ta.length > _pairMaxLen || tb.length > _pairMaxLen) return false;
  final digitsThenLetters = RegExp(r'^[0-9]+$').hasMatch(ta) && RegExp(r'^[A-Z]+$').hasMatch(tb);
  final lettersThenDigits = RegExp(r'^[A-Z]+$').hasMatch(ta) && RegExp(r'^[0-9]+$').hasMatch(tb);
  if (!digitsThenLetters && !lettersThenDigits) return false;
  final ha = _maxH(a), hb = _maxH(b), h = math.max(ha, hb);
  if (math.min(ha, hb) / h < 0.8) return false;
  final gap = b.map((w) => w.box.left).reduce(math.min) - a.map((w) => w.box.right).reduce(math.max);
  return gap <= h * _pairGap;
}

/// Çok uzun aday (ızgarada yan yana plakalar tek satıra birleşti): en büyük boşluktan (bant izi ek
/// puanla) ikiye bölünür, parçalar [_splitTarget] karaktere inene dek. Tek plakalık karede etkisizdir.
List<List<_Word>> _splitLong(List<_Word> seg) {
  final len = seg.fold<int>(0, (n, w) => n + w.alnum.length);
  if (len <= _splitTarget || seg.length < 2) return [seg];
  final h = _maxH(seg);
  var best = 1;
  var bestScore = double.negativeInfinity;
  for (var i = 1; i < seg.length; i++) {
    final gap = (seg[i].box.left - seg[i - 1].box.right) / h + (seg[i].markerBefore ? _markerBonus : 0);
    if (gap > bestScore) {
      bestScore = gap;
      best = i;
    }
  }
  return [..._splitLong(seg.sublist(0, best)), ..._splitLong(seg.sublist(best))];
}

/// Mavi bant artığı: ülke kodu bulunduysa, plakanın başında kodun sağ kenarına kadar uzanan
/// karakterler (bant yıldızları "E", "L", "-" diye okunur) atılır. Karakter kutusu yoksa yalnızca tek
/// harflik baş kelime atılabilir.
List<_Word> _stripBandJunk(List<_Word> seg, OcrBox? cc) {
  if (cc == null || seg.isEmpty) return seg;
  final out = [...seg];
  final x = cc.right + _maxH(seg) * _junkReach;
  while (out.length > 1 || (out.isNotEmpty && out.first.box.symbols.length > 1)) {
    final w = out.first;
    final s = w.box.symbols;
    if (s.isNotEmpty) {
      final k = s.takeWhile((c) => (c.left + c.right) / 2 < x).length;
      if (k == 0) break;
      final rest = w.dropLeading(k);
      if (rest == null) {
        if (out.length == 1) break;
        out.removeAt(0);
        continue;
      }
      out[0] = rest;
      break;
    }
    if (w.alnum.length == 1 && _cx(w) < x && out.length > 1) {
      out.removeAt(0);
      continue;
    }
    break;
  }
  return out.where((w) => w.alnum.isNotEmpty).toList();
}

double _cx(_Word w) => (w.box.left + w.box.right) / 2;
double _cy(_Word w) => (w.box.top + w.box.bottom) / 2;

/// Kelime merkezlerinden yazı çizgisi (en küçük kareler): (eğim, kesişim). Tek kelimede eğim 0.
(double, double) _baseline(List<_Word> ws) {
  final n = ws.length;
  final mx = ws.map(_cx).reduce((a, b) => a + b) / n;
  final my = ws.map(_cy).reduce((a, b) => a + b) / n;
  var sxx = 0.0, sxy = 0.0;
  for (final w in ws) {
    sxx += (_cx(w) - mx) * (_cx(w) - mx);
    sxy += (_cx(w) - mx) * (_cy(w) - my);
  }
  final slope = sxx > 0 ? sxy / sxx : 0.0;
  return (slope, my - slope * mx);
}

/// Eğimden arındırılmış yazı yüksekliği. Köşe noktası yüksekliği varsa o kullanılır; yoksa eksene
/// hizalı kutudan geri hesaplanır: h = W sin + H cos, w = W cos + H sin  =>  H = (h cos - w sin) / cos2θ.
double _trueH(_Word w, double slope) {
  final ch = w.box.charHeight;
  if (ch != null && ch > 0) return ch;
  final h = w.box.height;
  if (slope == 0) return h;
  final cos = 1 / math.sqrt(1 + slope * slope), sin = slope.abs() * cos;
  final cos2 = cos * cos - sin * sin;
  if (cos2 <= 0.5) return h; // 30 dereceden fazla eğim: güvenilmez, düzeltme yapılmaz
  return ((h * cos - w.box.width * sin) / cos2).clamp(h * 0.3, h);
}

/// Soldaki ülke kodunu (1-3 harflik kelime, ana yazıdan belirgin küçük) soyar; soyulanı da döner.
/// Yükseklikler eğimden arındırılır (eğik plakada geniş kelimenin kutusu şişer). Tek harfte ek kanıt
/// aranır: çok küçük olmalı ya da yazı çizgisinin altında (mavi bantta yıldızların altında) durmalı.
/// Böylece Düsseldorf "D AB 123", München "M AB 1234", "B MW 123" gibi tam boy ilk harf soyulmaz.
(List<_Word>, _Word?) _stripCountryCode(List<_Word> seg) {
  if (seg.length < 2) return (seg, null);
  final first = seg.first;
  final a = first.alnum;
  if (a.length > 3 || _hasDigit(a)) return (seg, null);
  final rest = seg.sublist(1);
  final (slope, icept) = _baseline(rest);
  final mainH = rest.map((w) => _trueH(w, slope)).reduce(math.max);
  if (mainH <= 0) return (seg, null);
  final ratio = _trueH(first, slope) / mainH;
  final bool strip;
  if (a.length == 1) {
    final below = (_cy(first) - (slope * _cx(first) + icept)) / mainH;
    strip = ratio < _singleSmall || (ratio < _singleRatio && below >= _singleLow);
  } else {
    strip = ratio < _countryRatio;
  }
  return strip ? (rest, first) : (seg, null);
}

/// Ayrı sıraya düşmüş ya da küçük diye atılmış bant kodu: plakanın hemen solunda, dikeyde plaka
/// hizasında, ana yazıdan küçük, bilinen ülke kodu olan en yakın kelime.
_Word? _nearbyCountryCode(List<_Word> seg, List<_Word> all) {
  final h = _maxH(seg);
  final left = seg.first.box.left;
  final top = seg.map((w) => w.box.top).reduce(math.min) - h * 0.25;
  final bottom = seg.map((w) => w.box.bottom).reduce(math.max) + h * 0.25;
  _Word? best;
  var bestGap = double.infinity;
  for (final w in all) {
    if (seg.contains(w) || !foreignCountryCodes.contains(w.alnum)) continue;
    final bh = w.box.height;
    if (bh >= h * _countryRatioKnown || bh < h * _countryMinRatio) continue;
    final gap = left - w.box.right;
    // Bant, plakanın ilk kelimesiyle örtüşebilir (bant yıldızları kelimeye harf olarak katılmışsa): -0.8h
    if (gap < -h * _countryOverlap || gap > h * _countryReach) continue;
    final cy = (w.box.top + w.box.bottom) / 2;
    if (cy < top || cy > bottom) continue;
    if (gap.abs() < bestGap) {
      best = w;
      bestGap = gap.abs();
    }
  }
  return best;
}

/// Kelime kutularından yabancı / özel plaka adayını bulur; yoksa null.
/// [frameWidth]/[frameHeight] (kutularla aynı, dik tutuş koordinatı) verilmezse merkez, tüm kutuları
/// saran alanın ortası sayılır.
/// Sıralama: harf+rakam karışık > 4-10 karakter > daha büyük yazı > çerçeve merkezine yakın.
ForeignPlateRead? readForeignPlate(List<OcrBox> boxes, {double? frameWidth, double? frameHeight}) {
  final words = <_Word>[];
  for (final b in boxes) {
    if (b.height <= 0 || b.width <= 0) continue;
    for (final w in _explode(b)) {
      if (w.alnum.isNotEmpty) words.add(w); // yalnızca nokta, tire, mühür sembolü atlanır
    }
  }
  if (words.isEmpty) return null;

  double cx, cy, halfDiag;
  if (frameWidth != null && frameHeight != null && frameWidth > 0 && frameHeight > 0) {
    cx = frameWidth / 2;
    cy = frameHeight / 2;
    halfDiag = math.sqrt(cx * cx + cy * cy);
  } else {
    final l = words.map((w) => w.box.left).reduce(math.min);
    final t = words.map((w) => w.box.top).reduce(math.min);
    final r = words.map((w) => w.box.right).reduce(math.max);
    final bt = words.map((w) => w.box.bottom).reduce(math.max);
    cx = (l + r) / 2;
    cy = (t + bt) / 2;
    halfDiag = math.sqrt((r - l) * (r - l) + (bt - t) * (bt - t)) / 2;
  }
  if (halfDiag <= 0) halfDiag = 1;

  final cands = <_Cand>[];
  for (final row in _rows(words)) {
    for (final whole in _segments(row)) {
      for (final raw in _splitLong(whole)) {
        final (seg, stripped) = _stripCountryCode(raw);
        final cc = stripped ?? _nearbyCountryCode(seg, words);
        final clean = _stripBandJunk(seg, cc?.box);
        if (clean.isEmpty) continue;
        cands.add(_Cand(clean, cc?.alnum));
      }
    }
  }
  _stackRows(cands);

  ForeignPlateRead? best;
  for (final k in cands) {
    if (k.absorbed) continue;
    final plate = k.plate;
    if (plate.length < foreignPlateMin || plate.length > foreignPlateMax) continue;
    final dx = (k.left + k.right) / 2 - cx, dy = (k.top + k.bottom) / 2 - cy;
    final c = ForeignPlateRead(
      plate,
      mixed: isMixedPlate(plate),
      height: k.height,
      centerDistance: math.sqrt(dx * dx + dy * dy) / halfDiag,
      countryCode: k.countryCode,
      dirty: k.words.any((w) => w.dirty),
    );
    if (best == null || _better(c, best)) best = c;
  }
  return best;
}

/// Bir sıra parçası (ya da iki satırlı plakada üst + alt satır) adayı.
class _Cand {
  final List<_Word> words;
  final String? countryCode;
  bool absorbed = false; // iki satırlı plakaya katıldı: tek başına aday değil
  _Cand(this.words, this.countryCode);

  String get plate => words.map((w) => w.alnum).join();
  double get left => words.map((w) => w.box.left).reduce(math.min);
  double get right => words.map((w) => w.box.right).reduce(math.max);
  double get top => words.map((w) => w.box.top).reduce(math.min);
  double get bottom => words.map((w) => w.box.bottom).reduce(math.max);
  double get height => _maxH(words);
}

/// İki satırlı (kare) plaka: hemen üst üste duran, benzer boyda ve yatayda örtüşen iki satır tek
/// aday olur (üst + alt). Yoksa alt satır "AB 1234" yarım plaka olarak onaylanırdı.
void _stackRows(List<_Cand> cands) {
  final merged = <_Cand>[];
  final byTop = [...cands]..sort((a, b) => a.top.compareTo(b.top));
  for (final up in byTop) {
    if (up.absorbed) continue;
    _Cand? pick;
    for (final down in byTop) {
      if (identical(up, down) || down.absorbed || down.top <= up.top) continue;
      final h = math.max(up.height, down.height);
      if (math.min(up.height, down.height) / h < _stackHeight) continue;
      final gap = down.top - up.bottom;
      if (gap < -0.2 * h || gap > _stackGap * h) continue;
      final overlap = math.min(up.right, down.right) - math.max(up.left, down.left);
      final narrow = math.min(up.right - up.left, down.right - down.left);
      if (narrow <= 0 || overlap < 0.5 * narrow) continue;
      if (up.plate.length + down.plate.length > foreignPlateMax) continue;
      if (pick == null || down.top < pick.top) pick = down;
    }
    if (pick != null) {
      up.absorbed = true;
      pick.absorbed = true;
      merged.add(_Cand([...up.words, ...pick.words], up.countryCode ?? pick.countryCode));
    }
  }
  cands.addAll(merged);
}

/// [readForeignPlate] sonucunun yalnızca plaka metni.
String? extractForeignPlate(List<OcrBox> boxes, {double? frameWidth, double? frameHeight}) =>
    readForeignPlate(boxes, frameWidth: frameWidth, frameHeight: frameHeight)?.plate;

bool _better(ForeignPlateRead a, ForeignPlateRead b) {
  final aGood = a.mixed && !a.dirty, bGood = b.mixed && !b.dirty;
  if (aGood != bGood) return aGood;
  if (a.mixed != b.mixed) return a.mixed;
  final aLen = a.plate.length >= 4 && a.plate.length <= 10;
  final bLen = b.plate.length >= 4 && b.plate.length <= 10;
  if (aLen != bLen) return aLen;
  final maxH = math.max(a.height, b.height);
  if ((a.height - b.height).abs() > maxH * _heightTie) return a.height > b.height;
  return a.centerDistance < b.centerDistance;
}

/// Yabancı modda onay: son [window] karenin [needed] tanesinde aynı plaka okunursa onaylar.
/// Eski kural (üst üste 3 kare) gerçek ML Kit çıktısında tutmuyordu: bir boş kare ya da bant / mühür
/// kaynaklı tek harflik oynama sayacı sıfırlıyordu. Burada boş kare sayacı sıfırlamaz, O/0 ve I/1
/// aynı sayılır; onaylanan yazım, oylar içinde en sık okunan (eşitlikte en son) yazımdır.
class ForeignVotes {
  final int window;
  final int needed;
  final _hist = <String?>[];
  ForeignVotes({this.window = 6, this.needed = 3});

  static String _key(String p) => p.replaceAll('O', '0').replaceAll('Q', '0').replaceAll('I', '1');

  /// Karedeki aday (onaya uygun değilse null) eklenir; onaylanan plakayı ya da null döner.
  String? add(String? plate) {
    _hist.add(plate);
    if (_hist.length > window) _hist.removeAt(0);
    if (plate == null) return null;
    final k = _key(plate);
    final same = _hist.whereType<String>().where((p) => _key(p) == k).toList();
    if (same.length < needed) return null;
    var best = same.last;
    var bestN = 0;
    for (final p in same.reversed) {
      final n = same.where((q) => q == p).length;
      if (n > bestN) {
        best = p;
        bestN = n;
      }
    }
    return best;
  }

  /// [plate] için penceredeki oy sayısı (ekrandaki tanı satırı için).
  int votesFor(String? plate) {
    if (plate == null) return 0;
    final k = _key(plate);
    return _hist.whereType<String>().where((p) => _key(p) == k).length;
  }
}
