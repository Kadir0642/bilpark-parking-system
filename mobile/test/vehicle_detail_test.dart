import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/tariff.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/vehicle_detail_screen.dart';

const _tariff = Tariff(
  id: 1,
  status: 'ACTIVE',
  smallFirstHour: 25,
  smallExtraHour: 15,
  largeFirstHour: 40,
  largeExtraHour: 25,
  freeMinutes: 5,
  paidStart: '08:00',
  paidEnd: '22:00',
  subscriptionSmall: 600,
  subscriptionLarge: 900,
);

// Sözleşme örneği: 22 Eki 20:00 girişli, 4 parça, 240 dk ücretli, 600 dk ücretsiz
final _preview = <String, dynamic>{
  'plate': '34ABC123',
  'vehicleType': 'SMALL',
  'entryTime': '2026-10-22T20:00:00',
  'durationMinutes': 840,
  'paidMinutes': 240,
  'freeMinutes': 600,
  'fee': 70.0,
  'hasSubscription': false,
  'segments': [
    {'date': '2026-10-22', 'start': '2026-10-22T20:00:00', 'end': '2026-10-22T22:00:00', 'paid': true, 'minutes': 120},
    {'date': '2026-10-22', 'start': '2026-10-22T22:00:00', 'end': '2026-10-23T00:00:00', 'paid': false, 'minutes': 120},
    {'date': '2026-10-23', 'start': '2026-10-23T00:00:00', 'end': '2026-10-23T08:00:00', 'paid': false, 'minutes': 480},
    {'date': '2026-10-23', 'start': '2026-10-23T08:00:00', 'end': '2026-10-23T10:00:00', 'paid': true, 'minutes': 120},
  ],
};

final _vehicle = {
  'currentPlate': '34ABC123',
  'currentType': 'SMALL',
  'entryTime': '2026-10-22T20:00:00',
  'side': 'LEFT',
  'hasSubscription': false,
  'accumulatedDebt': 0.0,
};

Widget _host(Future<Map<String, dynamic>> Function(String) load, {void Function(String, double)? onPay}) => MaterialApp(
      theme: BilParkTheme.light(),
      home: VehicleDetailScreen(
        vehicle: _vehicle,
        onPay: onPay ?? (_, _) {},
        onRunaway: () {},
        loadPreview: load,
      ),
    );

void main() {
  setUp(() => TariffStore.current.value = _tariff);
  tearDown(() => TariffStore.reset());

  testWidgets('sunucu önizlemesi gün gün döküm, tutar ve ödeme düğmesini gösterir', (tester) async {
    double? paid;
    String? method;
    await tester.pumpWidget(_host((_) async => _preview, onPay: (m, a) {
      method = m;
      paid = a;
    }));
    await tester.pump(); // önizleme tamamlanır

    expect(find.text('22 Eki Per'), findsOneWidget);
    expect(find.text('23 Eki Cum'), findsOneWidget);
    expect(find.text('Toplam'), findsOneWidget);
    expect(find.text('4 sa'), findsOneWidget); // ücretli toplam
    expect(find.text('10 sa'), findsOneWidget); // ücretsiz toplam
    expect(find.text('Tutar'), findsOneWidget);
    expect(find.text('₺70'), findsNWidgets(2)); // "Ödenecek tutar" kutusu + döküm
    expect(find.textContaining('Ödemeyi al · ₺70'), findsOneWidget);
    expect(find.textContaining('tahmini'), findsNothing);

    await tester.tap(find.textContaining('Ödemeyi al'));
    await tester.pumpAndSettle();
    expect(method, 'CASH');
    expect(paid, 70);

    await tester.pumpWidget(const SizedBox.shrink()); // 1 sn'lik zamanlayıcı kapanır
  });

  testWidgets('önizleme hatasında hata ve "Tekrar dene" görünür, tekrar deneyince düzelir', (tester) async {
    var calls = 0;
    await tester.pumpWidget(_host((_) async {
      calls++;
      if (calls == 1) throw const ApiException(null, null, 'Sunucuya ulaşılamadı');
      return _preview;
    }));
    await tester.pump();

    expect(find.text('Sunucuya ulaşılamadı'), findsOneWidget);
    expect(find.text('Tekrar dene'), findsOneWidget);
    expect(find.textContaining('Ödemeyi al'), findsOneWidget);

    await tester.tap(find.text('Tekrar dene'));
    await tester.pump();
    await tester.pump();
    expect(find.text('Tekrar dene'), findsNothing);
    expect(find.text('22 Eki Per'), findsOneWidget);
    expect(calls, 2);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}
