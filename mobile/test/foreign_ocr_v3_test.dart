// Yabancı plaka OCR düzeltmeleri (gerçek ML Kit dökümüyle bulunan sorunlar): onay oylaması, ızgarada
// birleşen plakaların bölünmesi, manşet / ülke adı elemesi, bant artığı, Suudi Latin satırı, Kiril harfler.
import 'package:bilpark/core/foreign_ocr.dart';
import 'package:bilpark/core/plate.dart';
import 'package:bilpark/screens/plate_scanner_screen.dart';
import 'package:flutter_test/flutter_test.dart';

const fw = 720.0, fh = 1280.0;

OcrBox b(String text, double left, double top, double w, double h, {List<OcrBox> symbols = const []}) =>
    OcrBox(text, left: left, top: top, right: left + w, bottom: top + h, symbols: symbols);

/// Kelimenin karakter kutuları: (genişlik, sonraki karaktere boşluk) çiftleri soldan sağa.
OcrBox word(String text, double left, double top, double h, List<(double, double)> chars) {
  final syms = <OcrBox>[];
  var x = left;
  for (var i = 0; i < text.length; i++) {
    final (w, gap) = chars[i];
    syms.add(OcrBox(text[i], left: x, top: top, right: x + w, bottom: top + h));
    x += w + gap;
  }
  return OcrBox(text, left: left, top: top, right: syms.last.right, bottom: top + h, symbols: syms);
}

ForeignPlateRead? rd(List<OcrBox> boxes) => readForeignPlate(boxes, frameWidth: fw, frameHeight: fh);

void main() {
  group('ForeignVotes: son 6 karede 3 oy', () {
    test('boş ya da farklı kare sayacı sıfırlamaz', () {
      final v = ForeignVotes();
      expect(v.add('AB1234'), isNull);
      expect(v.add(null), isNull);
      expect(v.add('XAB1234'), isNull);
      expect(v.add('AB1234'), isNull);
      expect(v.votesFor('AB1234'), 2);
      expect(v.add('AB1234'), 'AB1234');
    });

    test('O/0 ve I/1 aynı oy; en sık yazım döner', () {
      final v = ForeignVotes();
      expect(v.add('KZO9419'), isNull);
      expect(v.add('KZ09419'), isNull);
      expect(v.add('KZ09419'), 'KZ09419');
    });

    test('pencere dışına düşen oy sayılmaz', () {
      final v = ForeignVotes();
      v.add('AB1234');
      v.add('AB1234');
      for (var i = 0; i < 5; i++) {
        expect(v.add(null), isNull);
      }
      expect(v.add('AB1234'), isNull); // ilk iki oy pencereden çıktı
    });

    test('tarayıcı yine yalnızca harf+rakam adayını oy sayar', () {
      final v = ForeignVotes();
      for (var i = 0; i < 6; i++) {
        expect(v.add(foreignConfirmKey('123456')), isNull);
        expect(v.add(foreignConfirmKey('SWEDEN')), isNull);
      }
    });
  });

  group('karakter düzeltmesi', () {
    test('yalnızca iki yanı rakam olan O/Q -> 0, I -> 1', () {
      expect(fixDigitLookalikes('8O11'), '8011');
      expect(fixDigitLookalikes('5I7'), '517');
      expect(fixDigitLookalikes('O123OO'), 'O123OO'); // Rus plakası harf O'ları bozulmaz
      expect(fixDigitLookalikes('KZO94'), 'KZO94');
    });

    test('Kiril plaka harfleri Latin eşine iner (А В Е К М Н О Р С Т У Х)', () {
      expect(cleanPlate('А 123 ВС 77'), 'A123BC77');
      expect(cleanPlate('х456км199'), 'X456KM199');
      expect(latinizeCyrillic('М 001 РУ'), 'M 001 PY');
      expect(latinizeCyrillic('34 ABC 123'), '34 ABC 123');
      expect(isValidForeignPlateInput('А123ВС77'), isTrue);
      expect(foreignAlnum('Е777КХ'), 'E777KX');
    });
  });

  group('aday seçimi', () {
    test('küçük harfli / Ç Ş Ğ içeren yazı (manşet, ülke adı) temiz plakanın önüne geçmez', () {
      final boxes = [
        b('“6', 60, 300, 60, 110),
        b('AYDA', 140, 300, 220, 110),
        b('ÇIKIŞ”', 380, 300, 280, 110), // büyük manşet, harf+rakam
        b('WX', 250, 900, 40, 30),
        b('0265', 300, 900, 90, 30), // küçük plaka
        b('Austria2', 250, 950, 120, 20),
      ];
      final r = rd(boxes)!;
      expect(r.plate, 'WX0265');
      expect(r.dirty, isFalse);
    });

    test('ızgarada yan yana plakalar tek kelimeye birleşse de bant izinden bölünür', () {
      // "1-EBY-514" + bant + "CA 6505 PC": 4'ün kutusu banda taşar (geniş), ardından büyük boşluk
      final merged = word('1-EBY-514CA', 275, 600, 42, [
        (12, 0), (18, 9), (20, 3), (16, 4), (17, 3), (20, 7), (19, 3), (17, 0), (34, 29), (33, 4), (14, 0),
      ]);
      final boxes = [
        b('B228', 48, 600, 109, 42),
        b('FK', 166, 600, 38, 42),
        merged,
        b('6505', 553, 600, 90, 42),
        b('PC', 659, 600, 38, 42),
      ];
      final plates = <String>{};
      // merkez her plakaya kaydırılarak denenir: hepsi ayrı aday olmalı, 12'den uzun birleşik aday olmamalı
      for (final cx in [100.0, 400.0, 640.0]) {
        plates.add(readForeignPlate(boxes, frameWidth: cx * 2, frameHeight: 1242)!.plate);
      }
      expect(plates, {'B228FK', '1EBY514', 'CA6505PC'});
    });

    test('tek plakada bant izi benzeri geniş karakter plakayı bölmez', () {
      final w = word('BA742TP', 100, 600, 90, [(120, -1), (51, 35), (54, 13), (36, 6), (40, 20), (39, 10), (31, 0)]);
      expect(rd([w])!.plate, 'BA742TP');
    });

    test('Suudi Latin satırı: rakam grubu ve harf grubu geniş boşlukla da tek plaka', () {
      final boxes = [b('1234', 110, 655, 135, 62), b('JRS', 383, 656, 169, 63), b('KSA', 610, 618, 51, 26)];
      expect(rd(boxes)!.plate, '1234JRS');
      // iki ayrı plakanın parçaları (ikisi de karışık) birleşmez
      expect(rd([b('AB12', 60, 600, 140, 60), b('CD34', 420, 600, 140, 60)])!.plate.length, 4);
    });

    test('mavi bant artığı: bantta okunan kodun üstündeki harf plakaya katılmaz', () {
      final boxes = [
        b('E', 152, 598, 26, 88), // bant yıldızları "E" okunmuş
        b('NEZER', 234, 597, 210, 89),
        b('065', 525, 599, 123, 88),
        b('GE', 126, 657, 23, 27),
      ];
      final r = rd(boxes)!;
      expect(r.plate, 'NEZER065');
      expect(r.countryCode, 'GE');
      // karakter kutusu varsa kelimenin içindeki bant harfi de atılır
      final hr = word('EZG3543-AA', 74, 74, 84, [
        (66, 17), (30, -4), (33, 20), (38, 4), (37, 19), (37, 10), (26, -2), (34, 20), (38, 9), (28, 0),
      ]);
      expect(rd([hr, b('HR', 70, 119, 41, 34)])!.plate, 'ZG3543AA');
    });

    test('ülke kodu yokken baştaki tam boy harf atılmaz (Düsseldorf "D", Berlin "B")', () {
      expect(rd([b('B', 100, 600, 40, 90), b('CE', 170, 600, 90, 90), b('3849', 290, 600, 170, 90)])!.plate, 'BCE3849');
    });
  });

  group('tarayıcı ekranı yardımcıları', () {
    test('tanı satırı: aday yok / aday + ülke + ilerleme / onaylanmaz', () {
      expect(foreignDebugLine(null, votes: 0, needed: 3), 'aday yok');
      const r = ForeignPlateRead('AB1234', mixed: true, height: 80, centerDistance: 0.1, countryCode: 'D');
      expect(foreignDebugLine(r, votes: 2, needed: 3), 'aday: AB1234 · D · 2/3');
      const s = ForeignPlateRead('123456', mixed: false, height: 80, centerDistance: 0.1);
      expect(foreignDebugLine(s, votes: 0, needed: 3), contains('onaylanmaz'));
    });
  });
}
