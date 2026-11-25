// Türk plakası kuralları: backend PlateNormalizer.java ile birebir aynı tutulur.
// Tek doğruluk kaynağı burasıdır: giriş formu, kamera tarama ve OCR düzeltmesi buradan beslenir.

const plateExample = '34 ABC 123';

// İl kodu 01-81, ardından: 1 harf + 4-5 rakam | 2 harf + 3-4 rakam | 3 harf + 2-3 rakam
final _trPlate = RegExp(r'^(0[1-9]|[1-7][0-9]|8[01])([A-Z][0-9]{4,5}|[A-Z]{2}[0-9]{3,4}|[A-Z]{3}[0-9]{2,3})$');

/// Boşluk ve tireleri atar, Türkçe i/ı/İ harflerini I yapar, büyütür. Doğrulamaz.
String cleanPlate(String raw) {
  final s = raw.replaceAll(RegExp(r'[\s-]+'), '').replaceAll('İ', 'I').replaceAll('ı', 'I').replaceAll('i', 'I');
  return s.toUpperCase();
}

/// Ham girdi (boşluklu/küçük harfli olabilir) geçerli bir Türk plakası mı?
bool isValidTrPlate(String raw) => _trPlate.hasMatch(cleanPlate(raw));

/// Plaka geçersizse görevliye gösterilecek kısa Türkçe neden; geçerliyse null.
String? plateHint(String raw) {
  final p = cleanPlate(raw);
  if (p.isEmpty) return 'Plaka girin. Örnek: $plateExample';
  if (_trPlate.hasMatch(p)) return null;
  if (RegExp(r'[^A-Z0-9]').hasMatch(p)) return 'Yalnızca A-Z harfleri ve rakam kullanın';
  if (p.length >= 2) {
    final il = int.tryParse(p.substring(0, 2));
    if (il == null || il < 1 || il > 81) return 'İl kodu 01-81 olmalı';
  } else if (RegExp(r'[A-Z]').hasMatch(p)) {
    return 'İl kodu 01-81 olmalı';
  }
  return 'Örnek: $plateExample';
}

// Konuma göre OCR karışıklıkları
const _toDigit = {'O': '0', 'Q': '0', 'D': '0', 'I': '1', 'L': '1', 'T': '1', 'Z': '2', 'S': '5', 'G': '6', 'B': '8'};
const _toLetter = {'0': 'O', '1': 'I', '2': 'Z', '5': 'S', '8': 'B', '6': 'G'};

bool _isDigit(String ch) => ch.codeUnitAt(0) >= 48 && ch.codeUnitAt(0) <= 57;
bool _isLetter(String ch) => ch.codeUnitAt(0) >= 65 && ch.codeUnitAt(0) <= 90;

// (harf sayısı, rakam sayısı) çiftleri; il kodu ayrıca 2 rakamdır
const _shapes = [(1, 4), (1, 5), (2, 3), (2, 4), (3, 2), (3, 3)];
const _maxFixes = 2;

class _Cand {
  final String plate;
  final int fixes;
  const _Cand(this.plate, this.fixes);
}

/// [s] (yalnızca A-Z0-9) tam olarak [shape] biçiminde okunabilir mi? Düzeltme sayısıyla döner.
_Cand? _tryShape(String s, (int, int) shape) {
  final (l, d) = shape;
  if (s.length != 2 + l + d) return null;
  final out = StringBuffer();
  var fixes = 0;
  for (var i = 0; i < s.length; i++) {
    final ch = s[i];
    final wantDigit = i < 2 || i >= 2 + l;
    if (wantDigit) {
      if (_isDigit(ch)) {
        out.write(ch);
      } else {
        final f = _toDigit[ch];
        if (f == null) return null;
        out.write(f);
        fixes++;
      }
    } else {
      if (_isLetter(ch)) {
        out.write(ch);
      } else {
        final f = _toLetter[ch];
        if (f == null) return null;
        out.write(f);
        fixes++;
      }
    }
  }
  final plate = out.toString();
  if (!_trPlate.hasMatch(plate)) return null;
  return _Cand(plate, fixes);
}

/// Gürültülü OCR metninden en iyi geçerli plakayı bulur (yoksa null).
/// Çok satırlı/kare plaka ("34\nABC\n123"), fazladan yazı ("TR", nokta, tire) tolere edilir.
/// Önce düzeltmesiz geçerli olan, sonra en az düzeltme gerektiren, sonra en uzun aday seçilir.
String? extractPlate(String ocrText) {
  final tokens = ocrText
      .replaceAll('İ', 'I')
      .replaceAll('ı', 'I')
      .replaceAll('i', 'I')
      .toUpperCase()
      .split(RegExp(r'[^A-Z0-9]+'))
      .where((t) => t.isNotEmpty)
      .toList();
  if (tokens.isEmpty) return null;

  _Cand? best;
  for (var start = 0; start < tokens.length; start++) {
    var joined = '';
    for (var end = start; end < tokens.length && end < start + 4; end++) {
      joined += tokens[end];
      for (var from = 0; from < joined.length; from++) {
        for (final shape in _shapes) {
          final len = 2 + shape.$1 + shape.$2;
          if (from + len > joined.length) continue;
          final c = _tryShape(joined.substring(from, from + len), shape);
          if (c == null || c.fixes > _maxFixes) continue;
          if (best == null ||
              c.fixes < best.fixes ||
              (c.fixes == best.fixes && c.plate.length > best.plate.length)) {
            best = c;
          }
        }
      }
    }
  }
  return best?.plate;
}

// --- Yabancı / özel plaka (Türk biçimine uymayan: yabancı ülke, diplomatik CD vb.) ---
// Görevli giriş formunda "Yabancı / özel plaka" anahtarını açtığında kullanılır. Backend
// PlateNormalizer.isValidForeign ile aynı kural: boşluk ve tire atıldıktan sonra yalnızca A-Z ve 0-9,
// 2-12 karakter. TR biçim denetimi ve OCR düzeltmesi (O->0 gibi) YAPILMAZ.
// Not: format.dart isForeignPlate() başka bir şeydir (TR biçimli MA-MZ yabancı uyruklu plakası).

const foreignPlateExample = 'M AB 1234';
const foreignPlateMin = 2;
const foreignPlateMax = 12;

final _foreignPlate = RegExp('^[A-Z0-9]{$foreignPlateMin,$foreignPlateMax}\$');

/// Ham girdi yabancı / özel plaka kuralına uyuyor mu? (Her Türk plakası da uyar.)
bool isValidForeignPlateInput(String raw) => _foreignPlate.hasMatch(cleanPlate(raw));

/// Yabancı / özel plaka için kısa Türkçe neden; geçerliyse null.
String? foreignPlateHint(String raw) {
  final p = cleanPlate(raw);
  if (p.isEmpty) return 'Plakayı yazın. Örnek: $foreignPlateExample';
  if (RegExp(r'[^A-Z0-9]').hasMatch(p)) return 'Yalnızca A-Z harfleri ve rakam kullanın';
  if (p.length < foreignPlateMin) return 'En az $foreignPlateMin karakter';
  if (p.length > foreignPlateMax) return 'En fazla $foreignPlateMax karakter';
  return null;
}

/// Seçili kurala göre plaka geçerli mi: [foreign] = yabancı / özel plaka anahtarı.
bool isValidPlateFor(String raw, {required bool foreign}) =>
    foreign ? isValidForeignPlateInput(raw) : isValidTrPlate(raw);

/// Seçili kurala göre görevliye gösterilecek neden; geçerliyse null.
String? plateHintFor(String raw, {required bool foreign}) => foreign ? foreignPlateHint(raw) : plateHint(raw);

/// Yabancı / özel plaka modunda OCR: hiçbir harf/rakam düzeltmesi yapmadan satırları temizler
/// (A-Z0-9 dışı her şey atılır) ve plakaya en çok benzeyen satırı döner; görevli onaylar ya da düzeltir.
/// Tercih: harf ve rakam birlikte olan satır, sonra en uzun satır. 2-12 karakter dışı satırlar atlanır.
String? extractRawPlate(String ocrText) {
  String? best;
  var bestScore = -1;
  for (final line in ocrText.split(RegExp(r'[\r\n]+'))) {
    final p = cleanPlate(line).replaceAll(RegExp(r'[^A-Z0-9]'), '');
    if (p.length < foreignPlateMin || p.length > foreignPlateMax) continue;
    final mixed = RegExp(r'[A-Z]').hasMatch(p) && RegExp(r'[0-9]').hasMatch(p);
    final score = (mixed ? 100 : 0) + p.length;
    if (score > bestScore) {
      best = p;
      bestScore = score;
    }
  }
  return best;
}
