// Yabancı / özel plaka kamera OCR'ı: ML Kit benzeri kelime kutularıyla (metin + konum) plaka seçimi.
// Kutular dik tutulan telefonun 1080x1920 karesinde, plaka ekranın ortasında, ana yazı ~80 px boyunda.
import 'dart:math' as math;
import 'dart:math' show Point;

import 'package:bilpark/core/foreign_ocr.dart';
import 'package:bilpark/core/plate.dart';
import 'package:bilpark/screens/plate_scanner_screen.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:material_ui/material_ui.dart';

const fw = 1080.0, fh = 1920.0;

OcrBox b(String text, double left, double top, double w, double h) =>
    OcrBox(text, left: left, top: top, right: left + w, bottom: top + h);

String? read(List<OcrBox> boxes) => extractForeignPlate(boxes, frameWidth: fw, frameHeight: fh);
String? cc(List<OcrBox> boxes) => readForeignPlate(boxes, frameWidth: fw, frameHeight: fh)?.countryCode;

/// Eğik plaka: kelimeler (metin, gerçek en, gerçek boy, yazı çizgisinden aşağı kayma) [deg] derece
/// döndürülmüş bir çizgi üzerinde 40 px arayla dizilir; ML Kit gibi eksene hizalı kutu döner.
List<OcrBox> tiltedPlate(List<(String, double, double, double)> words, {required double deg}) {
  final t = deg * math.pi / 180, cos = math.cos(t), sin = math.sin(t).abs();
  final out = <OcrBox>[];
  var along = 150.0; // çizgi boyunca sol kenar
  for (final w in words) {
    final (text, ww, hh, drop) = w;
    final c = along + ww / 2;
    final cx = c * math.cos(t), cy = 960 + c * math.sin(t) + drop;
    final bw = ww * cos + hh * sin, bh = ww * sin + hh * cos;
    out.add(OcrBox(text, left: cx - bw / 2, top: cy - bh / 2, right: cx + bw / 2, bottom: cy + bh / 2));
    along += ww + 40;
  }
  return out;
}

/// Almanya: mavi bantta küçük "D", OHA ile AMK arasında iki mühür (ML Kit bunları "8", "O", "©" okur).
final germanOha = [
  b('D', 100, 985, 26, 40), // ülke kodu (ana yazının yarısı)
  b('OHA', 150, 920, 160, 80),
  b('8', 330, 922, 20, 30), // eyalet mührü
  b('O', 330, 958, 22, 28), // muayene etiketi
  b('©', 352, 940, 14, 16),
  b('AMK', 380, 922, 160, 78),
  b('07', 560, 921, 100, 79),
];

void main() {
  group('readForeignPlate: kutu konumlu yabancı plaka', () {
    test('Almanya OHA AMK 07: ülke kodu D ve mühür parçaları atılır', () {
      expect(read(germanOha), 'OHAAMK07');
      expect(cc(germanOha), 'D'); // bant kodu plakaya katılmaz ama sonuçta saklanır
      // Eski düz metin yolu bu karede "D" ve mühürleri karıştırıyordu (kanıt)
      expect(extractRawPlate('D OHA 8 O AMK 07'), isNot('OHAAMK07'));
    });

    test('Almanya OHA AMK 07: alttaki bayi yazısı ve üstte tabela seçilmez', () {
      final boxes = [
        ...germanOha,
        b('AUTOHAUS', 200, 1010, 200, 24),
        b('MÜLLER', 420, 1010, 150, 24),
        b('PARKEN', 300, 500, 300, 90), // uzaktaki tabela, büyük ama harf+rakam yok
      ];
      expect(read(boxes), 'OHAAMK07');
    });

    test('Almanya MH MT 042: orta boy ülke kodu D soyulur, Düsseldorf D tam boyda kalır', () {
      expect(
        read([
          b('D', 95, 960, 40, 60), // bant kodu 0.75 oran: tiny değil ama ülke kodu
          b('MH', 150, 920, 110, 80),
          b('MT', 320, 920, 110, 80),
          b('042', 450, 920, 150, 80),
        ]),
        'MHMT042',
      );
      expect(
        cc([b('D', 95, 960, 40, 60), b('MH', 150, 920, 110, 80), b('MT', 320, 920, 110, 80), b('042', 450, 920, 150, 80)]),
        'D',
      );
      // Ülke kodu yoksa null
      expect(cc([b('MH', 150, 920, 110, 80), b('MT', 320, 920, 110, 80), b('042', 450, 920, 150, 80)]), isNull);
      // Düsseldorf: ilk harf "D" plakanın kendisi, tam boy: soyulmaz
      expect(
        read([
          b('D', 100, 985, 26, 40), // bant
          b('D', 150, 920, 50, 80),
          b('AB', 260, 920, 110, 80),
          b('123', 390, 920, 150, 80),
        ]),
        'DAB123',
      );
      expect(
        cc([b('D', 100, 985, 26, 40), b('D', 150, 920, 50, 80), b('AB', 260, 920, 110, 80), b('123', 390, 920, 150, 80)]),
        'D',
      );
    });

    test('SA LiM 07: OCR küçük i okusa da I olur', () {
      expect(
        read([
          b('D', 100, 985, 26, 40),
          b('SA', 150, 920, 110, 80),
          b('LiM', 330, 921, 150, 79),
          b('07', 500, 920, 100, 80),
        ]),
        'SALIM07',
      );
    });

    test('Hollanda NL MH-MT-42: NL bandı soyulur, tireler atılır (tek ya da parçalı kelime)', () {
      expect(read([b('NL', 90, 940, 45, 55), b('MH-MT-42', 150, 920, 420, 80)]), 'MHMT42');
      expect(cc([b('NL', 90, 940, 45, 55), b('MH-MT-42', 150, 920, 420, 80)]), 'NL');
      // Bant kodu küçük okunup ayrı sıraya düşse de bulunur
      expect(cc([b('NL', 95, 985, 40, 35), b('MH-MT-42', 150, 920, 420, 80)]), 'NL');
      expect(
        read([
          b('NL', 90, 940, 45, 55),
          b('MH', 150, 920, 110, 80),
          b('-', 268, 955, 20, 8),
          b('MT', 295, 921, 110, 79),
          b('-', 412, 955, 20, 8),
          b('42', 440, 920, 100, 80),
        ]),
        'MHMT42',
      );
    });

    test('yan yana iki plaka birleşmez; merkeze yakın olan seçilir', () {
      final left = [b('B', 20, 930, 45, 70), b('XY', 80, 930, 95, 70), b('123', 190, 930, 130, 70)];
      final center = [b('MH', 520, 925, 110, 75), b('MT', 670, 925, 110, 75), b('042', 800, 925, 150, 75)];
      expect(read([...left, ...center]), 'MHMT042');
      // Sol plaka merkezdeyken o seçilir (yükseklikler %10 içinde: merkez belirler)
      final shiftedLeft = [for (final x in left) b(x.text, x.left + 380, x.top, x.width, x.height)];
      final farRight = [for (final x in center) b(x.text, x.left + 700, x.top, x.width, x.height)];
      expect(read([...shiftedLeft, ...farRight]), 'BXY123');
      // İkisi de 12 karakteri aşan tek aday olarak birleşmez
      final r = readForeignPlate([...left, ...center], frameWidth: fw, frameHeight: fh)!;
      expect(r.plate.length, lessThanOrEqualTo(foreignPlateMax));
    });

    test('daha büyük yazı, merkezdeki küçük yazıya baskın gelir', () {
      expect(
        read([
          b('AB12', 480, 950, 80, 20), // ortada küçük etiket
          b('K', 100, 1300, 60, 90),
          b('LM', 180, 1300, 120, 90),
          b('345', 320, 1300, 160, 90),
        ]),
        'KLM345',
      );
    });

    test('harf+rakam karışık aday, düz yazıya ve uzunluğa baskın gelir', () {
      expect(read([b('OTOPARK', 100, 700, 600, 120), b('AB', 300, 950, 110, 80), b('123', 430, 950, 150, 80)]), 'AB123');
    });

    test('kutu yoksa, yalnızca sembol varsa ya da çok uzunsa null', () {
      expect(read(const []), isNull);
      expect(read([b('·', 100, 900, 10, 10), b('-', 200, 900, 20, 8)]), isNull);
      expect(read([b('ABCDEFG1234567', 100, 900, 600, 80)]), isNull);
      expect(read([b('A', 100, 900, 50, 80)]), isNull);
    });

    test('çerçeve boyutu verilmezse kutuları saran alanın ortası kullanılır', () {
      expect(extractForeignPlate(germanOha), 'OHAAMK07');
    });
  });

  group('eğik plaka: tam boy ilk harf ülke kodu sanılmaz', () {
    test('5 derece eğik "M AB 1234" (München): M kalır', () {
      final boxes = tiltedPlate([('M', 45, 80, 0), ('AB', 105, 80, 0), ('1234', 200, 80, 0)], deg: 5);
      // Ham kutu yükseklik oranı inceleme bulgusundaki gibi 0.9'un altında (eski kod M'yi soyuyordu)
      expect(boxes[0].height / boxes[2].height, lessThan(0.9));
      expect(read(boxes), 'MAB1234');
      expect(cc(boxes), isNull);
      expect(read(tiltedPlate([('M', 45, 80, 0), ('AB', 105, 80, 0), ('1234', 200, 80, 0)], deg: -8)), 'MAB1234');
    });

    test('-6 derece eğik "B MW 123": B kalır', () {
      final boxes = tiltedPlate([('B', 45, 80, 0), ('MW', 110, 80, 0), ('123', 150, 80, 0)], deg: -6);
      expect(read(boxes), 'BMW123');
      expect(cc(boxes), isNull);
    });

    test('eğik plakada gerçek AB bandı D yine soyulur; NL de soyulur', () {
      // Bant kodu küçük (0.65) ve yazı çizgisinin altında
      final de = tiltedPlate([('D', 26, 52, 14.0), ('OHA', 160, 80, 0.0), ('AMK', 160, 80, 0.0), ('07', 100, 80, 0.0)], deg: 5);
      expect(read(de), 'OHAAMK07');
      expect(cc(de), 'D');
      // Orta boy (0.75) ama alçakta duran tek harf: bant kodu
      final de2 = tiltedPlate([('D', 30, 60, 14.0), ('MH', 110, 80, 0.0), ('MT', 110, 80, 0.0), ('042', 150, 80, 0.0)], deg: 4);
      expect(read(de2), 'MHMT042');
      expect(cc(de2), 'D');
      final nl = tiltedPlate([('NL', 45, 55, 10.0), ('MH-MT-42', 420, 80, 0.0)], deg: 5);
      expect(read(nl), 'MHMT42');
      expect(cc(nl), 'NL');
    });

    test('köşe noktası yüksekliği verilirse eksene hizalı kutu yerine o kullanılır', () {
      final boxes = tiltedPlate([('M', 45, 80, 0), ('AB', 105, 80, 0), ('1234', 200, 80, 0)], deg: 5);
      final withCorners = [
        for (final x in boxes) OcrBox(x.text, left: x.left, top: x.top, right: x.right, bottom: x.bottom, charHeight: 80),
      ];
      expect(read(withCorners), 'MAB1234');
      expect(cornerHeight(const [Point(0, 0), Point(100, 10), Point(92, 90), Point(-8, 80)]), closeTo(80.4, 0.1));
      expect(cornerHeight(const []), isNull);
    });
  });

  group('iki satırlı (kare) plaka', () {
    test('"MH / AB 1234" tek plaka olur, yarım plaka "AB1234" seçilmez', () {
      final boxes = [b('MH', 200, 820, 130, 80), b('AB', 150, 920, 110, 80), b('1234', 280, 920, 200, 80)];
      expect(read(boxes), 'MHAB1234');
    });

    test('"MH / 1234" tek plaka olur', () {
      expect(read([b('MH', 160, 820, 130, 80), b('1234', 140, 915, 180, 82)]), 'MH1234');
    });

    test('alttaki küçük bayi yazısı ya da uzak satır plakaya katılmaz', () {
      expect(read([b('AB', 150, 920, 110, 80), b('1234', 280, 920, 200, 80), b('AUTOHAUS', 180, 1010, 200, 24)]), 'AB1234');
      expect(read([b('MH', 200, 500, 130, 80), b('AB', 150, 920, 110, 80), b('1234', 280, 920, 200, 80)]), 'AB1234');
    });
  });

  group('tarayıcı: yabancı modda onay', () {
    test('yabancı modda 3, TR modunda 2 ardışık kare gerekir', () {
      expect(readsNeeded(foreign: true), 3);
      expect(readsNeeded(foreign: false), 2);
      final r = ConsecutiveReads(needed: readsNeeded(foreign: true));
      final p = read(germanOha);
      expect(const PlateScan('OHAAMK07', foreign: true, countryCode: 'D').countryCode, 'D');
      expect(const PlateScan('34ABC123').countryCode, isNull);
      expect(r.add(foreignConfirmKey(p)), isNull);
      expect(r.add(foreignConfirmKey(p)), isNull);
      expect(r.add(foreignConfirmKey(p)), 'OHAAMK07');
    });

    test('düz yazı (harf+rakam yok) kaç kare gelirse gelsin onaylanmaz', () {
      final frame = [b('BELEDIYE', 100, 900, 500, 90), b('OTOPARKI', 100, 1010, 500, 90)];
      final p = read(frame);
      expect(p, isNotNull); // aday olarak ekranda görünebilir
      expect(isMixedPlate(p!), isFalse);
      final r = ConsecutiveReads(needed: readsNeeded(foreign: true));
      for (var i = 0; i < 6; i++) {
        expect(r.add(foreignConfirmKey(read(frame))), isNull);
      }
      expect(foreignConfirmKey(null), isNull);
      expect(foreignConfirmKey('12345'), isNull);
    });

    test('ipucu süresi ve metni', () {
      expect(foreignHintDelay, const Duration(seconds: 3));
      expect(foreignHintText, isNotEmpty);
    });

    test('ML Kit uyarlayıcısı: blok > satır > kelime kutuları sırayla çıkar', () {
      TextElement el(String t, Rect r) => TextElement(
        text: t,
        symbols: const [],
        boundingBox: r,
        recognizedLanguages: const [],
        cornerPoints: const <Point<int>>[],
        confidence: 0.9,
        angle: 0,
      );
      final els = [
        el('D', const Rect.fromLTWH(100, 985, 26, 40)),
        el('OHA', const Rect.fromLTWH(150, 920, 160, 80)),
        el('AMK', const Rect.fromLTWH(380, 922, 160, 78)),
        el('07', const Rect.fromLTWH(560, 921, 100, 79)),
      ];
      final line = TextLine(
        text: 'D OHA AMK 07',
        elements: els,
        boundingBox: const Rect.fromLTWH(100, 920, 560, 105),
        recognizedLanguages: const [],
        cornerPoints: const <Point<int>>[],
        confidence: 0.9,
        angle: 0,
      );
      final rt = RecognizedText(
        text: 'D OHA AMK 07',
        blocks: [
          TextBlock(
            text: 'D OHA AMK 07',
            lines: [line],
            boundingBox: line.boundingBox,
            recognizedLanguages: const [],
            cornerPoints: const <Point<int>>[],
          ),
        ],
      );
      final boxes = ocrBoxesFrom(rt);
      expect(boxes.map((x) => x.text), ['D', 'OHA', 'AMK', '07']);
      expect(boxes[1].left, 150);
      expect(boxes[1].bottom, 1000);
      expect(plateFromRecognized(rt, foreign: true, frame: const Size(fw, fh)), 'OHAAMK07');
      expect(foreignReadFromRecognized(rt, frame: const Size(fw, fh))?.countryCode, 'D');
      // TR modu eski metin yolunu kullanır (değişmedi)
      expect(plateFromRecognized(rt, foreign: false), extractPlate('D OHA AMK 07'));
    });

    test('dik tutuş kare boyutu: 90/270 derecede en-boy yer değiştirir', () {
      expect(uprightFrameSize(const Size(1920, 1080), InputImageRotation.rotation90deg), const Size(1080, 1920));
      expect(uprightFrameSize(const Size(1920, 1080), InputImageRotation.rotation270deg), const Size(1080, 1920));
      expect(uprightFrameSize(const Size(1920, 1080), InputImageRotation.rotation0deg), const Size(1920, 1080));
    });
  });
}
