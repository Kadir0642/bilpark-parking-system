import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/format.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/widgets/connection_banner.dart';
import 'package:bilpark/core/live_sync.dart';
import 'package:bilpark/widgets/plate_badge.dart';
import 'package:bilpark/widgets/status_badge.dart';

Widget _host(Widget child, {ThemeData? theme}) =>
    MaterialApp(theme: theme ?? BilParkTheme.light(), home: Scaffold(body: child));

void main() {
  testWidgets('PlateBadge plakayı biçimlenmiş yazar', (tester) async {
    await tester.pumpWidget(_host(const PlateBadge('11ABC123')));
    expect(find.text('11 ABC 123'), findsOneWidget);
  });

  testWidgets('ConnectionBanner çevrimdışı iken son senkron saatini yazar', (tester) async {
    await tester.pumpWidget(_host(
      ConnectionBanner(status: LiveStatus.offline, lastSync: DateTime(2026, 10, 23, 9, 41), pending: 2),
      theme: BilParkTheme.dark(),
    ));
    expect(find.text('Çevrimdışı · son senkron 09:41'), findsOneWidget);
    expect(find.text('2 işlem bekliyor'), findsOneWidget);
  });

  testWidgets('ConnectionBanner durum metinleri', (tester) async {
    await tester.pumpWidget(_host(const ConnectionBanner(status: LiveStatus.connecting)));
    expect(find.text('Bağlanılıyor…'), findsOneWidget);
    await tester.pumpWidget(_host(ConnectionBanner(status: LiveStatus.online, lastSync: DateTime(2026, 1, 1, 8, 5))));
    expect(find.text('Çevrimiçi · son senkron 08:05'), findsOneWidget);
    await tester.pumpWidget(_host(const ConnectionBanner(status: LiveStatus.reconnecting)));
    expect(find.text('Yeniden bağlanıyor…'), findsOneWidget);
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
