// BilPark App - Merkezi API istemcisi
// Tüm ekranlar HTTP çağrılarını buradan yapar: token + X-Device-Id başlıkları,
// zaman aşımı, tek formatlı hata ayrıştırma ve 401'de otomatik girişe yönlendirme.

import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import '../screens/auth_screen.dart';
import 'app_info.dart';
import 'constants.dart';

/// Tüm uygulamanın tek Navigator'ı (401'de ekrandan bağımsız yönlendirme için).
final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

/// Sunucudan veya bağlantıdan gelen hata. [message] kullanıcıya gösterilebilir (Türkçe).
class ApiException implements Exception {
  final int? statusCode; // null: bağlantı/zaman aşımı
  final String? code; // ErrorCodes (ALREADY_PARKED, ALREADY_PROCESSED, ...)
  final String message;

  const ApiException(this.statusCode, this.code, this.message);

  bool get isUnauthorized => statusCode == 401;
  bool get isReadOnly => statusCode == 423;

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

  /// 423 READ_ONLY alınınca çağrılır (LicenseStore.attach bağlar; döngüsel import olmasın diye kanca).
  static void Function(String message)? onReadOnly;

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

  static Future<dynamic> post(String path, {Map<String, String>? query, Object? body, bool auth = true}) =>
      _send('POST', path, query: query, body: body, auth: auth);

  static Future<dynamic> put(String path, {Map<String, String>? query, Object? body, bool auth = true}) =>
      _send('PUT', path, query: query, body: body, auth: auth);

  static Future<dynamic> _send(String method, String path,
      {Map<String, String>? query, Object? body, bool auth = true}) async {
    final uri = Uri.parse('${AppConstants.apiBase}$path')
        .replace(queryParameters: (query == null || query.isEmpty) ? null : query);
    final headers = <String, String>{
      'X-Device-Id': await getDeviceId(),
      ...await AppInfo.headers(),
      if (body != null) 'Content-Type': 'application/json',
    };
    if (auth) {
      final token = await getToken();
      if (token != null) headers['Authorization'] = 'Bearer $token';
    }

    http.Response response;
    try {
      final encoded = body == null ? null : jsonEncode(body);
      final future = method == 'GET'
          ? http.get(uri, headers: headers)
          : method == 'PUT'
              ? http.put(uri, headers: headers, body: encoded)
              : http.post(uri, headers: headers, body: encoded);
      response = await future.timeout(timeout);
    } on TimeoutException {
      throw const ApiException(null, 'TIMEOUT', 'Sunucu yanıt vermedi. Lütfen tekrar deneyin.');
    } catch (_) {
      throw const ApiException(null, 'NETWORK', 'Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin.');
    }

    final text = utf8.decode(response.bodyBytes, allowMalformed: true);
    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (text.isEmpty) return null;
      try {
        return jsonDecode(text);
      } catch (_) {
        return text;
      }
    }

    final err = parseError(response.statusCode, text);
    if (response.statusCode == 401 && auth) {
      await handleUnauthorized();
      throw ApiException(401, err.code ?? 'UNAUTHORIZED', sessionExpiredMessage);
    }
    if (response.statusCode == 423) onReadOnly?.call(err.message);
    throw err;
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
