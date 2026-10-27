# Uçtan uca testler (gerçek telefon)

Saha uygulamasının `mobile/integration_test/` altındaki testleri gerçek bir Android telefonda, ayrı bir test
yığınına karşı çalışır. Kullanıcının demo yığınına (8081/5433/5500) dokunulmaz.

| Dosya | Maddeler |
|---|---|
| `m0_baglanti_test.dart` | Altyapı: backend, admin girişi, host yardımcısı, uygulama açılışı |
| `m1_saha_akisi_test.dart` | Atanmış saha, sola/sağa giriş ve mükerrer uyarı, Ödeme Al + Geri al, Kaçtı, abone/borçlu, vardiyayı bitir |
| `m2_canli_senkron_test.dart` | İkinci cihazdan giriş, ağ kesintisi, tarife zamanlama, motosiklet etkinleştirme |
| `m3_hesap_test.dart` | Panelde açılan memurla giriş, yanlış giriş iptali, saha değişikliği ve pasife alma |

## 1. Test yığını

Worktree kökünde `.env.demo` (git'e girmez) test değerleriyle doldurulur, sonra:

```bash
DEMO_DB_PORT=5434 DEMO_API_PORT=8082 DEMO_WEB_PORT=5501 docker compose -p bilpark-e2e up -d --build
```

Örnek veri ve test hazırlığı (yalnızca `bilpark-e2e-db-1`'e; `demo/ornek-veri.ps1` proje adı vermediği için kullanılmaz):

```bash
docker cp demo/ornek-veri.sql bilpark-e2e-db-1:/tmp/ornek-veri.sql
MSYS_NO_PATHCONV=1 docker exec bilpark-e2e-db-1 sh -c 'psql -U "$DB_USERNAME" -d bilpark_demo -v ON_ERROR_STOP=1 -q -f /tmp/ornek-veri.sql'
MSYS_NO_PATHCONV=1 docker exec -i bilpark-e2e-db-1 sh -c 'psql -U "$DB_USERNAME" -d bilpark_demo -v ON_ERROR_STOP=1' < mobile/integration_test/tools/e2e-hazirlik.sql
```

`m3` öncesinde panelde (`http://localhost:5501/admin-panel.html?api=http://localhost:8082`) Personel → Memur ekle ile
`panelmemur` adlı, sahası "Tevfik Bey - Bölüm 2" olan bir memur açılır.

## 2. Telefon bağlantısı

```bash
adb -s <cihaz> reverse tcp:8082 tcp:8082
adb -s <cihaz> reverse tcp:8099 tcp:8099
python mobile/integration_test/tools/host_helper.py --out <ekran-görüntüsü-klasörü>
```

Host yardımcısı (8099) testin ağ kesintisi için backend konteynerini durdurup başlatmasını, giriş zamanını geri
almasını (ücret hesabı) ve ekran görüntüsü almasını sağlar; sonuçları `<klasör>/results.jsonl`'e yazar.

## 3. Çalıştırma

Dosyalar tek tek ve sırayla (m1 → m2 → m3) çalıştırılır; m2 tarifeyi değiştirir.

```bash
cd mobile
flutter test integration_test/m1_saha_akisi_test.dart -d <cihaz> --dart-define=API_BASE=http://127.0.0.1:8082/api --dart-define=E2E_ADMIN_PASSWORD=<ADMIN_PASSWORD> --dart-define=E2E_OFFICER_PASSWORD=<OFFICER_PASSWORD> --dart-define=E2E_NEW_OFFICER_PASSWORD=<panelmemur şifresi>
```

Tercih edilen yol koşucu betiğidir: dosyaları sırayla koşar ve `flutter test`'in Android'de ara sıra uygulamayı
açılış ekranında bırakması durumunda (kurulumdan sonra 75 sn içinde test başlamazsa) koşuyu kapatıp yeniden dener.

```bash
E2E_ADMIN_PASSWORD=<...> E2E_OFFICER_PASSWORD=<...> E2E_NEW_OFFICER_PASSWORD=<...> bash integration_test/tools/run_e2e.sh <log-klasörü> m1_saha_akisi m2_canli_senkron m3_hesap
```

m2 motosikleti etkin bırakır; m2'yi yeniden koşmadan önce motosikleti pasif yapan bir tarife zamanlayın.

`E2E_STEP_MS` (varsayılan 1200) adımlar arasındaki izleme beklemesidir. Test sürerken telefonun kilidi açık kalmalı,
kablo çıkarılmamalı ve ekrana dokunulmamalı. Test uygulamayı (`com.example.mobile`) test adresiyle yeniden kurar;
bitince demo için `flutter build apk --debug --dart-define=API_BASE=http://127.0.0.1:8081/api` ile yeniden kurun
(bkz. `docs/yerel-demo.md` 4.6).

## 4. Temizlik

```bash
docker compose -p bilpark-e2e down -v
adb -s <cihaz> reverse --remove tcp:8082
adb -s <cihaz> reverse --remove tcp:8099
```
