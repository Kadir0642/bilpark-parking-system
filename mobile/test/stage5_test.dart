import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/constants.dart';
import 'package:bilpark/core/license.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/auth_screen.dart';
import 'package:bilpark/screens/change_password_screen.dart';
import 'package:bilpark/widgets/entry_sheet.dart';
import 'package:bilpark/widgets/license_banner.dart';

Map<String, dynamic> _json({String state = 'ACTIVE', int? level, bool readOnly = false, int daysLeft = 20, String message = 'Mesaj'}) => {
      'licensee': 'BilPark Müşterisi',
      'expiresOn': '2027-11-01',
      'graceEndsOn': '2027-11-16',
      'daysLeft': daysLeft,
      'graceDaysLeft': daysLeft + 15,
      'state': state,
      'warningLevel': level,
      'readOnly': readOnly,
      'message': message,
    };

Widget _host(Widget child) => MaterialApp(theme: BilParkTheme.light(), home: Scaffold(body: child));

void main() {
  tearDown(LicenseStore.reset);

  group('LicenseStatus', () {
    test('ACTIVE ve warningLevel null ayrıştırılır', () {
      final s = LicenseStatus.fromJson(_json());
      expect(s.state, LicenseState.active);
      expect(s.warningLevel, isNull);
      expect(s.readOnly, isFalse);
      expect(s.daysLeft, 20);
      expect(s.licensee, 'BilPark Müşterisi');
    });

    test('GRACE ve READ_ONLY ayrıştırılır', () {
      final g = LicenseStatus.fromJson(_json(state: 'GRACE', level: 0, daysLeft: -3));
      expect(g.state, LicenseState.grace);
      expect(g.warningLevel, 0);
      expect(g.readOnly, isFalse);
      final r = LicenseStatus.fromJson(_json(state: 'READ_ONLY', level: 0, readOnly: true));
      expect(r.state, LicenseState.readOnly);
      expect(r.readOnly, isTrue);
    });

    test('markReadOnly depoyu okunur moda alır, applyStatus geri açar', () {
      LicenseStore.markReadOnly('Sunucu mesajı');
      expect(LicenseStore.readOnly.value, isTrue);
      expect(LicenseStore.readOnlyMessage, 'Sunucu mesajı');
      LicenseStore.applyStatus(LicenseStatus.fromJson(_json()));
      expect(LicenseStore.readOnly.value, isFalse);
    });
  });

  group('ApiClient 423', () {
    test('parseError 423 isReadOnly olur ve sunucu mesajını taşır', () {
      final e = ApiClient.parseError(423, '{"error":"Lisans doldu","code":"READ_ONLY"}');
      expect(e.isReadOnly, isTrue);
      expect(e.code, 'READ_ONLY');
      expect(e.message, 'Lisans doldu');
    });

    test('gövde yoksa Türkçe yedek mesaj', () {
      final e = ApiClient.parseError(423, '');
      expect(e.isReadOnly, isTrue);
      expect(e.message, contains('okunur modda'));
    });

    test('attach kancayı depoya bağlar', () {
      LicenseStore.attach();
      ApiClient.onReadOnly!('Kilitli');
      expect(LicenseStore.readOnly.value, isTrue);
      ApiClient.onReadOnly = null;
    });
  });

  group('LicenseBanner', () {
    testWidgets('lisans sorunu yoksa görünmez', (tester) async {
      await tester.pumpWidget(_host(const LicenseBanner()));
      expect(find.byIcon(Icons.lock_outline), findsNothing);
      LicenseStore.applyStatus(LicenseStatus.fromJson(_json(level: 30)));
      await tester.pump();
      expect(find.textContaining('Lisansın bitmesine'), findsNothing);
    });

    testWidgets('7 günde sakin uyarı gösterir', (tester) async {
      LicenseStore.applyStatus(LicenseStatus.fromJson(_json(level: 7, daysLeft: 6)));
      await tester.pumpWidget(_host(const LicenseBanner()));
      expect(find.text('Lisansın bitmesine 6 gün kaldı.'), findsOneWidget);
    });

    testWidgets('okunur modda bant görünür, dokununca sunucu mesajı açılır, lisans gelince kaybolur', (tester) async {
      LicenseStore.applyStatus(
          LicenseStatus.fromJson(_json(state: 'READ_ONLY', level: 0, readOnly: true, message: 'Tam sunucu mesajı')));
      await tester.pumpWidget(_host(const LicenseBanner()));
      expect(find.textContaining('Okunur mod: Lisans süresi doldu'), findsOneWidget);
      await tester.tap(find.byType(InkWell));
      await tester.pumpAndSettle();
      expect(find.text('Tam sunucu mesajı'), findsOneWidget);
      await tester.tap(find.text('Kapat'));
      await tester.pumpAndSettle();

      LicenseStore.applyStatus(LicenseStatus.fromJson(_json()));
      await tester.pump();
      expect(find.textContaining('Okunur mod'), findsNothing);
    });
  });

  group('Giriş alt sayfası', () {
    Future<void> open(WidgetTester tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: BilParkTheme.light(),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(onPressed: () => showEntrySheet(context, '34ABC123'), child: const Text('aç')),
          ),
        ),
      ));
      await tester.tap(find.text('aç'));
      await tester.pumpAndSettle();
    }

    testWidgets('okunur modda yön düğmeleri pasif, lisans gelince açılır', (tester) async {
      LicenseStore.markReadOnly('x');
      await open(tester);
      ButtonStyleButton left() =>
          tester.widget<ButtonStyleButton>(find.ancestor(of: find.text('Sola park'), matching: find.bySubtype<ButtonStyleButton>()));
      expect(left().onPressed, isNull);
      expect(find.text(readOnlyReason), findsOneWidget);

      LicenseStore.applyStatus(LicenseStatus.fromJson(_json()));
      await tester.pump();
      expect(left().onPressed, isNotNull);
      expect(find.text(readOnlyReason), findsNothing);
    });
  });

  group('Şifremi değiştir', () {
    setUp(() => AppConstants.currentUser = 'ayse');

    Future<void> fill(WidgetTester tester, String cur, String next, String again) async {
      await tester.pumpWidget(MaterialApp(theme: BilParkTheme.light(), home: const ChangePasswordScreen()));
      await tester.enterText(find.byType(TextField).at(0), cur);
      await tester.enterText(find.byType(TextField).at(1), next);
      await tester.enterText(find.byType(TextField).at(2), again);
      await tester.tap(find.text('Şifreyi değiştir'));
      await tester.pump();
    }

    test('kurallar', () {
      expect(PasswordRules.check('abc12345', username: 'ayse', current: 'x').ok, isTrue);
      expect(PasswordRules.check('abc123', username: 'ayse').minLength, isFalse);
      expect(PasswordRules.check('abcdefgh', username: 'ayse').letterAndDigit, isFalse);
      expect(PasswordRules.check('Ayse12345', username: 'ayse').noUsername, isFalse);
      expect(PasswordRules.check('abc12345', username: 'ayse', current: 'abc12345').differsFromCurrent, isFalse);
    });

    testWidgets('tekrar eşleşmezse uyarır', (tester) async {
      await fill(tester, 'eski1234', 'Yeni-sifre1', 'Yeni-sifre2');
      expect(find.text('Şifreler eşleşmiyor.'), findsOneWidget);
    });

    testWidgets('kısa şifre kurallara uymaz', (tester) async {
      await fill(tester, 'eski1234', 'ab1', 'ab1');
      expect(find.text('Şifre kurallara uymuyor.'), findsOneWidget);
      expect(find.text('Şifreler eşleşmiyor.'), findsNothing);
    });

    testWidgets('mevcut şifre boşsa uyarır', (tester) async {
      await fill(tester, '', 'Yeni-sifre1', 'Yeni-sifre1');
      expect(find.text('Mevcut şifreyi girin.'), findsOneWidget);
    });
  });

  group('AuthScreen', () {
    testWidgets('mesaj verilirse giriş formunun üstünde gösterir', (tester) async {
      await tester.pumpWidget(MaterialApp(theme: BilParkTheme.light(), home: const AuthScreen(message: ApiClient.deactivatedMessage)));
      expect(find.text(ApiClient.deactivatedMessage), findsOneWidget);
    });

    testWidgets('mesaj yoksa uyarı kutusu çıkmaz', (tester) async {
      await tester.pumpWidget(MaterialApp(theme: BilParkTheme.light(), home: const AuthScreen()));
      expect(find.byIcon(Icons.info_outline), findsNothing);
    });
  });
}
