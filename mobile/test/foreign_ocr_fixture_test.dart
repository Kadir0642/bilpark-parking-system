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
import 'package:mobile/core/foreign_ocr.dart';

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
                  ),
              ],
            ),
        ]);
      }(),
  ];
}

void main() {
  final samples = loadSamples();

  test('tanı tablosu (yalnızca yazdırır)', () {
    for (final s in samples) {
      final reads = [
        for (final f in s.frames) readForeignPlate(f.boxes, frameWidth: f.w, frameHeight: f.h),
      ];
      // ignore: avoid_print
      print('${s.name.padRight(10)} exp=${s.expected} ${reads.map((r) => '${r?.plate}/${r?.countryCode}').join('  ')}');
    }
  });
}
