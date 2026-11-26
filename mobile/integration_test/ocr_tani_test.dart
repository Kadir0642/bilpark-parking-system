// Yabancı plaka OCR tanı aracı (EMÜLATÖRDE çalışır; telefonda değil).
//
// Uygulamanın iç klasörüne (files/ocr/in) konmuş görselleri ve ham NV21
// kareleri gerçek ML Kit TextRecognizer'dan geçirir; blok / satır / kelime metnini, kutularını ve köşe
// noktalarını ocr/out/<ad>.json dosyasına yazar. Görseller repoya girmez (telifli örnekler yalnızca yerelde);
// repoya yalnızca bu JSON'lardan türetilen metin + kutu fikstürleri girer (test/fixtures/ocr).
//
// Kullanım (yalnızca emülatör):
//   flutter build apk --debug -t integration_test/ocr_tani_test.dart && adb -s emulator-5554 install -r <apk>
//   adb -s emulator-5554 push in /data/local/tmp/ocr_in
//   adb -s emulator-5554 shell "run-as com.example.mobile sh -c 'mkdir -p files/ocr && cp -r /data/local/tmp/ocr_in files/ocr/in'"
//   adb -s emulator-5554 shell am start -n com.example.mobile/.MainActivity   (test kendiliğinden koşar)
//   adb -s emulator-5554 exec-out run-as com.example.mobile tar c files/ocr/out > out.tar
// in/nv21.json: {"<dosya>.nv21": {"width": 1280, "height": 720, "rotation": 90}}
//
// Klasör yoksa test atlanır.

import 'dart:convert';
import 'dart:io';
import 'dart:math' show Point;

import 'package:flutter/painting.dart' show Rect, Size;
import 'package:flutter_test/flutter_test.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:integration_test/integration_test.dart';

const _root = '/data/user/0/com.example.mobile/files/ocr'; // uygulamanın iç klasörü (run-as ile doldurulur)

Map<String, Object?> _rect(Rect r) => {'l': r.left, 't': r.top, 'r': r.right, 'b': r.bottom};
List<List<int>> _pts(List<Point<int>> p) => [for (final q in p) [q.x, q.y]];

Map<String, Object?> _dump(RecognizedText t) => {
  'text': t.text,
  'blocks': [
    for (final b in t.blocks)
      {
        'text': b.text,
        'box': _rect(b.boundingBox),
        'lines': [
          for (final l in b.lines)
            {
              'text': l.text,
              'box': _rect(l.boundingBox),
              'corners': _pts(l.cornerPoints),
              'angle': l.angle,
              'confidence': l.confidence,
              'elements': [
                for (final e in l.elements)
                  {
                    'text': e.text,
                    'box': _rect(e.boundingBox),
                    'corners': _pts(e.cornerPoints),
                    'confidence': e.confidence,
                    'symbols': [
                      for (final s in e.symbols)
                        {'text': s.text, 'box': _rect(s.boundingBox), 'corners': _pts(s.cornerPoints)},
                    ],
                  },
              ],
            },
        ],
      },
  ],
};

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('ML Kit çıktısını JSON olarak dök', (tester) async {
    final inDir = Directory('$_root/in');
    if (!inDir.existsSync()) {
      markTestSkipped('ocr/in klasörü yok');
      return;
    }
    final outDir = Directory('$_root/out')..createSync(recursive: true);
    final metaFile = File('$_root/in/nv21.json');
    final meta = metaFile.existsSync() ? jsonDecode(metaFile.readAsStringSync()) as Map<String, dynamic> : {};
    final rec = TextRecognizer(script: TextRecognitionScript.latin);
    var n = 0;
    try {
      final files = inDir.listSync().whereType<File>().toList()..sort((a, b) => a.path.compareTo(b.path));
      for (final f in files) {
        final name = f.uri.pathSegments.last;
        InputImage? input;
        Map<String, Object?> frame;
        if (name.endsWith('.png') || name.endsWith('.jpg')) {
          input = InputImage.fromFilePath(f.path);
          frame = {'path': 'photo'};
        } else if (name.endsWith('.nv21') && meta[name] != null) {
          final m = meta[name] as Map<String, dynamic>;
          final w = (m['width'] as num).toDouble(), h = (m['height'] as num).toDouble();
          final rot = InputImageRotationValue.fromRawValue(m['rotation'] as int)!;
          input = InputImage.fromBytes(
            bytes: f.readAsBytesSync(),
            metadata: InputImageMetadata(
              size: Size(w, h),
              rotation: rot,
              format: InputImageFormat.nv21,
              bytesPerRow: w.toInt(),
            ),
          );
          final upright = (rot == InputImageRotation.rotation90deg || rot == InputImageRotation.rotation270deg);
          frame = {'path': 'stream', 'width': upright ? h : w, 'height': upright ? w : h};
        } else {
          continue;
        }
        final t = await rec.processImage(input);
        File('${outDir.path}/${name.replaceAll('.', '_')}.json').writeAsStringSync(
          const JsonEncoder.withIndent(' ').convert({'name': name, 'frame': frame, ..._dump(t)}),
        );
        n++;
      }
    } finally {
      await rec.close();
    }
    // ignore: avoid_print
    print('OCR_TANI dokum: $n dosya');
    expect(n, greaterThan(0));
  });
}
