// BilPark App - Merkezi API istemcisi
// Tüm ekranlar HTTP çağrılarını buradan yapar: token + X-Device-Id başlıkları,
// zaman aşımı, tek formatlı hata ayrıştırma, 401'de (teyitli) girişe yönlendirme ve
// 503/429'da (sunucu yoğun) oturuma dokunmadan nazik yeniden deneme.

import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:material_ui/material_ui.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import '../screens/auth_screen.dart';
import 'app_info.dart';
import 'backoff.dart';
import 'constants.dart';

/// Tüm uygulamanın tek Navigator'ı (401'de ekrandan bağımsız yönlendirme için).
final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

/// Sunucudan veya bağlantıdan gelen hata. [message] kullanıcıya gösterilebilir (Türkçe).
class ApiException implements Exception {
  final int? statusCode; // null: bağlantı/zaman aşımı
  final String? code; // ErrorCodes (ALREADY_PARKED, ALREADY_PROCESSED, ...)
  final String message;

  /// 503/429'daki Retry-After (varsa). Yeniden deneyenler en az bu kadar bekler.
  final Duration? retryAfter;

  const ApiException(this.statusCode, this.code, this.message, {this.retryAfter});

  bool get isUnauthorized => statusCode == 401;
  bool get isReadOnly => statusCode == 423;

  /// 503/429: sunucu geçici olarak yoğun. Oturum ya da istek hatası DEĞİLDİR; kimse çıkış yaptırılmaz.
  bool get isBusy => statusCode == 503 || statusCode == 429;

  @override
  String toString() => message;
}

class ApiClient {
  ApiClient._();

  static const String tokenKey = 'auth_token';
  static const String deviceIdKey = 'device_id';
  static const Duration timeout = Duration(seconds: 15);
  static const String sessionExpiredMessage = 'Oturumunuzun süresi doldu, lütfen tekrar giriş yapın.';
  static const String deactivatedMessage = 'Hesabınız yönetici tarafından pasife alındı. Oturumunuz kapatıldı.';
  static const String busyNote = 'Sunucu yoğun, tekrar deneniyor…';
  static const String busyMessage = 'Sunucu şu an yoğun. Lütfen birazdan tekrar deneyin.';

  /// 503/429'da yeniden denenen (okuma) isteklerin en çok kaç kez tekrarlanacağı.
  static const int maxBusyRetries = 3;

  /// Sunucu ne kadar büyük Retry-After verirse versin, tek bir istek en çok bu kadar bekler.
  static const Duration maxRetryAfter = Duration(seconds: 20);

  /// Gerçek 401'i teyit etmeden önce beklenen süre (jitterli: bu değerin 0,5-1 katı).
  static const Duration unauthorizedConfirmDelay = Duration(seconds: 2);

  /// 423 READ_ONLY alınınca çağrılır (LicenseStore.attach bağlar; döngüsel import olmasın diye kanca).
  static void Function(String message)? onReadOnly;

  /// true: son istek 503/429 aldı (sunucu yoğun); bir sonraki başarılı istekte false olur. Bağlantı bandı bunu gösterir.
  static final ValueNotifier<bool> serverBusy = ValueNotifier(false);

  /// Testlerde gerçek beklemeyi atlamak için.
  @visibleForTesting
  static Future<void> Function(Duration) sleeper = (d) => Future<void>.delayed(d);

  @visibleForTesting
  static Random? rng;

  static String? _token;
  static String? _deviceId;
  static bool _redirecting = false;

  // --- Token ---
  static Future<String?> getToken() async {
    if (_token != null) return _token;
    final prefs = await SharedPreferences.getInstance();
    _token = prefs.getString(tokenKey);
    return _token;
  }

  static Future<void> saveToken(String token) async {
    _token = token;
    _redirecting = false; // yeni oturum: bir sonraki 401 yeniden yönlendirebilir
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(tokenKey, token);
  }

  static Future<void> clearToken() async {
    _token = null;
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(tokenKey);
  }

  // --- Cihaz kimliği (bir kez üretilir, kalıcıdır) ---
  /// 128 bit rastgele, 32 karakter hex.
  static String generateDeviceId([Random? rng]) {
    final r = rng ?? Random.secure();
    return List.generate(16, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
  }

  static Future<String> getDeviceId() async {
    if (_deviceId != null) return _deviceId!;
    final prefs = await SharedPreferences.getInstance();
    var id = prefs.getString(deviceIdKey);
    if (id == null || id.isEmpty) {
      id = generateDeviceId();
      await prefs.setString(deviceIdKey, id);
    }
    _deviceId = id;
    return id;
  }

  /// Test için önbelleği sıfırlar.
  @visibleForTesting
  static void resetCache() {
    _token = null;
    _deviceId = null;
    _redirecting = false;
    serverBusy.value = false;
    sleeper = (d) => Future<void>.delayed(d);
    rng = null;
  }

  // --- Hata ayrıştırma ---
  static String defaultMessage(int status) {
    switch (status) {
      case 400:
        return 'Geçersiz istek.';
      case 401:
        return sessionExpiredMessage;
      case 403:
        return 'Bu işlem için yetkiniz yok.';
      case 404:
        return 'Kayıt bulunamadı.';
      case 423:
        return 'Lisans süresi doldu; sistem okunur modda. Giriş, çıkış ve kaçak işlemleri yapılamaz.';
      case 409:
        return 'İşlem çakıştı, lütfen listeyi yenileyip tekrar deneyin.';
      case 429:
      case 503:
        return busyMessage;
      default:
        if (status >= 500) return 'Sunucu hatası oluştu. Lütfen daha sonra tekrar deneyin.';
        return 'Beklenmeyen bir hata oluştu ($status).';
    }
  }

  /// `{"error","code"}` gövdesini [ApiException]'a çevirir; olmazsa duruma göre genel mesaj.
  static ApiException parseError(int status, String body) {
    String? message;
    String? code;
    try {
      final decoded = jsonDecode(body);
      if (decoded is Map) {
        final err = decoded['error'];
        if (err is String && err.isNotEmpty) message = err;
        final c = decoded['code'];
        if (c is String) code = c;
      }
    } catch (_) {}
    return ApiException(status, code, message ?? defaultMessage(status));
  }

  // --- İstekler ---
  static Future<dynamic> get(String path, {Map<String, String>? query, bool auth = true}) =>
      _send('GET', path, query: query, auth: auth);

  static Future<dynamic> post(String path,
          {Map<String, String>? query, Object? body, bool auth = true, Duration? timeout}) =>
      _send('POST', path, query: query, body: body, auth: auth, timeout: timeout);

  static Future<dynamic> put(String path, {Map<String, String>? query, Object? body, bool auth = true}) =>
      _send('PUT', path, query: query, body: body, auth: auth);

  /// Tek HTTP denemesi. Ağ/zaman aşımı [ApiException] (statusCode null) olarak atılır.
  static Future<http.Response> _once(String method, Uri uri, Object? body, bool auth, Duration limit) async {
    final headers = <String, String>{
      'X-Device-Id': await getDeviceId(),
      ...await AppInfo.headers(),
      if (body != null) 'Content-Type': 'application/json',
    };
    if (auth) {
      final token = await getToken();
      if (token != null) headers['Authorization'] = 'Bearer $token';
    }
    try {
      final encoded = body == null ? null : jsonEncode(body);
      final future = method == 'GET'
          ? http.get(uri, headers: headers)
          : method == 'PUT'
              ? http.put(uri, headers: headers, body: encoded)
              : http.post(uri, headers: headers, body: encoded);
      return await future.timeout(limit);
    } on TimeoutException {
      throw const ApiException(null, 'TIMEOUT', 'Sunucu yanıt vermedi. Lütfen tekrar deneyin.');
    } catch (_) {
      throw const ApiException(null, 'NETWORK', 'Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin.');
    }
  }

  /// İstek + hata politikası:
  ///  - 2xx: gövde döner, "sunucu yoğun" notu kalkar.
  ///  - 503/429 (sunucu yoğun): oturuma DOKUNULMAZ. Okuma (GET) istekleri Retry-After'a ya da jitterli üstel
  ///    beklemeye göre en çok [maxBusyRetries] kez yeniden denenir; yazma istekleri burada tekrarlanmaz
  ///    (çağıran karar verir; giriş kaydı kuyruğa alınır).
  ///  - 401: aşırı yükte sunucu geçerli oturuma da 401 dönebildiği için bir kez bekleyip AYNI isteği yeniden dener;
  ///    yalnızca ikinci yanıt da 401 ise oturum kapatılır. İkinci yanıt 503 ya da ağ hatasıysa oturum korunur.
  static Future<dynamic> _send(String method, String path,
      {Map<String, String>? query, Object? body, bool auth = true, Duration? timeout}) async {
    final uri = Uri.parse('${AppConstants.apiBase}$path')
        .replace(queryParameters: (query == null || query.isEmpty) ? null : query);
    final limit = timeout ?? ApiClient.timeout;
    final random = rng ?? Random();
    var busyRetries = 0;
    var unauthorizedConfirmed = false;

    while (true) {
      final response = await _once(method, uri, body, auth, limit);
      final status = response.statusCode;
      final text = utf8.decode(response.bodyBytes, allowMalformed: true);

      if (status >= 200 && status < 300) {
        serverBusy.value = false;
        if (text.isEmpty) return null;
        try {
          return jsonDecode(text);
        } catch (_) {
          return text;
        }
      }

      if (status == 401 && auth) {
        if (!unauthorizedConfirmed) {
          unauthorizedConfirmed = true;
          await sleeper(backoffDelay(1, base: unauthorizedConfirmDelay, cap: unauthorizedConfirmDelay, rng: random));
          if (method == 'GET') continue; // okuma: aynı istek yeniden gönderilir
          // Yazma (POST/PUT) YENİDEN GÖNDERİLMEZ: oturum GET /me ile teyit edilir.
          final me = Uri.parse('${AppConstants.apiBase}/me');
          // (ağ hatası burada ApiException olarak yayılır: oturum durumu bilinmiyor, çıkış yok)
          final check = await _once('GET', me, null, true, limit);
          if (check.statusCode == 401) {
            await handleUnauthorized();
            throw ApiException(401, 'UNAUTHORIZED', sessionExpiredMessage);
          }
          // Oturum geçerli (ya da sunucu yoğun): yazma sunucuda işlenmedi, çıkış yok. Çağıran (kuyruk/ekran)
          // geçici hata olarak ele alır ve kendi kuralıyla (aynı requestId ile) tekrar dener.
          serverBusy.value = true;
          throw ApiException(503, 'SERVER_BUSY', busyMessage);
        }
        final err = parseError(status, text);
        await handleUnauthorized();
        throw ApiException(401, err.code ?? 'UNAUTHORIZED', sessionExpiredMessage);
      }

      if (status == 503 || status == 429) {
        serverBusy.value = true;
        final retryAfter = parseRetryAfter(response.headers['retry-after'], cap: maxRetryAfter);
        if (method == 'GET' && busyRetries < maxBusyRetries) {
          busyRetries++;
          final wait = backoffDelay(busyRetries,
              base: const Duration(seconds: 1), cap: const Duration(seconds: 8), rng: random);
          await sleeper(retryAfter != null && retryAfter > wait ? retryAfter : wait);
          continue;
        }
        final err = parseError(status, text);
        throw ApiException(status, err.code ?? 'SERVER_BUSY', busyMessage, retryAfter: retryAfter);
      }

      final err = parseError(status, text);
      if (status == 423) onReadOnly?.call(err.message);
      throw err;
    }
  }

  /// Oturum gerçekten geçersiz mi? LiveSync (SSE) 401 aldığında çağırır: /me yeniden denenir;
  /// gerçek 401 ise [handleUnauthorized] çalışır ve true döner. 503, ağ hatası ya da başarı: false (oturum sağlam).
  static Future<bool> sessionIsInvalid() async {
    try {
      await get('/me');
      return false;
    } on ApiException catch (e) {
      return e.isUnauthorized;
    } catch (_) {
      return false;
    }
  }

  /// 401: token temizlenir, girişe dönülür. Polling zamanlayıcıları üst üste çağırsa da bir kez çalışır.
  static Future<void> handleUnauthorized() async {
    if (_redirecting) return;
    _redirecting = true;
    await clearToken();
    AppConstants.clearAuth();
    final nav = navigatorKey.currentState;
    if (nav == null) return;
    // Mesaj giriş formunun üstünde kalıcı uyarı olarak görünür
    nav.pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const AuthScreen(message: sessionExpiredMessage)), (route) => false);
  }
}
