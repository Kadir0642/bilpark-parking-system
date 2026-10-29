import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/constants.dart';
import 'package:bilpark/core/entry_queue.dart';
import 'package:bilpark/core/live_sync.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/parking_map_screen.dart';
import 'package:bilpark/widgets/plate_badge.dart';

/// Sahte sunucu: check-in yanıtı ve bağlantı durumu test içinden değiştirilir.
class _Fake {
  bool online = true;
  double debt = 0;
  final List<Map<String, dynamic>> spots = [];
  final List<Map<String, String>> checkIns = [];

  http.Client client() => MockClient((req) async {
        if (!online) throw http.ClientException('bağlantı yok');
        if (req.url.path.endsWith('/parking/check-in')) {
          final q = req.url.queryParameters;
          checkIns.add(q);
          final spot = {
            'currentPlate': q['plate'],
            'currentType': q['type'],
            'entryTime': DateTime.now().toIso8601String(),
            'side': q['side'],
            'hasSubscription': false,
            'accumulatedDebt': 0.0,
          };
          spots.add(spot); // listede borç rozeti çıkmasın (yalnızca yanıtta borç var)
          return http.Response(jsonEncode({...spot, 'accumulatedDebt': debt}), 200, headers: {'content-type': 'application/json; charset=utf-8'});
        }
        if (req.url.path.endsWith('/parking/filter')) {
          return http.Response(jsonEncode(spots), 200, headers: {'content-type': 'application/json; charset=utf-8'});
        }
        return http.Response('{}', 200);
      });
}

Widget _host() => MaterialApp(
      theme: BilParkTheme.light(),
      home: const Scaffold(body: ParkingMapScreen(streetCode: 'TEVFIK_BEY', streetName: 'Tevfik Bey Caddesi')),
    );

void _setup(WidgetTester tester, {double w = 720, double h = 1600}) {
  tester.view.physicalSize = Size(w, h);
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.reset);
  SharedPreferences.setMockInitialValues({});
  ApiClient.resetCache();
  AppConstants.clearAuth();
  entryQueue.items.value = [];
}

Future<void> _pumpFor(WidgetTester tester, [int ms = 600]) async {
  for (var i = 0; i < ms ~/ 50; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
}

/// Snack'ler ve 30 sn'lik saat bitsin, ekran kapansın (bekleyen zamanlayıcı kalmasın).
Future<void> _teardown(WidgetTester tester) async {
  await tester.pump(const Duration(seconds: 15));
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(seconds: 1));
  liveSync.status.value = LiveStatus.connecting;
  entryQueue.items.value = [];
}

void main() {
  testWidgets('A) bildirim "+" düğmesini örtmez: snack görünürken dokunuş giriş sayfasını açar', (tester) async {
    _setup(tester, w: 720, h: 1600); // 360x800 mantıksal
    final fake = _Fake();
    await http.runWithClient(() async {
      await tester.pumpWidget(_host());
      await _pumpFor(tester);
      final state = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen));
      state.checkInVehicle('34ABC123', 'SMALL', 'LEFT');
      await _pumpFor(tester, 400);
      expect(find.text('34ABC123 sokağa eklendi.'), findsOneWidget);

      await tester.tap(find.byTooltip('Elle araç ekle'));
      await _pumpFor(tester, 500);
      expect(find.text('Araç girişi'), findsOneWidget);
      expect(find.text('34ABC123 sokağa eklendi.'), findsOneWidget, reason: 'snack hâlâ görünür olmalı');

      await tester.tapAt(const Offset(180, 20)); // sayfayı kapat
      await _pumpFor(tester, 500);
      await _teardown(tester);
    }, fake.client);
  });

  testWidgets('B) borçlu araç girişinde uyarı çıkar, araç yine eklenir', (tester) async {
    _setup(tester);
    final fake = _Fake()..debt = 120;
    await http.runWithClient(() async {
      await tester.pumpWidget(_host());
      await _pumpFor(tester);
      final state = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen));
      state.checkInVehicle('34 abc 123', 'SMALL', 'LEFT');
      await _pumpFor(tester, 900);

      expect(find.text('Borçlu araç'), findsOneWidget);
      expect(find.textContaining('kaçak geçmişi var'), findsOneWidget);
      expect(find.textContaining('₺120'), findsOneWidget);
      expect(fake.checkIns.single['checkInRequestId'], isNotEmpty);

      await tester.tap(find.text('Tamam'));
      await _pumpFor(tester, 500);
      expect(find.text('Borçlu araç'), findsNothing);
      expect(find.byType(PlateBadge), findsOneWidget); // araç listede
      await _teardown(tester);
    }, fake.client);
  });

  testWidgets('B) borç 0 ise uyarı çıkmaz', (tester) async {
    _setup(tester);
    final fake = _Fake()..debt = 0;
    await http.runWithClient(() async {
      await tester.pumpWidget(_host());
      await _pumpFor(tester);
      final state = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen));
      state.checkInVehicle('34ABC123', 'SMALL', 'LEFT');
      await _pumpFor(tester, 900);
      expect(find.text('Borçlu araç'), findsNothing);
      expect(find.byType(AlertDialog), findsNothing);
      expect(find.byType(PlateBadge), findsOneWidget);
      await _teardown(tester);
    }, fake.client);
  });

  testWidgets('C) çevrimdışı giriş "Beklemede" kartı + bant sayacı; bağlantı gelince normal karta döner', (tester) async {
    _setup(tester);
    final fake = _Fake()..online = false;
    await http.runWithClient(() async {
      liveSync.status.value = LiveStatus.offline;
      await tester.pumpWidget(_host());
      await _pumpFor(tester);
      final state = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen));
      state.checkInVehicle('34ABC123', 'SMALL', 'RIGHT');
      await _pumpFor(tester, 600);

      expect(entryQueue.items.value.length, 1);
      expect(find.text('Beklemede'), findsOneWidget);
      expect(find.text('1 işlem bekliyor'), findsOneWidget);
      expect(find.textContaining('bağlantı gelince gönderilecek (beklemede)'), findsOneWidget);

      // Bağlantı geldi
      fake.online = true;
      liveSync.status.value = LiveStatus.online;
      entryQueue.flush();
      await _pumpFor(tester, 1000);

      expect(entryQueue.items.value, isEmpty);
      expect(find.text('Beklemede'), findsNothing);
      expect(find.text('1 işlem bekliyor'), findsNothing);
      expect(fake.checkIns.length, greaterThanOrEqualTo(1));
      expect(find.byType(PlateBadge), findsOneWidget);
      await _teardown(tester);
    }, fake.client);
  });

  testWidgets('C) ağ hatasında aynı requestId ile kuyruğa alınır', (tester) async {
    _setup(tester);
    final fake = _Fake()..online = false;
    await http.runWithClient(() async {
      await tester.pumpWidget(_host()); // durum: connecting (çevrimdışı değil) -> önce POST denenir
      await _pumpFor(tester);
      final state = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen));
      state.checkInVehicle('34ABC123', 'SMALL', 'LEFT');
      await _pumpFor(tester, 600);
      expect(entryQueue.items.value.length, 1);
      final id = entryQueue.items.value.single.requestId;

      fake.online = true;
      entryQueue.flush();
      await _pumpFor(tester, 800);
      expect(fake.checkIns.single['checkInRequestId'], id);
      expect(entryQueue.items.value, isEmpty);
      await _teardown(tester);
    }, fake.client);
  });
}
