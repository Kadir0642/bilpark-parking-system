import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/streets.dart';
import 'package:bilpark/core/tariff.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/dashboard_screen.dart';
import 'package:bilpark/screens/login_screen.dart';

const _streets = [
  StreetInfo(id: 2, code: 'ALI_RIZA_OZKAY', displayName: 'Ali Rıza Özkay Caddesi', sortOrder: 2, zones: []),
  StreetInfo(id: 1, code: 'TEVFIK_BEY', displayName: 'Tevfik Bey Caddesi', sortOrder: 1, zones: [
    ZoneInfo(id: 11, zoneNumber: 1, zoneName: 'Tevfik Bey - Bölüm 1', leftLandmark: 'Öncü Döner', rightLandmark: 'Ziraat Bankası'),
    ZoneInfo(id: 12, zoneNumber: 2, zoneName: 'Tevfik Bey - Bölüm 2', leftLandmark: '[Sol Landmark - Güncelleyin]'),
  ]),
];

Widget _wrap(Widget child) => MaterialApp(theme: BilParkTheme.light(), home: child);

void main() {
  testWidgets('saha seçimi cadde ve bölümleri sunucu verisinden gösterir', (tester) async {
    StreetStore.seed(_streets);
    await tester.pumpWidget(_wrap(LoginScreen(loader: ({bool force = false}) async => _streets)));
    await tester.pumpAndSettle();

    expect(find.text('Tevfik Bey Caddesi'), findsOneWidget);
    expect(find.text('Tevfik Bey - Bölüm 1'), findsOneWidget);
    expect(find.text('Öncü Döner – Ziraat Bankası'), findsOneWidget);
    // Yer tutucu yer işareti gizlenir
    expect(find.textContaining('['), findsNothing);
    // Bölümü olmayan cadde "Tüm cadde" sunar
    expect(find.text('Ali Rıza Özkay Caddesi'), findsOneWidget);
    expect(find.text('Tüm cadde'), findsOneWidget);
    // sortOrder sırası: Tevfik Bey önce
    expect(tester.getTopLeft(find.text('Tevfik Bey Caddesi')).dy < tester.getTopLeft(find.text('Ali Rıza Özkay Caddesi')).dy, isTrue);
  });

  testWidgets('bölüm seçilince Vardiyayı başlat etkinleşir', (tester) async {
    await tester.pumpWidget(_wrap(LoginScreen(loader: ({bool force = false}) async => _streets)));
    await tester.pumpAndSettle();

    FilledButton button() => tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Vardiyayı başlat'));
    expect(button().onPressed, isNull);
    await tester.tap(find.text('Tevfik Bey - Bölüm 1'));
    await tester.pump();
    expect(button().onPressed, isNotNull);
    expect(find.textContaining('Tevfik Bey — Tevfik Bey - Bölüm 1'), findsOneWidget);
  });

  testWidgets('boş liste hata kartı ve tekrar dene gösterir', (tester) async {
    await tester.pumpWidget(_wrap(LoginScreen(loader: ({bool force = false}) async => const [])));
    await tester.pumpAndSettle();
    expect(find.text('Tekrar dene'), findsOneWidget);
  });

  testWidgets('panodaki Güncel tarife kartı TariffStore değişince güncellenir', (tester) async {
    TariffStore.applyPayload({
      'id': 1,
      'status': 'ACTIVE',
      'small': {'firstHour': 25, 'extraHour': 15},
      'large': {'firstHour': 50, 'extraHour': 30},
      'freeMinutes': 5,
      'paidStart': '08:00',
      'paidEnd': '22:00',
    });
    await tester.pumpWidget(_wrap(const DashboardScreen(streetCode: 'TEVFIK_BEY', streetName: 'Tevfik Bey Caddesi')));
    await tester.pump();
    expect(find.text('Otomobil: İlk saat ₺25 · sonraki ₺15'), findsOneWidget);

    TariffStore.applyPayload({
      'id': 2,
      'status': 'ACTIVE',
      'small': {'firstHour': 30, 'extraHour': 20},
      'large': {'firstHour': 50, 'extraHour': 30},
      'freeMinutes': 5,
      'paidStart': '08:00',
      'paidEnd': '22:00',
    });
    await tester.pump();
    expect(find.text('Otomobil: İlk saat ₺30 · sonraki ₺20'), findsOneWidget);
    TariffStore.reset();
  });
}
