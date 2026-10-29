import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/tariff.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/widgets/day_breakdown.dart';

Widget _host(Widget child) =>
    MaterialApp(theme: BilParkTheme.light(), home: Scaffold(body: SingleChildScrollView(child: child)));

DaySegmentInfo seg(String date, String start, String end, bool paid, int minutes) => DaySegmentInfo(
      date: DateTime.parse(date),
      start: DateTime.parse(start),
      end: DateTime.parse(end),
      paid: paid,
      minutes: minutes,
    );

final example = [
  seg('2026-10-22', '2026-10-22T20:00:00', '2026-10-22T22:00:00', true, 120),
  seg('2026-10-22', '2026-10-22T22:00:00', '2026-10-23T00:00:00', false, 120),
  seg('2026-10-23', '2026-10-23T00:00:00', '2026-10-23T08:00:00', false, 480),
  seg('2026-10-23', '2026-10-23T08:00:00', '2026-10-23T10:00:00', true, 120),
];

void main() {
  testWidgets('4 parçalı örnek 2 gün satırına gruplanır', (tester) async {
    await tester.pumpWidget(_host(DayBreakdown(segments: example, fee: 70)));
    expect(find.text('22 Eki Per'), findsOneWidget);
    expect(find.text('23 Eki Cum'), findsOneWidget);
    expect(find.text('2 sa'), findsNWidgets(3)); // 22 ücretli + 22 ücretsiz + 23 ücretli
    expect(find.text('8 sa'), findsOneWidget);
    expect(find.text('Toplam'), findsOneWidget);
    expect(find.text('4 sa'), findsOneWidget);
    expect(find.text('10 sa'), findsOneWidget);
    expect(find.text('Tutar'), findsOneWidget);
    expect(find.text('₺70'), findsOneWidget);
    expect(find.text('20:00–22:00 ücretli · 22:00–24:00 ücretsiz'), findsOneWidget);
    expect(find.text('00:00–08:00 ücretsiz · 08:00–10:00 ücretli'), findsOneWidget);
  });

  testWidgets('fee verilmezse Tutar satırı yok', (tester) async {
    await tester.pumpWidget(_host(DayBreakdown(segments: example)));
    expect(find.text('Tutar'), findsNothing);
  });

  testWidgets('yükleniyor durumu', (tester) async {
    await tester.pumpWidget(_host(const DayBreakdown(segments: null, loading: true)));
    expect(find.text('Döküm hesaplanıyor…'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });

  testWidgets('hata durumu ve Tekrar dene', (tester) async {
    var retried = 0;
    await tester.pumpWidget(_host(DayBreakdown(segments: null, error: 'Sunucuya ulaşılamadı.', onRetry: () => retried++)));
    expect(find.text('Sunucuya ulaşılamadı.'), findsOneWidget);
    await tester.tap(find.text('Tekrar dene'));
    expect(retried, 1);
  });

  testWidgets('boş liste: Henüz süre oluşmadı', (tester) async {
    await tester.pumpWidget(_host(const DayBreakdown(segments: [])));
    expect(find.text('Henüz süre oluşmadı'), findsOneWidget);
  });
}
