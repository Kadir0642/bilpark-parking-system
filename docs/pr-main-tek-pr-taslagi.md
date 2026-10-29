# PR taslağı: `claude/canli-gecis-provasi` → `main`

> Bu dosya, 1 Kasım'da açılacak tek PR'ın başlığı ve açıklamasıdır. PR'ı açarken aşağıdaki çizginin altını kopyala. Merge, `docs/1-kasim-canliya-gecis.md` 5. adımında yapılır.

**Başlık:** `BilPark Aşama 2-5, araç tipleri, saha kolaylıkları ve canlı veritabanı geçişi`

---

## Özet

Bu PR, `main`'deki Aşama 1'in üstüne kurulan 12 dalın tamamını ve 29 Ekim canlı geçiş provasının düzeltmesini tek seferde alır. Dallar sırayla üst üste kuruldu, ayrı ayrı merge edilmedi. Her biri bir öncekini içeriyor.

| Sıra | Dal | İçerik |
|---|---|---|
| 1 | `claude/dreamy-mccarthy-41ab1e` | Aşama 2: kilitli giriş/çıkış, Flyway V1+V2, vardiya, ödeme yöntemi |
| 2 | `claude/musing-mahavira-fd4b78` | Arayüz yenileme (mobil ve web) |
| 3 | `claude/lucid-roentgen-58dc28` | Aşama 3 backend: V3, sürümlü tarife, canlı senkron (SSE), `/api/health` |
| 4 | `claude/wonderful-ellis-5ce2ee` | Aşama 3 istemci |
| 5 | `claude/funny-meninsky-fcc351` | Yerel demo ortamı (docker compose) |
| 6 | `claude/frosty-morse-b8aa87` | Aşama 5 backend: V4, kullanıcı yönetimi, raporlar, denetim kaydı, lisans, şifre değiştirme |
| 7 | `claude/nice-gould-6528ac` | Aşama 5 istemci: personel, vardiya, rapor, denetim, lisans ekranları |
| 8 | `claude/gallant-ishizaka-388fe4` | Araç tipleri veritabanında (V5), motosiklet |
| 9 | `claude/e2e-tests` | Gerçek telefonda uçtan uca testler |
| 10 | `claude/e2e-bulgu-duzeltmeleri` | E2E bulgularının düzeltmeleri, idempotent giriş (V6), çevrimdışı giriş kuyruğu |
| 11 | `claude/saha-kolaylik` | Saha personeli için dokunarak çalışma: plaka doğrulama, canlı kamera, iptal gerekçeleri |
| 12 | `claude/cevrimdisi-giris-saati` | Çevrimdışı girişin saati, memurun dokunduğu an olarak kaydedilir |
| 13 | `claude/canli-gecis-provasi` | Canlıdaki Mart 2026 şemasından yükseltme (bu PR'ın son parçası) |

## Canlı veritabanı geçişi (prova bulgusu)

Canlı Neon veritabanında hâlâ Mart 2026 uygulamasının (`6abc999`) Hibernate ile kurduğu iki tablo var: `active_park_spots` ve `parking_records`. Eski ayar (`baseline-version=1`) V1'i atladığı için ilk açılışta V2, `relation "subscriptions" does not exist` hatasıyla düşüyordu.

Düzeltme:

- `spring.flyway.baseline-version=0`: idempotent V1 eksik tabloları kurar, V2-V6 olduğu gibi çalışır.
- `V7__legacy_march_schema.sql`:
  - `version` eklenir, eski araçlarda 0 olur.
  - `zone_id` ve yabancı anahtarı eklenir, eski kayıtlarda boş kalır.
  - `debt_paid` yalnızca kolon yoksa eklenir: `RUNAWAY` kayıtlarda `false`, diğerlerinde `true`.
  - Mart'tan kalan cadde ve durum CHECK kısıtları kaldırılır.
  - Boş veritabanında ve V6'daki veritabanında hiçbir şey değiştirmez.
- V1-V6'ya dokunulmadı. Mevcut veritabanlarında (yerel demo dahil) checksum değişmez.

Doğrulama:

- Mart şeması, `6abc999`'un kendisiyle ayrı bir Docker Postgres'te kuruldu. Veriler Mart API'si üzerinden girildi: giriş, çıkış, ödeme, kaçak.
- `MarchLiveSchemaUpgradePostgresTest`: bu şemadan son sürüme yükseltir. Her satır korunur: id, saat, ücret, tip ve cadde. Plakalar V2 ile normalize edilir. Sonuç şema, boş veritabanından kurulan şemayla birebir aynıdır. İkinci çalıştırma hiçbir şey yapmaz.
- `MarchLiveDatabaseAppPostgresTest`: uygulama bu veritabanında açılır ve `ddl-auto=validate` geçer.
  - `admin` ve `tevfik` oluşur.
  - Eski kaçak borcu araç tekrar girdiğinde görünür.
  - Memur, kendi caddesindeki eski aracı kapatabilir; başka caddedekini kapatamaz.
- `FlywayBaselinePostgresTest`: Aşama 1 şeması ve boş veritabanı, yeni ayarla da geçer.

## Testler

- Backend `./mvnw test`: **244 test, 0 hata, 0 atlanan.** Postgres Testcontainers dahil, 29 Ekim 2026.
- Mobil: `flutter analyze` ve `flutter test` son olarak `claude/cevrimdisi-giris-saati` dalında temiz (141/141). Bu PR'daki son değişiklik yalnızca backend ve dokümanı kapsar.

## Deploy notları

- **Deploy sırası önemli.** `docs/1-kasim-canliya-gecis.md` sırayla izlenir: Neon yedek dalı → şifre sıfırlama → Render ortam değişkenleri → salt okunur kontrol sorguları → bu PR'ın merge'i → elle deploy.
- Render'da `ADMIN_PASSWORD` ve `OFFICER_PASSWORD` **zorunlu**: canlıda `users` tablosu yok, ilk açılışta kullanıcılar bu şifrelerle oluşur.
- Merge'den önce Render'da Auto-Deploy kapatılır. Deploy, kontrol sorgularından sonra elle yapılır.
- Eski sürüme geri dönüş yok: Mart kodu eski, sızmış şifreyi kullanıyor ve yeni şemayla çalışmıyor. Geri dönüş planı kontrol listesinin 10. adımında.

## Bilinen sınırlar

- Mart'tan kalan aktif araçların bölgesi yok. Saha uygulaması yalnızca kendi bölgesini listelediği için memurlar bunları görmez. Kontrol listesinin 7.4 adımında bunlar admin olarak iptal edilir.
- Mart kayıtlarında `payment_method`, `tariff_id` ve `collected_by` boştur. O dönemde bu bilgiler tutulmuyordu; tahmin edilmedi.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
