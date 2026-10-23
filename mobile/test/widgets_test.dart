import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/format.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/widgets/connection_banner.dart';
import 'package:bilpark/widgets/day_breakdown.dart';
import 'package:bilpark/widgets/plate_badge.dart';
import 'package:bilpark/widgets/status_badge.dart';

Widget _host(Widget child, {ThemeData? theme}) =>
    MaterialApp(theme: theme ?? BilParkTheme.light(), home: Scaffold(body: child));

void main() {
  testWidgets('PlateBadge plakayı biçimlenmiş yazar', (tester) async {
    await tester.pumpWidget(_host(const PlateBadge('11ABC123')));
    expect(find.text('11 ABC 123'), findsOneWidget);
  });

  testWidgets('DayBreakdown döküm yoksa toplam süre ve not gösterir', (tester) async {
    await tester.pumpWidget(_host(const DayBreakdown(total: Duration(hours: 3, minutes: 25))));
    expect(find.text('Toplam süre'), findsOneWidget);
    expect(find.text('3 sa 25 dk'), findsOneWidget);
    expect(find.text('Gün gün döküm tarife güncellemesiyle gelecek.'), findsOneWidget);
  });

  testWidgets('DayBreakdown döküm gelirse gün satırlarını gösterir', (tester) async {
    await tester.pumpWidget(_host(const DayBreakdown(
      total: Duration(hours: 5),
      breakdown: [
        {'date': '2026-10-22T00:00:00', 'paidMinutes': 105, 'freeMinutes': 120},
        {'date': '2026-10-23T00:00:00', 'paidMinutes': 100, 'freeMinutes': 480},
      ],
    )));
    expect(find.text('Gün gün süre dökümü'), findsOneWidget);
    expect(find.text('Toplam'), findsOneWidget);
    expect(find.text('Toplam süre'), findsNothing);
  });

  testWidgets('ConnectionBanner çevrimdışı iken son veri saatini yazar', (tester) async {
    await tester.pumpWidget(_host(
      ConnectionBanner(online: false, lastUpdate: DateTime(2026, 10, 23, 9, 41), pending: 2),
      theme: BilParkTheme.dark(),
    ));
    expect(find.text('Bağlantı yok · son veri 09:41'), findsOneWidget);
    expect(find.text('2 işlem bekliyor'), findsOneWidget);
  });

  testWidgets('StatusBadge etiketi gösterir', (tester) async {
    await tester.pumpWidget(_host(const StatusBadge('Abone', tone: BadgeTone.sub)));
    expect(find.text('Abone'), findsOneWidget);
  });

  test('biçimlendirme yardımcıları', () {
    expect(formatDurationText(const Duration(hours: 13, minutes: 25)), '13 sa 25 dk');
    expect(formatDurationText(const Duration(minutes: 52)), '52 dk');
    expect(formatClock(const Duration(hours: 2, minutes: 5, seconds: 8)), '02:05:08');
    expect(formatPlate('34abc123'), '34 ABC 123');
    expect(isForeignPlate('34MA1234'), isTrue);
    expect(formatMoney(70), '₺70');
  });
}
