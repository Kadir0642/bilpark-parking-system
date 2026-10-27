// M3: yönetim panelinde açılan memur hesabıyla telefon akışı.
// Çalıştırma: docs/e2e-testler.md; ek sabitler:
//   --dart-define=E2E_NEW_OFFICER=panelmemur --dart-define=E2E_NEW_OFFICER_PASSWORD=...
// Ön koşul: panelde "panelmemur" adlı memur "Tevfik Bey - Bölüm 2" (zone 2) sahasıyla oluşturulmuş olmalı.

import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/format.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/e2e.dart';

const String newUser = String.fromEnvironment('E2E_NEW_OFFICER', defaultValue: 'panelmemur');
const String newPass = String.fromEnvironment('E2E_NEW_OFFICER_PASSWORD');

/// Ana ekran başlığı: zoneTitle() "Tevfik Bey - Bölüm N" metnini "Tevfik Bey — Bölüm N" yapar; ikisi de kabul.
Finder zoneTitleText(int n) => find.byWidgetPredicate(
      (w) => w is Text && w.data != null && RegExp('^Tevfik Bey [—-] Bölüm $n\$').hasMatch(w.data!),
      description: 'Text[Tevfik Bey — Bölüm $n]',
    );

String todayDate() => isoLocal(DateTime.now()).substring(0, 10);

List<dynamic> asList(dynamic body) {
  if (body is List) return body;
  if (body is Map && body['content'] is List) return body['content'] as List;
  return const [];
}

void main() {
  ensureE2eBinding();

  testWidgets('M3 hesap ve oturum akışları', (t) async {
    expect(newPass, isNotEmpty, reason: 'E2E_NEW_OFFICER_PASSWORD verilmedi');
    final report = E2eReport('m3_hesap_test.dart');
    final panel = await Api.admin();
    final plate = uniquePlate('11TC');
    final shown = formatPlate(plate);
    int? userId;

    try {
      // ---------------------------------------------------------------- M11
      await report.item(t, 'M11', 'Aşama 5: personel ekleme ve o memurla giriş', () async {
        final users = asList(await panel.get('/admin/users'));
        final me = users.cast<Map>().firstWhere((u) => u['username'] == newUser,
            orElse: () => throw TestFailure('Panelde "$newUser" kullanıcısı yok'));
        userId = (me['id'] as num).toInt();
        expect(me['role'], 'OFFICER');
        expect((me['zoneId'] as num?)?.toInt(), 2, reason: 'Memurun sahası Bölüm 2 olmalı');
        expect(me['active'], true);

        await startApp(t);
        await loginAs(t, newUser, newPass);
        // Uygulamada ilk girişte zorunlu şifre değiştirme akışı yok: doğrudan ana ekran gelir.
        await waitFor(t, zoneTitleText(2), timeout: const Duration(seconds: 30), reason: 'Ana ekranda Bölüm 2 başlığı');
        await waitFor(t, find.textContaining('Çevrimiçi'), timeout: const Duration(seconds: 20), reason: 'Bağlantı bandı Çevrimiçi');
        expect(find.text('Personel girişi'), findsNothing);
        await pause(t);
      });

      // ---------------------------------------------------------------- M12
      await report.item(t, 'M12',
          'Mobilde yanlış girişi gerekçeyle iptal etme; kayıt cirodan düşüyor ve panelde iptal sekmesinde görünüyor', () async {
        const reason = 'E2E yanlış plaka';
        // Ana sayfadaki "Elle ekle" küçük ekranda kaydırma alanının dışında kalıyor; Cadde'deki + düğmesi kullanılır.
        await tapOn(t, find.descendant(of: find.byType(NavigationBar), matching: find.text('Cadde')));
        await waitNoSnackBar(t);
        await tapOn(t, find.byTooltip('Elle araç ekle'));
        await waitFor(t, find.text('Araç girişi'));
        await typeInto(t, fieldWithLabel('Plaka'), plate);
        await pause(t);
        await tapOn(t, find.text('Sola park'));
        await waitFor(t, find.textContaining('sokağa eklendi'), reason: 'Giriş bildirimi');
        // Araç kartı listede görünür (kart metni formatPlate çıktısıdır)
        await waitFor(t, find.text(shown), timeout: const Duration(seconds: 20), reason: 'Araç listede');
        final inSpots = asList(await panel.get('/parking/spots')).any((s) => s['currentPlate'] == plate);
        expect(inSpots, true, reason: 'Giriş sonrası /parking/spots içinde olmalı');
        await pause(t);

        await tapOn(t, find.text(shown));
        await waitFor(t, find.text('Araç detayı'));
        await pause(t);
        await tapOn(t, find.text('Yanlış giriş, iptal et'));
        await waitFor(t, find.text('Gerekçe'), reason: 'İptal diyaloğu');
        await typeInto(t, fieldWithLabel('Gerekçe'), reason);
        await pause(t);
        await tapOn(t, find.widgetWithText(FilledButton, 'İptal et'));
        await waitFor(t, find.textContaining('girişi iptal edildi'), reason: 'İptal bildirimi');
        await waitFor(t, find.text('Cadde'), reason: 'Detaydan listeye dönüş');
        await waitGone(t, find.text(shown), reason: 'Araç listeden kalkmalı');
        await pause(t);

        // API doğrulaması
        final spotsAfter = asList(await panel.get('/parking/spots'));
        expect(spotsAfter.any((s) => s['currentPlate'] == plate), false, reason: '/parking/spots içinde artık olmamalı');

        final today = todayDate();
        final cancelled = asList(await panel.get('/admin/records/cancelled', query: {'from': today, 'to': today}));
        final rec = cancelled.cast<Map>().where((r) => r['plate'] == plate).toList();
        expect(rec, isNotEmpty, reason: 'İptal listesinde $plate olmalı');
        final r = rec.first;
        expect(r['status'], 'CANCELLED');
        expect(r['cancelReason'], reason);
        expect(((r['fee'] as num?) ?? 0).toDouble(), 0.0, reason: 'İptal edilen girişte ücret 0 olmalı');
        final prev = r['previousStatus'] ?? r['cancelledFromStatus'];
        expect(prev, 'ACTIVE');
        expect(r['zoneId'] == null ? 2 : (r['zoneId'] as num).toInt(), 2);

        // Ciro tarafı: son kayıtlarda bu plaka CANCELLED görünür, PAID olarak sayılmaz
        final records = asList(await panel.get('/parking/bi/last-records', query: {'start': today, 'end': today}));
        final mine = records.cast<Map>().where((x) => x['licensePlate'] == plate).toList(); // ParkingRecord alanı
        expect(mine, isNotEmpty, reason: 'bi/last-records içinde kayıt olmalı');
        expect(mine.any((x) => x['status'] == 'PAID'), false, reason: 'PAID sayılmamalı (ciroya girmez)');
        expect(mine.every((x) => x['status'] == 'CANCELLED'), true);
      });

      // ---------------------------------------------------------------- M13
      await report.item(t, 'M13',
          'Panelden memurun sahası değiştirilince ya da memur pasife alınınca telefonda anında uyarı ve yeniden giriş', () async {
        final id = userId;
        if (id == null) throw TestFailure('Kullanıcı id bulunamadı (M11 düştü)');
        await popToRoot(t);
        await waitFor(t, find.textContaining('Çevrimiçi'), timeout: const Duration(seconds: 20), reason: 'Çevrimiçi olmalı');

        // (a) saha değişikliği
        await pause(t);
        await panel.put('/admin/users/$id/zone', body: {'zoneId': 3});
        await waitFor(t, find.text('Saha değişti'), reason: 'Saha değişti diyaloğu (SSE, yenileme yok)');
        await waitFor(t, find.textContaining('Tevfik Bey - Bölüm 3'), reason: 'Diyalogda yeni saha adı');
        await pause(t, 2);
        await tapOn(t, find.text('Yeniden giriş yap'));
        await waitFor(t, find.text('Personel girişi'), timeout: const Duration(seconds: 20), reason: 'Giriş ekranı');
        await pause(t);
        await loginAs(t, newUser, newPass);
        await waitFor(t, zoneTitleText(3), timeout: const Duration(seconds: 30), reason: 'Yeni sahada ana ekran');
        await waitFor(t, find.textContaining('Çevrimiçi'), timeout: const Duration(seconds: 20));
        await pause(t, 2);

        // (b) pasife alma
        await panel.put('/admin/users/$id/active', body: {'active': false});
        await waitFor(t, find.text('Personel girişi'), reason: 'Pasife alınca giriş ekranına dönülmeli (SSE)');
        await waitFor(t, find.text(ApiClient.deactivatedMessage), reason: 'Pasife alma mesajı');
        await pause(t, 2);

        // Pasif hesapla giriş reddedilmeli
        await loginAs(t, newUser, newPass);
        await waitFor(t, find.byIcon(Icons.error_outline), reason: 'Giriş hata kutusu');
        expect(find.text('Personel girişi'), findsOneWidget, reason: 'Giriş ekranında kalınmalı');
        final errTexts = find
            .descendant(of: find.byType(Row), matching: find.byType(Text))
            .evaluate()
            .map((e) => (e.widget as Text).data)
            .whereType<String>()
            .toList();
        debugPrint('E2E pasif hesap giriş yanıtı, ekrandaki metinler: $errTexts');
        await expectLater(Api.login(newUser, newPass), throwsA(isA<ApiError>()));
        await pause(t, 2);
      });
    } finally {
      // (c) Temizlik: madde düşse de hesap eski haline döner
      final id = userId;
      if (id != null) {
        try {
          await panel.put('/admin/users/$id/active', body: {'active': true});
        } catch (e) {
          debugPrint('E2E temizlik (active) başarısız: $e');
        }
        try {
          await panel.put('/admin/users/$id/zone', body: {'zoneId': 2});
        } catch (e) {
          debugPrint('E2E temizlik (zone) başarısız: $e');
        }
      }
    }

    report.finish();
  }, timeout: const Timeout(Duration(minutes: 15)));
}
