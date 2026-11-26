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
class OcrBox {
  final String text;
  final double left, top, right, bottom;
  const OcrBox(this.text, {required this.left, required this.top, required this.right, required this.bottom});

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
  const ForeignPlateRead(
    this.plate, {
    required this.mixed,
    required this.height,
    required this.centerDistance,
    this.countryCode,
  });

  @override
  String toString() => 'ForeignPlateRead($plate, cc=$countryCode, mixed=$mixed, h=$height, d=$centerDistance)';
}

// Ayar değerleri (oranlar sıradaki en yüksek kutuya göre)
const _rowOverlap = 0.5; // dikey örtüşme / küçük kutunun yüksekliği bundan büyükse aynı sıra
const _tinyRatio = 0.6; // bundan küçük kutular mühür / etiket / bant yazısı: atılır
const _countryRatio = 0.85; // soldaki 1-3 harf bundan küçükse ülke kodu bandıdır: soyulur
const _countryRatioKnown = 0.9; // bilinen ülke kodlarında biraz daha esnek
const _gapSplit = 2.0; // kelimeler arası boşluk (yükseklik katı) bundan büyükse ayrı plaka
const _heightTie = 0.1; // yükseklik farkı %10'dan azsa eşit sayılır, merkeze yakınlık belirler
const _countryReach = 2.0; // ülke kodu plakanın en fazla bu kadar (yükseklik katı) solunda aranır
const _countryMinRatio = 0.25; // bundan küçük kutu ülke kodu sayılmaz (yıldız, nokta)

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

class _Word {
  final OcrBox box;
  final String alnum;
  const _Word(this.box, this.alnum);
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
  for (final p in parts) {
    final mainH = _maxH(p);
    final kept = p.where((w) => w.box.height >= mainH * _tinyRatio).toList();
    if (kept.isNotEmpty) yield kept;
  }
}

/// Soldaki ülke kodunu (tek 1-3 harflik kelime, ana yazıdan belirgin küçük) soyar; soyulanı da döner.
/// Boyuta bakılır: Düsseldorf "D AB 123" gibi tam boy ilk harf plakanın parçasıdır, soyulmaz.
(List<_Word>, String?) _stripCountryCode(List<_Word> seg) {
  if (seg.length < 2) return (seg, null);
  final first = seg.first;
  final a = first.alnum;
  if (a.length > 3 || _hasDigit(a)) return (seg, null);
  final limit = foreignCountryCodes.contains(a) ? _countryRatioKnown : _countryRatio;
  return first.box.height < _maxH(seg.skip(1)) * limit ? (seg.sublist(1), a) : (seg, null);
}

/// Ayrı sıraya düşmüş ya da küçük diye atılmış bant kodu: plakanın hemen solunda, dikeyde plaka
/// hizasında, ana yazıdan küçük, bilinen ülke kodu olan en yakın kelime.
String? _nearbyCountryCode(List<_Word> seg, List<_Word> all) {
  final h = _maxH(seg);
  final left = seg.first.box.left;
  final top = seg.map((w) => w.box.top).reduce(math.min) - h * 0.25;
  final bottom = seg.map((w) => w.box.bottom).reduce(math.max) + h * 0.25;
  String? best;
  var bestGap = double.infinity;
  for (final w in all) {
    if (seg.contains(w) || !foreignCountryCodes.contains(w.alnum)) continue;
    final bh = w.box.height;
    if (bh >= h * _countryRatioKnown || bh < h * _countryMinRatio) continue;
    final gap = left - w.box.right;
    if (gap < -h * 0.1 || gap > h * _countryReach) continue;
    final cy = (w.box.top + w.box.bottom) / 2;
    if (cy < top || cy > bottom) continue;
    if (gap < bestGap) {
      best = w.alnum;
      bestGap = gap;
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
    final a = foreignAlnum(b.text);
    if (a.isEmpty) continue; // yalnızca nokta, tire, mühür sembolü
    words.add(_Word(b, a));
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

  ForeignPlateRead? best;
  for (final row in _rows(words)) {
    for (final raw in _segments(row)) {
      final (seg, stripped) = _stripCountryCode(raw);
      final plate = seg.map((w) => w.alnum).join();
      if (plate.length < foreignPlateMin || plate.length > foreignPlateMax) continue;
      final l = seg.first.box.left, r = seg.last.box.right;
      final t = seg.map((w) => w.box.top).reduce(math.min);
      final bt = seg.map((w) => w.box.bottom).reduce(math.max);
      final dx = (l + r) / 2 - cx, dy = (t + bt) / 2 - cy;
      final c = ForeignPlateRead(
        plate,
        mixed: isMixedPlate(plate),
        height: _maxH(seg),
        centerDistance: math.sqrt(dx * dx + dy * dy) / halfDiag,
        countryCode: stripped ?? _nearbyCountryCode(seg, words),
      );
      if (best == null || _better(c, best)) best = c;
    }
  }
  return best;
}

/// [readForeignPlate] sonucunun yalnızca plaka metni.
String? extractForeignPlate(List<OcrBox> boxes, {double? frameWidth, double? frameHeight}) =>
    readForeignPlate(boxes, frameWidth: frameWidth, frameHeight: frameHeight)?.plate;

bool _better(ForeignPlateRead a, ForeignPlateRead b) {
  if (a.mixed != b.mixed) return a.mixed;
  final aLen = a.plate.length >= 4 && a.plate.length <= 10;
  final bLen = b.plate.length >= 4 && b.plate.length <= 10;
  if (aLen != bLen) return aLen;
  final maxH = math.max(a.height, b.height);
  if ((a.height - b.height).abs() > maxH * _heightTie) return a.height > b.height;
  return a.centerDistance < b.centerDistance;
}
