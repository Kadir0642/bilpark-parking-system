import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/dashboard_screen.dart';
import 'package:bilpark/widgets/entry_sheet.dart';

// Galaxy M12: mantıksal 360x800, dpr 2 -> fiziksel 720x1600
const _m12 = Size(720, 1600);

MockClient _client() => MockClient((req) async {
      final path = req.url.path;
      if (path.endsWith('/parking/history')) {
        final now = DateTime.now().toIso8601String();
        final rows = [
          for (var i = 0; i < 3; i++)
            {'status': 'PAID', 'paymentMethod': 'CASH', 'fee': 4115.5, 'exitTime': now, 'entryTime': now},
          {'status': 'PAID', 'paymentMethod': 'POS', 'fee': 100.0, 'exitTime': now, 'entryTime': now},
          {'status': 'RUNAWAY', 'fee': 0, 'exitTime': now, 'entryTime': now},
        ];
        return http.Response(jsonEncode(rows), 200, headers: {'content-type': 'application/json; charset=utf-8'});
      }
      return http.Response('[]', 200, headers: {'content-type': 'application/json; charset=utf-8'});
    });

void _setView(WidgetTester tester, Size physical, {double dpr = 2, double keyboardPx = 0}) {
  tester.view.physicalSize = physical;
  tester.view.devicePixelRatio = dpr;
  tester.view.viewInsets = FakeViewPadding(bottom: keyboardPx);
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
    tester.view.resetViewInsets();
  });
}

Future<void> _pumpDashboard(WidgetTester tester, {double textScale = 1.0, VoidCallback? onManual}) async {
  await tester.pumpWidget(MaterialApp(
    theme: BilParkTheme.light(),
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
      child: child!,
    ),
    home: Scaffold(
      body: DashboardScreen(streetCode: 'S1', streetName: 'Cadde', onManualEntry: onManual, onScanPlate: () {}),
    ),
  ));
  await tester.pump(const Duration(milliseconds: 100));
  await tester.pump(const Duration(milliseconds: 100));
}

/// flutter_test'in varsayılan yazı tipi (Ahem) gerçek cihazdan çok geniştir; sahte taşma üretmesin diye
/// SDK'daki gerçek Roboto yüklenir (Galaxy M12 varsayılanı). Bulunamazsa Ahem kalır.
Future<void> _loadRoboto() async {
  final root = Platform.environment['FLUTTER_ROOT'] ?? File(Platform.resolvedExecutable).parent.parent.parent.parent.parent.parent.path;
  final dir = Directory('$root/bin/cache/artifacts/material_fonts');
  final loader = FontLoader('Roboto');
  for (final f in ['regular', 'medium', 'bold', 'black', 'light']) {
    final file = File('${dir.path}/roboto-$f.ttf');
    if (file.existsSync()) loader.addFont(Future.value(ByteData.sublistView(file.readAsBytesSync())));
  }
  await loader.load();
}

void main() {
  setUpAll(_loadRoboto);
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    ApiClient.resetCache();
  });

  for (final scale in [1.0, 1.15, 1.3]) {
    testWidgets('Vardiya özeti kutuları 360x800 ekranda taşmaz (yazı ölçeği $scale)', (tester) async {
      _setView(tester, _m12);
      await http.runWithClient(() async {
        await _pumpDashboard(tester, textScale: scale);
        expect(find.textContaining('Tahsil edilen'), findsOneWidget);
        expect(tester.takeException(), isNull);
      }, _client);
    });
  }

  for (final size in [const Size(720, 1600), const Size(720, 1280)]) {
    for (final kb in [0.0, 600.0]) {
      testWidgets('Elle ekle her zaman görünür: ${size.width ~/ 2}x${size.height ~/ 2}, klavye ${kb ~/ 2}', (tester) async {
        _setView(tester, size, keyboardPx: kb);
        var tapped = 0;
        await http.runWithClient(() async {
          await _pumpDashboard(tester, onManual: () => tapped++);
          final btn = find.text('Elle ekle');
          expect(btn, findsOneWidget);
          final rect = tester.getRect(btn);
          final visibleBottom = (size.height - kb) / 2;
          expect(rect.top, greaterThanOrEqualTo(0));
          expect(rect.bottom, lessThanOrEqualTo(visibleBottom));
          expect(rect.left, greaterThanOrEqualTo(0));
          expect(rect.right, lessThanOrEqualTo(size.width / 2));
          await tester.tap(btn);
          expect(tapped, 1);
          expect(tester.takeException(), isNull);
        }, _client);
      });
    }
  }

  for (final size in [const Size(720, 1600), const Size(720, 1280)]) {
    testWidgets('Giriş sayfası: klavye açıkken Sola/Sağa park görünür (${size.width ~/ 2}x${size.height ~/ 2})', (tester) async {
      _setView(tester, size, keyboardPx: 600);
      await tester.pumpWidget(MaterialApp(
        theme: BilParkTheme.light(),
        home: Scaffold(
          body: Builder(
            builder: (ctx) => TextButton(onPressed: () => showEntrySheet(ctx, ''), child: const Text('aç')),
          ),
        ),
      ));
      await tester.tap(find.text('aç'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      final visibleBottom = (size.height - 600) / 2;
      for (final label in ['Sola park', 'Sağa park']) {
        final r = tester.getRect(find.text(label));
        expect(r.top, greaterThanOrEqualTo(0), reason: label);
        expect(r.bottom, lessThanOrEqualTo(visibleBottom), reason: label);
      }
      expect(tester.takeException(), isNull);
    });
  }
}
