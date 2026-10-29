// Uygulama sürümü ve cihaz bilgisi: her isteğe X-App-Version / X-Device başlığı olarak eklenir.
// Değerler bir kez okunup önbelleklenir; okuma hata verirse ilgili başlık gönderilmez.

import 'dart:io' show Platform;
import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:package_info_plus/package_info_plus.dart';

class AppInfo {
  AppInfo._();

  static const int maxDeviceLength = 80;

  static Map<String, String>? _cache;
  static Future<Map<String, String>>? _loading;

  /// Test için okuyucuları değiştirir.
  @visibleForTesting
  static Future<String?> Function() versionReader = _readVersion;
  @visibleForTesting
  static Future<String?> Function() deviceReader = _readDevice;

  @visibleForTesting
  static void reset() {
    _cache = null;
    _loading = null;
    versionReader = _readVersion;
    deviceReader = _readDevice;
  }

  /// `X-App-Version` ve `X-Device` (okunabilenler). Hata durumunda boş harita; çökmez.
  static Future<Map<String, String>> headers() {
    final c = _cache;
    if (c != null) return Future.value(c);
    return _loading ??= _load();
  }

  static Future<Map<String, String>> _load() async {
    final h = <String, String>{};
    try {
      final v = await versionReader();
      if (v != null && v.isNotEmpty) h['X-App-Version'] = _clip(v);
    } catch (_) {}
    try {
      final d = await deviceReader();
      if (d != null && d.isNotEmpty) h['X-Device'] = _clip(d);
    } catch (_) {}
    _cache = h;
    return h;
  }

  /// Başlık değeri: yalnız yazdırılabilir ASCII, en fazla 80 karakter.
  static String _clip(String s) {
    final clean = s.replaceAll(RegExp(r'[^\x20-\x7E]'), '').trim();
    return clean.length > maxDeviceLength ? clean.substring(0, maxDeviceLength) : clean;
  }

  // flutter test altında eklenti kanalı yanıt vermez (pumpAndSettle takılır): okuma atlanır.
  static bool get _inTest => Platform.environment.containsKey('FLUTTER_TEST');

  static Future<String?> _readVersion() async {
    if (_inTest) return null;
    final p = await PackageInfo.fromPlatform();
    return p.buildNumber.isEmpty ? p.version : '${p.version}+${p.buildNumber}';
  }

  static Future<String?> _readDevice() async {
    if (_inTest) return null;
    final plugin = DeviceInfoPlugin();
    if (Platform.isAndroid) {
      final a = await plugin.androidInfo;
      return '${a.model} / Android ${a.version.release}';
    }
    if (Platform.isIOS) {
      final i = await plugin.iosInfo;
      return '${i.utsname.machine} / iOS ${i.systemVersion}';
    }
    return null;
  }
}
