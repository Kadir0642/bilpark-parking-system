// Yarışlar: uçuştaki liste çekmesi ile canlı olay, atlanan yenileme, doğrudan girişte 503 beklemesi.
// Sahte API tamamlayıcılarla (Completer) kontrol edilir; sonuçlar zamana değil sıraya bağlıdır.
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
import 'package:bilpark/core/spot_events.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/dashboard_screen.dart';
import 'package:bilpark/screens/parking_map_screen.dart';

const _json = {'content-type': 'application/json; charset=utf-8'};

Map<String, dynamic> _spot(String plate) => {
      'currentPlate': plate,
      'currentType': 'SMALL',
      'entryTime': '2026-11-26T10:05:00',
      'side': 'LEFT',
      'hasSubscription': false,
    };

SyncEvent _exited(int v, String plate) => SyncEvent(
    version: v, type: 'VEHICLE_EXITED', scope: 'ZONE', street: 'TEVFIK_BEY', payload: {'plate': plate});

SyncEvent _entered(int v, String plate) => SyncEvent(
    version: v,
    type: 'VEHICLE_ENTERED',
    scope: 'ZONE',
    street: 'TEVFIK_BEY',
    payload: {
      'plate': plate,
      'vehicleType': 'SMALL',
      'street': 'TEVFIK_BEY',
      'side': 'RIGHT',
      'entryTime': '2026-11-26T10:06:00',
      'hasSubscription': false,
    });

/// /parking/filter yanıtı [hold] ile bekletilebilir (yalnızca bir sonraki istek için).
class _Api {
  Completer<http.Response>? hold;
  List<Map<String, dynamic>> spots = [];
  int filterCalls = 0;
  final posts = <http.Request>[];
  http.Response Function(http.Request req)? onCheckIn;

  http.Client client() => MockClient((req) async {
        if (req.url.path.endsWith('/parking/filter')) {
          filterCalls++;
          final h = hold;
          if (h != null) {
            hold = null;
            return h.future;
          }
          return http.Response(jsonEncode(spots), 200, headers: _json);
        }
        if (req.url.path.endsWith('/parking/check-in')) {
          posts.add(req);
          return onCheckIn!(req);
        }
        final list = req.url.path.endsWith('/parking/history');
        return http.Response(list ? '[]' : '{}', 200, headers: _json);
      });
}

void _setup(WidgetTester tester) {
  tester.view.physicalSize = const Size(720, 1600);
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.reset);
  SharedPreferences.setMockInitialValues({});
  ApiClient.resetCache();
  AppConstants.clearAuth();
  entryQueue.items.value = [];
  entryQueue.cancelScheduled();
  liveSync.status.value = LiveStatus.connecting;
  final prev = FlutterError.onError;
  FlutterError.onError = (d) {
    if (!d.exceptionAsString().contains('overflowed')) prev?.call(d);
  };
  addTearDown(() => FlutterError.onError = prev);
}

Future<void> _teardown(WidgetTester tester) async {
  entryQueue.cancelScheduled();
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(seconds: 15));
  entryQueue.cancelScheduled();
  entryQueue.items.value = [];
}

Widget _map() => MaterialApp(
      theme: BilParkTheme.light(),
      home: const Scaffold(body: ParkingMapScreen(streetCode: 'TEVFIK_BEY', streetName: 'Tevfik Bey Caddesi')),
    );

void main() {
  group('SpotFetchGuard', () {
    test('çekme sırasında gelen çıkış yanıta yeniden uygulanır; giriş de eklenir; sonraki çekme etkilenmez', () {
      final g = SpotFetchGuard();
      final a = g.begin();
      g.record(_exited(1, '34AAA111'));
      g.record(_entered(2, '34ZZZ999'));
      final out = g.finish(a, [_spot('34AAA111'), _spot('34BBB222')]);
      expect(out.map((s) => s['currentPlate']), ['34BBB222', '34ZZZ999']);

      // Yeni çekme eski olayları görmez
      final b = g.begin();
      expect(g.finish(b, [_spot('34AAA111')]).map((s) => s['currentPlate']), ['34AAA111']);
    });

    test('iptal edilen çekmenin kayıtları atılır (birikme yok)', () {
      final g = SpotFetchGuard();
      final a = g.begin();
      g.record(_exited(1, 'X'));
      g.cancel(a);
      final resp = [_spot('X')];
      expect(identical(g.finish(a, resp), resp), isTrue);
    });
  });

  testWidgets('harita: uçuştaki GET sonradan gelen çıkışı ezmez (çıkan araç canlanmaz), giriş korunur', (tester) async {
    _setup(tester);
    final api = _Api();
    await http.runWithClient(() async {
      await tester.pumpWidget(_map());
      await tester.pump(const Duration(milliseconds: 300));
      final state = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen));

      final gate = Completer<http.Response>();
      api.hold = gate;
      final fetch = state.fetchActiveVehicles(); // istek çıktı, yanıt bekliyor
      await tester.pump(const Duration(milliseconds: 10));

      // Çekme başladıktan SONRA: biri çıktı, biri girdi
      state.applyEvent(_exited(5, '34AAA111'));
      state.applyEvent(_entered(6, '34ZZZ999'));

      // Eski anlık görüntü gelir: çıkan araç hâlâ içinde, yeni giren yok
      gate.complete(http.Response(jsonEncode([_spot('34AAA111'), _spot('34BBB222')]), 200, headers: _json));
      await fetch;
      await tester.pump();

      expect(state.activeVehicles.map((s) => s['currentPlate']), unorderedEquals(['34BBB222', '34ZZZ999']));
      await _teardown(tester);
    }, api.client);
  });

  testWidgets('gösterge: aynı yarış sayıları ezmez', (tester) async {
    _setup(tester);
    final api = _Api();
    await http.runWithClient(() async {
      await tester.pumpWidget(MaterialApp(
        theme: BilParkTheme.light(),
        home: const Scaffold(body: DashboardScreen(streetCode: 'TEVFIK_BEY', streetName: 'Tevfik Bey Caddesi')),
      ));
      await tester.pump(const Duration(milliseconds: 300));
      final state = tester.state<DashboardScreenState>(find.byType(DashboardScreen));

      final gate = Completer<http.Response>();
      api.hold = gate;
      final fetch = state.refresh(summary: false, vehicleTypes: false);
      await tester.pump(const Duration(milliseconds: 10));
      state.applyEvent(_exited(5, '34AAA111'));
      gate.complete(http.Response(jsonEncode([_spot('34AAA111'), _spot('34BBB222')]), 200, headers: _json));
      await fetch;
      await tester.pump();

      expect(state.activeVehicleCount, 1); // eski görüntüdeki 2 değil
      await _teardown(tester);
    }, api.client);
  });

  testWidgets('doğrudan girişte 503 + Retry-After: kuyruk hemen yeniden denemez, beklemeye uyar, aynı requestId gider', (tester) async {
    _setup(tester);
    final api = _Api()..onCheckIn = (_) => http.Response('{"error":"yoğun"}', 503, headers: {..._json, 'retry-after': '20'});
    await http.runWithClient(() async {
      await tester.pumpWidget(_map());
      await tester.pump(const Duration(milliseconds: 300));
      final state = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen));

      unawaited(state.checkInVehicle('34ABC123', 'SMALL', 'LEFT'));
      await tester.pump(const Duration(milliseconds: 500));
      expect(api.posts, hasLength(1));
      expect(entryQueue.items.value, hasLength(1)); // kayıt düşmedi
      expect(entryQueue.lastRetryDelay, greaterThanOrEqualTo(const Duration(seconds: 20)));

      // 15 sn boyunca ikinci gönderim YOK (eski davranış: anında flush)
      await tester.pump(const Duration(seconds: 15));
      expect(api.posts, hasLength(1));

      // Retry-After dolunca aynı requestId ile yeniden gider
      await tester.pump(const Duration(seconds: 10));
      expect(api.posts, hasLength(2));
      expect(api.posts[1].url.queryParameters['checkInRequestId'], api.posts[0].url.queryParameters['checkInRequestId']);
      expect(entryQueue.items.value, hasLength(1)); // yine 503: kayıt hâlâ kuyrukta
      await _teardown(tester);
    }, api.client);
  });
}
