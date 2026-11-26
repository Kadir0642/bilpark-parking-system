import 'dart:math';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/backoff.dart';
import 'package:bilpark/core/live_sync.dart';

Matcher durBetween(Duration lo, Duration hi) => allOf(greaterThanOrEqualTo(lo), lessThanOrEqualTo(hi));

void main() {
  group('backoffDelay', () {
    test('üstel artar, her denemede üst sınır (base*2^(n-1)) ve alt sınır (yarısı) içinde kalır', () {
      for (var attempt = 1; attempt <= 6; attempt++) {
        final expected = 1000 * (1 << (attempt - 1)); // ms, cap 45 sn'nin altında
        for (var seed = 0; seed < 50; seed++) {
          final d = backoffDelay(attempt, rng: Random(seed));
          expect(d.inMilliseconds, inInclusiveRange(expected ~/ 2, expected), reason: 'deneme $attempt seed $seed');
        }
      }
    });

    test('tavan aşılmaz: 45 sn (ve çok büyük deneme sayısı taşmaz)', () {
      for (final attempt in [7, 10, 30, 1000]) {
        for (var seed = 0; seed < 50; seed++) {
          final d = backoffDelay(attempt, rng: Random(seed));
          expect(d, lessThanOrEqualTo(const Duration(seconds: 45)));
          expect(d, greaterThanOrEqualTo(const Duration(milliseconds: 22500)));
        }
      }
    });

    test('jitter gerçekten yayar: farklı tohumlar farklı süreler üretir (aynı anda kopan telefonlar dağılır)', () {
      final set = {for (var s = 0; s < 40; s++) backoffDelay(4, rng: Random(s)).inMilliseconds};
      expect(set.length, greaterThan(20));
    });

    test('0 ve negatif deneme 1 sayılır', () {
      expect(backoffDelay(0, rng: Random(1)), backoffDelay(1, rng: Random(1)));
      expect(backoffDelay(-5, rng: Random(1)), backoffDelay(1, rng: Random(1)));
    });

    test('canlı akış yeniden bağlanma beklemesi 45 sn üstüne çıkmaz', () {
      for (var a = 1; a <= 40; a++) {
        expect(defaultReconnectBackoff(a, Random(a)), lessThanOrEqualTo(const Duration(seconds: 45)));
      }
      // 1. deneme en fazla 1 sn
      expect(defaultReconnectBackoff(1, Random(3)), lessThanOrEqualTo(const Duration(seconds: 1)));
    });
  });

  group('randomJitter', () {
    test('0 ile üst sınır arasında; sınır sıfırsa sıfır', () {
      for (var s = 0; s < 50; s++) {
        final d = randomJitter(const Duration(seconds: 20), Random(s));
        expect(d, durBetween(Duration.zero, const Duration(seconds: 20)));
      }
      expect(randomJitter(Duration.zero), Duration.zero);
    });
  });

  group('parseRetryAfter', () {
    test('saniye sayısı', () {
      expect(parseRetryAfter('7'), const Duration(seconds: 7));
      expect(parseRetryAfter(' 30 '), const Duration(seconds: 30));
      expect(parseRetryAfter('0'), Duration.zero);
    });

    test('HTTP tarihi: şimdiye göre fark', () {
      final now = DateTime.utc(2026, 11, 26, 12, 0, 0);
      expect(parseRetryAfter('Thu, 26 Nov 2026 12:00:45 GMT', now: now), const Duration(seconds: 45));
      expect(parseRetryAfter('Thu, 26 Nov 2026 11:00:00 GMT', now: now), Duration.zero); // geçmiş
    });

    test('geçersiz / boş / negatif null', () {
      expect(parseRetryAfter(null), isNull);
      expect(parseRetryAfter(''), isNull);
      expect(parseRetryAfter('yakında'), isNull);
      expect(parseRetryAfter('-5'), isNull);
      expect(parseRetryAfter('Thu, 99 Foo 2026 12:00:45 GMT'), isNull);
    });

    test('üst sınır uygulanır', () {
      expect(parseRetryAfter('3600', cap: const Duration(seconds: 20)), const Duration(seconds: 20));
      expect(parseRetryAfter('5', cap: const Duration(seconds: 20)), const Duration(seconds: 5));
    });
  });
}
