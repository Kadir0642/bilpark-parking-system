// M4: 27 Ekim uçtan uca koşusunun 5 bulgusunun düzeltmeleri, gerçek telefonda.
// Çalıştırma: docs/e2e-testler.md ("Telefon bağlanınca tek komut").
//
// Maddeler:
//   B1  Ana sayfa (Vardiya özeti) taşmıyor.
//   B2  Ana sayfadaki "Elle ekle" kaydırmadan görünür ve çalışır.
//   B3  "sokağa eklendi" bildirimi varken Cadde'deki "+" basılabiliyor.
//   B4  Borçlu araç girişinde "Borçlu araç" uyarısı (tutar + "kaçak geçmişi var"); giriş yine yapılıyor.
//   B5  Bağlantı yokken giriş kuyruğa alınıyor ("Beklemede" + "1 işlem bekliyor"), bağlantı gelince
//       kendiliğinden gönderiliyor ve sunucuda tek kayıt oluşuyor.

import 'package:bilpark/core/format.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/e2e.dart';

const String _street = 'TEVFIK_BEY';
const int _zoneId = 1;

Future<void> _goTab(WidgetTester t, String label) =>
    tapOn(t, find.descendant(of: find.byType(NavigationBar), matching: find.text(label)));

Finder _textStarting(String prefix) =>
    find.byWidgetPredicate((w) => w is Text && (w.data ?? '').startsWith(prefix), description: 'Text "$prefix…"');

Finder get _bandOnline => _textStarting('Çevrimiçi');
Finder get _bandDown => find.byWidgetPredicate(
      (w) => w is Text && ((w.data ?? '').startsWith('Yeniden bağlanıyor') || (w.data ?? '').startsWith('Çevrimdışı')),
      description: 'Bant "Yeniden bağlanıyor…" veya "Çevrimdışı"',
    );

/// Cadde ekranındaki plaka aramasına yazar; tembel listede kartın ağaçta olmasını sağlar.
Future<void> _filter(WidgetTester t, String plate) async {
  final field = find.byWidgetPredicate(
      (w) => w is TextField && w.decoration?.hintText == 'Plaka ara', description: 'TextField[Plaka ara]');
  await waitFor(t, field);
  await t.enterText(field.first, plate);
  await settle(t);
}

/// Cadde'de "+" -> plaka -> "Sola park". Bildirim beklenmez: düğme bildirim varken de basılabilmeli.
Future<void> _enter(WidgetTester t, String plate) async {
  await _goTab(t, 'Cadde');
  await tapOn(t, find.byTooltip('Elle araç ekle'));
  await waitFor(t, find.text('Araç girişi'));
  await typeInto(t, fieldWithLabel('Plaka'), plate);
  await tapOn(t, find.text('Sola park'));
}

Future<List<Map>> _spots(Api api) async =>
    ((await api.get('/parking/filter', query: {'street': _street})) as List).whereType<Map>().toList();

Future<void> _checkOutQuietly(Api api, String plate) async {
  try {
    await api.post('/parking/check-out', query: {'plate': plate, 'paymentMethod': 'CASH'});
  } catch (_) {}
}

void main() {
  ensureE2eBinding();

  testWidgets('Bulgu düzeltmeleri (B1-B5)', (t) async {
    final report = E2eReport('m4_bulgular');
    // Taşma hataları FlutterError olarak gelir; B1 için toplanır, test bağlamasına yine iletilir.
    final overflows = <String>[];
    final prevOnError = FlutterError.onError;
    FlutterError.onError = (d) {
      final s = d.exceptionAsString();
      if (s.contains('overflowed')) overflows.add(s);
      prevOnError?.call(d);
    };
    addTearDown(() => FlutterError.onError = prevOnError);

    await startApp(t);
    final admin = await Api.admin();
    await loginAs(t, E2e.officerUser, E2e.officerPassword);
    await waitFor(t, find.text('Sahadaki araç'), timeout: const Duration(seconds: 30), reason: 'Ana ekran açılmadı');
    await waitFor(t, _bandOnline, timeout: const Duration(seconds: 30), reason: 'Bant Çevrimiçi olmalı');

    await report.item(t, 'B1', 'Ana sayfa küçük ekranda taşmıyor (Vardiya özeti)', () async {
      await waitFor(t, find.text('Vardiya özeti'));
      await waitFor(t, find.text('Teslim edilecek nakit'));
      await pause(t, 2);
      expect(overflows, isEmpty, reason: 'Ana sayfada taşma: ${overflows.join(' | ')}');
    });

    await report.item(t, 'B2', 'Ana sayfadaki "Elle ekle" kaydırmadan görünüyor ve formu açıyor', () async {
      final size = t.view.physicalSize / t.view.devicePixelRatio;
      final btn = find.text('Elle ekle');
      await waitFor(t, btn);
      final r = t.getRect(btn);
      expect(r.top >= 0 && r.bottom <= size.height, true, reason: '"Elle ekle" ekran dışında: $r (ekran $size)');
      // ensureVisible kullanılmaz: kaydırmadan basılabilmeli
      await t.tap(btn);
      await waitFor(t, find.text('Araç girişi'), reason: '"Elle ekle" giriş formunu açmadı');
      await pause(t);
      await t.tapAt(const Offset(10, 60)); // formu kapat (üstteki karartmaya dokun)
      await waitGone(t, find.text('Araç girişi'));
    });

    await report.item(t, 'B3', '"sokağa eklendi" bildirimi varken "+" düğmesi basılabiliyor', () async {
      final p = uniquePlate('11TF');
      await _enter(t, p);
      await waitFor(t, find.text('$p sokağa eklendi.'), reason: 'Giriş bildirimi');
      expect(find.byType(SnackBar), findsOneWidget);
      // Bildirim hâlâ ekrandayken "+"
      await t.tap(find.byTooltip('Elle araç ekle'));
      await waitFor(t, find.text('Araç girişi'), timeout: const Duration(seconds: 3), reason: '"+" bildirim varken çalışmadı');
      await pause(t);
      await t.tapAt(const Offset(10, 60));
      await waitGone(t, find.text('Araç girişi'));
      await _checkOutQuietly(admin, p);
    });

    await report.item(t, 'B4', 'Borçlu araç girişinde borç tutarı ve "kaçak geçmişi var" uyarısı', () async {
      // Kendi borcunu üretir: API ile giriş, 75 dk geri al, kaçak işaretle
      final p = uniquePlate('11KD');
      await admin.post('/parking/check-in', query: {'plate': p, 'street': _street, 'type': 'SMALL', 'side': 'LEFT', 'zoneId': _zoneId});
      await Host.backdate(p, 75);
      final run = await admin.post('/parking/runaway', query: {'plate': p});
      final debt = (run as Map)['debt'] as num;
      expect(debt > 0, true, reason: 'Kaçak borcu oluşmalı (debt=$debt)');

      await _enter(t, p);
      await waitFor(t, find.text('Borçlu araç'), reason: 'Borçlu araç uyarısı çıkmadı');
      expect(find.textContaining('kaçak geçmişi var'), findsOneWidget);
      expect(find.textContaining(formatMoney(debt)), findsWidgets, reason: 'Uyarıda tutar ${formatMoney(debt)} olmalı');
      await pause(t, 2);
      await tapOn(t, find.text('Tamam'));
      // Uyarı girişi engellemez
      expect((await _spots(admin)).any((s) => s['currentPlate'] == p), true, reason: '$p giriş yapılmış olmalı');
      await _checkOutQuietly(admin, p);
    });

    await report.item(t, 'B5', 'Bağlantı yokken giriş bekletiliyor, bağlantı gelince bir kez gönderiliyor', () async {
      final p = uniquePlate('11TG');
      await _goTab(t, 'Cadde');
      await Host.stopBackend();
      try {
        await waitFor(t, _bandDown, timeout: const Duration(seconds: 30), reason: 'Backend durunca bant değişmeli');
        await _enter(t, p);
        await waitFor(t, find.textContaining('bağlantı gelince gönderilecek'),
            timeout: const Duration(seconds: 30), reason: 'Kuyruk bildirimi');
        await _filter(t, p);
        await waitFor(t, find.text('Beklemede'), reason: '"Beklemede" rozetli kart');
        expect(find.text(formatPlate(p)), findsOneWidget);
        expect(find.textContaining('1 işlem bekliyor'), findsWidgets, reason: 'Bantta bekleyen işlem sayısı');
        await pause(t, 2);
      } finally {
        await Host.startBackend();
      }
      // Bağlantı gelince kendiliğinden gönderilir; uygulamada hiçbir şeye dokunulmaz
      await waitFor(t, _bandOnline, timeout: const Duration(seconds: 90), reason: 'Bant tekrar Çevrimiçi olmalı');
      await waitGone(t, find.text('Beklemede'), timeout: const Duration(seconds: 30), reason: 'Kayıt kuyruktan gönderilmedi');
      await waitGone(t, find.textContaining('işlem bekliyor'), timeout: const Duration(seconds: 10));
      await waitFor(t, find.text(formatPlate(p)), timeout: const Duration(seconds: 20), reason: '$p normal kart olarak görünmeli');
      final api = await Api.admin();
      final mine = (await _spots(api)).where((s) => s['currentPlate'] == p).toList();
      expect(mine.length, 1, reason: '$p sunucuda tam bir kez kayıtlı olmalı');
      await pause(t, 2);
      await _filter(t, '');
      await _checkOutQuietly(api, p);
    });

    report.finish();
  }, timeout: const Timeout(Duration(minutes: 15)));
}
