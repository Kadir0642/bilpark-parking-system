import 'package:bilpark/core/theme.dart';
import 'package:bilpark/widgets/entry_sheet.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Future<void> open(WidgetTester t, String initial) async {
    await t.pumpWidget(MaterialApp(
      theme: BilParkTheme.light(),
      home: Builder(
        builder: (c) => Scaffold(body: TextButton(onPressed: () => showEntrySheet(c, initial), child: const Text('aç'))),
      ),
    ));
    await t.tap(find.text('aç'));
    await t.pumpAndSettle();
  }

  bool enabled(WidgetTester t, String label) =>
      t.widget<FilledButton>(find.ancestor(of: find.text(label), matching: find.byWidgetPredicate((w) => w is FilledButton))).onPressed != null;

  testWidgets('boş plakada park düğmeleri kapalı, hint görünür', (t) async {
    await open(t, '');
    expect(enabled(t, 'Sola park'), isFalse);
    expect(enabled(t, 'Sağa park'), isFalse);
    expect(find.textContaining('Örnek'), findsOneWidget);
  });

  testWidgets('geçerli plaka yazınca açılır, bozunca kapanır', (t) async {
    await open(t, '');
    await t.enterText(find.byType(TextField), '34 abc 123');
    await t.pump();
    expect(find.text('Geçerli plaka'), findsOneWidget);
    expect(enabled(t, 'Sola park'), isTrue);
    await t.enterText(find.byType(TextField), '99 ABC 123');
    await t.pump();
    expect(find.textContaining('01-81'), findsOneWidget);
    expect(enabled(t, 'Sağa park'), isFalse);
  });
}
