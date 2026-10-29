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
import 'package:bilpark/main.dart';
import 'package:bilpark/screens/parking_map_screen.dart';

int _selected(WidgetTester t) => t.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex;

void main() {
  testWidgets('sekmeler kaydırılır, Cadde ekranı atılmaz', (tester) async {
    tester.view.physicalSize = const Size(720, 1600);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    SharedPreferences.setMockInitialValues({});
    ApiClient.resetCache();
    AppConstants.clearAuth();
    entryQueue.items.value = [];

    // Test yazı tipi geniş olduğundan panodaki taşma uyarıları bu testin konusu değil
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
      await tester.pump(const Duration(milliseconds: 300));
      expect(_selected(tester), 0);

      final mapState = tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen, skipOffstage: false));

      await tester.fling(find.byType(PageView), const Offset(-250, 0), 600);
      await tester.pumpAndSettle(const Duration(milliseconds: 100));
      expect(_selected(tester), 1);

      await tester.fling(find.byType(PageView), const Offset(-250, 0), 600);
      await tester.pumpAndSettle(const Duration(milliseconds: 100));
      expect(_selected(tester), 2);

      await tester.tap(find.text('Ana sayfa'));
      await tester.pumpAndSettle(const Duration(milliseconds: 100));
      expect(_selected(tester), 0);

      // Cadde ekranı sayfalar arasında yeniden kurulmadı
      expect(tester.state<ParkingMapScreenState>(find.byType(ParkingMapScreen, skipOffstage: false)), same(mapState));

      await tester.tap(find.text('Cadde'));
      await tester.pumpAndSettle(const Duration(milliseconds: 100));
      expect(_selected(tester), 1);

      // sahneyi sökmeden önce zamanlayıcılar durdurulur
      await tester.pumpWidget(const SizedBox.shrink());
      await liveSync.stop();
      await tester.pump(const Duration(seconds: 1));
    }, () => MockClient((req) async => http.Response(req.url.path.endsWith('s') ? '[]' : '{}', 200, headers: {'content-type': 'application/json; charset=utf-8'})));
  });
}
