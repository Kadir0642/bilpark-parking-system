// M2: canlı senkron (SSE) uçtan uca testi. Gerçek telefon + gerçek backend.
// Çalıştırma: docs/e2e-testler.md (m1 ile aynı --dart-define değerleri).
//
// Maddeler:
//   M7  İkinci cihazdan eklenen araç, yenilemeden listede belirir.
//   M8  Ağ kesilip gelince bağlantı bandı değişir, kaçan olaylar yakalanır.
//   M6  Panelden zamanlanan tarife anında bildirilir, yürürlüğe girince değerler değişir.
//   M14 Motosiklet etkinleştirilince giriş formunda anında belirir; ücret doğru hesaplanır.
//
// "İkinci istemci" adımlarında uygulamada hiçbir yenileme hareketi yapılmaz; yalnızca beklenir.

import 'package:bilpark/core/format.dart';
import 'package:bilpark/core/vehicle_types.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/e2e.dart';

const String _street = 'TEVFIK_BEY';
const int _zoneId = 1;
const String _secondDevice = 'e2e-ikinci-cihaz';

// --- Bant ve metin bulucuları (metinler lib/widgets/connection_banner.dart'tan) ---

Finder _textStarting(String prefix) => find.byWidgetPredicate(
      (w) => w is Text && (w.data ?? '').startsWith(prefix),
      description: 'Text başlangıcı "$prefix"',
    );

Finder get _bandOnline => _textStarting('Çevrimiçi');
Finder get _bandReconnectingOrOffline => find.byWidgetPredicate(
      (w) => w is Text && ((w.data ?? '').startsWith('Yeniden bağlanıyor') || (w.data ?? '').startsWith('Çevrimdışı')),
      description: 'Bant "Yeniden bağlanıyor…" veya "Çevrimdışı"',
    );

/// Araç kartındaki plaka (PlateBadge formatPlate ile yazar: "11 TB 123").
Finder _plateCard(String plate) => find.text(formatPlate(plate));

// --- Ortak adımlar ---

/// Cadde ekranındaki "Plaka ara" kutusuna yazar (boş metin süzgeci kaldırır). Liste tembel kurulduğu için
/// ekran dışındaki kartlar ağaçta yoktur; süzgeç kartı görünür kılar. Süzgeç yereldir, sunucuya istek atmaz.
Future<void> _filter(WidgetTester t, String plate) async {
  final field = find.byWidgetPredicate(
      (w) => w is TextField && w.decoration?.hintText == 'Plaka ara', description: 'TextField[Plaka ara]');
  await waitFor(t, field);
  await t.enterText(field.first, plate);
  await settle(t);
}

Future<void> _goTab(WidgetTester t, String label) async {
  await tapOn(t, find.text(label));
  await pause(t, 0.5);
}

/// Cadde ekranında + düğmesiyle formu açar; 'Araç girişi' başlığını bekler.
Future<void> _openEntrySheet(WidgetTester t) async {
  await tapOn(t, find.byTooltip('Elle araç ekle'));
  await waitFor(t, find.text('Araç girişi'));
  await pause(t, 0.5);
}

/// Formu açık varsayar: plakayı yazar, (verilirse) araç türünü seçer, sola park ile gönderir.
Future<void> _fillAndSubmitSheet(WidgetTester t, String plate, {String? typeLabel}) async {
  await typeInto(t, fieldWithLabel('Plaka'), plate);
  if (typeLabel != null) await tapOn(t, find.text(typeLabel));
  await tapOn(t, find.text('Sola park'));
}

/// Telefondan elle giriş (kamera yok): form -> plaka -> sola park.
Future<void> _enterOnPhone(WidgetTester t, String plate, {String? typeLabel}) async {
  await _openEntrySheet(t);
  await _fillAndSubmitSheet(t, plate, typeLabel: typeLabel);
}

Future<void> _checkIn(Api api, String plate, {String type = 'SMALL', String side = 'LEFT'}) async {
  await api.post('/parking/check-in', query: {
    'plate': plate,
    'street': _street,
    'type': type,
    'side': side,
    'zoneId': _zoneId,
  });
}

Future<List<dynamic>> _activeVehicles(Api api) async {
  final data = await api.get('/parking/filter', query: {'street': _street});
  return data is List ? data : <dynamic>[];
}

Map<String, dynamic>? _findVehicle(List<dynamic> list, String plate) {
  for (final e in list) {
    if (e is Map && e['currentPlate'] == plate) return Map<String, dynamic>.from(e);
  }
  return null;
}

double _n(dynamic v) => (v as num).toDouble();

Future<Map<String, dynamic>> _currentTariff(Api api) async =>
    Map<String, dynamic>.from(await api.get('/tariff/current') as Map);

Map<String, dynamic> _typeRow(Map<String, dynamic> tariff, String code) {
  final row = (tariff['vehicleTypes'] as List).cast<Map>().firstWhere((e) => e['code'] == code);
  return Map<String, dynamic>.from(row);
}

/// Aynı anda yalnızca bir SCHEDULED tarife olabilir; kalıntı varsa iptal edilir.
Future<void> _cancelScheduled(Api admin) async {
  final list = await admin.get('/admin/tariffs') as List;
  for (final e in list) {
    if (e is Map && e['status'] == 'SCHEDULED') {
      await admin.post('/admin/tariffs/${e['id']}/cancel');
    }
  }
}

void main() {
  ensureE2eBinding();
  final report = E2eReport('m2_canli_senkron_test.dart');

  testWidgets('M2 canlı senkron', (t) async {
    Api? second; // ikinci cihaz: tevfik, farklı X-Device-Id
    Future<Api> secondClient() async => second ??= await Api.login('tevfik', E2e.officerPassword, deviceId: _secondDevice);

    // --- Başlangıç ---
    await startApp(t);
    await loginAs(t, E2e.officerUser, E2e.officerPassword);
    await waitFor(t, find.textContaining('Tevfik Bey'), timeout: const Duration(seconds: 30), reason: 'Ana ekranda saha adı');
    await waitFor(t, _bandOnline, timeout: const Duration(seconds: 30), reason: 'Bant Çevrimiçi olmalı');
    await pause(t);
    await _goTab(t, 'Cadde');
    await waitFor(t, find.textContaining('SOL KALDIRIM'));
    await waitFor(t, _bandOnline, timeout: const Duration(seconds: 30), reason: 'Cadde ekranında bant Çevrimiçi');
    await pause(t);

    // --- M7 ---
    await report.item(t, 'M7', 'İkinci bir cihazdan araç eklenince liste kendiliğinden güncelleniyor', () async {
      await _goTab(t, 'Cadde');
      final plate = uniquePlate('11TB');
      await _filter(t, plate);
      expect(_plateCard(plate), findsNothing);
      final api = await secondClient();
      await pause(t);
      await _checkIn(api, plate);
      // Uygulamada hiçbir yenileme yapılmaz; olay SSE ile gelmeli.
      await waitFor(t, _plateCard(plate),
          timeout: const Duration(seconds: 10), reason: '$plate yenilemesiz (SSE) listede görünmeli');
      expect(_plateCard(plate), findsWidgets);
      await pause(t, 1.5);
    });

    // --- M8 ---
    await report.item(t, 'M8', 'Ağ kesilip geri gelince bağlantı bandı görünüyor ve liste tamamlanıyor', () async {
      await _goTab(t, 'Cadde');
      final plateOffline = uniquePlate('11TB');
      final plateMissed = uniquePlate('11TB');
      await _filter(t, plateOffline);

      await Host.stopBackend();
      await waitFor(t, _bandReconnectingOrOffline,
          timeout: const Duration(seconds: 30), reason: 'Backend durunca bant "Yeniden bağlanıyor…"/"Çevrimdışı" olmalı');
      await pause(t, 1.5);

      // Çevrimdışı giriş telefonda bekletilir: "Beklemede" rozetli kart ve bantta bekleyen işlem sayısı.
      await _enterOnPhone(t, plateOffline);
      await waitFor(t, find.textContaining('bağlantı gelince gönderilecek'),
          timeout: const Duration(seconds: 30), reason: 'Çevrimdışı girişte kuyruk bildirimi');
      await waitFor(t, find.text('Beklemede'), reason: 'Bekleyen giriş "Beklemede" rozetiyle görünmeli');
      expect(find.textContaining('1 işlem bekliyor'), findsWidgets, reason: 'Bantta bekleyen işlem sayısı');
      await pause(t, 1.5);

      await Host.startBackend();
      // Ağ gelir gelmez ikinci istemci bir araç ekler; telefon kaçan olayı yakalamalı.
      Future<void> insertMissed() async {
        for (var i = 0; i < 4; i++) {
          try {
            await _checkIn(await secondClient(), plateMissed);
            return;
          } on ApiError catch (e) {
            if (e.status == 401) second = null; // gerekirse yeniden giriş
            await Future<void>.delayed(const Duration(seconds: 2));
            if (i == 3) rethrow;
          }
        }
      }

      await insertMissed();
      await waitFor(t, _bandOnline, timeout: const Duration(seconds: 60), reason: 'Bant tekrar Çevrimiçi olmalı');
      await _filter(t, plateMissed);
      await waitFor(t, _plateCard(plateMissed),
          timeout: const Duration(seconds: 30), reason: 'Kesinti sırasında/sonrasında eklenen $plateMissed listede görünmeli');
      final apiList = await _activeVehicles(await secondClient());
      expect(_findVehicle(apiList, plateMissed), isNotNull, reason: 'API de $plateMissed kaydını göstermeli');
      await pause(t);

      // Bağlantı döndü: bekleyen giriş kendiliğinden gönderilir, sunucuda tek kayıt olur.
      await _filter(t, plateOffline);
      await waitGone(t, find.text('Beklemede'), timeout: const Duration(seconds: 30), reason: 'Bekleyen giriş gönderilmedi');
      await waitFor(t, _plateCard(plateOffline), timeout: const Duration(seconds: 20), reason: 'Gönderilen giriş listede görünmeli');
      final apiList2 = await _activeVehicles(await secondClient());
      expect(apiList2.whereType<Map>().where((e) => e['currentPlate'] == plateOffline).length, 1,
          reason: '$plateOffline sunucuda tam bir kez kayıtlı olmalı');
      await pause(t, 1.5);
    });

    // --- M6 ---
    await report.item(t, 'M6', 'Panelden tarife zamanlanınca uygulamada anında güncelleniyor', () async {
      await _goTab(t, 'Cadde');
      final admin = await Api.admin();
      await _cancelScheduled(admin);
      final cur = await _currentTariff(admin);
      final curSmall = _typeRow(cur, 'SMALL');
      final curLarge = _typeRow(cur, 'LARGE');
      // Yürürlüktekinden kesin farklı fiyatlar
      final sFirst = _n(curSmall['firstHour']) == 30 ? 35.0 : 30.0;
      final sExtra = _n(curSmall['extraHour']) == 20 ? 25.0 : 20.0;
      final lFirst = _n(curLarge['firstHour']) == 60 ? 65.0 : 60.0;
      final lExtra = _n(curLarge['extraHour']) == 35 ? 40.0 : 35.0;

      final draft = Map<String, dynamic>.from(await admin.post('/admin/tariffs', body: {
        'freeMinutes': cur['freeMinutes'],
        'paidStart': cur['paidStart'],
        'paidEnd': cur['paidEnd'],
        'note': 'E2E M6',
        'vehicleTypes': [
          {'code': 'SMALL', 'enabled': true, 'firstHour': sFirst, 'extraHour': sExtra, 'subscriptionMonthly': 2750},
          {'code': 'LARGE', 'enabled': true, 'firstHour': lFirst, 'extraHour': lExtra, 'subscriptionMonthly': 3300},
        ],
      }) as Map);
      final id = draft['id'];
      final preview = Map<String, dynamic>.from(await admin.post('/admin/tariffs/$id/preview') as Map);
      expect((preview['rows'] as List), isNotEmpty, reason: 'Önizleme satırları dönmeli');

      final eff = DateTime.parse(isoLocal(DateTime.now().add(const Duration(seconds: 35))));
      await pause(t);
      await admin.post('/admin/tariffs/$id/schedule', body: {'effectiveFrom': isoLocal(eff), 'password': E2e.adminPassword});

      // Uygulamada yenileme yok: snack SSE ile ≤10 sn içinde gelmeli.
      final scheduledMsg = 'Yeni tarife zamanlandı: ${formatDayMonth(eff)} ${formatHm(eff)}';
      await waitFor(t, find.text(scheduledMsg), timeout: const Duration(seconds: 10), reason: 'Snack "$scheduledMsg"');
      await waitFor(t, find.textContaining("den itibaren yeni tarife"),
          timeout: const Duration(seconds: 10), reason: 'Cadde başlığında yaklaşan tarife notu');
      await pause(t, 1.5);

      // Ana sayfadaki "Güncel tarife" kartında yaklaşan tarife
      await _goTab(t, 'Ana sayfa');
      await waitFor(t, find.text('Güncel tarife'));
      final upcoming = await waitFor(t, find.textContaining('itibarıyla yeni tarife'), reason: 'Tarife kartında yaklaşan tarife');
      final upText = (upcoming.evaluate().first.widget as Text).data ?? '';
      final wantSmall = '${labelFor('SMALL')} ${formatMoney(sFirst)}/${formatMoney(sExtra)}';
      final wantLarge = '${labelFor('LARGE')} ${formatMoney(lFirst)}/${formatMoney(lExtra)}';
      expect(upText, contains(wantSmall));
      expect(upText, contains(wantLarge));
      await pause(t, 2);

      // Yürürlük anı: snack + güncel tarife satırları yeni fiyat
      await waitFor(t, find.text('Yeni tarife yürürlüğe girdi'),
          timeout: const Duration(seconds: 80), reason: 'Yürürlük anında snack');
      final smallLine = '${labelFor('SMALL')}: İlk saat ${formatMoney(sFirst)} · sonraki ${formatMoney(sExtra)}';
      final largeLine = '${labelFor('LARGE')}: İlk saat ${formatMoney(lFirst)} · sonraki ${formatMoney(lExtra)}';
      await waitFor(t, find.text(smallLine), timeout: const Duration(seconds: 10), reason: smallLine);
      await waitFor(t, find.text(largeLine), timeout: const Duration(seconds: 10), reason: largeLine);
      await waitGone(t, find.textContaining('itibarıyla yeni tarife'), reason: 'Yaklaşan tarife kutusu kalkmalı');
      await pause(t, 2);
      await _goTab(t, 'Cadde');
      await waitGone(t, find.textContaining("den itibaren yeni tarife"), reason: 'Cadde başlığındaki not kalkmalı');
    });

    // --- M14 ---
    await report.item(t, 'M14', 'Motosiklet: panelden etkinleştirilip fiyat girilince telefonda seçim listesinde anında görünüyor ve ücret doğru hesaplanıyor',
        () async {
      await _goTab(t, 'Cadde');
      final admin = await Api.admin();
      await _cancelScheduled(admin);
      final cur = await _currentTariff(admin);
      final moto = labelFor('MOTORCYCLE'); // 'Motosiklet' (sunucudaki görünen ad; pasif olsa da bilinir)

      // Başlangıç: motosiklet pasif -> seçenek yok
      await _openEntrySheet(t);
      await waitFor(t, find.text(labelFor('SMALL')), reason: 'Otomobil seçeneği');
      expect(find.text(moto), findsNothing, reason: 'Pasif motosiklet seçenekte olmamalı');
      await pause(t);

      // Panel: motosiklet etkin + fiyat (SMALL/LARGE yürürlüktekinden kopyalanır)
      final draft = Map<String, dynamic>.from(await admin.post('/admin/tariffs', body: {
        'freeMinutes': 5,
        'paidStart': cur['paidStart'],
        'paidEnd': cur['paidEnd'],
        'note': 'E2E M14',
        'vehicleTypes': [
          {'code': 'MOTORCYCLE', 'enabled': true, 'firstHour': 10, 'extraHour': 5, 'subscriptionMonthly': 800},
        ],
      }) as Map);
      final id = draft['id'];
      final preview = Map<String, dynamic>.from(await admin.post('/admin/tariffs/$id/preview') as Map);
      final changes = (preview['typeChanges'] as List).cast<Map>();
      final mc = changes.where((e) => e['code'] == 'MOTORCYCLE').toList();
      expect(mc, hasLength(1), reason: 'typeChanges içinde MOTORCYCLE olmalı');
      expect(mc.first['currentEnabled'], false);
      expect(mc.first['proposedEnabled'], true);

      final eff = DateTime.now().add(const Duration(seconds: 30));
      await admin.post('/admin/tariffs/$id/schedule', body: {'effectiveFrom': isoLocal(eff), 'password': E2e.adminPassword});

      // Form AÇIK kalır; yürürlükte seçenek kendiliğinden belirmeli (form kapatılıp açılmaz).
      await waitFor(t, find.text(moto), timeout: const Duration(seconds: 90), reason: '"$moto" seçeneği açık formda belirmeli');
      await pause(t, 1.5);

      // Motosiklet ile sola park
      final plate = uniquePlate('11MT');
      await _fillAndSubmitSheet(t, plate, typeLabel: moto);
      await _filter(t, plate);
      await waitFor(t, _plateCard(plate), timeout: const Duration(seconds: 15), reason: 'Motosiklet kartı');
      final rec = _findVehicle(await _activeVehicles(await secondClient()), plate);
      expect(rec, isNotNull);
      expect(rec!['currentType'], 'MOTORCYCLE');
      expect(rec['side'], 'LEFT');

      // Kartta motosiklet ikonu
      expect(iconFor('motorcycle'), Icons.two_wheeler_outlined);
      final card = find.ancestor(of: _plateCard(plate), matching: find.byType(InkWell)).first;
      expect(find.descendant(of: card, matching: find.byIcon(Icons.two_wheeler_outlined)), findsOneWidget,
          reason: 'Kartta motosiklet ikonu');
      await pause(t);

      // Giriş zamanını 75 dk geriye al; listeyi tazelemek için ikinci istemci başka bir araç ekler (SSE).
      await Host.backdate(plate, 75);
      final refresher = uniquePlate('11TB');
      await _checkIn(await secondClient(), refresher);
      await _filter(t, refresher);
      await waitFor(t, _plateCard(refresher), timeout: const Duration(seconds: 10), reason: 'Liste olayla tazelenmeli');

      // Sunucu önizlemesi (referans)
      final preview2 = Map<String, dynamic>.from(
          await (await secondClient()).get('/parking/fee-preview', query: {'plate': plate}) as Map);
      final serverFee = _n(preview2['fee']);
      final paidMinutes = (preview2['paidMinutes'] as num?)?.toInt() ?? 0;
      // Ücretli pencere 75 dk'yı tamamen kapsıyorsa: ilk saat 10 + 1 ek saat 5 = 15
      if (paidMinutes > 60 && paidMinutes <= 120) {
        expect(serverFee, 15.0, reason: 'Sunucu: ilk saat 10 + 1 ek saat 5 = 15 (paidMinutes=$paidMinutes)');
      } else {
        debugPrint('E2E-NOT|M14|Ücretli pencere 75 dk\'yı kapsamıyor (paidMinutes=$paidMinutes); yalnızca sunucu = telefon karşılaştırılır.');
      }

      // Kart tahmini ve detay
      await _filter(t, plate);
      await tapOn(t, _plateCard(plate));
      await waitFor(t, find.text('Araç detayı'));
      await waitFor(t, find.textContaining('$moto · Sol kaldırım'), reason: 'Detayda tip adı');
      expect(find.byIcon(Icons.two_wheeler_outlined), findsWidgets, reason: 'Detayda motosiklet ikonu');
      final money = formatMoney(serverFee); // ör. ₺15
      await waitFor(t, find.textContaining('Ödemeyi al · $money'),
          timeout: const Duration(seconds: 15), reason: 'Detay tutarı = fee-preview ($money)');
      await waitFor(t, find.text(money), reason: '"Ödenecek tutar" kutusu $money');
      await pause(t, 2);
      await tapOn(t, find.byTooltip('Geri'));
      await waitFor(t, find.textContaining('SOL KALDIRIM'), reason: 'Listeye dönüş');
      await pause(t);
    });

    report.finish();
  }, timeout: const Timeout(Duration(minutes: 20)));
}
