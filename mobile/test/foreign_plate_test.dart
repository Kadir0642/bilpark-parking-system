// Yabancı / özel plaka (V10): plate.dart kuralı, giriş formu anahtarı, çevrimdışı kuyruk bayrağı, OCR.
import 'package:bilpark/core/entry_queue.dart';
import 'package:bilpark/core/plate.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/plate_scanner_screen.dart';
import 'package:bilpark/widgets/entry_sheet.dart';
import 'package:bilpark/widgets/plate_badge.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  group('plate.dart yabancı / özel kural', () {
    test('A-Z0-9, 2-12 karakter; boşluk ve tire sayılmaz', () {
      for (final ok in ['M AB 1234', '12-ABC-3', 'cd 1234', 'AB', 'ABCDEF 123456', '34 ABC 123', 'TAXI']) {
        expect(isValidForeignPlateInput(ok), isTrue, reason: ok);
        expect(foreignPlateHint(ok), isNull, reason: ok);
      }
      for (final bad in ['', 'A', 'ABCDEFG1234567', 'M.AB 1234', 'AB/12', 'ÇAB123', 'AB_12']) {
        expect(isValidForeignPlateInput(bad), isFalse, reason: bad);
        expect(foreignPlateHint(bad), isNotNull, reason: bad);
      }
      expect(foreignPlateHint('A'), 'En az 2 karakter');
      expect(foreignPlateHint('ABCDEFG1234567'), 'En fazla 12 karakter');
      expect(foreignPlateHint('AB.12'), 'Yalnızca A-Z harfleri ve rakam kullanın');
    });

    test('kural seçimi: anahtar kapalıyken TR, açıkken yabancı', () {
      expect(isValidPlateFor('M AB 1234', foreign: false), isFalse);
      expect(isValidPlateFor('M AB 1234', foreign: true), isTrue);
      expect(plateHintFor('M AB 1234', foreign: false), isNotNull);
      expect(plateHintFor('M AB 1234', foreign: true), isNull);
      expect(isValidPlateFor('34 ABC 123', foreign: false), isTrue);
    });

    test('OCR ham metin: TR düzeltmesi yok (O harfi 0 olmaz), en olası satır seçilir', () {
      expect(extractRawPlate('D\nM AB 1234'), 'MAB1234');
      expect(extractRawPlate('NL\n12-ABC-3\nAMSTERDAM'), '12ABC3');
      expect(extractRawPlate('OO 123'), 'OO123'); // TR modunda 00 olurdu
      expect(extractPlate('OO 123'), isNull);
      expect(extractRawPlate('CD · 1234'), 'CD1234');
      expect(extractRawPlate('A'), isNull);
      expect(extractRawPlate(''), isNull);
      expect(plateFromOcr('O6 AB 1234', foreign: false), '06AB1234');
      expect(plateFromOcr('O6 AB 1234', foreign: true), 'O6AB1234');
    });
  });

  group('çevrimdışı kuyruk', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('yabancı bayrağı kalıcıdır ve gönderimde foreignPlate=true gider', () async {
      final e = PendingEntry(requestId: 'rf1', plate: 'm ab-1234', street: 'S', type: 'SMALL', side: 'LEFT', foreign: true);
      expect(e.plate, 'MAB1234');
      expect(e.toQuery()['foreignPlate'], 'true');
      expect(e.toJson()['foreignPlate'], isTrue);

      await EntryQueue(send: (_) async => null).enqueue(e);
      final sent = <Map<String, String>>[];
      final q2 = EntryQueue(send: (query) async => sent.add(query));
      await q2.load(); // uygulama yeniden açıldı
      expect(q2.items.value.single.foreign, isTrue);
      await q2.flush();
      expect(sent.single['foreignPlate'], 'true');
      expect(sent.single['plate'], 'MAB1234');
      expect(sent.single['checkInRequestId'], 'rf1');
    });

    test('TR girişinde ve eski sürüm kaydında bayrak yok', () {
      final tr = PendingEntry(requestId: 'rt', plate: '34ABC123', street: 'S', type: 'SMALL', side: 'LEFT');
      expect(tr.toQuery().containsKey('foreignPlate'), isFalse);
      expect(tr.toJson().containsKey('foreignPlate'), isFalse);
      final old = PendingEntry.fromJson(
          {'requestId': 'r', 'plate': '34ABC123', 'street': 'S', 'type': 'SMALL', 'side': 'LEFT'});
      expect(old.foreign, isFalse);
    });
  });

  group('giriş formu anahtarı', () {
    EntryResult? result;

    Future<void> open(WidgetTester t, String initial, {bool foreign = false}) async {
      result = null;
      await t.pumpWidget(MaterialApp(
        theme: BilParkTheme.light(),
        home: Builder(
          builder: (c) => Scaffold(
              body: TextButton(
                  onPressed: () async => result = await showEntrySheet(c, initial, foreign: foreign),
                  child: const Text('aç'))),
        ),
      ));
      await t.tap(find.text('aç'));
      await t.pumpAndSettle();
    }

    bool enabled(WidgetTester t, String label) => t
            .widget<FilledButton>(
                find.ancestor(of: find.text(label), matching: find.byWidgetPredicate((w) => w is FilledButton)))
            .onPressed !=
        null;

    testWidgets('varsayılan kapalı; açınca TR denetimi kalkar, sonuç bayrağı taşır', (t) async {
      await open(t, 'M AB 1234');
      final sw = find.byKey(const Key('foreignPlateSwitch'));
      expect(find.text('Yabancı / özel plaka'), findsOneWidget);
      expect(t.widget<Switch>(sw).value, isFalse);
      expect(enabled(t, 'Sola park'), isFalse); // TR kuralına uymuyor

      await t.tap(sw);
      await t.pump();
      expect(t.widget<Switch>(sw).value, isTrue);
      expect(find.text('Geçerli plaka'), findsOneWidget);
      expect(enabled(t, 'Sola park'), isTrue);

      // yabancı kuralı da geçersiz karakteri reddeder; ipucu metni değişir
      await t.enterText(find.byType(TextField), 'M.AB');
      await t.pump();
      expect(find.text('Yalnızca A-Z harfleri ve rakam kullanın'), findsOneWidget);
      expect(enabled(t, 'Sola park'), isFalse);
      await t.enterText(find.byType(TextField), '');
      await t.pump();
      expect(find.textContaining(foreignPlateExample), findsWidgets);

      await t.enterText(find.byType(TextField), 'cd 1234');
      await t.pump();
      await t.tap(find.text('Sağa park'));
      await t.pumpAndSettle();
      expect(result?.foreign, isTrue);
      expect(result?.plate, 'cd 1234');
      expect(result?.side, 'RIGHT');
    });

    testWidgets('yabancı modda taranan plaka anahtar açık gelir; kapatınca TR kuralı döner', (t) async {
      await open(t, 'MAB1234', foreign: true);
      final sw = find.byKey(const Key('foreignPlateSwitch'));
      expect(t.widget<Switch>(sw).value, isTrue);
      expect(enabled(t, 'Sola park'), isTrue);
      await t.tap(sw);
      await t.pump();
      expect(enabled(t, 'Sola park'), isFalse);
      await t.enterText(find.byType(TextField), '34 abc 123');
      await t.pump();
      await t.tap(find.text('Sola park'));
      await t.pumpAndSettle();
      expect(result?.foreign, isFalse);
    });
  });

  testWidgets('PlateBadge: yabancı plakada TR yazısı yok, metin olduğu gibi', (t) async {
    Future<void> show(Widget w) => t.pumpWidget(MaterialApp(theme: BilParkTheme.light(), home: Scaffold(body: Center(child: w))));
    await show(const PlateBadge('34ABC123', size: PlateSize.large));
    expect(find.text('TR'), findsOneWidget);
    expect(find.text('34 ABC 123'), findsOneWidget);
    await show(const PlateBadge('12ABC34', size: PlateSize.large, foreign: true));
    expect(find.text('TR'), findsNothing);
    expect(find.text('12ABC34'), findsOneWidget);
    await show(const PlateBadge('MAB1234', size: PlateSize.large));
    expect(find.text('TR'), findsNothing); // TR biçimine uymuyor
  });
}
