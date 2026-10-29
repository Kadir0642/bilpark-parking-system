// Cadde ve bölüm listesi (GET /streets). Cadde adları sunucudan gelir; kod eski enum adıdır.

import 'package:flutter/foundation.dart';
import 'api_client.dart';

class ZoneInfo {
  final int id;
  final int zoneNumber;
  final String zoneName;
  final String? leftLandmark;
  final String? rightLandmark;

  const ZoneInfo({
    required this.id,
    required this.zoneNumber,
    required this.zoneName,
    this.leftLandmark,
    this.rightLandmark,
  });

  factory ZoneInfo.fromJson(Map<String, dynamic> j) => ZoneInfo(
        id: (j['id'] as num?)?.toInt() ?? 0,
        zoneNumber: (j['zoneNumber'] as num?)?.toInt() ?? 0,
        zoneName: (j['zoneName'] as String?) ?? '',
        leftLandmark: j['leftLandmark'] as String?,
        rightLandmark: j['rightLandmark'] as String?,
      );

  /// "Öncü Döner – Ziraat Bankası"; ikisi de yoksa null.
  String? get landmarks {
    final l = (leftLandmark ?? '').trim();
    final r = (rightLandmark ?? '').trim();
    if (l.isEmpty && r.isEmpty) return null;
    if (l.isEmpty) return r;
    if (r.isEmpty) return l;
    return '$l – $r';
  }
}

class StreetInfo {
  final int id;
  final String code;
  final String displayName;
  final int sortOrder;
  final List<ZoneInfo> zones;

  const StreetInfo({
    required this.id,
    required this.code,
    required this.displayName,
    required this.sortOrder,
    required this.zones,
  });

  factory StreetInfo.fromJson(Map<String, dynamic> j) => StreetInfo(
        id: (j['id'] as num?)?.toInt() ?? 0,
        code: (j['code'] as String?) ?? '',
        displayName: (j['displayName'] as String?) ?? (j['code'] as String?) ?? '',
        sortOrder: (j['sortOrder'] as num?)?.toInt() ?? 0,
        zones: [
          if (j['zones'] is List)
            for (final z in j['zones'] as List)
              if (z is Map) ZoneInfo.fromJson(Map<String, dynamic>.from(z)),
        ],
      );
}

class StreetStore {
  StreetStore._();

  static List<StreetInfo> _streets = const [];
  static bool _loaded = false;

  static List<StreetInfo> get streets => _streets;

  /// İlk çağrıda sunucudan alır, sonra önbellekten döner. Hata olursa eldeki liste korunur.
  static Future<List<StreetInfo>> load({bool force = false}) async {
    if (_loaded && !force) return _streets;
    try {
      final data = await ApiClient.get('/streets', auth: false);
      if (data is List) {
        _streets = [
          for (final e in data)
            if (e is Map) StreetInfo.fromJson(Map<String, dynamic>.from(e)),
        ];
        _loaded = true;
      }
    } catch (_) {}
    return _streets;
  }

  static StreetInfo? byCode(String code) {
    for (final s in _streets) {
      if (s.code == code) return s;
    }
    return null;
  }

  /// Bilinmeyen kodda kodun kendisi döner.
  static String displayName(String code) => byCode(code)?.displayName ?? code;

  @visibleForTesting
  static void seed(List<StreetInfo> list) {
    _streets = list;
    _loaded = true;
  }
}
