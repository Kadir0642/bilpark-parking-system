// Canlı olay (SSE) yağmuru altında ana kabuk: olaylar yerelde uygulanır, sunucudan okuma birleştirilir,
// yalnızca görünen sekme için ve yalnızca uygulama öndeyken yapılır.
import 'dart:async';
import 'dart:convert';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/constants.dart';
import 'package:bilpark/core/entry_queue.dart';
import 'package:bilpark/core/live_sync.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/main.dart';
import 'package:bilpark/screens/parking_map_screen.dart';

SyncEvent _entered(int v) => SyncEvent(
      version: v,
      type: 'VEHICLE_ENTERED',
      scope: 'ZONE',
      street: 'TEVFIK_BEY',
      payload: {
        'plate': '34AAA${100 + v}',
        'vehicleType': 'SMALL',
        'street': 'TEVFIK_BEY',
        'side': 'LEFT',
        'entryTime': '2026-11-26T10:05:00',
        'hasSubscription': false,
      },
    );

SyncEvent _exited(int v, int plateNo) => SyncEvent(
      version: v,
      type: 'VEHICLE_EXITED',
      scope: 'ZONE',
      street: 'TEVFIK_BEY',
      payload: {'plate': '34AAA$plateNo'},
    );

Map<String, dynamic> _spot(int n) => {
      'id': n,
      'currentPlate': '34AAA$n',
      'currentType': 'SMALL',
      'entryTime': '2026-11-26T10:05:00',
      'side': 'LEFT',
      'hasSubscription': false,
    };

void main() {
  late List<String> paths;
  List<Map<String, dynamic>> serverSpots = [];
  Completer<http.Response>? hold; // bir sonraki /parking/filter yanıtını bekletir

  int count(String p) => paths.where((x) => x == p).length;

  Future<void> boot(WidgetTester tester, Future<void> Function() body) async {
    tester.view.physicalSize = const Size(720, 1600);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    SharedPreferences.setMockInitialValues({});
    ApiClient.resetCache();
    AppConstants.clearAuth();
    entryQueue.items.value = [];
    paths = [];
    serverSpots = [];
    hold = null;
    final prev = FlutterError.onError;
    FlutterError.onError = (d) {
      if (!d.exceptionAsString().contains('overflowed')) prev?.call(d);
    };
    addTearDown(() => FlutterError.onError = prev);

    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(
        theme: BilParkTheme.light(),
        home: const MainContainer(streetCode: 'TEVFIK_BEY', streetName: 'Tevfik Bey Caddesi'),
      ));
      await tester.pump(const Duration(seconds: 7)); // açılış istekleri ve "yeni açıldı" penceresi geçsin
      paths.clear();
      await body();
      // sahneyi sökmeden önce zamanlayıcılar durdurulur
      await tester.pumpWidget(const SizedBox.shrink());
      await liveSync.stop();
      entryQueue.cancelScheduled();
      await tester.pump(const Duration(seconds: 3));
    }, () => MockClient((req) async {
          paths.add(req.url.path.replaceFirst('/api', '')); // sahte sunucu: yalnızca yol sayılır
          if (req.url.path.endsWith('/parking/filter') && hold != null) {
            final h = hold!;
            hold = null;
            return h.future;
          }
          final list = req.url.path.endsWith('/parking/filter') || req.url.path.endsWith('/parking/history');
          final body = req.url.path.endsWith('/parking/filter') ? jsonEncode(serverSpots) : (list ? '[]' : '{}');
          return http.Response(body, 200, headers: {'content-type': 'application/json; charset=utf-8'});
        }));
  }

  testWidgets('30 giriş olayı: liste yerelde güncellenir (ağ yok), sunucu okuması birleşir, geçmiş/araç tipi çekilmez', (tester) async {
    await boot(tester, () async {
      final map = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen, skipOffstage: false));
      var v = 1;
      for (var i = 0; i < 3; i++) {
        liveSync.debugDeliver(_entered(v++));
        await tester.pump(const Duration(milliseconds: 100));
      }
      // 0,3 sn: birleştirme gecikmesi dolmadı, ama liste olay yükünden zaten güncel
      expect(map.activeVehicles, hasLength(3));
      expect(count('/parking/filter'), 0);

      for (var i = 0; i < 27; i++) {
        liveSync.debugDeliver(_entered(v++));
        await tester.pump(const Duration(milliseconds: 100));
      }
      await tester.pump(const Duration(seconds: 6));

      // Eski davranış: 30 olay x (filtre x2 + geçmiş + araç tipi) = 120+ istek. Şimdi: ~3 sn'de en çok 2 pencere.
      expect(count('/parking/filter'), inInclusiveRange(1, 3));
      expect(count('/parking/history'), 0); // giriş olayı vardiya özetini değiştirmez
      expect(count('/vehicle-types'), 0); // araç tipleri olayla yenilenmez
    });
  });

  testWidgets('çıkış olayları: araç yerelde silinir; yalnızca özet bir kez çekilir, araç listesi çekilmez', (tester) async {
    await boot(tester, () async {
      final map = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen, skipOffstage: false));
      serverSpots = [for (var i = 1; i <= 5; i++) _spot(100 + i)];
      await map.fetchActiveVehicles();
      await tester.pump(const Duration(seconds: 1));
      expect(map.activeVehicles, hasLength(5));
      paths.clear();

      var v = 1;
      for (var i = 1; i <= 3; i++) {
        liveSync.debugDeliver(_exited(v++, 100 + i));
        await tester.pump(const Duration(milliseconds: 50));
      }
      // sunucuya sormadan araçlar listeden düştü
      expect(map.activeVehicles.map((s) => s['currentPlate']), ['34AAA104', '34AAA105']);
      await tester.pump(const Duration(seconds: 6));
      expect(count('/parking/filter'), 0);
      expect(count('/parking/history'), 1); // vardiya özeti tek istek
    });
  });

  testWidgets('arka planda hiç istek yok; öne gelince birikenler tek yenilemeyle yetişir', (tester) async {
    await boot(tester, () async {
      for (final s in [AppLifecycleState.inactive, AppLifecycleState.hidden, AppLifecycleState.paused]) {
        tester.binding.handleAppLifecycleStateChanged(s);
      }
      var v = 1;
      for (var i = 0; i < 40; i++) {
        liveSync.debugDeliver(_entered(v++));
        liveSync.debugDeliver(_exited(v++, 100 + i));
        await tester.pump(const Duration(milliseconds: 250));
      }
      await tester.pump(const Duration(seconds: 10));
      expect(count('/parking/filter'), 0);
      expect(count('/parking/history'), 0);

      for (final s in [AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]) {
        tester.binding.handleAppLifecycleStateChanged(s);
      }
      await tester.pump(const Duration(seconds: 6));
      expect(count('/parking/filter'), inInclusiveRange(1, 2));
      expect(count('/parking/history'), inInclusiveRange(1, 2));
    });
  });

  testWidgets('görünmeyen sekme için istek yok: Geçmiş sekmesindeyken giriş olayları liste çekmez; ana sayfaya dönünce bir kez çeker', (tester) async {
    await boot(tester, () async {
      await tester.tap(find.text('Geçmiş'));
      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(seconds: 1)); // sekmenin ilk açılış isteği de tamamlansın
      paths.clear();

      var v = 1;
      for (var i = 0; i < 20; i++) {
        liveSync.debugDeliver(_entered(v++));
        await tester.pump(const Duration(milliseconds: 200));
      }
      await tester.pump(const Duration(seconds: 6));
      expect(count('/parking/filter'), 0);
      expect(count('/parking/history'), 0);

      await tester.tap(find.text('Ana sayfa'));
      await tester.pump(const Duration(seconds: 1));
      expect(count('/parking/filter'), 1);
    });
  });

  testWidgets('atlanan yenileme kaybolmaz: uçuştaki çekme varken gelen giriş olayı için bir kez daha zamanlanır', (tester) async {
    await boot(tester, () async {
      await tester.tap(find.text('Cadde'));
      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(seconds: 3));
      final map = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen, skipOffstage: false));
      paths.clear();

      // Kendi işlemimizin çekmesi uçuşta (yanıt bekliyor)
      final gate = Completer<http.Response>();
      hold = gate;
      final inflight = map.fetchActiveVehicles();
      await tester.pump(const Duration(milliseconds: 50));
      expect(count('/parking/filter'), 1);

      // Olay geldi: birleştirici ~0,4 sn sonra çalışır ama minAge (2 sn) yüzünden istek atlanır
      liveSync.debugDeliver(_entered(1));
      await tester.pump(const Duration(milliseconds: 700));
      expect(count('/parking/filter'), 1);

      // Uçuştaki çekme (olaydan ÖNCEki görüntü) döner; olayın yenilemesi kaybolmamış olmalı
      gate.complete(http.Response('[]', 200, headers: {'content-type': 'application/json; charset=utf-8'}));
      await inflight;
      // minAge gerçek saatle ölçülür (DateTime.now sahte saatle ilerlemez): 2 sn gerçek süre geçsin
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 2100)));
      await tester.pump(const Duration(seconds: 6));
      expect(count('/parking/filter'), 2);
    });
  });

  testWidgets('60 sn boyunca saniyede 2 olay (120 olay): sunucu okuması pencere başına bir', (tester) async {
    await boot(tester, () async {
      var v = 1;
      for (var i = 0; i < 120; i++) {
        liveSync.debugDeliver(_entered(v++));
        await tester.pump(const Duration(milliseconds: 500));
      }
      await tester.pump(const Duration(seconds: 4));
      expect(count('/parking/filter'), lessThanOrEqualTo(26)); // 60 sn / 2,5 sn
      expect(count('/parking/filter'), greaterThan(10));
    });
  });
}
