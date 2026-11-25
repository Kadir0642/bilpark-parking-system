# BilPark: cadde otoparkı yönetim sistemi

**Belediyeye bağlı bir cadde otoparkı işletmesi için kâğıt fişin yerini alan saha uygulaması, yönetim paneli ve vatandaş sayfası. Canlıda çalışıyor.**

[![Java CI with Maven](https://github.com/Kadir0642/bilpark-parking-system/actions/workflows/maven.yml/badge.svg)](https://github.com/Kadir0642/bilpark-parking-system/actions/workflows/maven.yml)
[![Flutter CI](https://github.com/Kadir0642/bilpark-parking-system/actions/workflows/flutter.yml/badge.svg)](https://github.com/Kadir0642/bilpark-parking-system/actions/workflows/flutter.yml)
[![License: All rights reserved](https://img.shields.io/badge/license-All%20rights%20reserved-red)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4-brightgreen)
![Flutter](https://img.shields.io/badge/Flutter-3.38-02569B)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)

[English README](README.md)

![Yönetim paneli turu: genel bakış, kayıtlar, tarife önizlemesi, bildirimler, geliştirici sayfası](docs/images/panel-tour.gif)

---

## Problem

Üç caddedeki otopark görevlileri kâğıt fiş kesiyordu. Ücret elle hesaplanıyor, ödemeden giden araçlar zor takip ediliyordu; işletme sahibi de ciroyu anlık göremiyordu. BilPark her aracı, ödemeyi ve mesaiyi ücretsiz bulut katmanlarında çalışan tek bir sistemde toplar.

## Ne yapar

**Saha uygulaması (Flutter, Android)**, otopark görevlileri için
- Plakayı kamerayla ve cihaz üzerinde OCR ile okur (Google ML Kit); iki satırlı motosiklet plakaları da okunur. Plaka elle de yazılabilir.
- Sol veya sağ kaldırıma giriş, nakit ya da POS ile çıkış, ödemeden sonra kısa bir geri alma süresi.
- Sayaçlı ve anlık ücretli canlı cadde görünümü. Aboneler farklı renkte gösterilir ve gizlenebilir.
- Kaçak aracı işaretler; borçlu plaka geri geldiğinde görevliyi uyarır.
- Çevrimdışı giriş kuyruğu: çekim olmayan yerde yapılan girişler, görevlinin dokunduğu saatle sonradan gönderilir.
- Server-Sent Events ile canlı senkron. Tarife, araç tipi ve hesap değişiklikleri birkaç saniyede her telefona ulaşır.
- Araç için vatandaş sayfasını açan bir QR kod gösterir.

**Yönetim paneli (statik web, saf JS)**, işletme için
- Canlı genel bakış: sahadaki araç, ciro, açık kaçak borcu, saha doluluğu ve aktif personel.
- Filtreli kayıtlar, ayrıntı satırı, gerekçeli iptal (hiçbir kayıt silinmez) ve Excel çıktısı.
- Sürümlü tarife: taslak → örnek ücretli ve gün gün dökümlü önizleme → şifre tekrar girilerek zamanlanmış yürürlük.
- Personel hesapları ve saha ataması, vardiya geçmişi, aylık performans raporu.
- Excel/CSV raporları: personel, belediye için kaçak araç listesi, dönem cirosu ve kira sonrası net, tam kayıt dökümü.
- Kimin ne zaman ne yaptığını gösteren denetim kaydı ve dışa aktarma.
- Lisans durumu ve görünür okunur mod, bildirim zili, koyu ve açık tema.
- Lisans, sistem sağlığı, son hatalar ve uyarılar için ayrı bir geliştirici (bakım) sayfası. Bu sayfa plakaları yalnızca maskeli görür.

**Vatandaş sayfası (mobil web)**
- Sürücü tabeladaki ya da görevlinin telefonundaki QR'ı okutur, plakayı girer ve güncel tutarı görür.
- Gün gün döküm hangi saatlerin ücretli, hangilerinin ücretsiz olduğunu gösterir (gece aralığı ücretsizdir).

**Backend (Spring Boot)**
- JWT kimlik doğrulamalı REST API ve üç rol (görevli, yönetici, geliştirici); vatandaş sayfası için az sayıda salt okunur, herkese açık uç nokta.
- Flyway migration'ları, tekrara dayanıklı (idempotent) giriş, iyimser kilit, yakalama destekli SSE, gece çalışan saklama işleri.

## Ekran görüntüleri

| | | |
|---|---|---|
| ![Genel bakış](docs/images/panel/dashboard.png) **Genel bakış** | ![Ayrıntı satırı açık kayıtlar](docs/images/panel/records.png) **Kayıtlar** | ![Gün gün dökümlü tarife önizlemesi](docs/images/panel/tariff-preview.png) **Tarife önizlemesi** |
| ![Kaçak araçlar](docs/images/panel/runaways.png) **Kaçaklar** | ![Personel](docs/images/panel/staff.png) **Personel** | ![Vardiyalar](docs/images/panel/shifts.png) **Vardiyalar** |
| ![Raporlar](docs/images/panel/reports.png) **Raporlar** | ![Denetim kaydı](docs/images/panel/audit.png) **Denetim kaydı** | ![Geliştirici sayfası](docs/images/panel/developer.png) **Geliştirici sayfası** |
| ![Bildirim zili](docs/images/panel/notifications.png) **Bildirimler** | ![Koyu tema genel bakış](docs/images/panel/dashboard-dark.png) **Koyu tema** | ![Tarife sayfası](docs/images/panel/tariff.png) **Tarife taslağı** |

**Saha uygulaması** (Android, Ekim 2026 arayüzü)

| | | | |
|---|---|---|---|
| ![Giriş](docs/images/mobile/login.png) | ![Ana sayfa](docs/images/mobile/home.png) | ![Cadde görünümü](docs/images/mobile/street.png) | ![Cadde görünümü, koyu](docs/images/mobile/street-dark.png) |
| Giriş | Ana sayfa ve vardiya özeti | Cadde görünümü | Cadde görünümü, koyu |
| ![Araç ayrıntısı ve ödeme](docs/images/mobile/vehicle-detail.png) | ![Ödemeden sonra geri al](docs/images/mobile/undo-payment.png) | ![Geçmiş](docs/images/mobile/history.png) | |
| Araç ayrıntısı ve ödeme | Ödemeden sonra geri al | Geçmiş | |

**Vatandaş sayfası**

| | | |
|---|---|---|
| ![Vatandaş QR akışı](docs/images/citizen-qr.gif) | ![Plaka girişi](docs/images/citizen/plate-entry.png) | ![Gün gün dökümlü tutar](docs/images/citizen/fee-breakdown.png) |
| QR akışı | Plaka girişi | Tutar ve gün gün döküm |

Panel ve vatandaş sayfası görüntülerinin hepsi örnek veriyle çalışan yerel Docker demosundan alınmıştır.

## Mimari

```mermaid
flowchart LR
    subgraph Istemciler["İstemciler"]
        APP["Flutter saha uygulaması<br/>(Android, cihazda OCR)"]
        PANEL["Yönetim paneli<br/>(statik HTML/JS)"]
        CIT["Vatandaş sayfası<br/>(mobil web, QR)"]
    end
    PAGES["GitHub Pages<br/>(panel ve vatandaş sayfası)"]
    subgraph Render["Render (ücretsiz web servisi, Docker)"]
        API["Spring Boot API<br/>JWT · REST · SSE"]
        JOBS["Zamanlanmış işler<br/>gece saklama,<br/>tarife yürürlüğü"]
    end
    DB[("Neon üzerinde PostgreSQL<br/>(boşta sıfıra iner)")]

    PAGES -. sunar .-> PANEL
    PAGES -. sunar .-> CIT
    APP -- "REST /api" --> API
    PANEL -- "REST /api" --> API
    CIT -- "GET /api/parking/debt" --> API
    API -- "SSE /api/events" --> APP
    API -- "SSE /api/events" --> PANEL
    API -- "JDBC, Flyway" --> DB
    JOBS --> DB
    MON["Çalışırlık izleyicisi"] -- "GET /api/health<br/>(veritabanına dokunmaz)" --> API
```

## Teknolojiler

| Katman | Teknoloji |
|---|---|
| Backend | Java 21, Spring Boot 4 (Web MVC, Security, Data JPA, Validation), Flyway, JJWT, Apache POI (Excel), springdoc OpenAPI |
| Veritabanı | PostgreSQL 16 (canlıda Neon, yerelde Docker), hızlı testler için H2 |
| Mobil | Flutter 3.38 / Dart, Google ML Kit metin tanıma, camera, `qr_flutter`, `shared_preferences` |
| Web | Ortak tasarım değişkenli statik HTML/CSS/saf JS, Chart.js, EventSource (SSE) |
| Test | JUnit 5, Mockito, Spring Boot Test, Testcontainers (PostgreSQL), Flutter widget testleri, gerçek telefonda `integration_test` |
| Operasyon | Docker, Docker Compose, Render, Neon, GitHub Pages, GitHub Actions |

## Mühendislik notları

- **Tekrara dayanıklı giriş.** Telefon her girişte bir `checkInRequestId` gönderir. Zaman aşımından sonra tekrarlanan istek ikinci bir araç oluşturmaz, ilk sonucu döndürür. Bkz. [`CheckInRequest`](backend/src/main/java/com/bilpark/backend/model/CheckInRequest.java), [`V6__check_in_request_id.sql`](backend/src/main/resources/db/migration/V6__check_in_request_id.sql) ve [`CheckInIdempotencyTest`](backend/src/test/java/com/bilpark/backend/CheckInIdempotencyTest.java).
- **İyimser kilit.** Aktif park kayıtlarında JPA `@Version` var; iki görevli (ya da görevli ve vatandaş sayfası) aynı aracı birlikte kapatamaz. [`ParkSpot`](backend/src/main/java/com/bilpark/backend/model/ParkSpot.java); gerçek Postgres'e karşı [`ConcurrentCheckoutPostgresTest`](backend/src/test/java/com/bilpark/backend/postgres/ConcurrentCheckoutPostgresTest.java) ile test edilir.
- **Sürümlü tarife motoru.** Tarife taslak → zamanlandı → yürürlükte → arşiv yolunu izler. [`FeeEngine`](backend/src/main/java/com/bilpark/backend/fee/FeeEngine.java) saf bir fonksiyondur: yalnızca günlük ücretli aralıktaki (ör. 08:00–22:00) dakikaları toplar, tarifeyi bir kez uygular ve uygulamanın, panelin ve vatandaş sayfasının gösterdiği gün gün dökümü döndürür. Sözleşme: [`docs/api-asama3.md`](docs/api-asama3.md).
- **Dokunma saatini koruyan çevrimdışı kuyruk.** Çekim yokken yapılan girişler [`entry_queue.dart`](mobile/lib/core/entry_queue.dart) içinde bekler ve `clientEntryTime` ile gönderilir. Sunucu bu saati yalnızca makul bir aralıkta kabul eder (en çok 24 saat geri, 5 dakika ileri); böylece çekmeyen bölge ücreti düşürmez. Bkz. [`ParkingService`](backend/src/main/java/com/bilpark/backend/service/ParkingService.java) içindeki `resolveEntryTime`.
- **Yoklamasız canlı senkron.** [`SyncService`](backend/src/main/java/com/bilpark/backend/sync/SyncService.java) sürüm numaralı olayları SSE ile, kalp atışı ve `RESET` sinyaliyle yayınlar. Bağlantı kopunca istemci `GET /api/sync?since=<sürüm>` ile eksikleri alır. `EventSource` başlık gönderemediği için tarayıcı akışı kısa ömürlü, tek kullanımlık bir biletle açar. İstemci tarafı: [`live_sync.dart`](mobile/lib/core/live_sync.dart).
- **Eski canlı şemanın yükseltilmesi.** Canlıda Hibernate'in Mart 2026'da kurduğu, Flyway geçmişi olmayan şema vardı. [`V7__legacy_march_schema.sql`](backend/src/main/resources/db/migration/V7__legacy_march_schema.sql) ve `baseline-version=0` onu güncel hâle getirir. Yükseltme canlı şemanın birebir kopyasında prova edildi ve [`MarchLiveSchemaUpgradePostgresTest`](backend/src/test/java/com/bilpark/backend/postgres/MarchLiveSchemaUpgradePostgresTest.java) ile test ediliyor.
- **Sınırlı yetkili geliştirici rolü.** Bakım hesabı lisansı uzatabilir, sistem sağlığına ve hatalara bakabilir. Geliştiricinin her işlemi yöneticinin gördüğü denetim kaydına düşer. Bu rol plakaları maskeli görür (`34 *** *23`).
- **Gizli kapatma düğmesi yerine lisans ve okunur mod.** Lisans dolunca `/api` altındaki her yazma isteği `423 Locked` döner ve panel nedenini gösterir. Okuma ve Excel çıktısı çalışmaya devam eder, hiçbir veri silinmez. Bkz. [`ReadOnlyModeInterceptor`](backend/src/main/java/com/bilpark/backend/license/ReadOnlyModeInterceptor.java) ve [`LicenseService`](backend/src/main/java/com/bilpark/backend/license/LicenseService.java).
- **Ücretsiz katmanlara göre tasarım.** [`/api/health`](backend/src/main/java/com/bilpark/backend/controller/HealthController.java) veritabanına hiç dokunmaz; izleyici Render'ı uyanık tutarken Neon boşta sıfıra inebilir. Hikari havuzu boşta bağlantı tutmaz (`minimum-idle=0`), bakım işleri yalnızca gece çalışır ve altı saniyelik yoklamanın yerini SSE aldı.
- **Testler.** 250'den fazla backend testi (JUnit 5, Spring Boot Test; migration, eşzamanlılık ve saklama için PostgreSQL 16'ya karşı Testcontainers), 130'dan fazla Flutter birim ve widget testi, ayrı bir Docker yığınına karşı gerçek Android telefonda koşan beş uçtan uca test paketi ([`docs/e2e-testler.md`](docs/e2e-testler.md)).

## Hızlı başlangıç (yerel demo)

Docker Desktop gerekir. Demo canlı ortamdan tamamen ayrıdır.

```bash
powershell -ExecutionPolicy Bypass -File demo/yeni-env.ps1   # rastgele sırlarla .env.demo oluşturur
docker compose up --build                                     # Postgres :5433, API :8081, web :5500
powershell -ExecutionPolicy Bypass -File demo/ornek-veri.ps1  # isteğe bağlı örnek veri
```

- Yönetim paneli: http://localhost:5500/admin-panel.html
- Vatandaş sayfası: http://localhost:5500/customer-payment.html
- API belgeleri (Swagger UI): http://localhost:8081/swagger-ui.html

Demo kullanıcıları, telefon kurulumu (Wi-Fi ya da `adb reverse` ile USB) ve sık hatalar: [`docs/yerel-demo.md`](docs/yerel-demo.md).

**Mobil uygulama.** API adresi derleme sırasında verilir:

```bash
cd mobile
flutter pub get
flutter run --dart-define=API_BASE=http://<bilgisayar-ip>:8081/api
# release APK
flutter build apk --release --dart-define=API_BASE=https://<api-adresi>/api
```

**Yalnızca backend.** [`backend/.env.example`](backend/.env.example) içindeki değişkenleri (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD`, `OFFICER_PASSWORD`) ayarlayıp `./mvnw spring-boot:run` çalıştırın. `JWT_SECRET` yoksa uygulama açılmaz.

## Testler

```bash
cd backend && ./mvnw test          # H2 testleri; Docker varsa Testcontainers testleri de koşar
cd mobile && flutter test          # birim ve widget testleri
cd mobile && flutter analyze       # CI ile aynı denetim
bash mobile/integration_test/tools/telefon_dogrula.sh   # gerçek telefonda uçtan uca, yaklaşık 30 dakika
```

GitHub Actions her pull request'te tüm testlerle Maven derlemesini ve `flutter analyze`'ı çalıştırır.

## Proje yapısı

```
backend/        Spring Boot API: controller, servis, ücret motoru, senkron (SSE), denetim, lisans, rapor, saklama
  src/main/resources/db/migration/   Flyway migration'ları V1–V9
mobile/         Flutter saha uygulaması
  lib/core/     API istemcisi, çevrimdışı kuyruk, canlı senkron, plaka ayrıştırma, tarife ve araç tipi depoları
  lib/screens/  giriş, ana sayfa, cadde görünümü, plaka tarayıcı, araç ayrıntısı, geçmiş, ayarlar
  integration_test/  gerçek telefonda uçtan uca testler
web/            Yönetim paneli, vatandaş sayfası, ortak tema; sayfa modülleri web/js/ altında
demo/           Yerel demo yardımcıları: .env üretici ve örnek veri
docs/           API sözleşmeleri, demo ve canlıya geçiş rehberleri, görseller (dizin: docs/README.md)
web-admin/      İlk React denemesi, kullanılmıyor
```

## Nasıl gelişti

| Ne zaman | Aşama | Ne değişti |
|---|---|---|
| Şubat 2026 | Prototip | Spring Boot ve Neon, ilk ücret algoritması, numaralı sabit park yerleri olan bir Flutter uygulaması, elle çizilmiş katmanlı mimari şeması ve caddelerin uydu krokisi. |
| Şubat sonu – Mart 2026 | İlk yayın | ngrok'tan Render'a geçiş. Sabit kapasitesiz dinamik cadde modeli, vatandaş QR ödeme sayfası, grafikli ve Excel çıktılı yönetim paneli, kaçak takibi. |
| Ekim 2026 | Gerçek kullanım için yeniden yapım | Sırlar ortam değişkenlerine taşındı, JWT rolleri ve görevli sahaları, Flyway, kilitli ve tekrara dayanıklı giriş, SSE ile çok cihazlı canlı senkron, zamanlanabilir sürümlü tarife, yeni arayüz, personel, vardiya, rapor, denetim kaydı, lisans ve okunur mod, veriden gelen araç tipleri, gerçek telefonda uçtan uca testler. |
| Kasım 2026 | Canlı | Eski canlı şema kopyada prova edildikten sonra yükseltildi, deneme verisi temizlendi, release derlemesi telefonda doğrulandı ve sistem canlıya alındı. Ardından geliştirici izleme ve yönetici bildirimleri geldi. |

| Şubat 2026 mimari | Şubat 2026 kroki | Şubat 2026 saha uygulaması | Ekim 2026 panel |
|---|---|---|---|
| ![Şubat 2026 mimari şeması](docs/images/evolution/2026-02-architecture.png) | ![Şubat 2026 cadde krokisi](docs/images/evolution/2026-02-street-sketch.jpg) | ![Şubat 2026 sabit ızgara ekranı, plakalar bulanık](docs/images/evolution/2026-02-mobile-street.png) | ![Ekim 2026 panel genel bakışı](docs/images/evolution/2026-10-panel-overview.png) |

## Yol haritası

- [ ] Vatandaş sayfasında online ödeme; önce bir ödeme simülatörüyle
- [ ] Bulut derleme servisiyle iOS sürümü (bazı görevliler iPhone kullanıyor)
- [ ] Panelden saha başına yazdırılabilir QR tabelaları
- [ ] Panelde aboneler sayfası

## Geliştirici

**Kadir Kacır**, öğrenci geliştirici. Backend'i, mobil uygulamayı ve web istemcilerini tasarlayıp geliştirdim; canlı kurulumu da ben yürütüyorum.

<!-- LinkedIn / e-posta -->

Tüm hakları saklıdır; bkz. [LICENSE](LICENSE).
