// Araç tipleri (SMALL, LARGE, MOTORCYCLE...) sunucudaki vehicle_types tablosundan gelir.
// Sözleşme: docs/api-arac-tipleri.md. Giriş formu yalnızca etkin tipleri gösterir; ad/ikon çözümleme
// ise pasifleşmiş tipleri de bilir (park hâlindeki bir motosiklet kapatıldıktan sonra da "Motosiklet" görünür).

import 'package:flutter/material.dart';
import 'api_client.dart';

class VehicleTypeInfo {
  final String code;
  final String displayName;
  final String iconKey;
  final int sortOrder;

  const VehicleTypeInfo({
    required this.code,
    required this.displayName,
    this.iconKey = 'car',
    this.sortOrder = 0,
  });

  /// Kodu olmayan öğe null döner.
  static VehicleTypeInfo? tryParse(dynamic j) {
    if (j is! Map) return null;
    final code = j['code'];
    if (code is! String || code.isEmpty) return null;
    final name = j['displayName'];
    return VehicleTypeInfo(
      code: code,
      displayName: name is String && name.isNotEmpty ? name : code,
      iconKey: (j['iconKey'] as String?) ?? 'car',
      sortOrder: (j['sortOrder'] as num?)?.toInt() ?? 0,
    );
  }

  /// Bozuk öğeleri atlayarak, sortOrder sırasıyla.
  static List<VehicleTypeInfo> listFrom(dynamic raw) {
    if (raw is! List) return const [];
    final out = <VehicleTypeInfo>[];
    for (final e in raw) {
      final t = tryParse(e);
      if (t != null) out.add(t);
    }
    out.sort((a, b) => a.sortOrder.compareTo(b.sortOrder));
    return out;
  }
}

/// İlk yüklemeden önce / hata durumunda kullanılan yerleşik liste.
const List<VehicleTypeInfo> kFallbackVehicleTypes = [
  VehicleTypeInfo(code: 'SMALL', displayName: 'Otomobil', iconKey: 'car', sortOrder: 10),
  VehicleTypeInfo(code: 'LARGE', displayName: 'Kamyonet', iconKey: 'truck', sortOrder: 20),
];

/// iconKey -> ikon. Bilinmeyen anahtar otomobil ikonudur.
IconData iconFor(String? iconKey) {
  switch (iconKey) {
    case 'truck':
      return Icons.local_shipping_outlined;
    case 'motorcycle':
      return Icons.two_wheeler_outlined;
    case 'car':
    default:
      return Icons.directions_car_outlined;
  }
}

/// Etkin araç tiplerini tutar; ekranlar [active]'i dinler.
class VehicleTypeStore {
  VehicleTypeStore._();

  /// Etkin tipler (sortOrder sırasıyla).
  static final ValueNotifier<List<VehicleTypeInfo>> active =
      ValueNotifier<List<VehicleTypeInfo>>(kFallbackVehicleTypes);

  /// Görülmüş tüm tipler (pasifler dahil): ad/ikon çözümlemesi için.
  static final Map<String, VehicleTypeInfo> _known = {
    for (final t in kFallbackVehicleTypes) t.code: t,
  };

  /// GET /vehicle-types (herkese açık). Hata olursa eski değer korunur.
  static Future<List<VehicleTypeInfo>> load() async {
    try {
      final data = await ApiClient.get('/vehicle-types', auth: false);
      applyList(data);
    } catch (_) {}
    return active.value;
  }

  /// /vehicle-types gövdesi ya da VEHICLE_TYPES_UPDATED yükündeki `vehicleTypes` listesi.
  static void applyList(dynamic raw) {
    final list = VehicleTypeInfo.listFrom(raw);
    if (list.isEmpty) return; // boş/bozuk yanıt mevcut listeyi silmez
    for (final t in list) {
      _known[t.code] = t;
    }
    if (!_same(active.value, list)) active.value = list;
  }

  /// VEHICLE_TYPES_UPDATED olayı: {"vehicleTypes":[...]}.
  static void applyPayload(Map<String, dynamic> payload) => applyList(payload['vehicleTypes']);

  /// Tarife yükündeki (pasifler dahil) tiplerden ad/ikon öğrenir; etkin listeyi değiştirmez.
  static void learn(Iterable<VehicleTypeInfo> types) {
    for (final t in types) {
      _known[t.code] = t;
    }
  }

  static bool _same(List<VehicleTypeInfo> a, List<VehicleTypeInfo> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      final x = a[i], y = b[i];
      if (x.code != y.code || x.displayName != y.displayName || x.iconKey != y.iconKey || x.sortOrder != y.sortOrder) {
        return false;
      }
    }
    return true;
  }

  @visibleForTesting
  static void reset() {
    active.value = kFallbackVehicleTypes;
    _known
      ..clear()
      ..addEntries(kFallbackVehicleTypes.map((t) => MapEntry(t.code, t)));
  }
}

/// Kodun görünen adı. null -> SMALL (sunucu kuralı); bilinmeyen kod olduğu gibi döner.
String labelFor(String? code) {
  final c = code ?? 'SMALL';
  return VehicleTypeStore._known[c]?.displayName ?? c;
}

/// Kodun ikonu; bilinmeyen kod otomobil ikonudur.
IconData iconForCode(String? code) => iconFor(VehicleTypeStore._known[code ?? 'SMALL']?.iconKey);
