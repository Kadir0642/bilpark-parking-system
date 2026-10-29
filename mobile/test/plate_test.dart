import 'package:bilpark/core/plate.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('isValidTrPlate', () {
    test('geçerli biçimler', () {
      for (final p in ['34 A 1234', '34 A 12345', '34 AB 123', '34 AB 1234', '34 ABC 12', '34 ABC 123', '01ABC123', '81 ab 1234', '34-abc-123']) {
        expect(isValidTrPlate(p), isTrue, reason: p);
      }
    });
    test('geçersiz biçimler', () {
      for (final p in ['', '00 ABC 123', '82 ABC 123', '34 A 123', '34 A 123456', '34 ABCD 123', '34 ABC 1', '34 ABC 1234', 'ABC 123', '34 123 ABC', '34 ÇAB 123']) {
        expect(isValidTrPlate(p), isFalse, reason: p);
      }
    });
    test('Türkçe i/ı/İ I olur', () {
      expect(cleanPlate('34 ıİi 123'), '34III123');
      expect(isValidTrPlate('34 iıİ 123'), isTrue);
    });
  });

  group('plateHint', () {
    test('geçerliyse null', () => expect(plateHint('34 ABC 123'), isNull));
    test('boş', () => expect(plateHint(''), contains('Örnek')));
    test('il kodu', () {
      expect(plateHint('00 ABC 123'), contains('01-81'));
      expect(plateHint('82 ABC 123'), contains('01-81'));
      expect(plateHint('AB 123'), contains('01-81'));
    });
    test('eksik/biçim', () => expect(plateHint('34 ABC'), contains('34 ABC 123')));
    test('geçersiz karakter', () => expect(plateHint('34 ÇAB 123'), contains('A-Z')));
  });

  group('extractPlate', () {
    test('düz metin', () => expect(extractPlate('34 ABC 123'), '34ABC123'));
    test('5 rakamlı', () => expect(extractPlate('34 A 12345'), '34A12345'));
    test('tüm biçimler', () {
      expect(extractPlate('06 AB 1234'), '06AB1234');
      expect(extractPlate('35 ABC 12'), '35ABC12');
    });
    test('kare plaka', () => expect(extractPlate('34\nABC\n123'), '34ABC123'));
    test('fazla yazı ve noktalama', () {
      expect(extractPlate('TR 34 ABC 123'), '34ABC123');
      expect(extractPlate('TR\n34 - ABC . 123\nxx'), '34ABC123');
    });
    test('konuma göre düzeltme', () {
      expect(extractPlate('34 ABC l23'), '34ABC123');
      expect(extractPlate('O6 AB 1234'), '06AB1234');
      expect(extractPlate('34 A8C 123'), '34ABC123');
      expect(extractPlate('34 ABC 1S5'), '34ABC155');
      expect(extractPlate('3L ABC 123'), '31ABC123');
    });
    test('düzeltmesiz aday önceliklidir', () {
      expect(extractPlate('34 AB 123 Z'), '34AB123');
    });
    test('Türkçe i', () => expect(extractPlate('34 iıİ 123'), '34III123'));
    test('plaka yok', () {
      expect(extractPlate(''), isNull);
      expect(extractPlate('MERHABA DÜNYA'), isNull);
      expect(extractPlate('99 ABC 123'), isNull);
      expect(extractPlate('12345'), isNull);
    });
  });
}
