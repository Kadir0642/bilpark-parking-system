import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/live_sync.dart';
import 'package:bilpark/core/tariff.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/core/vehicle_types.dart';
import 'package:bilpark/widgets/entry_sheet.dart';

const _active = [
  {'code': 'SMALL', 'displayName': 'Otomobil', 'iconKey': 'car', 'sortOrder': 10},
  {'code': 'LARGE', 'displayName': 'Kamyonet', 'iconKey': 'truck', 'sortOrder': 20},
];
const _withMoto = [
  ..._active,
  {'code': 'MOTORCYCLE', 'displayName': 'Motosiklet', 'iconKey': 'motorcycle', 'sortOrder': 30},
];

Map<String, dynamic> _tariffJson({bool motoEnabled = false}) => {
      'id': 7,
      'status': 'ACTIVE',
      'freeMinutes': 5,
      'paidStart': '08:00',
      'paidEnd': '22:00',
      'small': {'firstHour': 25.0, 'extraHour': 15.0},
      'large': {'firstHour': 50.0, 'extraHour': 30.0},
      'vehicleTypes': [
        {'code': 'SMALL', 'displayName': 'Otomobil', 'iconKey': 'car', 'sortOrder': 10, 'enabled': true, 'firstHour': 25.0, 'extraHour': 15.0, 'subscriptionMonthly': 2500.0},
        {'code': 'LARGE', 'displayName': 'Kamyonet', 'iconKey': 'truck', 'sortOrder': 20, 'enabled': true, 'firstHour': 50.0, 'extraHour': 30.0, 'subscriptionMonthly': 3000.0},
        {
          'code': 'MOTORCYCLE', 'displayName': 'Motosiklet', 'iconKey': 'motorcycle', 'sortOrder': 30, 'enabled': motoEnabled,
          'firstHour': motoEnabled ? 10.0 : null, 'extraHour': motoEnabled ? 5.0 : null, 'subscriptionMonthly': motoEnabled ? 800.0 : null,
        },
      ],
    };

void main() {
  setUp(() {
    VehicleTypeStore.reset();
    TariffStore.reset();
  });

  test('yerleşik liste: Otomobil ve Kamyonet', () {
    expect(VehicleTypeStore.active.value.map((t) => t.code), ['SMALL', 'LARGE']);
    expect(labelFor('SMALL'), 'Otomobil');
    expect(labelFor('LARGE'), 'Kamyonet');
    expect(labelFor(null), 'Otomobil');
    expect(labelFor('XYZ'), 'XYZ');
  });

  test('/vehicle-types gövdesi sortOrder sırasıyla ayrıştırılır', () {
    VehicleTypeStore.applyList(_withMoto.reversed.toList());
    expect(VehicleTypeStore.active.value.map((t) => t.code), ['SMALL', 'LARGE', 'MOTORCYCLE']);
    expect(labelFor('MOTORCYCLE'), 'Motosiklet');
    VehicleTypeStore.applyList('bozuk'); // bozuk yanıt listeyi silmez
    expect(VehicleTypeStore.active.value.length, 3);
  });

  test('iconFor eşlemesi', () {
    expect(iconFor('car'), Icons.directions_car_outlined);
    expect(iconFor('truck'), Icons.local_shipping_outlined);
    expect(iconFor('motorcycle'), Icons.two_wheeler_outlined);
    expect(iconFor('???'), Icons.directions_car_outlined);
    expect(iconFor(null), Icons.directions_car_outlined);
  });

  test('tarife yükü pasif tipin adını/ikonunu öğretir', () {
    TariffStore.applyPayload(_tariffJson());
    expect(VehicleTypeStore.active.value.length, 2); // etkin liste değişmez
    expect(labelFor('MOTORCYCLE'), 'Motosiklet');
    expect(iconForCode('MOTORCYCLE'), Icons.two_wheeler_outlined);
  });

  test('Tariff vehicleTypes ayrıştırma ve fiyat arama', () {
    final t = Tariff.fromJson(_tariffJson(motoEnabled: true));
    expect(t.enabledTypes.map((e) => e.code), ['SMALL', 'LARGE', 'MOTORCYCLE']);
    expect(t.firstHour('MOTORCYCLE'), 10);
    expect(t.extraHour('MOTORCYCLE'), 5);
    expect(t.vehicleTypes!.last.subscriptionMonthly, 800);
    expect(t.firstHour('LARGE'), 50);
  });

  test('pasif/bilinmeyen/null tip SMALL fiyatına düşer; eski sunucuda small/large', () {
    final t = Tariff.fromJson(_tariffJson());
    expect(t.enabledTypes.map((e) => e.code), ['SMALL', 'LARGE']);
    expect(t.firstHour('MOTORCYCLE'), 25); // fiyatı null
    expect(t.firstHour('NOPE'), 25);
    expect(t.extraHour(null), 15);
    final legacy = Tariff.fromJson({..._tariffJson()}..remove('vehicleTypes'));
    expect(legacy.vehicleTypes, isNull);
    expect(legacy.enabledTypes.map((e) => e.code), ['SMALL', 'LARGE']);
    expect(legacy.firstHour('LARGE'), 50);
    expect(legacy.firstHour('MOTORCYCLE'), 25);
  });

  test('estimateFee motosiklet fiyatıyla', () {
    final t = Tariff.fromJson(_tariffJson(motoEnabled: true));
    final base = DateTime(2026, 10, 22, 10);
    expect(estimateFee(t, 'MOTORCYCLE', base, base.add(const Duration(minutes: 30))).fee, 10);
    expect(estimateFee(t, 'MOTORCYCLE', base, base.add(const Duration(minutes: 150))).fee, 10 + 2 * 5);
    expect(estimateFee(t, 'SMALL', base, base.add(const Duration(minutes: 30))).fee, 25);
  });

  test('VEHICLE_TYPES_UPDATED olayı yükü depoyu günceller', () {
    var n = 0;
    VehicleTypeStore.active.addListener(() => n++);
    final e = SyncEvent.fromJson({
      'version': 5,
      'type': 'VEHICLE_TYPES_UPDATED',
      'scope': 'GLOBAL',
      'payload': {'vehicleTypes': _withMoto},
    });
    VehicleTypeStore.applyPayload(e.payload);
    expect(VehicleTypeStore.active.value.length, 3);
    expect(n, 1);
    VehicleTypeStore.applyPayload(e.payload); // aynı liste: bildirim yok
    expect(n, 1);
  });

  testWidgets('giriş sayfası açıkken Motosiklet seçeneği belirir, kalkarsa ilk tipe dönülür', (tester) async {
    tester.view.physicalSize = const Size(360 * 3, 800 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    EntryResult? result;
    await tester.pumpWidget(MaterialApp(
      theme: BilParkTheme.light(),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () async => result = await showEntrySheet(context, '34ABC123'),
            child: const Text('aç'),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('aç'));
    await tester.pumpAndSettle();
    expect(find.text('Otomobil'), findsOneWidget);
    expect(find.text('Kamyonet'), findsOneWidget);
    expect(find.text('Motosiklet'), findsNothing);

    VehicleTypeStore.applyList(_withMoto);
    await tester.pumpAndSettle();
    expect(find.text('Motosiklet'), findsOneWidget);
    expect(find.byIcon(Icons.two_wheeler_outlined), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('Motosiklet'));
    await tester.pump();
    VehicleTypeStore.applyList(_active); // seçili tip düştü
    await tester.pumpAndSettle();
    expect(find.text('Motosiklet'), findsNothing);

    await tester.tap(find.text('Sağa park'));
    await tester.pumpAndSettle();
    expect(result?.type, 'SMALL');
  });
}
