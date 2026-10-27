// BilPark uçtan uca test yardımcıları (gerçek telefonda, gerçek backend'e karşı).
//
// Çalıştırma: docs/e2e-testler.md. Kısaca:
//   flutter test integration_test/m1_saha_akisi_test.dart -d <cihaz> \
//     --dart-define=API_BASE=http://127.0.0.1:8082/api \
//     --dart-define=E2E_ADMIN_PASSWORD=... --dart-define=E2E_OFFICER_PASSWORD=...
//
// Telefon backend'e ve host yardımcısına (tools/host_helper.py) `adb reverse` ile ulaşır.

import 'dart:convert';
import 'dart:math';

import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/constants.dart';
import 'package:bilpark/main.dart' as app;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:integration_test/integration_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

class E2e {
  E2e._();

  static const String apiBase = AppConstants.apiBase;
  static const String helperBase = String.fromEnvironment('E2E_HELPER', defaultValue: 'http://127.0.0.1:8099');
  static const String adminUser = 'admin';
  static const String adminPassword = String.fromEnvironment('E2E_ADMIN_PASSWORD');
  static const String officerUser = 'tevfik';
  static const String officerPassword = String.fromEnvironment('E2E_OFFICER_PASSWORD');

  /// Önemli adımlar arasındaki bekleme; kullanıcı telefonda izleyebilsin diye.
  static const int stepMs = int.fromEnvironment('E2E_STEP_MS', defaultValue: 1200);
}

IntegrationTestWidgetsFlutterBinding ensureE2eBinding() {
  final b = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  // Animasyonlar gerçek zamanda aksın (izlenebilir olsun).
  b.framePolicy = LiveTestWidgetsFlutterBindingFramePolicy.fullyLive;
  return b;
}

final Random _rnd = Random();

/// Her koşuda benzersiz bir TR plakası: [prefix] iki rakam + harfler, ör. '11TA' → '11TA482'.
String uniquePlate(String prefix) => '$prefix${100 + _rnd.nextInt(900)}';

// --- Bekleme ve etkileşim ---

/// İzlenebilirlik için kısa bekleme ([factor] × E2E_STEP_MS), bu sırada ekran çizilmeye devam eder.
Future<void> pause(WidgetTester t, [double factor = 1]) => settle(t, Duration(milliseconds: (E2e.stepMs * factor).round()));

/// Gerçek zamanda [d] kadar kare çizer. pumpAndSettle sonsuz animasyonlarda takıldığı için kullanılmaz.
Future<void> settle(WidgetTester t, [Duration d = const Duration(milliseconds: 400)]) async {
  final end = DateTime.now().add(d);
  while (DateTime.now().isBefore(end)) {
    await Future<void>.delayed(const Duration(milliseconds: 50));
    await t.pump();
  }
}

/// [f] ekranda görünene kadar bekler; süre dolarsa açıklamalı TestFailure fırlatır.
Future<Finder> waitFor(WidgetTester t, Finder f, {Duration timeout = const Duration(seconds: 15), String? reason}) async {
  final end = DateTime.now().add(timeout);
  while (true) {
    await t.pump();
    if (f.evaluate().isNotEmpty) return f;
    if (DateTime.now().isAfter(end)) {
      throw TestFailure('${reason ?? 'Beklenen öğe görünmedi'} (${timeout.inSeconds} sn): $f');
    }
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
}

/// [f] ekrandan kaybolana kadar bekler.
Future<void> waitGone(WidgetTester t, Finder f, {Duration timeout = const Duration(seconds: 15), String? reason}) async {
  final end = DateTime.now().add(timeout);
  while (true) {
    await t.pump();
    if (f.evaluate().isEmpty) return;
    if (DateTime.now().isAfter(end)) {
      throw TestFailure('${reason ?? 'Öğe kaybolmadı'} (${timeout.inSeconds} sn): $f');
    }
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
}

/// Bekler, görünür kılar, dokunur ve kısa bir izleme beklemesi yapar. Birden çok eşleşmede ilkine dokunur.
Future<void> tapOn(WidgetTester t, Finder f, {Duration timeout = const Duration(seconds: 15), double pauseFactor = 1}) async {
  await waitFor(t, f, timeout: timeout);
  final target = f.first;
  await t.ensureVisible(target);
  await t.pump();
  await t.tap(target, warnIfMissed: false);
  await pause(t, pauseFactor);
}

/// TextField'a metin yazar (önceki metin silinir).
Future<void> typeInto(WidgetTester t, Finder f, String text) async {
  await waitFor(t, f);
  await t.ensureVisible(f.first);
  await t.enterText(f.first, text);
  await pause(t, 0.6);
}

/// Ekrandaki snackbar kapanana kadar bekler: alttaki "+" düğmesinin üstüne gelip dokunuşu yutuyor.
Future<void> waitNoSnackBar(WidgetTester t) =>
    waitGone(t, find.byType(SnackBar), timeout: const Duration(seconds: 12), reason: 'Bildirim kapanmadı');

/// labelText'i [label] olan TextField.
Finder fieldWithLabel(String label) =>
    find.byWidgetPredicate((w) => w is TextField && w.decoration?.labelText == label, description: 'TextField[$label]');

/// Açık diyalogları ve sayfaları kapatıp ilk rotaya döner (bir madde düştükten sonra toparlanmak için).
Future<void> popToRoot(WidgetTester t) async {
  navigatorKey.currentState?.popUntil((r) => r.isFirst);
  await settle(t, const Duration(milliseconds: 600));
}

// --- Uygulama başlatma ve giriş ---

/// Kayıtlı oturumu siler ve uygulamayı başlatır; giriş ekranını bekler.
Future<void> startApp(WidgetTester t) async {
  debugPrint('E2E-LOG|startApp: başlıyor');
  // Motor ilk kareye hazır olmadan pump çağrılırsa test açılış ekranında takılabiliyor; kısa bir gerçek bekleme.
  await Future<void>.delayed(const Duration(seconds: 2));
  final prefs = await SharedPreferences.getInstance();
  await prefs.remove(ApiClient.tokenKey);
  debugPrint('E2E-LOG|startApp: uygulama açılıyor');
  app.main();
  await Future<void>.delayed(const Duration(seconds: 2));
  await waitFor(t, find.text('Personel girişi'), timeout: const Duration(seconds: 30));
  debugPrint('E2E-LOG|startApp: giriş ekranı hazır');
  await pause(t);
}

/// Giriş ekranında kullanıcı adı/şifreyi yazıp "Giriş yap"a basar.
Future<void> loginAs(WidgetTester t, String username, String password) async {
  await waitFor(t, find.text('Personel girişi'), timeout: const Duration(seconds: 30));
  await typeInto(t, fieldWithLabel('Kullanıcı adı'), username);
  await typeInto(t, fieldWithLabel('Şifre'), password);
  await t.testTextInput.receiveAction(TextInputAction.done);
  await t.pump();
  final btn = find.text('Giriş yap');
  if (btn.evaluate().isNotEmpty) await tapOn(t, btn);
}

// --- İkinci istemci: doğrudan API ---

class ApiError implements Exception {
  final int status;
  final String body;
  ApiError(this.status, this.body);
  @override
  String toString() => 'HTTP $status: $body';
}

/// Testin "ikinci cihaz" / "panel" rolü: kendi token'ı ve cihaz kimliğiyle API'ye doğrudan istek atar.
class Api {
  final String token;
  final String deviceId;
  final Map<String, dynamic> loginData;
  Api._(this.token, this.deviceId, this.loginData);

  static Future<Api> login(String username, String password, {String? deviceId}) async {
    final dev = deviceId ?? 'e2e-$username-${_rnd.nextInt(1 << 30)}';
    final res = await http.post(Uri.parse('${E2e.apiBase}/auth/login'),
        headers: {'Content-Type': 'application/json', 'X-Device-Id': dev},
        body: jsonEncode({'username': username, 'password': password}));
    if (res.statusCode >= 300) throw ApiError(res.statusCode, utf8.decode(res.bodyBytes));
    final data = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
    return Api._(data['token'] as String, dev, data);
  }

  static Future<Api> admin() => login(E2e.adminUser, E2e.adminPassword, deviceId: 'e2e-panel');

  Map<String, String> get _headers =>
      {'Authorization': 'Bearer $token', 'X-Device-Id': deviceId, 'Content-Type': 'application/json'};

  Uri _uri(String path, Map<String, dynamic>? query) {
    final u = Uri.parse('${E2e.apiBase}$path');
    if (query == null) return u;
    return u.replace(queryParameters: {...u.queryParameters, ...query.map((k, v) => MapEntry(k, '$v'))});
  }

  dynamic _decode(http.Response res) {
    final text = utf8.decode(res.bodyBytes);
    if (res.statusCode >= 300) throw ApiError(res.statusCode, text);
    if (text.isEmpty) return null;
    try {
      return jsonDecode(text);
    } on FormatException {
      return text;
    }
  }

  /// [path] '/api' sonrası kısım, ör. '/admin/users'.
  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async =>
      _decode(await http.get(_uri(path, query), headers: _headers));

  Future<dynamic> post(String path, {Object? body, Map<String, dynamic>? query}) async =>
      _decode(await http.post(_uri(path, query), headers: _headers, body: body == null ? null : jsonEncode(body)));

  Future<dynamic> put(String path, {Object? body, Map<String, dynamic>? query}) async =>
      _decode(await http.put(_uri(path, query), headers: _headers, body: body == null ? null : jsonEncode(body)));
}

/// Backend'in yerel saatiyle (Europe/Istanbul) ISO tarih: '2026-10-27T15:40:00'. Telefonun saat dilimi de İstanbul varsayılır.
String isoLocal(DateTime d) {
  String two(int v) => v.toString().padLeft(2, '0');
  return '${d.year}-${two(d.month)}-${two(d.day)}T${two(d.hour)}:${two(d.minute)}:${two(d.second)}';
}

// --- Host yardımcısı (tools/host_helper.py) ---

class Host {
  Host._();

  static Future<String> _call(String path, [Map<String, String>? query, Duration timeout = const Duration(seconds: 120)]) async {
    final uri = Uri.parse('${E2e.helperBase}$path').replace(queryParameters: query);
    final res = await http.post(uri).timeout(timeout);
    final text = utf8.decode(res.bodyBytes);
    if (res.statusCode >= 300) throw ApiError(res.statusCode, text);
    return text;
  }

  /// Ağ kesintisi: test backend konteynerini durdurur.
  static Future<void> stopBackend() => _call('/backend/stop');

  /// Konteyneri başlatır ve /api/health 200 dönene kadar bekler.
  static Future<void> startBackend() => _call('/backend/start', null, const Duration(seconds: 180));

  /// Park hâlindeki aracın giriş zamanını [minutes] dakika geriye alır (ücret hesabı için).
  static Future<void> backdate(String plate, int minutes) =>
      _call('/backdate', {'plate': plate, 'minutes': '$minutes'});

  /// Telefon ekranının görüntüsünü host'a kaydeder (adb screencap).
  static Future<void> screenshot(String name) async {
    try {
      await _call('/screenshot', {'name': name}, const Duration(seconds: 30));
    } catch (e) {
      debugPrint('E2E ekran görüntüsü alınamadı: $e');
    }
  }

  static Future<void> result(String id, bool pass, String title, String message) async {
    try {
      await _call('/result', {'id': id, 'status': pass ? 'PASS' : 'FAIL', 'title': title, 'message': message});
    } catch (e) {
      debugPrint('E2E sonuç kaydedilemedi: $e');
    }
  }
}

// --- Madde bazında sonuç ---

/// Rapordaki her madde ayrı kaydedilir; biri düşse de sonrakiler denenir. Dosya sonunda [finish] çağrılır.
class E2eReport {
  final String file;
  final List<String> _failed = [];
  final List<String> _passed = [];
  E2eReport(this.file);

  Future<bool> item(WidgetTester t, String id, String title, Future<void> Function() body) async {
    debugPrint('E2E-START|$id|$title');
    try {
      await body();
      await pause(t, 0.5);
      await Host.screenshot('${id}_PASS');
      _passed.add(id);
      debugPrint('E2E-RESULT|$id|PASS|$title');
      await Host.result(id, true, title, '');
      return true;
    } catch (e, st) {
      final msg = '$e'.split('\n').take(12).join('\n');
      await Host.screenshot('${id}_FAIL');
      _failed.add(id);
      debugPrint('E2E-RESULT|$id|FAIL|$title|$msg');
      debugPrint('$st'.split('\n').take(8).join('\n'));
      await Host.result(id, false, title, msg);
      // Sonraki madde temiz bir yerden başlasın
      try {
        await popToRoot(t);
      } catch (_) {}
      return false;
    }
  }

  void finish() {
    debugPrint('E2E-SUMMARY|$file|passed=${_passed.join(',')}|failed=${_failed.join(',')}');
    expect(_failed, isEmpty, reason: 'Düşen maddeler: ${_failed.join(', ')}');
  }
}
