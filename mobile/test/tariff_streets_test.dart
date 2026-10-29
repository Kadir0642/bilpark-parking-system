import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/streets.dart';
import 'package:bilpark/core/tariff.dart';

const tariffJson = {
  'id': 1,
  'status': 'ACTIVE',
  'effectiveFrom': '2026-10-23T12:00:00',
  'small': {'firstHour': 25.0, 'extraHour': 15.0},
  'large': {'firstHour': 50.0, 'extraHour': 30.0},
  'freeMinutes': 5,
  'paidStart': '08:00',
  'paidEnd': '22:00',
  'subscription': {'small': 2500.0, 'large': 3000.0},
  'upcoming': null,
};

void main() {
  test('Tariff.fromJson', () {
    final t = Tariff.fromJson(tariffJson);
    expect(t.id, 1);
    expect(t.status, 'ACTIVE');
    expect(t.effectiveFrom, DateTime(2026, 10, 23, 12));
    expect(t.effectiveFrom!.isUtc, isFalse);
    expect(t.firstHour('SMALL'), 25);
    expect(t.extraHour('SMALL'), 15);
    expect(t.firstHour('LARGE'), 50);
    expect(t.extraHour('LARGE'), 30);
    expect(t.firstHour('OTHER'), 25);
    expect(t.freeMinutes, 5);
    expect(t.subscriptionSmall, 2500);
    expect(t.subscriptionLarge, 3000);
    expect(t.upcoming, isNull);
  });

  test('upcoming iç içe ayrıştırılır, tamsayı fiyatlar da kabul edilir', () {
    final t = Tariff.fromJson({
      ...tariffJson,
      'small': {'firstHour': 30, 'extraHour': 20},
      'upcoming': {...tariffJson, 'id': 2, 'status': 'SCHEDULED'},
    });
    expect(t.smallFirstHour, 30);
    expect(t.upcoming!.id, 2);
    expect(t.upcoming!.status, 'SCHEDULED');
  });

  test('tariffSummary', () {
    final t = Tariff.fromJson(tariffJson);
    expect(tariffSummary(t, 'SMALL'), 'İlk saat ₺25, sonraki her saat ₺15 · ilk 5 dk ücretsiz · ücretli 08:00–22:00');
    expect(tariffSummary(t, 'LARGE'), contains('₺50'));
  });

  test('TariffStore.applyPayload dinleyicileri tetikler', () {
    TariffStore.reset();
    var n = 0;
    TariffStore.current.addListener(() => n++);
    TariffStore.applyPayload(Map<String, dynamic>.from(tariffJson));
    expect(TariffStore.current.value!.id, 1);
    expect(n, 1);
    TariffStore.reset();
  });

  test('StreetInfo / ZoneInfo.fromJson', () {
    final s = StreetInfo.fromJson({
      'id': 1,
      'code': 'TEVFIK_BEY',
      'displayName': 'Tevfik Bey Caddesi',
      'sortOrder': 1,
      'zones': [
        {'id': 1, 'zoneNumber': 1, 'zoneName': 'Tevfik Bey - Bölüm 1', 'leftLandmark': 'Öncü Döner', 'rightLandmark': 'Ziraat Bankası'},
        {'id': 2, 'zoneNumber': 2, 'zoneName': 'Tevfik Bey - Bölüm 2', 'leftLandmark': null, 'rightLandmark': null},
      ],
    });
    expect(s.zones.length, 2);
    expect(s.zones[0].landmarks, 'Öncü Döner – Ziraat Bankası');
    expect(s.zones[1].landmarks, isNull);
  });

  test('StreetStore.displayName koda geri düşer', () {
    StreetStore.seed([
      const StreetInfo(id: 1, code: 'TEVFIK_BEY', displayName: 'Tevfik Bey Caddesi', sortOrder: 1, zones: []),
    ]);
    expect(StreetStore.displayName('TEVFIK_BEY'), 'Tevfik Bey Caddesi');
    expect(StreetStore.displayName('YENI_CADDE'), 'YENI_CADDE');
    expect(StreetStore.byCode('TEVFIK_BEY')!.id, 1);
    expect(StreetStore.byCode('X'), isNull);
    StreetStore.seed(const []);
  });
}
