import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/live_sync.dart';
import 'package:bilpark/core/refresh_coalescer.dart';
import 'package:bilpark/core/spot_events.dart';

SyncEvent _ev(String type, Map<String, dynamic> payload, {int version = 1}) =>
    SyncEvent(version: version, type: type, scope: 'ZONE', zoneId: 1, street: 'TEVFIK_BEY', payload: payload);

void main() {
  group('RefreshCoalescer (olay birleştirme)', () {
    test('art arda gelen 50 istek tek çalıştırmaya iner (lead sonrası)', () {
      fakeAsync((async) {
        var runs = 0;
        final c = RefreshCoalescer(action: () => runs++, lead: const Duration(milliseconds: 400), window: const Duration(milliseconds: 2500));
        for (var i = 0; i < 50; i++) {
          c.request();
          async.elapse(const Duration(milliseconds: 5));
        }
        expect(runs, 0); // lead dolmadı
        async.elapse(const Duration(milliseconds: 400));
        expect(runs, 1);
        async.elapse(const Duration(seconds: 10));
        expect(runs, 1); // yeni istek yok: tekrar çalışmaz
        c.dispose();
      });
    });

    test('pencere içinde gelen istekler pencere bitince EN ÇOK BİR kez daha çalışır', () {
      fakeAsync((async) {
        var runs = 0;
        final c = RefreshCoalescer(action: () => runs++);
        c.request();
        async.elapse(const Duration(milliseconds: 400));
        expect(runs, 1);
        // 2,5 sn'lik pencerede 100 olay
        for (var i = 0; i < 100; i++) {
          c.request();
          async.elapse(const Duration(milliseconds: 20));
        }
        expect(runs, 1); // 2 sn geçti, hâlâ pencere içinde
        async.elapse(const Duration(milliseconds: 600)); // pencere bitti
        expect(runs, 2);
        async.elapse(const Duration(seconds: 30));
        expect(runs, 2);
        c.dispose();
      });
    });

    test('sürekli olay akışında saniyede değil, pencere başına bir çalıştırma (60 sn / 2,5 sn)', () {
      fakeAsync((async) {
        var runs = 0;
        final c = RefreshCoalescer(action: () => runs++);
        // 60 sn boyunca her 100 ms'de bir olay = 600 olay
        for (var i = 0; i < 600; i++) {
          c.request();
          async.elapse(const Duration(milliseconds: 100));
        }
        expect(runs, lessThanOrEqualTo(60 ~/ 2 + 1)); // en çok ~24
        expect(runs, greaterThan(10));
        c.dispose();
      });
    });

    test('boştayken yeni istek yine lead sonra çalışır', () {
      fakeAsync((async) {
        var runs = 0;
        final c = RefreshCoalescer(action: () => runs++);
        c.request();
        async.elapse(const Duration(seconds: 5));
        expect(runs, 1);
        c.request();
        async.elapse(const Duration(milliseconds: 399));
        expect(runs, 1);
        async.elapse(const Duration(milliseconds: 2));
        expect(runs, 2);
        c.dispose();
      });
    });

    test('arka plandayken (canRun false) çalışmaz; istek bekler, öne gelince (resume) tek kez çalışır', () {
      fakeAsync((async) {
        var runs = 0;
        var fg = false;
        final c = RefreshCoalescer(action: () => runs++, canRun: () => fg);
        for (var i = 0; i < 20; i++) {
          c.request();
          async.elapse(const Duration(seconds: 1));
        }
        expect(runs, 0);
        expect(c.pending, isTrue);
        fg = true;
        c.resume();
        async.elapse(const Duration(milliseconds: 500));
        expect(runs, 1);
        expect(c.pending, isFalse);
        c.dispose();
      });
    });

    test('bekleyen istek yoksa resume bir şey yapmaz', () {
      fakeAsync((async) {
        var runs = 0;
        final c = RefreshCoalescer(action: () => runs++);
        c.resume();
        async.elapse(const Duration(seconds: 10));
        expect(runs, 0);
        c.dispose();
      });
    });

    test('dispose bekleyen zamanlayıcıları iptal eder; sonraki istek yok sayılır', () {
      fakeAsync((async) {
        var runs = 0;
        final c = RefreshCoalescer(action: () => runs++);
        c.request();
        c.dispose();
        c.request();
        async.elapse(const Duration(seconds: 10));
        expect(runs, 0);
        expect(async.pendingTimers, isEmpty);
      });
    });

    test('eylem hata atsa da zamanlayıcı bozulmaz; sonraki istek çalışır', () {
      fakeAsync((async) {
        var runs = 0;
        final c = RefreshCoalescer(action: () {
          runs++;
          throw StateError('boom');
        });
        c.request();
        async.elapse(const Duration(seconds: 3));
        c.request();
        async.elapse(const Duration(seconds: 3));
        expect(runs, 2);
        c.dispose();
      });
    });
  });

  group('olayı yerelde uygulama (ağ çağrısı yok)', () {
    final entered = {
      'plate': '34ABC123',
      'vehicleType': 'SMALL',
      'zoneId': 1,
      'street': 'TEVFIK_BEY',
      'side': 'LEFT',
      'entryTime': '2026-11-26T10:05:00',
      'hasSubscription': true,
      'spotId': 7,
    };

    test('VEHICLE_ENTERED yükten liste satırı ekler', () {
      final out = applyVehicleEvent(const [], _ev('VEHICLE_ENTERED', entered));
      expect(out, hasLength(1));
      final s = out.single as Map;
      expect(s['currentPlate'], '34ABC123');
      expect(s['currentType'], 'SMALL');
      expect(s['side'], 'LEFT');
      expect(s['hasSubscription'], true);
      expect(s['zone'], {'id': 1});
      expect(s['id'], 7);
    });

    test('aynı plaka zaten listedeyse aynı liste örneği döner (yinelenen olay)', () {
      final list = <dynamic>[
        {'currentPlate': '34ABC123', 'zone': {'id': 1}}
      ];
      expect(identical(applyVehicleEvent(list, _ev('VEHICLE_ENTERED', entered)), list), isTrue);
    });

    test('VEHICLE_EXITED ve VEHICLE_RUNAWAY aracı plakadan siler, diğerlerine dokunmaz', () {
      final list = <dynamic>[
        {'currentPlate': '34ABC123'},
        {'currentPlate': '06XYZ789'},
      ];
      final afterExit = applyVehicleEvent(list, _ev('VEHICLE_EXITED', {'plate': '34ABC123'}));
      expect(afterExit.map((e) => e['currentPlate']), ['06XYZ789']);
      final afterRun = applyVehicleEvent(list, _ev('VEHICLE_RUNAWAY', {'plate': '06XYZ789'}));
      expect(afterRun.map((e) => e['currentPlate']), ['34ABC123']);
      // listede olmayan plaka: değişiklik yok
      expect(identical(applyVehicleEvent(list, _ev('VEHICLE_EXITED', {'plate': '99ZZZ999'})), list), isTrue);
    });

    test('eksik yük (plaka ya da giriş saati yok) listeyi değiştirmez', () {
      final list = <dynamic>[];
      expect(identical(applyVehicleEvent(list, _ev('VEHICLE_ENTERED', {'plate': '34ABC123'})), list), isTrue);
      expect(identical(applyVehicleEvent(list, _ev('VEHICLE_EXITED', const {})), list), isTrue);
      expect(identical(applyVehicleEvent(list, _ev('TARIFF_UPDATED', {'plate': 'X'})), list), isTrue);
    });

    test('hangi ekran verisinin eskidiği: çıkış/kaçak liste çekmez, giriş listeyi tazeler', () {
      expect(targetsForEvent('VEHICLE_ENTERED'), {RefreshTarget.dashSpots, RefreshTarget.map});
      expect(targetsForEvent('VEHICLE_EXITED'), {RefreshTarget.dashSummary, RefreshTarget.history});
      expect(targetsForEvent('VEHICLE_RUNAWAY'), {RefreshTarget.dashSummary, RefreshTarget.runaways});
      expect(targetsForEvent('VEHICLE_EXITED'), isNot(contains(RefreshTarget.map)));
      expect(targetsForEvent('LICENSE_UPDATED'), isEmpty);
    });
  });
}
