import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/tariff.dart';

Tariff tariff({String start = '08:00', String end = '22:00', int free = 5}) => Tariff(
      id: 1,
      status: 'ACTIVE',
      smallFirstHour: 25,
      smallExtraHour: 15,
      largeFirstHour: 50,
      largeExtraHour: 30,
      freeMinutes: free,
      paidStart: start,
      paidEnd: end,
      subscriptionSmall: 2500,
      subscriptionLarge: 3000,
    );

void main() {
  final t = tariff();
  final base = DateTime(2026, 10, 24, 10, 0);

  test('sözleşme örneği: 20:00 -> ertesi gün 10:00 = 4 parça, 240 dk ücretli, 70 TL', () {
    final r = estimateFee(t, 'SMALL', DateTime(2026, 10, 22, 20), DateTime(2026, 10, 23, 10));
    expect(r.totalMinutes, 840);
    expect(r.paidMinutes, 240);
    expect(r.freeMinutes, 600);
    expect(r.fee, 70);
    expect(r.segments.length, 4);
    expect(r.segments.map((s) => s.paid), [true, false, false, true]);
    expect(r.segments.map((s) => s.minutes), [120, 120, 480, 120]);
    expect(r.segments.map((s) => s.date.day), [22, 22, 23, 23]);
  });

  test('kenar süreler: 5/6/60/61 dk', () {
    double fee(int m) => estimateFee(t, 'SMALL', base, base.add(Duration(minutes: m))).fee;
    expect(fee(5), 0);
    expect(fee(6), 25);
    expect(fee(60), 25);
    expect(fee(61), 40);
    expect(fee(120), 40);
    expect(fee(121), 55);
  });

  test('LARGE fiyatları', () {
    double fee(int m) => estimateFee(t, 'LARGE', base, base.add(Duration(minutes: m))).fee;
    expect(fee(5), 0);
    expect(fee(30), 50);
    expect(fee(61), 80);
    expect(estimateFee(t, 'LARGE', DateTime(2026, 10, 22, 20), DateTime(2026, 10, 23, 10)).fee, 50 + 3 * 30);
  });

  test('saniyeler toplanıp dakikaya taban alınır', () {
    final r = estimateFee(t, 'SMALL', base, base.add(const Duration(minutes: 6, seconds: 30)));
    expect(r.totalMinutes, 6);
    expect(r.paidMinutes, 6);
    final r2 = estimateFee(t, 'SMALL', base, base.add(const Duration(minutes: 5, seconds: 59)));
    expect(r2.paidMinutes, 5);
    expect(r2.fee, 0);
  });

  test('çıkış girişten önce/eşit ise boş sonuç', () {
    final r = estimateFee(t, 'SMALL', base, base);
    expect(r.fee, 0);
    expect(r.segments, isEmpty);
    expect(r.totalMinutes, 0);
  });

  test('ücretli saat dışında kalış ücretsiz', () {
    final r = estimateFee(t, 'SMALL', DateTime(2026, 10, 24, 23), DateTime(2026, 10, 25, 7));
    expect(r.paidMinutes, 0);
    expect(r.fee, 0);
    expect(r.segments.map((s) => s.paid), [false, false]);
  });

  test('paidEnd 00:00 gece yarısı demektir', () {
    final m = tariff(end: '00:00');
    final r = estimateFee(m, 'SMALL', DateTime(2026, 10, 24, 20), DateTime(2026, 10, 25, 1));
    expect(r.paidMinutes, 240);
    expect(r.segments.length, 2);
    expect(r.segments.first.end, DateTime(2026, 10, 25));
    expect(r.fee, 25 + 3 * 15);
  });

  test('00:00-00:00 tüm gün ücretli', () {
    final m = tariff(start: '00:00', end: '00:00');
    final r = estimateFee(m, 'SMALL', DateTime(2026, 10, 24, 23), DateTime(2026, 10, 25, 1));
    expect(r.paidMinutes, 120);
    expect(r.freeMinutes, 0);
    expect(r.segments.every((s) => s.paid), isTrue);
    expect(r.fee, 40);
  });

  test('FeeResult.fromJson eksik paid/free dakikayı parçalardan toplar', () {
    final r = FeeResult.fromJson({
      'fee': 70.0,
      'segments': [
        {'date': '2026-10-22', 'start': '2026-10-22T20:00:00', 'end': '2026-10-22T22:00:00', 'paid': true, 'minutes': 120},
        {'date': '2026-10-22', 'start': '2026-10-22T22:00:00', 'end': '2026-10-23T00:00:00', 'paid': false, 'minutes': 120},
      ],
    });
    expect(r.paidMinutes, 120);
    expect(r.freeMinutes, 120);
    expect(r.totalMinutes, 240);
    final r2 = FeeResult.fromJson({'durationMinutes': 840, 'paidMinutes': 240, 'freeMinutes': 600, 'fee': 70, 'segments': null});
    expect(r2.totalMinutes, 840);
    expect(r2.segments, isEmpty);
    expect(DaySegmentInfo.listFrom('garbage'), isEmpty);
    expect(DaySegmentInfo.listFrom([1, null, {}]).length, 1);
  });
}
