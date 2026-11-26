// Yabancı plaka ülke kodu (V11): doğrulama, giriş formu, kuyruk, model ayrıştırma, rozet.
import 'package:bilpark/core/entry_queue.dart';
import 'package:bilpark/core/plate.dart';
import 'package:bilpark/core/tariff.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/screens/plate_scanner_screen.dart';
import 'package:bilpark/screens/vehicle_detail_screen.dart';
import 'package:bilpark/widgets/entry_sheet.dart';
import 'package:bilpark/widgets/plate_badge.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  group('doğrulama (sunucu kuralının aynısı)', () {
    test('kırpılır, büyütülür, Türkçe i/ı/İ -> I', () {
      expect(normalizeCountryCode('d'), 'D');
      expect(normalizeCountryCode(' nl '), 'NL');
      expect(normalizeCountryCode('gb'), 'GB');
      expect(normalizeCountryCode('ı'), 'I');
      expect(normalizeCountryCode('İ'), 'I');
      expect(normalizeCountryCode('i'), 'I');
      expect(normalizeCountryCode('Tİ'), 'TI');
      expect(normalizeCountryCode('ABC'), 'ABC');
    });

    test('boş, çok uzun ya da harf dışı geçersizdir', () {
      expect(normalizeCountryCode(null), isNull);
      expect(normalizeCountryCode('  '), isNull);
      expect(normalizeCountryCode('ABCD'), isNull);
      expect(normalizeCountryCode('D1'), isNull);
      expect(normalizeCountryCode('D-E'), isNull);
      expect(normalizeCountryCode('Ş'), isNull);
    });

    test('ipucu: boş serbest, geçersizde Türkçe neden', () {
      expect(countryCodeHint(''), isNull);
      expect(countryCodeHint('  '), isNull);
      expect(countryCodeHint('d'), isNull);
      expect(countryCodeHint('NL'), isNull);
      expect(countryCodeHint('ABCD'), contains('en fazla 3'));
      expect(countryCodeHint('D1'), contains('yalnızca harf'));
    });

    test('parseCountryCode: API/SSE alanı, eksik ya da bozuk değer güvenle null', () {
      expect(parseCountryCode('D'), 'D');
      expect(parseCountryCode('nl'), 'NL');
      expect(parseCountryCode(null), isNull);
      expect(parseCountryCode(5), isNull);
      expect(parseCountryCode(''), isNull);
      expect(parseCountryCode('XXXXX'), isNull);
    });

    test('tarayıcı sonucu ülke kodunu taşır', () {
      const scan = PlateScan('MAB1234', foreign: true, countryCode: 'D');
      expect(scan.countryCode, 'D');
    });
  });

  group('çevrimdışı kuyruk', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    PendingEntry mk({bool foreign = true, String? code}) => PendingEntry(
        requestId: 'rc1',
        plate: 'm ab-1234',
        street: 'S',
        type: 'SMALL',
        side: 'LEFT',
        foreign: foreign,
        foreignCountryCode: code);

    test('istek: yabancı + kod varsa gider, kod yoksa ya da TR ise gitmez', () {
      expect(mk(code: 'd').toQuery()['foreignCountryCode'], 'D');
      expect(mk().toQuery().containsKey('foreignCountryCode'), isFalse);
      expect(mk(code: '').toQuery().containsKey('foreignCountryCode'), isFalse);
      expect(mk(code: 'ABCD').toQuery().containsKey('foreignCountryCode'), isFalse);
      final tr = mk(foreign: false, code: 'D');
      expect(tr.foreignCountryCode, isNull);
      expect(tr.toQuery().containsKey('foreignCountryCode'), isFalse);
      expect(tr.toJson().containsKey('foreignCountryCode'), isFalse);
    });

    test('kod kalıcıdır ve yeniden açılışta kuyruk gönderiminde gider', () async {
      await EntryQueue(send: (_) async => null).enqueue(mk(code: 'nl'));
      final sent = <Map<String, String>>[];
      final q2 = EntryQueue(send: (query) async => sent.add(query));
      await q2.load();
      expect(q2.items.value.single.foreignCountryCode, 'NL');
      await q2.flush();
      expect(sent.single['foreignPlate'], 'true');
      expect(sent.single['foreignCountryCode'], 'NL');
      expect(sent.single['plate'], 'MAB1234');
    });

    test('eski kayıtlarda (alan yok) ya da bozuk alanda kod yok, kayıt atlanmaz', () {
      final old = PendingEntry.fromJson({
        'requestId': 'r',
        'plate': 'MAB1234',
        'street': 'S',
        'type': 'SMALL',
        'side': 'LEFT',
        'foreignPlate': true,
      });
      expect(old.foreign, isTrue);
      expect(old.foreignCountryCode, isNull);
      expect(old.toQuery().containsKey('foreignCountryCode'), isFalse);
      final bad = PendingEntry.fromJson({
        'requestId': 'r2',
        'plate': 'MAB1234',
        'street': 'S',
        'type': 'SMALL',
        'side': 'LEFT',
        'foreignPlate': true,
        'foreignCountryCode': 42,
      });
      expect(bad.foreignCountryCode, isNull);
    });
  });

  group('giriş formu', () {
    EntryResult? result;

    Future<void> open(WidgetTester t, String initial, {bool foreign = false, String? code}) async {
      result = null;
      await t.pumpWidget(MaterialApp(
        theme: BilParkTheme.light(),
        home: Builder(
          builder: (c) => Scaffold(
              body: TextButton(
                  onPressed: () async => result = await showEntrySheet(c, initial, foreign: foreign, countryCode: code),
                  child: const Text('aç'))),
        ),
      ));
      await t.tap(find.text('aç'));
      await t.pumpAndSettle();
    }

    final field = find.byKey(const Key('foreignCountryField'));
    final sw = find.byKey(const Key('foreignPlateSwitch'));

    bool enabled(WidgetTester t, String label) => t
            .widget<FilledButton>(
                find.ancestor(of: find.text(label), matching: find.byWidgetPredicate((w) => w is FilledButton)))
            .onPressed !=
        null;

    testWidgets('anahtar kapalıyken alan yok; açınca görünür, kapatınca silinir', (t) async {
      await open(t, 'MAB1234');
      expect(field, findsNothing);
      await t.tap(sw);
      await t.pump();
      expect(field, findsOneWidget);
      await t.enterText(field, 'nl');
      await t.pump();
      await t.tap(sw);
      await t.pump();
      expect(field, findsNothing);
      await t.tap(sw); // yeniden aç: boş gelir
      await t.pump();
      expect(t.widget<TextField>(field).controller!.text, isEmpty);
    });

    testWidgets('kameradan gelen kod ön doldurulur; düzenlenebilir; sonuç normalize kodu taşır', (t) async {
      await open(t, 'MAB1234', foreign: true, code: 'nl');
      expect(t.widget<TextField>(field).controller!.text, 'NL');
      await t.enterText(field, 'ı'); // Türkçe harf I'ya çevrilir
      await t.pump();
      await t.tap(find.text('Sola park'));
      await t.pumpAndSettle();
      expect(result?.foreign, isTrue);
      expect(result?.countryCode, 'I');
    });

    testWidgets('kod silinirse sonuçta kod yok; hiç yazılmadıysa da yok', (t) async {
      await open(t, 'MAB1234', foreign: true, code: 'D');
      await t.enterText(field, '');
      await t.pump();
      await t.tap(find.text('Sağa park'));
      await t.pumpAndSettle();
      expect(result?.countryCode, isNull);

      await open(t, 'MAB1234', foreign: true);
      expect(t.widget<TextField>(field).controller!.text, isEmpty);
      await t.tap(find.text('Sola park'));
      await t.pumpAndSettle();
      expect(result?.countryCode, isNull);
    });

    testWidgets('geçersiz kodda Türkçe hata görünür ve giriş düğmeleri kapanır', (t) async {
      await open(t, 'MAB1234', foreign: true);
      expect(enabled(t, 'Sola park'), isTrue);
      await t.enterText(field, 'ABCD');
      await t.pump();
      expect(find.textContaining('en fazla 3'), findsOneWidget);
      expect(enabled(t, 'Sola park'), isFalse);
      expect(enabled(t, 'Sağa park'), isFalse);
      await t.enterText(field, 'D1');
      await t.pump();
      expect(find.textContaining('yalnızca harf'), findsOneWidget);
      expect(enabled(t, 'Sola park'), isFalse);
      await t.enterText(field, 'GB');
      await t.pump();
      expect(enabled(t, 'Sola park'), isTrue);
    });

    testWidgets('TR modunda (anahtar kapalı) ön doldurma yok, sonuçta kod yok', (t) async {
      await open(t, '34ABC123', code: 'D');
      expect(field, findsNothing);
      await t.tap(find.text('Sola park'));
      await t.pumpAndSettle();
      expect(result?.foreign, isFalse);
      expect(result?.countryCode, isNull);
    });
  });

  group('gösterim', () {
    Future<void> show(WidgetTester t, Widget w) =>
        t.pumpWidget(MaterialApp(theme: BilParkTheme.light(), home: Scaffold(body: Center(child: w))));

    testWidgets('PlateBadge: yabancıda kod şeritte, TR ya da kodsuzda yok', (t) async {
      await show(t, const PlateBadge('MAB1234', size: PlateSize.large, foreign: true, countryCode: 'nl'));
      expect(find.text('NL'), findsOneWidget);
      expect(find.text('TR'), findsNothing);
      await show(t, const PlateBadge('MAB1234', foreign: true, countryCode: 'D'));
      expect(find.text('D'), findsOneWidget);
      await show(t, const PlateBadge('MAB1234', size: PlateSize.large, foreign: true));
      expect(find.byKey(const Key('plateCountryCode')), findsNothing);
      await show(t, const PlateBadge('34ABC123', size: PlateSize.large, countryCode: 'D'));
      expect(find.byKey(const Key('plateCountryCode')), findsNothing);
      expect(find.text('TR'), findsOneWidget);
    });

    testWidgets('araç detayı: API alanından "Yabancı · D" rozeti; kod yoksa "Yabancı"; TR\'de rozet yok', (t) async {
      TariffStore.current.value = const Tariff(
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
      Future<void> detail(Map<String, dynamic> extra) async {
        final vehicle = <String, dynamic>{
          'currentPlate': 'MAB1234',
          'currentType': 'SMALL',
          'entryTime': '2026-10-22T20:00:00',
          'side': 'LEFT',
          'hasSubscription': false,
          'accumulatedDebt': 0.0,
          ...extra,
        };
        await t.pumpWidget(MaterialApp(
          theme: BilParkTheme.light(),
          home: VehicleDetailScreen(
            vehicle: vehicle,
            onPay: (_, _) {},
            onRunaway: () {},
            loadPreview: (_) async => <String, dynamic>{
              'plate': 'MAB1234',
              'vehicleType': 'SMALL',
              'entryTime': '2026-10-22T20:00:00',
              'durationMinutes': 10,
              'paidMinutes': 0,
              'freeMinutes': 10,
              'fee': 0.0,
              'hasSubscription': false,
              'segments': <dynamic>[],
            },
          ),
        ));
        await t.pump(const Duration(milliseconds: 100));
      }

      await detail({'foreignPlate': true, 'foreignCountryCode': 'D'});
      expect(find.text('Yabancı · D'), findsOneWidget);
      await detail({'foreignPlate': true});
      expect(find.text('Yabancı'), findsOneWidget);
      await detail({});
      expect(find.textContaining('Yabancı'), findsNothing);
      await t.pumpWidget(const SizedBox());
      await t.pump(const Duration(seconds: 1));
    });
  });
}
