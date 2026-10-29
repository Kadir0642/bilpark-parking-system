import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/license.dart';
import 'package:bilpark/core/tariff.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/core/user_update.dart';
import 'package:bilpark/screens/vehicle_detail_screen.dart';
import 'package:bilpark/widgets/cancel_entry_dialog.dart';

final _vehicle = {
  'currentPlate': '34ABC123',
  'currentType': 'SMALL',
  'entryTime': '2026-10-22T20:00:00',
  'side': 'LEFT',
  'hasSubscription': false,
  'accumulatedDebt': 0.0,
};

Widget _detail({
  Future<void> Function(String, String)? cancel,
  VoidCallback? onCancelled,
}) => MaterialApp(
  theme: BilParkTheme.light(),
  home: Scaffold(
    body: Builder(
      builder: (context) => TextButton(
        onPressed: () => Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => VehicleDetailScreen(
              vehicle: _vehicle,
              onPay: (_, _) {},
              onRunaway: () {},
              onCancelled: onCancelled,
              cancelEntry: cancel,
              loadPreview: (_) async => <String, dynamic>{
                'fee': 25.0,
                'segments': [],
              },
            ),
          ),
        ),
        child: const Text('aç'),
      ),
    ),
  ),
);

Future<void> _open(WidgetTester tester, Widget w) async {
  await tester.pumpWidget(w);
  await tester.tap(find.text('aç'));
  await tester.pumpAndSettle();
}

Future<void> _openDialog(WidgetTester tester) async {
  await tester.ensureVisible(find.text('Yanlış giriş, iptal et'));
  await tester.tap(find.text('Yanlış giriş, iptal et'));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    LicenseStore.reset();
    TariffStore.current.value = null;
  });

  group('validateCancelReason', () {
    test('kısa, boş ve uzun gerekçe hata verir', () {
      expect(validateCancelReason('abc'), isNotNull);
      expect(validateCancelReason('     '), isNotNull);
      expect(validateCancelReason('a' * 501), isNotNull);
      expect(validateCancelReason('Plaka yanlış okundu'), isNull);
    });
  });

  group('İptal diyaloğu', () {
    testWidgets('kısa gerekçe hata gösterir, istek gitmez', (tester) async {
      var calls = 0;
      await _open(tester, _detail(cancel: (_, _) async => calls++));
      await _openDialog(tester);
      await tester.enterText(find.byType(TextField), 'abc');
      await tester.tap(find.widgetWithText(FilledButton, 'İptal et'));
      await tester.pump();
      expect(find.text('Gerekçe en az 5 karakter olmalı.'), findsOneWidget);
      expect(calls, 0);
    });

    testWidgets('çip alanı doldurur, gönderince istek gider ve ekran kapanır', (
      tester,
    ) async {
      String? sent;
      var refreshed = false;
      await _open(
        tester,
        _detail(
          cancel: (p, r) async => sent = '$p|$r',
          onCancelled: () => refreshed = true,
        ),
      );
      await _openDialog(tester);
      await tester.tap(find.text('Mükerrer giriş'));
      await tester.pump();
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        'Mükerrer giriş',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'İptal et'));
      await tester.pumpAndSettle();
      expect(sent, '34ABC123|Mükerrer giriş');
      expect(refreshed, isTrue);
      expect(find.text('Araç detayı'), findsNothing);
      expect(find.textContaining('girişi iptal edildi'), findsOneWidget);
    });

    testWidgets('404/409 sunucu mesajını gösterir ve listeyi yeniletir', (
      tester,
    ) async {
      var refreshed = false;
      await _open(
        tester,
        _detail(
          cancel: (_, _) async => throw const ApiException(
            409,
            'ALREADY_PROCESSED',
            'Araç zaten çıkış yapmış.',
          ),
          onCancelled: () => refreshed = true,
        ),
      );
      await _openDialog(tester);
      await tester.enterText(find.byType(TextField), 'Yanlış sahaya girildi');
      await tester.tap(find.widgetWithText(FilledButton, 'İptal et'));
      await tester.pumpAndSettle();
      expect(find.text('Araç zaten çıkış yapmış.'), findsOneWidget);
      expect(refreshed, isTrue);
    });

    testWidgets('VALIDATION_ERROR alan altında kalır', (tester) async {
      await _open(
        tester,
        _detail(
          cancel: (_, _) async => throw const ApiException(
            400,
            'VALIDATION_ERROR',
            'Gerekçe geçersiz.',
          ),
        ),
      );
      await _openDialog(tester);
      await tester.enterText(find.byType(TextField), 'Plaka yanlış okundu');
      await tester.tap(find.widgetWithText(FilledButton, 'İptal et'));
      await tester.pumpAndSettle();
      expect(find.text('Gerekçe geçersiz.'), findsOneWidget);
      expect(find.byType(CancelEntryDialog), findsOneWidget);
    });

    testWidgets('okunur modda düğme pasif', (tester) async {
      LicenseStore.markReadOnly('x');
      await _open(tester, _detail());
      final btn = tester.widget<ButtonStyleButton>(
        find.ancestor(
          of: find.text('Yanlış giriş, iptal et'),
          matching: find.bySubtype<ButtonStyleButton>(),
        ),
      );
      expect(btn.onPressed, isNull);
    });
  });

  group('decideUserUpdate', () {
    test('aynı saha: değişiklik yok', () {
      expect(
        decideUserUpdate(role: 'OFFICER', zoneId: 3, currentZoneId: 3),
        UserUpdateAction.none,
      );
    });
    test('farklı saha: yeniden giriş', () {
      expect(
        decideUserUpdate(role: 'OFFICER', zoneId: 4, currentZoneId: 3),
        UserUpdateAction.zoneChanged,
      );
    });
    test('saha boş: çıkış', () {
      expect(
        decideUserUpdate(role: 'OFFICER', zoneId: null, currentZoneId: 3),
        UserUpdateAction.zoneRemoved,
      );
    });
    test('rol memur değil: çıkış', () {
      expect(
        decideUserUpdate(role: 'ADMIN', zoneId: null, currentZoneId: 3),
        UserUpdateAction.roleChanged,
      );
      expect(
        decideUserUpdate(role: 'ADMIN', zoneId: 3, currentZoneId: 3),
        UserUpdateAction.roleChanged,
      );
    });
  });
}
