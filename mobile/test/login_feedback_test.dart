// Giriş hataları: ApiClient 401/429/503'ü doğru ayrıştırır, ekran sakin mesaj ve geri sayım gösterir.
// Canlı API'ye gidilmez: http MockClient ve ağsız loginCall.
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/constants.dart';
import 'package:bilpark/core/login_feedback.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/auth_screen.dart';

http.Response _json(int status, String body, {Map<String, String> headers = const {}}) =>
    http.Response(body, status, headers: {'content-type': 'application/json; charset=utf-8', ...headers});

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    ApiClient.resetCache();
    AppConstants.clearAuth();
  });
  tearDown(ApiClient.resetCache);

  group('ApiClient: giriş yanıtları', () {
    late List<http.Request> requests;

    Future<T> login<T>(http.Response Function() res, Future<T> Function() body) {
      requests = [];
      return http.runWithClient(body, () => MockClient((req) async {
            requests.add(req);
            return res();
          }));
    }

    Future<dynamic> doLogin() => ApiClient.post('/auth/login', body: {'username': 'u', 'password': 'p'}, auth: false);

    test('401, hak bilgisi yok: sunucu metni gelir, remainingAttempts null, oturum/yönlendirme yok', () async {
      await expectLater(
        login(() => _json(401, '{"error":"Kullanıcı adı veya şifre hatalı.","code":"UNAUTHORIZED"}'), doLogin),
        throwsA(isA<ApiException>()
            .having((e) => e.statusCode, 's', 401)
            .having((e) => e.message, 'm', 'Kullanıcı adı veya şifre hatalı.')
            .having((e) => e.remainingAttempts, 'r', isNull)),
      );
      expect(requests, hasLength(1)); // giriş 401'i "teyit için yeniden gönderilmez"
    });

    test('401, remainingAttempts=2: sayı taşınır', () async {
      await expectLater(
        login(
            () => _json(401, '{"error":"Kullanıcı adı veya şifre hatalı. 2 deneme hakkınız kaldı.","code":"UNAUTHORIZED","remainingAttempts":2}'),
            doLogin),
        throwsA(isA<ApiException>()
            .having((e) => e.remainingAttempts, 'r', 2)
            .having((e) => e.message, 'm', contains('2 deneme hakkınız kaldı'))),
      );
    });

    test('429 LOGIN_LOCKED: mesaj sunucudan, bekleme retryAfterSeconds (15 dk sınırı aşılsa da 20 sn\'ye kırpılmaz), yoğun bandı açılmaz, tekrar denenmez', () async {
      await expectLater(
        login(
            () => _json(
                429,
                '{"error":"Çok fazla başarısız giriş denemesi yapıldı. Lütfen 5 dakika sonra tekrar deneyin.","code":"LOGIN_LOCKED","retryAfterSeconds":300}',
                headers: {'retry-after': '300'}),
            doLogin),
        throwsA(isA<ApiException>()
            .having((e) => e.isLoginLimited, 'limited', isTrue)
            .having((e) => e.code, 'code', 'LOGIN_LOCKED')
            .having((e) => e.retryAfter, 'retryAfter', const Duration(seconds: 300))
            .having((e) => e.message, 'm', contains('5 dakika'))),
      );
      expect(requests, hasLength(1));
      expect(ApiClient.serverBusy.value, isFalse);
    });

    test('429 LOGIN_LOCKED yalnızca Retry-After başlığıyla: başlıktan okunur', () async {
      await expectLater(
        login(() => _json(429, '{"error":"Bekleyin.","code":"LOGIN_LOCKED"}', headers: {'retry-after': '60'}), doLogin),
        throwsA(isA<ApiException>().having((e) => e.retryAfter, 'retryAfter', const Duration(seconds: 60))),
      );
    });

    test('429 RATE_LIMITED: sunucu mesajı gösterilir ("sunucu yoğun" denmez)', () async {
      await expectLater(
        login(
            () => _json(429, '{"error":"Bu bağlantıdan kısa sürede çok sayıda giriş denemesi yapıldı. Lütfen 1 dakika sonra tekrar deneyin.","code":"RATE_LIMITED","retryAfterSeconds":42}',
                headers: {'retry-after': '42'}),
            doLogin),
        throwsA(isA<ApiException>()
            .having((e) => e.isLoginLimited, 'limited', isTrue)
            .having((e) => e.retryAfter, 'retryAfter', const Duration(seconds: 42))
            .having((e) => e.message, 'm', startsWith('Bu bağlantıdan kısa sürede'))),
      );
      expect(ApiClient.serverBusy.value, isFalse);
    });

    test('503: eskisi gibi yoğun sayılır (oturum/yoğun bandı), giriş POST\'u yeniden denenmez', () async {
      await expectLater(
        login(() => _json(503, '{"error":"x","code":"SERVICE_UNAVAILABLE"}', headers: {'retry-after': '3'}), doLogin),
        throwsA(isA<ApiException>().having((e) => e.isBusy, 'busy', isTrue).having((e) => e.isLoginLimited, 'limited', isFalse)),
      );
      expect(requests, hasLength(1));
      expect(ApiClient.serverBusy.value, isTrue);
    });
  });

  group('loginFeedbackFor', () {
    test('401 hak bilgisiyle: sunucu metni + nazik ipucu', () {
      final fb = loginFeedbackFor(const ApiException(401, null, 'Kullanıcı adı veya şifre hatalı. 1 deneme hakkınız kaldı.',
          remainingAttempts: 1));
      expect(fb.message, contains('1 deneme hakkınız kaldı'));
      expect(fb.hint, loginForgotHint);
      expect(fb.wait, isNull);
    });

    test('401 hak bilgisiz: ipucu yok', () {
      final fb = loginFeedbackFor(const ApiException(401, null, 'Kullanıcı adı veya şifre hatalı.'));
      expect(fb.message, 'Kullanıcı adı veya şifre hatalı.');
      expect(fb.hint, isNull);
    });

    test('401 gövdesiz: "oturum süresi doldu" yerine yerel sakin metin', () {
      final fb = loginFeedbackFor(ApiClient.parseError(401, ''));
      expect(fb.message, loginWrongMessage);
    });

    test('LOGIN_LOCKED: sunucu metni ve bekleme süresi', () {
      final fb = loginFeedbackFor(const ApiException(429, 'LOGIN_LOCKED', 'Lütfen 1 dakika sonra tekrar deneyin.',
          retryAfter: Duration(seconds: 45)));
      expect(fb.wait, const Duration(seconds: 45));
      expect(fb.message, contains('1 dakika'));
    });

    test('RATE_LIMITED süresiz: yalnızca mesaj, geri sayım yok', () {
      final fb = loginFeedbackFor(const ApiException(429, 'RATE_LIMITED', 'Biraz bekleyin.'));
      expect(fb.wait, isNull);
      expect(fb.message, 'Biraz bekleyin.');
    });

    test('503 ve kodsuz 429: sakin yoğunluk metni', () {
      expect(loginFeedbackFor(const ApiException(503, 'SERVER_BUSY', ApiClient.busyMessage)).message, loginBusyMessage);
      expect(loginFeedbackFor(const ApiException(429, 'SERVER_BUSY', ApiClient.busyMessage)).message, loginBusyMessage);
    });

    test('bağlantı hatası: kendi mesajı', () {
      expect(loginFeedbackFor(const ApiException(null, 'NETWORK', 'Sunucuya ulaşılamadı.')).message, 'Sunucuya ulaşılamadı.');
    });

    test('geri sayım biçimi', () {
      expect(formatCountdown(const Duration(seconds: 45)), '0:45');
      expect(formatCountdown(const Duration(seconds: 75)), '1:15');
      expect(formatCountdown(const Duration(minutes: 15)), '15:00');
      expect(formatCountdown(const Duration(seconds: -3)), '0:00');
    });
  });

  group('AuthScreen: giriş mesajları', () {
    var calls = 0;

    Future<void> pump(WidgetTester tester, Future<dynamic> Function(String, String) call) async {
      calls = 0;
      await tester.pumpWidget(MaterialApp(
          theme: BilParkTheme.light(),
          home: AuthScreen(loginCall: (u, p) {
            calls++;
            return call(u, p);
          })));
    }

    Future<void> submit(WidgetTester tester, {String user = 'memur1', String pass = 'yanlis'}) async {
      await tester.enterText(find.byType(TextField).at(0), user);
      await tester.enterText(find.byType(TextField).at(1), pass);
      await tester.tap(find.byType(FilledButton));
      await tester.pump();
      await tester.pump();
    }

    FilledButton button(WidgetTester tester) => tester.widget<FilledButton>(find.byType(FilledButton));

    testWidgets('401 hak bilgisiz: sunucu metni, ipucu yok, kullanıcı adı silinmez, düğme açık', (tester) async {
      await pump(tester, (u, p) async => throw const ApiException(401, null, 'Kullanıcı adı veya şifre hatalı.'));
      await submit(tester);
      expect(find.text('Kullanıcı adı veya şifre hatalı.'), findsOneWidget);
      expect(find.text(loginForgotHint), findsNothing);
      expect(find.text('memur1'), findsOneWidget);
      expect(button(tester).onPressed, isNotNull);
    });

    testWidgets('401 kalan hak 2: sunucu metni + nazik ipucu', (tester) async {
      await pump(tester, (u, p) async => throw const ApiException(
          401, null, 'Kullanıcı adı veya şifre hatalı. 2 deneme hakkınız kaldı.', remainingAttempts: 2));
      await submit(tester);
      expect(find.textContaining('2 deneme hakkınız kaldı'), findsOneWidget);
      expect(find.text(loginForgotHint), findsOneWidget);
      expect(find.text('memur1'), findsOneWidget);
    });

    testWidgets('429 LOGIN_LOCKED: düğme kapanır, geri sayım akar, süre dolunca düğme açılır ve otomatik giriş denenmez',
        (tester) async {
      await pump(tester, (u, p) async => throw const ApiException(
          429, 'LOGIN_LOCKED', 'Çok fazla başarısız giriş denemesi yapıldı. Lütfen 1 dakika sonra tekrar deneyin.',
          retryAfter: Duration(seconds: 3)));
      await submit(tester);
      expect(calls, 1);
      expect(find.textContaining('Lütfen 1 dakika sonra tekrar deneyin.'), findsOneWidget);
      expect(find.text('Kalan süre: 0:03'), findsOneWidget);
      expect(button(tester).onPressed, isNull);
      expect(find.textContaining('Lütfen bekleyin'), findsOneWidget);
      expect(find.text('memur1'), findsOneWidget);

      await tester.pump(const Duration(seconds: 1));
      expect(find.text('Kalan süre: 0:02'), findsOneWidget);
      expect(button(tester).onPressed, isNull);

      await tester.pump(const Duration(seconds: 2));
      expect(find.byKey(const ValueKey('loginCountdown')), findsNothing);
      expect(find.text('Bekleme süresi doldu. Şimdi tekrar deneyebilirsiniz.'), findsOneWidget);
      expect(button(tester).onPressed, isNotNull);
      expect(find.text('Giriş yap'), findsOneWidget);

      await tester.pump(const Duration(seconds: 30));
      expect(calls, 1, reason: 'süre dolunca kendiliğinden yeniden denenmemeli');
    });

    testWidgets('429 RATE_LIMITED süresiz: sunucu metni gösterilir, düğme kapanmaz', (tester) async {
      await pump(tester, (u, p) async => throw const ApiException(
          429, 'RATE_LIMITED', 'Çok fazla giriş denemesi yapıldı. Lütfen biraz bekleyip tekrar deneyin.'));
      await submit(tester);
      expect(find.textContaining('Çok fazla giriş denemesi'), findsOneWidget);
      expect(find.textContaining('Kalan süre'), findsNothing);
      expect(button(tester).onPressed, isNotNull);
    });

    testWidgets('503: sakin "sunucu yoğun" metni, geri sayım yok', (tester) async {
      await pump(tester, (u, p) async => throw const ApiException(503, 'SERVER_BUSY', ApiClient.busyMessage,
          retryAfter: Duration(seconds: 5)));
      await submit(tester);
      expect(find.text(loginBusyMessage), findsOneWidget);
      expect(find.textContaining('Kalan süre'), findsNothing);
      expect(button(tester).onPressed, isNotNull);
    });

    testWidgets('boş alan: nazik uyarı, istek atılmaz', (tester) async {
      await pump(tester, (u, p) async => throw StateError('çağrılmamalı'));
      await tester.tap(find.byType(FilledButton));
      await tester.pump();
      expect(find.text('Lütfen kullanıcı adı ve şifrenizi girin.'), findsOneWidget);
      expect(calls, 0);
    });

    testWidgets('şifreyi göster düğmesi hata sonrası da çalışır', (tester) async {
      await pump(tester, (u, p) async => throw const ApiException(401, null, 'Kullanıcı adı veya şifre hatalı.'));
      await submit(tester);
      expect(tester.widget<TextField>(find.byType(TextField).at(1)).obscureText, isTrue);
      await tester.tap(find.byTooltip('Şifreyi göster'));
      await tester.pump();
      expect(tester.widget<TextField>(find.byType(TextField).at(1)).obscureText, isFalse);
    });
  });
}
