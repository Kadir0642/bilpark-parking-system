// Sunucu yoğunken (503/429) ve geçici 401'de istemci: oturum KAPANMAZ, okuma istekleri nazikçe yeniden denenir.
import 'dart:math';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/constants.dart';

http.Response _json(int status, String body, {Map<String, String> headers = const {}}) =>
    http.Response(body, status, headers: {'content-type': 'application/json; charset=utf-8', ...headers});

Matcher durBetween(Duration lo, Duration hi) => allOf(greaterThanOrEqualTo(lo), lessThanOrEqualTo(hi));

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<Duration> sleeps;
  late List<http.Request> requests;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    ApiClient.resetCache();
    AppConstants.clearAuth();
    await ApiClient.saveToken('test-secret-token');
    sleeps = [];
    requests = [];
    ApiClient.sleeper = (d) async => sleeps.add(d); // gerçek beklemeye gerek yok
    ApiClient.rng = Random(42);
  });

  tearDown(() {
    ApiClient.resetCache();
  });

  /// [responses] sırayla verilir; bittiğinde sonuncusu tekrarlanır.
  Future<T> run<T>(List<http.Response Function()> responses, Future<T> Function() body) {
    var i = 0;
    return http.runWithClient(body, () => MockClient((req) async {
          requests.add(req);
          final f = responses[i < responses.length ? i : responses.length - 1];
          i++;
          return f();
        }));
  }

  group('503 / 429: sunucu yoğun', () {
    test('GET 503 sonra 200: yeniden denenir, sonuç döner, "yoğun" notu kalkar, oturum bozulmaz', () async {
      final r = await run([() => _json(503, '{"error":"yoğun"}'), () => _json(200, '{"ok":true}')], () => ApiClient.get('/me'));
      expect(r, {'ok': true});
      expect(requests, hasLength(2));
      expect(sleeps, hasLength(1));
      expect(ApiClient.serverBusy.value, isFalse);
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('GET sürekli 503: en çok 3 yeniden deneme (toplam 4 istek), sonra ApiException 503; ÇIKIŞ YAPILMAZ', () async {
      await expectLater(
        run([() => _json(503, '{"error":"x","code":"SERVICE_UNAVAILABLE"}')], () => ApiClient.get('/parking/filter')),
        throwsA(isA<ApiException>()
            .having((e) => e.statusCode, 'statusCode', 503)
            .having((e) => e.isBusy, 'isBusy', isTrue)
            .having((e) => e.isUnauthorized, 'isUnauthorized', isFalse)
            .having((e) => e.code, 'code', 'SERVICE_UNAVAILABLE')
            .having((e) => e.message, 'message', ApiClient.busyMessage)),
      );
      expect(requests, hasLength(1 + ApiClient.maxBusyRetries));
      expect(ApiClient.serverBusy.value, isTrue); // bant "sunucu yoğun, tekrar deneniyor" gösterir
      expect(await ApiClient.getToken(), 'test-secret-token'); // oturum sağlam
    });

    test('bekleme üstel artar (jitterli) ve 8 sn tavanını aşmaz', () async {
      await expectLater(run([() => _json(503, '{}')], () => ApiClient.get('/x')), throwsA(isA<ApiException>()));
      expect(sleeps, hasLength(3));
      expect(sleeps[0], durBetween(const Duration(milliseconds: 500), const Duration(seconds: 1)));
      expect(sleeps[1], durBetween(const Duration(seconds: 1), const Duration(seconds: 2)));
      expect(sleeps[2], durBetween(const Duration(seconds: 2), const Duration(seconds: 4)));
    });

    test('Retry-After (saniye) beklemeden kısa olmaz; çok büyük değer 20 sn ile sınırlanır', () async {
      await run([() => _json(503, '{}', headers: {'retry-after': '7'}), () => _json(200, '{}')], () => ApiClient.get('/x'));
      expect(sleeps.single, const Duration(seconds: 7));

      sleeps.clear();
      await expectLater(
        run([() => _json(503, '{}', headers: {'retry-after': '3600'})], () => ApiClient.get('/x')),
        throwsA(isA<ApiException>().having((e) => e.retryAfter, 'retryAfter', ApiClient.maxRetryAfter)),
      );
      expect(sleeps, everyElement(ApiClient.maxRetryAfter));
    });

    test('429 de aynı şekilde geçici sayılır: oturum kapanmaz', () async {
      await expectLater(
        run([() => _json(429, '{"error":"çok istek"}', headers: {'retry-after': '1'})], () => ApiClient.get('/parking/debt')),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 's', 429).having((e) => e.isBusy, 'busy', isTrue)),
      );
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('POST (yazma) 503: yeniden DENENMEZ (çift işlem riski), Retry-After hatada taşınır, oturum korunur', () async {
      await expectLater(
        run([() => _json(503, '{}', headers: {'retry-after': '12'})],
            () => ApiClient.post('/parking/check-out', query: {'plate': '34ABC123'})),
        throwsA(isA<ApiException>()
            .having((e) => e.isBusy, 'busy', isTrue)
            .having((e) => e.retryAfter, 'retryAfter', const Duration(seconds: 12))),
      );
      expect(requests, hasLength(1));
      expect(sleeps, isEmpty);
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('ağ hatası/zaman aşımı oturumu kapatmaz', () async {
      await expectLater(
        http.runWithClient(() => ApiClient.get('/me'), () => MockClient((_) async => throw http.ClientException('kesik'))),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', isNull)),
      );
      expect(await ApiClient.getToken(), 'test-secret-token');
    });
  });

  group('401: yalnızca teyitli gerçek 401 oturumu kapatır', () {
    test('401 sonra 200 (geçici 401, aşırı yük): yeniden denenir, oturum KORUNUR', () async {
      final r = await run([() => _json(401, '{"error":"x"}'), () => _json(200, '{"ok":1}')], () => ApiClient.get('/me'));
      expect(r, {'ok': 1});
      expect(requests, hasLength(2));
      expect(sleeps.single, durBetween(const Duration(seconds: 1), const Duration(seconds: 2)));
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('401 sonra 503: oturum KORUNUR (çıkış yok), hata "yoğun"', () async {
      await expectLater(
        run([() => _json(401, '{}'), () => _json(503, '{}')], () => ApiClient.post('/parking/check-in')),
        throwsA(isA<ApiException>().having((e) => e.isBusy, 'busy', isTrue)),
      );
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('401 sonra ağ hatası: oturum KORUNUR', () async {
      var n = 0;
      await expectLater(
        http.runWithClient(
            () => ApiClient.get('/me'),
            () => MockClient((req) async {
                  requests.add(req);
                  if (n++ == 0) return _json(401, '{}');
                  throw http.ClientException('kesik');
                })),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', isNull)),
      );
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('yazma (POST) + 401: istek YENİDEN GÖNDERİLMEZ, oturum GET /me ile teyit edilir; geçerliyse çıkış yok', () async {
      await expectLater(
        run([() => _json(401, '{}'), () => _json(200, '{}')], () => ApiClient.post('/parking/check-in', query: {'plate': 'X'})),
        throwsA(isA<ApiException>().having((e) => e.isBusy, 'busy', isTrue)),
      );
      expect(requests.map((r) => '${r.method} ${r.url.path.split('/').last}'), ['POST check-in', 'GET me']);
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('yazma + 401 ve /me de 401: çıkış yapılır, yazma yine tek kez gönderilmiştir', () async {
      await expectLater(
        run([() => _json(401, '{}')], () => ApiClient.put('/admin/x', body: {'a': 1})),
        throwsA(isA<ApiException>().having((e) => e.isUnauthorized, 'isUnauthorized', isTrue)),
      );
      expect(requests.where((r) => r.method == 'PUT'), hasLength(1));
      expect(await ApiClient.getToken(), isNull);
    });

    test('yazma + 401 sonra /me ağ hatası: oturum korunur', () async {
      var n = 0;
      await expectLater(
        http.runWithClient(
            () => ApiClient.post('/parking/check-in'),
            () => MockClient((req) async {
                  requests.add(req);
                  if (n++ == 0) return _json(401, '{}');
                  throw http.ClientException('kesik');
                })),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', isNull)),
      );
      expect(requests.where((r) => r.method == 'POST'), hasLength(1));
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('401 sonra yine 401 (gerçekten geçersiz oturum): token silinir, 401 atılır', () async {
      await expectLater(
        run([() => _json(401, '{"error":"x"}')], () => ApiClient.get('/me')),
        throwsA(isA<ApiException>().having((e) => e.isUnauthorized, 'isUnauthorized', isTrue)),
      );
      expect(requests, hasLength(2)); // bir teyit denemesi
      expect(await ApiClient.getToken(), isNull);
    });

    test('giriş isteği (auth: false) 401: yeniden denenmez, oturum işlemine dokunulmaz', () async {
      await expectLater(
        run([() => _json(401, '{"error":"Kullanıcı adı veya şifre hatalı","code":"BAD_CREDENTIALS"}')],
            () => ApiClient.post('/auth/login', body: {'username': 'a', 'password': 'b'}, auth: false)),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 401)),
      );
      expect(requests, hasLength(1));
      expect(sleeps, isEmpty);
      expect(await ApiClient.getToken(), 'test-secret-token');
    });

    test('sessionIsInvalid: 503 -> false (oturum sağlam), gerçek 401 -> true ve çıkış', () async {
      expect(await run([() => _json(503, '{}')], ApiClient.sessionIsInvalid), isFalse);
      expect(await ApiClient.getToken(), 'test-secret-token');

      expect(await run([() => _json(200, '{}')], ApiClient.sessionIsInvalid), isFalse);

      expect(await run([() => _json(401, '{}')], ApiClient.sessionIsInvalid), isTrue);
      expect(await ApiClient.getToken(), isNull);
    });
  });

  test('başarılı istek "sunucu yoğun" notunu kaldırır', () async {
    ApiClient.serverBusy.value = true;
    await run([() => _json(200, '[]')], () => ApiClient.get('/parking/filter'));
    expect(ApiClient.serverBusy.value, isFalse);
  });
}
