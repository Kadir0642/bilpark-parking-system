// Tarife modeli, ücret tahmini (sunucu FeeEngine'in birebir aynası) ve tarife deposu.
// Kesin tutarlar her zaman sunucudan gelir; estimateFee yalnızca liste kartlarındaki canlı tahmin içindir.

import 'package:flutter/foundation.dart';
import 'api_client.dart';
import 'format.dart';
import 'vehicle_types.dart';

/// Saat dilimi eki olmayan yerel saat; toLocal/UTC dönüşümü yapılmaz.
DateTime? _parseLocal(dynamic v) => v is String ? DateTime.tryParse(v) : null;

double _num(dynamic v, [double def = 0]) => v is num ? v.toDouble() : def;

/// Tarife sürümündeki tek bir araç tipinin satırı (`vehicleTypes` öğesi).
class TariffTypePrice {
  final VehicleTypeInfo info;
  final bool enabled;
  final double? firstHour, extraHour, subscriptionMonthly;

  const TariffTypePrice({
    required this.info,
    this.enabled = true,
    this.firstHour,
    this.extraHour,
    this.subscriptionMonthly,
  });

  String get code => info.code;

  static TariffTypePrice? tryParse(dynamic j) {
    final info = VehicleTypeInfo.tryParse(j);
    if (info == null) return null;
    final m = j as Map;
    double? n(dynamic v) => v is num ? v.toDouble() : null;
    return TariffTypePrice(
      info: info,
      enabled: m['enabled'] != false,
      firstHour: n(m['firstHour']),
      extraHour: n(m['extraHour']),
      subscriptionMonthly: n(m['subscriptionMonthly']),
    );
  }
}

class Tariff {
  final int id;
  final String status;
  final DateTime? effectiveFrom;
  final double smallFirstHour, smallExtraHour, largeFirstHour, largeExtraHour;
  final int freeMinutes;
  final String paidStart;
  final String paidEnd;
  final double subscriptionSmall, subscriptionLarge;
  final Tariff? upcoming;

  /// Sunucudaki `vehicleTypes` (pasifler dahil); null: eski sunucu, small/large alanları geçerli.
  final List<TariffTypePrice>? vehicleTypes;

  const Tariff({
    required this.id,
    required this.status,
    this.effectiveFrom,
    this.smallFirstHour = 0,
    this.smallExtraHour = 0,
    this.largeFirstHour = 0,
    this.largeExtraHour = 0,
    required this.freeMinutes,
    required this.paidStart,
    required this.paidEnd,
    this.subscriptionSmall = 0,
    this.subscriptionLarge = 0,
    this.upcoming,
    this.vehicleTypes,
  });

  factory Tariff.fromJson(Map<String, dynamic> j) {
    final small = j['small'] is Map ? j['small'] as Map : const {};
    final large = j['large'] is Map ? j['large'] as Map : const {};
    final sub = j['subscription'] is Map ? j['subscription'] as Map : const {};
    final up = j['upcoming'];
    List<TariffTypePrice>? types;
    if (j['vehicleTypes'] is List) {
      types = [
        for (final e in j['vehicleTypes'] as List)
          ?TariffTypePrice.tryParse(e),
      ];
      if (types.isEmpty) types = null;
    }
    return Tariff(
      id: (j['id'] as num?)?.toInt() ?? 0,
      status: (j['status'] as String?) ?? 'ACTIVE',
      effectiveFrom: _parseLocal(j['effectiveFrom']),
      smallFirstHour: _num(small['firstHour']),
      smallExtraHour: _num(small['extraHour']),
      largeFirstHour: _num(large['firstHour']),
      largeExtraHour: _num(large['extraHour']),
      freeMinutes: (j['freeMinutes'] as num?)?.toInt() ?? 0,
      paidStart: (j['paidStart'] as String?) ?? '08:00',
      paidEnd: (j['paidEnd'] as String?) ?? '22:00',
      subscriptionSmall: _num(sub['small']),
      subscriptionLarge: _num(sub['large']),
      upcoming: up is Map ? Tariff.fromJson(Map<String, dynamic>.from(up)) : null,
      vehicleTypes: types,
    );
  }

  /// Bu sürümde fiyatı gösterilecek tipler: `enabled` olanlar; eski sunucuda SMALL + LARGE.
  List<TariffTypePrice> get enabledTypes {
    final t = vehicleTypes;
    if (t == null) {
      return [
        for (final i in kFallbackVehicleTypes)
          TariffTypePrice(
            info: i,
            firstHour: firstHour(i.code),
            extraHour: extraHour(i.code),
            subscriptionMonthly: i.code == 'LARGE' ? subscriptionLarge : subscriptionSmall,
          ),
      ];
    }
    return t.where((e) => e.enabled).toList();
  }

  TariffTypePrice? _row(String? code) {
    final t = vehicleTypes;
    if (t == null) return null;
    for (final e in t) {
      if (e.code == code) return e;
    }
    return null;
  }

  /// Sunucu kuralı: kod null/bilinmiyor ya da fiyat yoksa SMALL fiyatı.
  double firstHour(String? vehicleType) {
    if (vehicleTypes == null) return vehicleType == 'LARGE' ? largeFirstHour : smallFirstHour;
    return _row(vehicleType)?.firstHour ?? _row('SMALL')?.firstHour ?? smallFirstHour;
  }

  double extraHour(String? vehicleType) {
    if (vehicleTypes == null) return vehicleType == 'LARGE' ? largeExtraHour : smallExtraHour;
    return _row(vehicleType)?.extraHour ?? _row('SMALL')?.extraHour ?? smallExtraHour;
  }
}

/// Günün tek bir ücretli/ücretsiz parçası.
class DaySegmentInfo {
  final DateTime date;
  final DateTime start;
  final DateTime end;
  final bool paid;
  final int minutes;

  const DaySegmentInfo({
    required this.date,
    required this.start,
    required this.end,
    required this.paid,
    required this.minutes,
  });

  factory DaySegmentInfo.fromJson(Map<String, dynamic> j) {
    final start = _parseLocal(j['start']) ?? DateTime.fromMillisecondsSinceEpoch(0);
    final end = _parseLocal(j['end']) ?? start;
    final date = _parseLocal(j['date']) ?? start;
    return DaySegmentInfo(
      date: DateTime(date.year, date.month, date.day),
      start: start,
      end: end,
      paid: j['paid'] == true,
      minutes: (j['minutes'] as num?)?.toInt() ?? end.difference(start).inMinutes,
    );
  }

  /// null veya bozuk veriye dayanıklı liste ayrıştırma.
  static List<DaySegmentInfo> listFrom(dynamic raw) {
    if (raw is! List) return const [];
    final out = <DaySegmentInfo>[];
    for (final e in raw) {
      if (e is! Map) continue;
      try {
        out.add(DaySegmentInfo.fromJson(Map<String, dynamic>.from(e)));
      } catch (_) {}
    }
    return out;
  }
}

class FeeResult {
  final int totalMinutes, paidMinutes, freeMinutes;
  final double fee;
  final List<DaySegmentInfo> segments;

  const FeeResult({
    required this.totalMinutes,
    required this.paidMinutes,
    required this.freeMinutes,
    required this.fee,
    required this.segments,
  });

  /// `/parking/fee-preview` ve `/parking/debt` gövdesi.
  factory FeeResult.fromJson(Map<String, dynamic> j) {
    final segs = DaySegmentInfo.listFrom(j['segments']);
    final total = (j['durationMinutes'] as num?)?.toInt() ?? segs.fold<int>(0, (a, s) => a + s.minutes);
    final paid = (j['paidMinutes'] as num?)?.toInt() ?? segs.where((s) => s.paid).fold<int>(0, (a, s) => a + s.minutes);
    final free = (j['freeMinutes'] as num?)?.toInt() ?? segs.where((s) => !s.paid).fold<int>(0, (a, s) => a + s.minutes);
    return FeeResult(
      totalMinutes: total,
      paidMinutes: paid,
      freeMinutes: free,
      fee: _num(j['fee']),
      segments: segs,
    );
  }
}

(int, int) _hm(String s) {
  final p = s.split(':');
  return (int.tryParse(p[0]) ?? 0, p.length > 1 ? (int.tryParse(p[1]) ?? 0) : 0);
}

DateTime _max(DateTime a, DateTime b) => a.isAfter(b) ? a : b;
DateTime _min(DateTime a, DateTime b) => a.isBefore(b) ? a : b;

/// Ücretli dakikaya göre tutar (FeeEngine.priceFor).
double _priceFor(Tariff t, String vehicleType, int paidMinutes) {
  if (paidMinutes <= t.freeMinutes) return 0.0;
  final first = t.firstHour(vehicleType);
  final extra = t.extraHour(vehicleType);
  if (paidMinutes <= 60) return first;
  final extraHours = (paidMinutes - 60 + 59) ~/ 60;
  return first + extraHours * extra;
}

/// Sunucudaki FeeEngine.calculate'in aynısı: gün gün döngü, ücretli pencere dışı ücretsiz,
/// ücretli saniyeler toplanıp dakikaya (taban) çevrilir, tarife bir kez uygulanır.
FeeResult estimateFee(Tariff t, String vehicleType, DateTime entry, DateTime exit) {
  if (!exit.isAfter(entry)) {
    return const FeeResult(totalMinutes: 0, paidMinutes: 0, freeMinutes: 0, fee: 0, segments: []);
  }
  final (psH, psM) = _hm(t.paidStart);
  final (peH, peM) = _hm(t.paidEnd);
  final endIsMidnight = peH == 0 && peM == 0;

  final segments = <DaySegmentInfo>[];
  var paidSeconds = 0;

  int add(DateTime date, DateTime from, DateTime to, bool paid) {
    if (!from.isBefore(to)) return 0;
    final dur = to.difference(from);
    segments.add(DaySegmentInfo(date: date, start: from, end: to, paid: paid, minutes: dur.inMinutes));
    return paid ? dur.inSeconds : 0;
  }

  final lastDay = DateTime(exit.year, exit.month, exit.day);
  for (var d = DateTime(entry.year, entry.month, entry.day);
      !d.isAfter(lastDay);
      d = DateTime(d.year, d.month, d.day + 1)) {
    final dayEnd = DateTime(d.year, d.month, d.day + 1);
    final lo = _max(entry, d);
    final hi = _min(exit, dayEnd);
    if (!lo.isBefore(hi)) continue;

    final wStart = DateTime(d.year, d.month, d.day, psH, psM);
    final wEnd = endIsMidnight ? dayEnd : DateTime(d.year, d.month, d.day, peH, peM);

    paidSeconds += add(d, lo, _min(hi, wStart), false);
    paidSeconds += add(d, _max(lo, wStart), _min(hi, wEnd), true);
    add(d, _max(lo, wEnd), hi, false);
  }

  final total = exit.difference(entry).inMinutes;
  final paid = paidSeconds ~/ 60;
  return FeeResult(
    totalMinutes: total,
    paidMinutes: paid,
    freeMinutes: total - paid,
    fee: _priceFor(t, vehicleType, paid),
    segments: segments,
  );
}

/// Güncel tarifeyi tutar; ekranlar [current]'ı dinler.
class TariffStore {
  TariffStore._();

  static final ValueNotifier<Tariff?> current = ValueNotifier<Tariff?>(null);

  /// GET /tariff/current. Hata olursa eski değer korunur.
  static Future<Tariff?> load() async {
    try {
      final data = await ApiClient.get('/tariff/current', auth: false);
      if (data is Map) applyPayload(Map<String, dynamic>.from(data));
    } catch (_) {}
    return current.value;
  }

  /// TARIFF_UPDATED olayının payload'ı (current ile aynı biçim).
  static void applyPayload(Map<String, dynamic> payload) {
    try {
      final t = Tariff.fromJson(payload);
      // Pasif tiplerin adı/ikonu da öğrenilir (park hâlindeki araç kartı için)
      VehicleTypeStore.learn(t.vehicleTypes?.map((e) => e.info) ?? const []);
      current.value = t;
    } catch (_) {}
  }

  @visibleForTesting
  static void reset() => current.value = null;
}

/// "İlk saat ₺25, sonraki her saat ₺15 · ilk 5 dk ücretsiz · ücretli 08:00–22:00"
String tariffSummary(Tariff t, String vehicleType) {
  final end = t.paidEnd == '00:00' ? '24:00' : t.paidEnd;
  return 'İlk saat ${formatMoney(t.firstHour(vehicleType))}, sonraki her saat ${formatMoney(t.extraHour(vehicleType))}'
      ' · ilk ${t.freeMinutes} dk ücretsiz · ücretli ${t.paidStart}–$end';
}
