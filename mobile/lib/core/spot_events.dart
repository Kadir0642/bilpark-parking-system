// Canlı araç olaylarını (SSE) elimizdeki "içerideki araçlar" listesine doğrudan uygular, böylece her olayda
// sunucudan GET /parking/filter çekmek gerekmez. Sözleşme: docs/api-asama3.md §5 (olay şeması).
//  - VEHICLE_EXITED / VEHICLE_RUNAWAY: yük plaka taşır, araç listeden çıkarılır (kesin, sunucuya gerek yok).
//  - VEHICLE_ENTERED: yük listedeki satırın büyük kısmını taşır, satır eklenir. Birikmiş borç (accumulatedDebt)
//    yükte yok; o yüzden çağıran birleştirilmiş tek bir tazeleme de zamanlar.

import 'live_sync.dart';

bool _samePlate(dynamic spot, String plate) => spot is Map && spot['currentPlate'] == plate;

/// ENTERED yükünden `/parking/filter` satırı biçiminde kayıt; yük eksikse null.
Map<String, dynamic>? spotFromEnteredPayload(Map<String, dynamic> p, {String? street}) {
  final plate = p['plate'];
  final entry = p['entryTime'];
  if (plate is! String || plate.isEmpty || entry is! String || DateTime.tryParse(entry) == null) return null;
  final zoneId = p['zoneId'];
  return <String, dynamic>{
    if (p['spotId'] != null) 'id': p['spotId'],
    'currentPlate': plate,
    'currentType': p['vehicleType'],
    'entryTime': entry,
    'side': p['side'],
    'hasSubscription': p['hasSubscription'] == true,
    'street': p['street'] ?? street,
    'zone': zoneId is num ? <String, dynamic>{'id': zoneId.toInt()} : null,
    if (p['foreignPlate'] == true) 'foreignPlate': true,
    if (p['foreignCountryCode'] != null) 'foreignCountryCode': p['foreignCountryCode'],
  };
}

/// Bir olayın hangi ekran verisini eskittiği. Yalnızca GÖRÜNEN ekranın verisi yenilenir; diğerleri
/// "kirli" kalır ve ekrana dönüldüğünde tazelenir.
enum RefreshTarget { dashSpots, dashSummary, map, history, runaways }

/// Olay türüne göre (araç olayları) sunucudan yeniden çekilmesi gereken veriler.
/// Çıkış/kaçak olayları araç listesini yükteki plakayla yerelde günceller; bu yüzden liste (dashSpots/map) istemez.
Set<RefreshTarget> targetsForEvent(String type) {
  switch (type) {
    case 'VEHICLE_ENTERED':
      // Yük satırı ekler; birikmiş borç yükte yok, o yüzden liste bir kez tazelenir
      return {RefreshTarget.dashSpots, RefreshTarget.map};
    case 'VEHICLE_EXITED':
      return {RefreshTarget.dashSummary, RefreshTarget.history};
    case 'VEHICLE_RUNAWAY':
      return {RefreshTarget.dashSummary, RefreshTarget.runaways};
    case 'VEHICLE_CANCELLED':
      return {RefreshTarget.dashSpots, RefreshTarget.map, RefreshTarget.dashSummary};
    case 'RECORD_CANCELLED':
      return {RefreshTarget.dashSummary, RefreshTarget.history, RefreshTarget.runaways};
    default:
      return const {};
  }
}

/// [spots] listesine olayı uygular. Değişiklik yoksa AYNI liste örneği döner.
List<dynamic> applyVehicleEvent(List<dynamic> spots, SyncEvent e) {
  final plate = e.payload['plate'];
  if (plate is! String || plate.isEmpty) return spots;
  switch (e.type) {
    case 'VEHICLE_EXITED' || 'VEHICLE_RUNAWAY':
      if (!spots.any((s) => _samePlate(s, plate))) return spots;
      return spots.where((s) => !_samePlate(s, plate)).toList();
    case 'VEHICLE_ENTERED':
      if (spots.any((s) => _samePlate(s, plate))) return spots;
      final spot = spotFromEnteredPayload(e.payload, street: e.street);
      return spot == null ? spots : [...spots, spot];
    default:
      return spots;
  }
}
