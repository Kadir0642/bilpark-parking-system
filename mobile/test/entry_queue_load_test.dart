// Yük altında nazik kuyruk boşaltma: tek tek gönderim, jitter, üstel bekleme, Retry-After, kayıt kaybı yok.
import 'dart:math';
import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/constants.dart';
import 'package:bilpark/core/entry_queue.dart';

PendingEntry _e(String plate, {String? id}) => PendingEntry(
      requestId: id ?? newRequestId(),
      plate: plate,
      street: 'TEVFIK_BEY',
      type: 'SMALL',
      side: 'LEFT',
    );

List<PendingEntry> _items(int n) => [for (var i = 0; i < n; i++) _e('34AAA${100 + i}', id: 'rid$i')];

Matcher durBetween(Duration lo, Duration hi) => allOf(greaterThanOrEqualTo(lo), lessThanOrEqualTo(hi));

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    AppConstants.clearAuth();
  });

  group('tek tek gönderim', () {
    test('aynı anda en çok bir istek uçuşta; kayıtlar arasında 0,5-1,5 x pacing beklenir', () {
      fakeAsync((async) {
        var inFlight = 0;
        var maxInFlight = 0;
        final starts = <Duration>[];
        final q = EntryQueue(
          rng: Random(1),
          pacing: const Duration(seconds: 1),
          send: (query) async {
            starts.add(async.elapsed);
            inFlight++;
            maxInFlight = max(maxInFlight, inFlight);
            await Future<void>.delayed(const Duration(milliseconds: 50)); // sunucu süresi
            inFlight--;
            return null;
          },
        );
        q.items.value = _items(5);
        q.flush();
        async.elapse(const Duration(seconds: 30));

        expect(maxInFlight, 1);
        expect(starts, hasLength(5));
        expect(q.items.value, isEmpty);
        for (var i = 1; i < starts.length; i++) {
          final gap = starts[i] - starts[i - 1] - const Duration(milliseconds: 50);
          expect(gap, durBetween(const Duration(milliseconds: 500), const Duration(milliseconds: 1500)));
        }
      });
    });

    test('her denemede aynı checkInRequestId gider (idempotency korunur)', () {
      fakeAsync((async) {
        final seen = <String>[];
        var calls = 0;
        final q = EntryQueue(
          rng: Random(2),
          send: (query) async {
            seen.add(query['checkInRequestId']!);
            if (++calls < 4) throw const ApiException(503, 'SERVER_BUSY', 'yoğun');
            return null;
          },
        );
        q.items.value = [_e('34BBB001', id: 'sabit-id')];
        q.flush();
        async.elapse(const Duration(minutes: 5));
        expect(seen, ['sabit-id', 'sabit-id', 'sabit-id', 'sabit-id']);
        expect(q.items.value, isEmpty);
      });
    });
  });

  group('hata ve yeniden deneme', () {
    test('503: kayıtlar KALIR, gönderim durur (2. kayıt denenmez), üstel bekleme + jitter ile yeniden denenir', () {
      fakeAsync((async) {
        final sendTimes = <Duration>[];
        final q = EntryQueue(
          rng: Random(3),
          retryBase: const Duration(seconds: 2),
          retryCap: const Duration(seconds: 60),
          send: (query) async {
            sendTimes.add(async.elapsed);
            throw const ApiException(503, 'SERVER_BUSY', 'yoğun');
          },
        );
        q.items.value = _items(3);
        q.flush();
        async.elapse(const Duration(minutes: 10));

        // Her turda yalnızca İLK kayıt denenir (kuyruk sırası, sunucuya yığılma yok)
        expect(q.items.value, hasLength(3)); // hiçbir kayıt düşmedi
        expect(sendTimes.length, greaterThan(5));
        // Beklemeler üstel artar ve tavanı (60 sn) aşmaz; her bekleme taban*2^(n-1)'in yarısı ile kendisi arasında
        for (var i = 1; i < sendTimes.length; i++) {
          final wait = sendTimes[i] - sendTimes[i - 1];
          final n = i; // i. başarısızlıktan sonraki bekleme
          final upper = min(2000 * (1 << (n - 1)), 60000);
          expect(wait.inMilliseconds, inInclusiveRange(upper ~/ 2, upper), reason: 'bekleme $n');
        }
        // sonunda tavana yaklaşıldı
        expect(sendTimes.last - sendTimes[sendTimes.length - 2], greaterThanOrEqualTo(const Duration(seconds: 30)));
        q.cancelScheduled();
      });
    });

    test('503 Retry-After: bekleme Retry-After süresinden kısa olmaz', () {
      fakeAsync((async) {
        final sendTimes = <Duration>[];
        var calls = 0;
        final q = EntryQueue(
          rng: Random(4),
          send: (query) async {
            sendTimes.add(async.elapsed);
            if (++calls == 1) throw const ApiException(503, 'SERVER_BUSY', 'yoğun', retryAfter: Duration(seconds: 25));
            return null;
          },
        );
        q.items.value = _items(1);
        q.flush();
        async.elapse(const Duration(seconds: 24));
        expect(sendTimes, hasLength(1)); // 24 sn'de yeniden denenmedi
        expect(q.lastRetryDelay, greaterThanOrEqualTo(const Duration(seconds: 25)));
        async.elapse(const Duration(seconds: 2));
        expect(sendTimes, hasLength(2));
        expect(q.items.value, isEmpty);
      });
    });

    test('429 geçici hatadır: kayıt düşmez, Retry-After beklenir', () {
      fakeAsync((async) {
        var calls = 0;
        final q = EntryQueue(
          rng: Random(5),
          send: (query) async {
            if (++calls == 1) throw const ApiException(429, 'RATE_LIMITED', 'çok istek', retryAfter: Duration(seconds: 10));
            return null;
          },
        );
        q.items.value = _items(1);
        q.flush();
        async.elapse(const Duration(seconds: 9));
        expect(calls, 1);
        expect(q.items.value, hasLength(1));
        async.elapse(const Duration(seconds: 2));
        expect(calls, 2);
        expect(q.items.value, isEmpty);
      });
    });

    test('ağ hatası ve 500 de kaydı düşürmez; başarıdan sonra sayaç sıfırlanır', () {
      fakeAsync((async) {
        var calls = 0;
        final q = EntryQueue(
          rng: Random(6),
          send: (query) async {
            calls++;
            if (calls == 1) throw const ApiException(null, 'NETWORK', 'ağ yok');
            if (calls == 2) throw const ApiException(500, null, 'hata');
            return null;
          },
        );
        q.items.value = _items(1);
        q.flush();
        async.elapse(const Duration(seconds: 1));
        expect(q.failStreak, 1);
        async.elapse(const Duration(minutes: 2));
        expect(calls, 3);
        expect(q.items.value, isEmpty);
        expect(q.failStreak, 0);
        expect(q.hasScheduledFlush, isFalse);
      });
    });

    test('kalıcı red (400) kaydı kuyruktan çıkarır ve yeniden denemez; 401 yeniden deneme zamanlamaz', () {
      fakeAsync((async) {
        final results = <EntryResultKind>[];
        var calls = 0;
        final q = EntryQueue(
          rng: Random(7),
          pacing: Duration.zero,
          send: (query) async {
            calls++;
            if (query['plate']!.endsWith('100')) throw const ApiException(400, 'INVALID_PLATE', 'geçersiz');
            throw const ApiException(401, 'UNAUTHORIZED', 'oturum');
          },
        );
        q.results.listen((r) => results.add(r.kind));
        q.items.value = _items(2);
        q.flush();
        async.elapse(const Duration(minutes: 1));
        expect(results, [EntryResultKind.rejected]);
        expect(calls, 2); // 400 + 401
        expect(q.items.value, hasLength(1)); // 401: kayıt kaldı
        expect(q.hasScheduledFlush, isFalse); // 401'de otomatik tekrar yok
      });
    });
  });

  group('bağlantı dönünce rastgele başlangıç gecikmesi (jitter)', () {
    test('flushLater 0-20 sn arasında rastgele bir anda başlar; o zamana dek hiçbir şey gönderilmez', () {
      fakeAsync((async) {
        final delays = <int>[];
        for (var seed = 0; seed < 30; seed++) {
          var sentAt = -1;
          final q = EntryQueue(
            rng: Random(seed),
            send: (query) async {
              sentAt = async.elapsed.inMilliseconds;
              return null;
            },
          );
          final base = async.elapsed.inMilliseconds;
          q.items.value = _items(1);
          q.flushLater();
          expect(sentAt, -1);
          async.elapse(const Duration(seconds: 21));
          expect(sentAt, isNot(-1));
          delays.add(sentAt - base);
          expect(sentAt - base, inInclusiveRange(0, 20000));
        }
        // dağılım gerçek: telefonlar aynı anda başlamaz
        expect(delays.toSet().length, greaterThan(20));
        expect(delays.reduce(max), greaterThan(10000));
        expect(delays.reduce(min), lessThan(10000));
      });
    });

    test('kuyruk boşsa zamanlayıcı kurulmaz; zaten zamanlanmışsa ikinci çağrı bozmaz', () {
      fakeAsync((async) {
        var sends = 0;
        final q = EntryQueue(rng: Random(8), send: (query) async => sends++);
        q.flushLater();
        expect(q.hasScheduledFlush, isFalse);
        q.items.value = _items(1);
        q.flushLater();
        q.flushLater();
        q.flushLater();
        async.elapse(const Duration(seconds: 25));
        expect(sends, 1);
      });
    });

    test('hata beklemesi sürerken flushLater onu kısaltmaz (backoff korunur)', () {
      fakeAsync((async) {
        var sends = 0;
        final q = EntryQueue(
          rng: Random(9),
          send: (query) async {
            sends++;
            throw const ApiException(503, 'SERVER_BUSY', 'yoğun', retryAfter: Duration(seconds: 40));
          },
        );
        q.items.value = _items(1);
        q.flush();
        async.elapse(const Duration(seconds: 1));
        expect(sends, 1);
        q.flushLater(maxJitter: Duration.zero); // her "çevrimiçi" olayında çağrılır
        async.elapse(const Duration(seconds: 30));
        expect(sends, 1); // Retry-After (40 sn) dolmadan gönderilmedi
        q.cancelScheduled();
      });
    });

    test('100 telefon aynı anda döndü: ilk saniyede istek yığılması yok (jitter dağıtır)', () {
      fakeAsync((async) {
        final perSecond = <int, int>{};
        final queues = <EntryQueue>[];
        for (var i = 0; i < 100; i++) {
          final q = EntryQueue(
            rng: Random(1000 + i),
            send: (query) async {
              perSecond.update(async.elapsed.inSeconds, (v) => v + 1, ifAbsent: () => 1);
              return null;
            },
          );
          q.items.value = _items(1);
          queues.add(q);
          q.flushLater();
        }
        async.elapse(const Duration(seconds: 21));
        final total = perSecond.values.fold(0, (a, b) => a + b);
        expect(total, 100);
        // 20 saniyeye yayıldı: hiçbir saniyede 100'ün çoğu birden gelmez (rastgele dağılımda ~5 beklenir)
        expect(perSecond.values.reduce(max), lessThan(20));
      });
    });
  });
}
