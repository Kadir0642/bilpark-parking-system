// M1 saha akışı: giriş, park girişi, ödeme/geri al, kaçak, abone/borç, vardiya sonu.
// Çalıştırma: docs/e2e-testler.md (bkz. support/e2e.dart başlığı).

import 'package:bilpark/core/format.dart';
import 'package:bilpark/screens/parking_map_screen.dart';
import 'package:bilpark/widgets/search_field.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/e2e.dart';

const String _street = 'TEVFIK_BEY';
const String _zoneTitleText = 'Tevfik Bey — Bölüm 1'; // zoneTitle(): " - " -> " — "

// --- Yardımcılar ---

Future<void> goTab(WidgetTester t, String label) =>
    tapOn(t, find.descendant(of: find.byType(NavigationBar), matching: find.text(label)));

/// Bir madde düştükten sonra da temiz başlamak için: açık sayfaları kapat, aramayı temizle, Ana sayfaya dön.
Future<void> backHome(WidgetTester t) async {
  await popToRoot(t);
  if (find.byType(NavigationBar).evaluate().isEmpty) return;
  await goTab(t, 'Cadde');
  await clearSearch(t);
  await goTab(t, 'Ana sayfa');
}

Finder get _searchInput => find.descendant(of: find.byType(BpSearchField), matching: find.byType(TextField));

/// Cadde ekranındaki plaka aramasına yazar; liste yalnızca o aracı gösterir (ListView tembel kurulduğu için kart görünür olur).
Future<void> searchPlate(WidgetTester t, String plate) async {
  await goTab(t, 'Cadde');
  await typeInto(t, _searchInput, plate);
  await settle(t);
}

Future<void> clearSearch(WidgetTester t) async {
  final clear = find.byTooltip('Temizle');
  if (clear.evaluate().isNotEmpty) {
    await t.tap(clear.first, warnIfMissed: false);
    await settle(t);
  }
}

/// Cadde ekranı listesini sunucudan hemen yeniler (backdate gibi dış değişiklikler olay üretmez).
Future<void> refreshStreet(WidgetTester t) async {
  await goTab(t, 'Cadde');
  final st = t.state<ParkingMapScreenState>(find.byType(ParkingMapScreen, skipOffstage: false));
  await st.fetchActiveVehicles(userTriggered: true);
  await settle(t);
}

/// "Elle araç ekle" -> plaka -> "Sola park" / "Sağa park".
Future<void> enterPlate(WidgetTester t, String plate, {required bool left}) async {
  await goTab(t, 'Cadde');
  await waitNoSnackBar(t);
  await tapOn(t, find.byTooltip('Elle araç ekle'));
  await waitFor(t, find.text('Araç girişi'));
  await typeInto(t, fieldWithLabel('Plaka'), plate);
  await tapOn(t, find.text(left ? 'Sola park' : 'Sağa park'));
}

/// Plakaya göre arar ve kartı döndürür (kartın Material'i).
Future<Finder> showCard(WidgetTester t, String plate) async {
  await searchPlate(t, plate);
  await waitFor(t, find.text(formatPlate(plate)), reason: '$plate kartı listede görünmedi');
  return cardOf(plate);
}

Finder cardOf(String plate) => find.ancestor(
      of: find.text(formatPlate(plate)),
      matching: find.byWidgetPredicate(
        (w) => w is Material && w.shape is RoundedRectangleBorder && (w.shape as RoundedRectangleBorder).side.width == 2,
        description: 'araç kartı',
      ),
    );

Future<List<Map>> activeSpots(Api api) async {
  final l = await api.get('/parking/filter', query: {'street': _street});
  return (l as List).whereType<Map>().toList();
}

Map? spotOf(List<Map> spots, String plate) {
  for (final s in spots) {
    if (s['currentPlate'] == plate) return s;
  }
  return null;
}

Future<String> freshPlate(Api api, String prefix) async {
  final taken = (await activeSpots(api)).map((s) => '${s['currentPlate']}').toSet();
  while (true) {
    final p = uniquePlate(prefix);
    if (!taken.contains(p)) return p;
  }
}

String ymd(DateTime d) => '${d.year}-${two(d.month)}-${two(d.day)}';

/// tevfik'in vardiyaları (en yeni önce).
Future<List<Map>> tevfikShifts(Api admin) async {
  final now = DateTime.now();
  final res = await admin.get('/admin/shifts', query: {
    'from': ymd(now.subtract(const Duration(days: 1))),
    'to': ymd(now),
    'size': 100,
  });
  return ((res as Map)['content'] as List).whereType<Map>().where((s) => s['username'] == E2e.officerUser).toList();
}

/// Detay sayfası açılıp gün gün döküm ve tutar yüklenene kadar bekler.
Future<void> waitDetailLoaded(WidgetTester t) async {
  await waitFor(t, find.text('Araç detayı'));
  await waitFor(t, find.text('Gün gün süre dökümü'));
  await waitFor(t, find.text('Toplam'), reason: 'Gün gün döküm yüklenmedi');
  await waitFor(t, find.textContaining('Ödemeyi al ·'));
}

void main() {
  ensureE2eBinding();
  testWidgets('Saha akışı (M1-M5, M10)', (t) async {
    final report = E2eReport('m1_saha_akisi');
    await startApp(t);
    final admin = await Api.admin();

    // M1
    await report.item(t, 'M1', 'Giriş yapınca memurun atanmış sahası kendiliğinden açılıyor', () async {
      await loginAs(t, E2e.officerUser, E2e.officerPassword);
      await waitFor(t, find.text('Sahadaki araç'), timeout: const Duration(seconds: 30), reason: 'Ana ekran açılmadı');
      expect(find.text('Çalışacağınız sahayı seçin'), findsNothing, reason: 'Saha seçim ekranı çıkmamalı');
      expect(find.text(_zoneTitleText), findsWidgets, reason: 'Ana ekranda saha adı görünmeli');
      await pause(t);
    });

    // M2
    await report.item(t, 'M2', 'Sola ve sağa park girişi; aynı plakayı ikinci kez girince anlaşılır bir uyarı çıkıyor', () async {
      await backHome(t);
      final p1 = await freshPlate(admin, '11TA');
      var p2 = await freshPlate(admin, '11TB');
      if (p2 == p1) p2 = await freshPlate(admin, '11TC');

      await enterPlate(t, p1, left: true);
      await waitFor(t, find.text('$p1 sokağa eklendi.'));
      await pause(t);
      await enterPlate(t, p2, left: false);
      await waitFor(t, find.text('$p2 sokağa eklendi.'));
      await pause(t);

      // API tarafı
      var spots = await activeSpots(admin);
      expect(spotOf(spots, p1)?['side'], 'LEFT', reason: '$p1 sunucuda LEFT olmalı');
      expect(spotOf(spots, p2)?['side'], 'RIGHT', reason: '$p2 sunucuda RIGHT olmalı');

      // Ekran tarafı: sol kart ekranın solunda, sağ kart sağında
      final half = t.view.physicalSize.width / t.view.devicePixelRatio / 2;
      await showCard(t, p1);
      expect(t.getCenter(find.text(formatPlate(p1))).dx, lessThan(half), reason: '$p1 sol kaldırımda görünmeli');
      await pause(t);
      await showCard(t, p2);
      expect(t.getCenter(find.text(formatPlate(p2))).dx, greaterThan(half), reason: '$p2 sağ kaldırımda görünmeli');
      await pause(t);
      await clearSearch(t);

      // Aynı plaka ikinci kez
      await enterPlate(t, p1, left: true);
      await waitFor(t, find.textContaining('zaten sistemde aktif'), reason: 'ALREADY_PARKED uyarısı çıkmadı');
      await pause(t, 2);
      spots = await activeSpots(admin);
      expect(spots.where((s) => s['currentPlate'] == p1).length, 1, reason: '$p1 iki kez eklenmemeli');
      await showCard(t, p1);
      expect(find.text(formatPlate(p1)), findsOneWidget, reason: 'Listede $p1 tek kart olmalı');
      await clearSearch(t);
    });

    // M3
    await report.item(t, 'M3', '"Ödeme Al": yöntem seçimi, gün gün süre dökümü, onay, 5 saniyelik "Geri Al"', () async {
      await backHome(t);
      final plate = await freshPlate(admin, '11TD');
      await enterPlate(t, plate, left: true);
      await waitFor(t, find.text('$plate sokağa eklendi.'));
      await Host.backdate(plate, 26 * 60);
      await refreshStreet(t);
      await showCard(t, plate);
      await tapOn(t, find.text(formatPlate(plate)));
      await waitDetailLoaded(t);

      // Sunucu önizlemesi ile ekran aynı olmalı
      final prev = await admin.get('/parking/fee-preview', query: {'plate': plate}) as Map;
      final fee = prev['fee'] as num;
      final segs = (prev['segments'] as List).whereType<Map>().toList();
      final days = segs.map((s) => formatDayLabel(DateTime.parse('${s['date']}'))).toSet();
      expect(days.length, greaterThanOrEqualTo(2), reason: '26 saatlik kalış en az 2 güne yayılmalı: $days');
      expect(find.text('Gün'), findsOneWidget);
      expect(find.text('Ücretli'), findsOneWidget);
      expect(find.text('Ücretsiz'), findsOneWidget);
      for (final d in days) {
        expect(find.text(d), findsWidgets, reason: 'Dökümde "$d" günü görünmeli');
      }
      expect(find.text(formatMoney(fee)), findsWidgets, reason: 'Tutar fee-preview (${formatMoney(fee)}) ile aynı olmalı');
      expect(find.text('Ödemeyi al · ${formatMoney(fee)} Nakit'), findsOneWidget);
      await pause(t, 2);

      // Yöntem seçimi
      expect(find.text('Nakit'), findsWidgets);
      await tapOn(t, find.text('POS'));
      expect(find.text('Ödemeyi al · ${formatMoney(fee)} POS'), findsOneWidget, reason: 'POS seçilince düğme güncellenmeli');
      await tapOn(t, find.text('Nakit'));
      expect(find.text('Ödemeyi al · ${formatMoney(fee)} Nakit'), findsOneWidget);

      // Onay -> 5 sn Geri al
      await tapOn(t, find.textContaining('Ödemeyi al ·'), pauseFactor: 0.2);
      await waitFor(t, find.text('Geri al'), reason: '5 sn Geri al çubuğu çıkmadı');
      await waitFor(t, find.text('Ödeme sürüyor'));
      await pause(t);
      await tapOn(t, find.text('Geri al'), pauseFactor: 0.5);
      await waitFor(t, find.textContaining('Ödeme iptal edildi.'));
      await waitGone(t, find.text('Ödeme sürüyor'));
      expect(find.text(formatPlate(plate)), findsOneWidget, reason: 'Araç listeye geri dönmeli');
      await pause(t, 2);
      expect(spotOf(await activeSpots(admin), plate), isNotNull, reason: 'Geri al sonrası araç hâlâ park hâlinde olmalı');

      // Bu sefer geri alma, 5 sn dolsun
      await tapOn(t, find.text(formatPlate(plate)));
      await waitDetailLoaded(t);
      await tapOn(t, find.text('POS'));
      await tapOn(t, find.textContaining('Ödemeyi al ·'), pauseFactor: 0.2);
      await waitFor(t, find.text('Geri al'));
      await waitGone(t, find.text(formatPlate(plate)), timeout: const Duration(seconds: 25), reason: 'Araç 5 sn sonra listeden kalkmalı');
      expect(find.textContaining('çıkış yapıldı'), findsWidgets, reason: 'Çıkış bildirimi görünmeli');
      await pause(t);
      expect(spotOf(await activeSpots(admin), plate), isNull, reason: 'Ödeme sonrası araç park hâlinde olmamalı');
      await clearSearch(t);
    });

    // M4
    await report.item(t, 'M4', '"Kaçtı" onayı ve kaçaklar listesi', () async {
      await backHome(t);
      final plate = await freshPlate(admin, '11TE');
      await enterPlate(t, plate, left: false);
      await waitFor(t, find.text('$plate sokağa eklendi.'));
      await Host.backdate(plate, 90);
      await refreshStreet(t);
      await showCard(t, plate);
      await tapOn(t, find.text(formatPlate(plate)));
      await waitDetailLoaded(t);
      await tapOn(t, find.text('Kaçtı'));
      await waitFor(t, find.text('Kaçak işaretle'));
      await waitFor(t, find.textContaining('kaçak olarak işaretlensin mi?'));
      await pause(t, 2);
      await tapOn(t, find.descendant(of: find.byType(AlertDialog), matching: find.widgetWithText(FilledButton, 'Kaçak işaretle')),
          pauseFactor: 0.2);
      await waitFor(t, find.text('Geri al'), reason: 'Kaçak için 5 sn Geri al çubuğu çıkmadı');
      await waitGone(t, find.text(formatPlate(plate)), timeout: const Duration(seconds: 25), reason: 'Kaçak sonrası araç listeden kalkmalı');
      expect(find.textContaining('kaçak olarak işaretlendi'), findsWidgets, reason: 'Kaçak bildirimi görünmeli');
      expect(spotOf(await activeSpots(admin), plate), isNull, reason: 'Kaçak aracı park hâlinde olmamalı');
      await clearSearch(t);

      await goTab(t, 'Kaçaklar');
      await waitFor(t, find.text(formatPlate(plate)), timeout: const Duration(seconds: 20), reason: 'Kaçaklar sekmesinde $plate yok');
      expect(find.text('Kaçak'), findsWidgets);
      await pause(t, 2);
    });

    // M5
    await report.item(t, 'M5', 'Abone araç farklı renkte, "Aboneleri gizle" çalışıyor, borçlu araç girişinde uyarı çıkıyor', () async {
      await backHome(t);
      const subPlate = '11CC1923';
      const normalPlate = '11ABC123';
      Color? colorOf(String p) => (cardOf(p).evaluate().first.widget as Material).color;

      final subCard = await showCard(t, subPlate);
      expect(find.descendant(of: subCard, matching: find.text('Abone')), findsWidgets, reason: '$subPlate kartında "Abone" rozeti olmalı');
      final subColor = colorOf(subPlate);
      await pause(t);

      // Aboneleri gizle
      await tapOn(t, find.text('Aboneleri gizle'));
      await waitGone(t, find.text(formatPlate(subPlate)), reason: 'Aboneleri gizle açıkken $subPlate görünmemeli');
      await pause(t, 2);
      await tapOn(t, find.text('Aboneleri gizle'));
      await waitFor(t, find.text(formatPlate(subPlate)), reason: 'Aboneleri gizle kapanınca $subPlate geri gelmeli');
      await pause(t);

      // Renk karşılaştırması (normal araçla)
      await showCard(t, normalPlate);
      final normalColor = colorOf(normalPlate);
      expect(subColor, isNot(normalColor), reason: 'Abone kart rengi normal karttan farklı olmalı');
      expect(find.descendant(of: cardOf(normalPlate), matching: find.text('Abone')), findsNothing);
      await clearSearch(t);

      // Borçlu araç girişi
      const debtPlate = '11KCK47';
      var spots = await activeSpots(admin);
      if (spotOf(spots, debtPlate) == null) {
        await enterPlate(t, debtPlate, left: true);
        await waitFor(t, find.text('$debtPlate sokağa eklendi.'));
        await pause(t);
      }
      spots = await activeSpots(admin);
      final debt = spotOf(spots, debtPlate)?['accumulatedDebt'];
      expect(debt is num && debt > 0, true, reason: '$debtPlate için sunucuda eski borç görünmeli (accumulatedDebt=$debt)');
      final debtCard = await showCard(t, debtPlate);
      await waitFor(t, find.descendant(of: debtCard, matching: find.text('Eski borç ${formatMoney(debt as num)}')),
          reason: 'Kartta "Eski borç ${formatMoney(debt)}" uyarısı görünmeli');
      await pause(t, 2);
      await tapOn(t, find.text(formatPlate(debtPlate)));
      await waitFor(t, find.text('Araç detayı'));
      expect(find.text('Eski borç ${formatMoney(debt)}'), findsWidgets, reason: 'Detayda da borç uyarısı görünmeli');
      await pause(t, 2);
      await tapOn(t, find.byTooltip('Geri'));
      await waitGone(t, find.text('Araç detayı'));
      await clearSearch(t);
    });

    // M10 (en sonda)
    await report.item(t, 'M10', 'Vardiyayı sonlandır: panelde vardiya kapanıyor', () async {
      await backHome(t);
      final before = (await tevfikShifts(admin)).where((s) => s['open'] == true).toList();
      expect(before, isNotEmpty, reason: 'tevfik için açık vardiya olmalı');
      final shiftId = before.first['id'];
      expect(before.first['endTime'], isNull);

      await tapOn(t, find.byTooltip('Ayarlar'));
      await waitFor(t, find.text('Ayarlar'));
      await tapOn(t, find.byIcon(Icons.logout));
      await waitFor(t, find.text('Vardiya bitsin mi?'));
      await pause(t, 2);
      await tapOn(t, find.descendant(of: find.byType(AlertDialog), matching: find.text('Vardiyayı bitir')), pauseFactor: 0.2);
      await waitFor(t, find.text('Personel girişi'), timeout: const Duration(seconds: 20), reason: 'Giriş ekranına dönülmedi');

      Map? shift;
      for (var i = 0; i < 10; i++) {
        shift = (await tevfikShifts(admin)).where((s) => s['id'] == shiftId).firstOrNull;
        if (shift != null && shift['endTime'] != null) break;
        await settle(t, const Duration(seconds: 1));
      }
      expect(shift, isNotNull, reason: 'Vardiya #$shiftId panelde bulunamadı');
      expect(shift!['endTime'], isNotNull, reason: 'Vardiya #$shiftId kapanmalı (endTime dolu)');
      expect(shift['open'], false);
    });

    report.finish();
  }, timeout: const Timeout(Duration(minutes: 20)));
}
