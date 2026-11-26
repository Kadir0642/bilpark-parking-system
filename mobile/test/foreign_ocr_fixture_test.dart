// Gerçek ML Kit çıktısıyla yabancı plaka regresyon testi.
//
// test/fixtures/ocr/*.json: emülatörde gerçek TextRecognizer'dan (integration_test/ocr_tani_test.dart)
// alınan kelime metinleri + kutular. Örnek başına: 1 fotoğraf yolu karesi + 3 canlı kamera karesi
// (1280x720 NV21, dönüş 90). Gerçek plaka repoya girmesin diye bant ülke kodu dışındaki harf ve rakamlar
// sabit bir eşlemeyle değiştirildi (harf harfe, rakam rakama; uzunluk ve kutular aynı).
//
// OCR_FIXTURE_DIR ortam değişkeni başka bir klasörü gösterebilir (yerel tanı için).

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/foreign_ocr.dart';

class Frame {
  final String path;
  final double? w, h;
  final List<OcrBox> boxes;
  Frame(this.path, this.w, this.h, this.boxes);
}

class Sample {
  final String name;
  final String? expected;
  final String? country;
  final List<Frame> frames;
  Sample(this.name, this.expected, this.country, this.frames);
}

List<Sample> loadSamples() {
  final dir = Directory(Platform.environment['OCR_FIXTURE_DIR'] ?? 'test/fixtures/ocr');
  if (!dir.existsSync()) return [];
  final files = dir.listSync().whereType<File>().where((f) => f.path.endsWith('.json')).toList()
    ..sort((a, b) => a.path.compareTo(b.path));
  return [
    for (final f in files)
      () {
        final j = jsonDecode(f.readAsStringSync()) as Map<String, dynamic>;
        return Sample(j['sample'] as String, j['expected'] as String?, j['country'] as String?, [
          for (final fr in j['frames'] as List)
            Frame(
              fr['path'] as String,
              (fr['w'] as num?)?.toDouble(),
              (fr['h'] as num?)?.toDouble(),
              [
                for (final w in fr['words'] as List)
                  OcrBox(
                    w[0] as String,
                    left: (w[1] as num).toDouble(),
                    top: (w[2] as num).toDouble(),
                    right: (w[3] as num).toDouble(),
                    bottom: (w[4] as num).toDouble(),
                    charHeight: (w[5] as num?)?.toDouble(),
                    symbols: [
                      if (w.length > 7)
                        for (final s in w[7] as List)
                          OcrBox(
                            s[0] as String,
                            left: (s[1] as num).toDouble(),
                            top: (w[2] as num).toDouble(),
                            right: (s[2] as num).toDouble(),
                            bottom: (w[4] as num).toDouble(),
                          ),
                    ],
                  ),
              ],
            ),
        ]);
      }(),
  ];
}

/// Tarayıcının yabancı mod onayı: örneğin 4 karesi (3 canlı + fotoğraf) sırayla [passes] tur
/// [ForeignVotes]'a verilir (harf+rakam olmayan aday oy değildir). İlk onaylanan plaka ya da null.
/// Asıl ölçüt tek turdur (her kare bir kez); iki tur, aynı kareleri tekrar oynatır (daha gevşek).
String? simulateScanner(Sample s, {int passes = 1}) {
  final votes = ForeignVotes();
  final frames = [for (var i = 0; i < passes; i++) ...s.frames];
  for (final f in frames) {
    final p = readForeignPlate(f.boxes, frameWidth: f.w, frameHeight: f.h)?.plate;
    final c = votes.add(p != null && isMixedPlate(p) ? p : null);
    if (c != null) return c;
  }
  return null;
}

Sample _byName(List<Sample> all, String name) => all.firstWhere((s) => s.name == name);

// Mühür / bant izi harf olarak okunuyor (ör. "OHA [mühür] MK" -> "OHASMK"): ML Kit karakter kutuları
// mührü ayırt etmeye yetmiyor. Tarayıcı yine bir aday onaylar, görevli formda düzeltir.
const _sealMisread = {'de_muhur_1', 'de_muhur_2', 'ab_d', 'ab_slo', 'ab_cz', 'ab2_tn'};
// Onaylanmaz: ML Kit plakayı yanlış okuyor (sıkışık yazı), plaka yalnız rakam (Suriye) ya da örnek anlamsız.
const _noConfirmOk = {'ge_2', 's_SY', 'ab2_dz'};
// Tek turda (4 kare) 3 oy toplanamayanlar: kare kare bir karakter oynuyor; iki turda doğru onaylanıyor.
const _needMoreFrames = {'ab_hr', 'ab_irl', 's_AM', 's_RU'};
// Rakamsız (özel / tanıtım) ya da Latin olmayan plakalar: hiçbir karede onaylanmamalı.
const _mustNotConfirm = {'ab2_a', 'ab2_b', 'ab2_ch', 'ab2_d', 'ab2_ro', 'ab2_s', 'ab2_uk', 'ab2_ma', 'ozel_1', 's_IR'};

void main() {
  final samples = loadSamples();

  test('fikstürler yüklendi', () {
    expect(samples.length, greaterThanOrEqualTo(60));
    expect(samples.where((s) => s.expected != null).length, 49);
  });

  test('tek plakalık örnekler, her kare bir kez: tarayıcı doğru plakayı onaylar', () {
    var correct = 0;
    for (final s in samples.where((s) => s.expected != null)) {
      final c = simulateScanner(s);
      if (c == s.expected) {
        correct++;
      } else {
        // 4 karede 3 oy toplanamadı (kare kare oynuyor) ya da mühür harfi: telefonda daha çok kare gelir
        expect({..._sealMisread, ..._noConfirmOk, ..._needMoreFrames}, contains(s.name),
            reason: '${s.name}: beklenen ${s.expected}, onay $c');
        if (c != null) expect(_sealMisread, contains(s.name), reason: '${s.name}: yanlış onay $c');
      }
    }
    // Aynı dökümde eski kural (üst üste 3 kare, uzun aday atılır) tek turda 30/49 onaylıyordu
    expect(correct, greaterThanOrEqualTo(36));
  });

  test('aynı kareler iki tur oynatılınca (daha uzun bekleme) mühürlü plakalar da bir aday onaylar', () {
    var correct = 0;
    for (final s in samples.where((s) => s.expected != null)) {
      final c = simulateScanner(s, passes: 2);
      if (c == s.expected) {
        correct++;
      } else if (_sealMisread.contains(s.name)) {
        expect(c, isNotNull, reason: s.name);
      } else {
        expect(_noConfirmOk, contains(s.name), reason: '${s.name}: beklenen ${s.expected}, onay $c');
      }
    }
    expect(correct, greaterThanOrEqualTo(40)); // eski kural iki turda 32/49
  });

  test('rakamsız ya da Latin olmayan plaka onaylanmaz', () {
    for (final n in _mustNotConfirm) {
      expect(simulateScanner(_byName(samples, n)), isNull, reason: n);
    }
  });

  test('ekranda birden çok plaka / manşet: plaka okunur, manşet ve ülke adları okunmaz', () {
    // Haber görseli: büyük manşet ("6 AYDA ...") harf+rakam içerse de plakanın önüne geçmez
    expect(simulateScanner(_byName(samples, 'coklu_5')), _byName(samples, 'de_arac_1').expected);
    // Beş plakalı görsel: ortadaki plaka
    expect(simulateScanner(_byName(samples, 'coklu_6')), _byName(samples, 'az_1').expected);
    // 27 plakalı AB ızgarası: yan yana plakalar tek satıra birleşiyordu (aday atılıyor, ülke adları kalıyordu)
    final grid = simulateScanner(_byName(samples, 'coklu_7'));
    final gridPlates = samples.where((s) => s.name.startsWith('ab_')).map((s) => s.expected).toSet();
    expect(gridPlates, contains(grid));
  });

  test('ülke kodu: bantta okunan kod plakaya katılmaz ve taşınır', () {
    final r = _byName(samples, 'ge_1').frames.where((f) => f.path == 'stream').map(
      (f) => readForeignPlate(f.boxes, frameWidth: f.w, frameHeight: f.h),
    );
    expect(r.every((x) => x?.plate == _byName(samples, 'ge_1').expected), isTrue);
    expect(r.where((x) => x?.countryCode == 'GE').length, greaterThanOrEqualTo(2));
  });

  test('tanı tablosu (OCR_TABLE=1 ile yazdırır)', () {
    if (Platform.environment['OCR_TABLE'] == null) return;
    var ok = 0, conf = 0, conf2 = 0, total = 0;
    for (final s in samples) {
      final reads = [
        for (final f in s.frames) readForeignPlate(f.boxes, frameWidth: f.w, frameHeight: f.h),
      ];
      final c = simulateScanner(s);
      final c2 = simulateScanner(s, passes: 2);
      if (s.expected != null) {
        total++;
        if (reads.every((r) => r?.plate == s.expected)) ok++;
        if (c == s.expected) conf++;
        if (c2 == s.expected) conf2++;
      }
      // ignore: avoid_print
      print('${s.name.padRight(10)} exp=${s.expected} onay=$c onay2=$c2 | ${reads.map((r) => '${r?.plate}/${r?.countryCode}').join('  ')}');
    }
    // ignore: avoid_print
    print('TOPLAM: $total örnek, tüm karelerde doğru $ok, onay tek tur $conf, iki tur $conf2');
  });
}
