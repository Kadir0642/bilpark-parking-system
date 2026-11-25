# Canlıya geçiş kontrol listesi

Bu liste, yeni sürümü (Aşama 1-5, araç tipleri, saha kolaylıkları, çevrimdışı giriş saati) canlıya çıkarmak için adım adım izlenir. Her adımın sonunda bir **Kontrol** satırı var. O kontrol tutmadan sonraki adıma geçme.

Liste ilk olarak 1 Kasım 2026 için yazıldı (dosya adı oradan geliyor). 17 Kasım 2026'da, canlı veritabanının gerçek durumuna göre güncellendi.

Gerekenler: Neon, Render, GitHub ve UptimeRobot hesaplarına giriş, bir tarayıcı, PowerShell, canlı API'ye bağlı saha uygulaması yüklü bir telefon ve QR okutmak için ikinci bir telefon. Hiçbir adım ücretli değildir. Bir ekran ücretli plan ya da kredi önerirse (ör. Neon'daki "Claim $100 credit" bandı) kabul etme, önce sor.

Adresler:

- API: `https://bilpark-api-rtdl.onrender.com` (aşağıda `API` diye geçiyor)
- Swagger: `API/swagger-ui.html`
- Vatandaş sayfası (QR): `https://kadir0642.github.io/bilpark-parking-system/web/customer-payment.html`

Adlar:

- Neon'da ana dalın adı **`production`** (GitHub'daki `main` ile karıştırma).
- Yedek dal: **`yedek-2026-11-14-mart`**.

## Durum (17 Kasım 2026)

| Adım | Durum |
|---|---|
| 0. Başlamadan önce | Neon kısmı tamam (14 Kasım). Render'da Auto-Deploy'un **Off** olduğu teyit edilecek. |
| 1. Yedek dal | **Tamam** (14 Kasım): iki dalda da 8 aktif araç, 67 kayıt. |
| 4.1-4.5 Kontrol sorguları | **Tamam** (14 Kasım). Sonuçlar 4. adımda yazılı. |
| 5. Merge | **Tamam** (29 Ekim, PR #3). O günkü otomatik deploy Neon kotası kapalı olduğu için düştü; veritabanına dokunulmadı. |
| 2, 3, 4.6, 6-9 | **Bekliyor.** 2 → 3 → 4.6 → 6 tek oturumda yapılır. |

> **Güncelleme (25 Kasım 2026):** İlk canlıya geçiş yapıldı; canlı veritabanı Flyway **V7**'de (eski şemadan, `baseline-version=0` ile yükseltildi). Yukarıdaki tablo ve aşağıdaki 2-10. adımlar bu ilk geçişin kaydıdır. Sonraki sürüm (V8 ve V9) için sayfanın sonundaki **Sonraki deploy (V8+V9)** bölümüne bak. `main`'e ayrıca release derlemesi için R8 kuralları (`mobile/android/app/proguard-rules.pro`) ve Flutter 3.38.9 sabitlemesi (CI) girdi; canlı sistemi etkilemez.

---

## Neden bu sıra? (29 Ekim ve 17 Kasım provaları)

29 Ekim provası, canlıda yalnızca iki tablo (`active_park_spots`, `parking_records`) olduğu varsayımıyla yapıldı. 14 Kasım'da canlıda çalıştırılan sorgular durumun farklı olduğunu gösterdi:

- **Altı tablo var.** `users`, `zones`, `subscriptions` ve `fiscal_years` da mevcut. Büyük olasılıkla Ekim'deki kod, şifre koda gömülüyken bir geliştirme bilgisayarından Neon'a karşı çalıştırıldı ve Hibernate bu tabloları ekledi. `flyway_schema_history` yok.
- **Şema Aşama 1 biçiminde, tek eksikle.** `active_park_spots` tablosunda `version` ve `zone_id` var. `parking_records` tablosunda `zone_id` var, `debt_paid` yok. Kısıt adları Hibernate'in rastgele adları.
- **`users` tablosunda iki hesap var:** `admin` ve `tevfik`. Bunları eski kod, kodda sabit yazılı şifrelerle oluşturdu. O şifreler herkese açık repo geçmişinde duruyor.
- Enum'lar ad olarak saklı (`PAID`, `SMALL` gibi), sayı yok. Plaka çakışması yok.

17 Kasım provası, bu şemanın birebir kopyası üzerinde yapıldı. Kopya, canlıdan dışa aktarılan kolon ve kısıt listesinden (48 kolon, 23 kısıt) ayrı bir Docker Postgres'te kuruldu ve `main`'deki sürüm (`ed5b207`) üzerinde çalıştırıldı. Sonuçlar:

- **Temizlik yapılmadan:** 7 migration hatasız geçiyor ve veri korunuyor. Ama `users` boş olmadığı için uygulama kullanıcı oluşturmuyor: `admin` ve `tevfik` eski, açıkta kalmış şifreleriyle kalıyor ve `ADMIN_PASSWORD` hiç kullanılmıyor. **Bu yüzden 4.6 zorunlu.**
- **4.6'daki temizlikten sonra:** `admin` ve `tevfik`, Render'daki `ADMIN_PASSWORD` ve `OFFICER_PASSWORD` ile sıfırdan oluşuyor. Eski şifreyle giriş `401` dönüyor. Yeni şifreyle giriş, araç girişi ve mükerrer giriş engeli çalışıyor. Kimliksiz `GET /api/parking/spots` artık `401`.
- **Şema karşılaştırması:** Yükseltilen şema, boş bir veritabanından kurulan şemayla birebir aynı (kolonlar, kısıtlar, indeksler).

Migration'ların bu şemada yaptıkları:

- `baseline-version=0` olduğu için V1 çalışır; tablolar zaten var olduğundan hiçbirini değiştirmez (`already exists, skipping` uyarıları normaldir).
- V2-V6 eksik kolonları ve yeni tabloları ekler, Hibernate'in eski enum CHECK kısıtlarını kaldırır.
- V7 `debt_paid` kolonunu ekler: `RUNAWAY` kayıtlarda `false`, diğerlerinde `true`.

> **Önemli:** 2. adımda Neon şifresi değiştiği anda şu an canlıda çalışan eski uygulama veritabanına bağlanamaz. Mart kodunda şifre koda gömülü. Site, 6. adımdaki deploy bitene kadar kapalı kalır. Bu yüzden 2 → 3 → 4.6 → 6 adımlarını **tek oturumda** yap; yaklaşık 30-40 dakika sürer. Eski sürüme geri dönmek mümkün değil, geri dönüş planı 10. adımda.

---

## 0. Başlamadan önce

- [ ] **Render → `bilpark-API` → Settings → Build & Deploy → Auto-Deploy: Off.** PR zaten merge edildiği için bu adım en başta gelir. Açık kalırsa `main`'e gelen herhangi bir commit, yedek ve temizlik yapılmadan deploy başlatabilir. 6. adıma kadar **Manual Deploy** düğmesine de basma.
- [ ] Yeni şifreleri bir parola yöneticisine kaydedeceksin. Hiçbir şifreyi sohbete, commit'e ya da ekran görüntüsüne koyma.
- [x] Neon → Usage: Compute saatleri sıfırlanmış (14 Kasım: 0,32 CU-saat).
- [x] Neon compute **0.25 CU** ve uykuya geçme (scale to zero) çalışıyor.
- [x] PR merge edildi (29 Ekim, PR #3).
- [ ] Saha ekibi varsa haber ver: geçiş sırasında sistem yaklaşık bir saat kapalı olabilir.

**Kontrol:** Render'da Auto-Deploy kutusunda `Off` yazıyor. Neon SQL Editor'da `SELECT 1;` çalışıyor.

---

## 1. Neon'da yedek dal oluştur (tamamlandı: 14 Kasım)

Her şeyden önce verinin o günkü hâli donduruldu.

1. Neon → **Branches** → **Create branch**.
   - Ad: `yedek-2026-11-14-mart`
   - Parent: `production`
   - Seçenek: **Branch data and schema** (şu anki veri). Auto-delete: **Never**.
2. Dal oluşunca SQL Editor'ın sol üstünden yedek dalı seç ve çalıştır:

   ```sql
   SELECT (SELECT count(*) FROM active_park_spots) AS aktif,
          (SELECT count(*) FROM parking_records)   AS kayit;
   ```

3. Aynı sorguyu `production` dalında da çalıştır.

**Kontrol:** İki dal aynı sayıları veriyor: `8 | 67`. Yedek dal Branches listesinde görünüyor.

> Yedek 14 Kasım'da alındı. Geçiş başka bir gün yapılacaksa ve arada canlıya kayıt girildiyse, 2. adımdan önce aynı sorguyu tekrar çalıştır. Sayılar değiştiyse yeni bir yedek dal al.

---

## 2. Neon şifresini sıfırla

Eski `neondb_owner` şifresi, public repo geçmişindeki Mart commit'lerinde açıkta. Bu yüzden şifre değişmek zorunda.

1. Neon → **Roles** (dal: `production`) → `neondb_owner` → **Reset password**. Yeni şifreyi parola yöneticisine kaydet.
2. Rol şifreleri dal başınadır. Yedek dal eski şifreyle oluştu. Aynı işlemi `yedek-2026-11-14-mart` dalında da yap. Aynı yeni şifreyi kullanabilirsin.

Bu andan sonra eski canlı uygulama veritabanına bağlanamaz. Bu beklenen bir durum.

**Kontrol:** Neon'un **Connect** penceresinde yeni bağlantı bilgisi görünüyor.

---

## 3. Render'da ortam değişkenlerini ayarla

1. Render → `bilpark-API` → **Environment**.
2. `DB_PASSWORD` değerini yeni şifreyle değiştir. **Save changes**. Render "Save and deploy" sorarsa **Save only** seç.
3. Aşağıdakilerin dolu olduğunu kontrol et:

   | Değişken | Değer |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://<neon-host>/neondb?sslmode=require` (başında `jdbc:` olmalı; host, `production` dalının host'u) |
   | `DB_USERNAME` | `neondb_owner` |
   | `JWT_SECRET` | En az 32 bayt. Boşsa PowerShell'de üret: `[Convert]::ToBase64String((1..48 \| % {Get-Random -Max 256}))` |
   | `ADMIN_PASSWORD` | **Zorunlu.** 4.6'da `users` boşaltıldığı için ilk açılışta `admin` kullanıcısı bu şifreyle oluşur. |
   | `OFFICER_PASSWORD` | **Zorunlu.** İlk açılışta örnek memur `tevfik` (Tevfik Bey, Bölüm 1) bu şifreyle oluşur. |
   | `DEVELOPER_PASSWORD` | İsteğe bağlı, önerilir. `gelistirici` bakım hesabını bir kez oluşturur (bkz. `docs/sozlesme-eki-gelistirici-ve-lisans.md`). |

   Şifreler en az 8 karakter olmalı ve harf ile rakam içermeli. Kodda sabit yazılı eski şifreleri kullanma. `ADMIN_PASSWORD` ve `OFFICER_PASSWORD` 7. adımda değiştirilecek.

**Kontrol:** Environment sayfasında `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD` ve `OFFICER_PASSWORD` var. Auto-Deploy hâlâ kapalı ve deploy başlamadı.

---

## 4. Kontrol sorguları ve temizlik

Neon SQL Editor'da, **`production` dalında** çalıştırılır. 4.1-4.5 salt okunurdur ve 14 Kasım'da çalıştırıldı; sonuçları aşağıda. Veriyi değiştiren tek adım 4.6'dır ve henüz yapılmadı.

### 4.1 Hangi tablolar var?

```sql
SELECT table_name FROM information_schema.tables
 WHERE table_schema = 'public' ORDER BY 1;
```

**14 Kasım sonucu:** `active_park_spots`, `fiscal_years`, `parking_records`, `subscriptions`, `users`, `zones`. Bu, 17 Kasım provasında denenen durum.

| Sonuç | Ne yapmalı |
|---|---|
| Yukarıdaki altı tablo | Beklenen durum. Devam et. |
| `flyway_schema_history` var | **Dur ve sor.** Yarım kalmış bir deploy olmuş demektir (ör. Auto-Deploy açık kalmış). |
| Başka beklenmedik bir şey | Dur ve sor. |

### 4.2 Enum değerleri ad olarak mı saklanmış?

```sql
SELECT 'aktif.status'  AS alan, status       AS deger, count(*) FROM active_park_spots GROUP BY 2
UNION ALL SELECT 'aktif.tip',   current_type, count(*) FROM active_park_spots GROUP BY 2
UNION ALL SELECT 'aktif.cadde', street,       count(*) FROM active_park_spots GROUP BY 2
UNION ALL SELECT 'kayit.status', status,      count(*) FROM parking_records   GROUP BY 2
UNION ALL SELECT 'kayit.tip',   vehicle_type, count(*) FROM parking_records   GROUP BY 2
UNION ALL SELECT 'kayit.cadde', street,       count(*) FROM parking_records   GROUP BY 2
ORDER BY 1, 2;
```

**14 Kasım sonucu:** Yalnızca `ACTIVE`, `PAID`, `RUNAWAY`, `SMALL`, `LARGE`, `TEVFIK_BEY`, `ALI_RIZA_OZKAY`, `CUMHURIYET`. Sayı yok. Temiz.

### 4.3 Plaka çakışması

Yeni sürüm plakaları boşluksuz ve tiresiz saklar (`34 ABC 123` → `34ABC123`). Normalize edilince aynı olan iki aktif araç varsa V2 durur ve hiçbir şey değiştirmez.

```sql
SELECT upper(regexp_replace(current_plate, '[\s-]', '', 'g')) AS plaka,
       count(*)                                               AS adet,
       string_agg(id::text || ' ' || current_plate, ', ' ORDER BY id) AS kayitlar
  FROM active_park_spots
 GROUP BY 1
HAVING count(*) > 1;
```

**14 Kasım sonucu:** 0 satır. Temiz.

### 4.4 Anlık görüntü

```sql
SELECT (SELECT count(*) FROM active_park_spots)                                       AS aktif_arac,
       (SELECT count(*) FROM parking_records)                                         AS kayit,
       (SELECT count(*) FROM parking_records WHERE status = 'PAID')                   AS odenen,
       (SELECT coalesce(sum(fee), 0) FROM parking_records WHERE status = 'PAID')      AS ciro,
       (SELECT count(*) FROM parking_records WHERE status = 'RUNAWAY')                AS kacak,
       (SELECT coalesce(sum(fee), 0) FROM parking_records WHERE status = 'RUNAWAY')   AS kacak_borc,
       (SELECT min(entry_time) FROM parking_records)                                  AS ilk_kayit,
       (SELECT max(exit_time) FROM parking_records)                                   AS son_cikis;
```

**14 Kasım sonucu:** 8 aktif araç, 67 kayıt, 45 ödenen (117.490 TL), 22 kaçak (119.715 TL), ilk kayıt 2 Mart 2026, son çıkış 11 Ekim 2026. Kayıt başına yaklaşık 2.600 TL: bunlar günlerce açık bırakılmış deneme kayıtları.

### 4.5 Kullanıcılar ve diğer tablolar

```sql
SELECT (SELECT count(*) FROM users)         AS users,
       (SELECT count(*) FROM zones)         AS zones,
       (SELECT count(*) FROM subscriptions) AS abonelik,
       (SELECT count(*) FROM fiscal_years)  AS mali_yil;
SELECT id, username, role FROM users ORDER BY id;
```

**14 Kasım sonucu:** 2 kullanıcı (`admin` ADMIN, `tevfik` OFFICER), 6 saha, 0 abonelik, 0 mali yıl. Şifre kolonunu sorgulama ve ekran görüntüsüne koyma.

### 4.6 Temizlik (zorunlu; 3. adımdan sonra, deploy'dan hemen önce)

Ne siliniyor ve neden:

- **Deneme kayıtları** (`active_park_spots`, `parking_records`): Hepsi geliştirme sırasında yapılan denemeler, gerçek müşteri kaydı yok (karar: 29 Ekim). Denemelerin ücretleri ciro raporlarına karışmaz.
- **İki eski hesap** (`users`): Şifreleri repo geçmişinde açıkta. Silinmezlerse yeni sürüm bu hesapları eski şifreleriyle kullanmaya devam eder. Silinince ilk açılışta `ADMIN_PASSWORD` ve `OFFICER_PASSWORD` ile yeniden oluşurlar.
- **İki eski CHECK kısıtı:** Hibernate'ten kalma `zones_street_check` ve `subscriptions_status_check`. Sıfırdan kurulan şemada yoklar; kaldırılınca canlı şema onunla birebir aynı olur.

Sahalar (`zones`, 6 satır) silinmez. Silinen her şey yedek dalda durur.

Önce `production` dalının seçili olduğunu kontrol et. Sonra hepsini birlikte çalıştır:

```sql
BEGIN;
DELETE FROM active_park_spots;
DELETE FROM parking_records;
DELETE FROM users;
ALTER TABLE zones DROP CONSTRAINT IF EXISTS zones_street_check;
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_check;
COMMIT;
SELECT (SELECT count(*) FROM active_park_spots) AS aktif,
       (SELECT count(*) FROM parking_records)   AS kayit,
       (SELECT count(*) FROM users)             AS kullanici,
       (SELECT count(*) FROM zones)             AS saha;
```

Son sorgu `0 | 0 | 0 | 6` döndürmeli. Tablolar silinmez, yalnızca içleri boşalır. (17 Kasım'da prova veritabanında denendi.)

**Kontrol:** 4.6 sonrası sonuç `0 | 0 | 0 | 6`.

---

## 5. Tek PR'ı `main`'e merge et (tamamlandı: 29 Ekim)

Zincirin tamamı `claude/canli-gecis-provasi` dalındaydı ve 29 Ekim 2026'da PR #3 ile `main`'e merge edildi (merge commit `ed5b207`). Render o an otomatik deploy denedi; Neon kotası kapalı olduğu için deploy düştü ve veritabanına dokunulmadı. Canlıda Mart sürümü çalışmaya devam etti.

GitHub Pages `main`'den yayın yaptığı için vatandaş sayfası o gün güncellendi.

**Kontrol:** Render → Deploys listesinde `ed5b207` satırı "Failed", `6abc999` "Live" görünüyor. Auto-Deploy kapalı.

---

## 6. Deploy

1. Render → `bilpark-API` → **Manual Deploy → Deploy latest commit**.
2. **Logs** sekmesinde şu satırları bekle (sırayla):
   - `Successfully baselined schema with version: 0`
   - `Migrating schema "public" to version "1 - baseline"`, sonra `2 - stage2 multi device` … `7 - legacy march schema`
   - `Successfully applied 7 migrations to schema "public", now at version v7`
   - `Started BackendApplication`
   - `Default users (admin, tevfik) created.`
   - `Developer account 'gelistirici' created.` (`DEVELOPER_PASSWORD` girildiyse)

   Arada `relation "zones" already exists, skipping` ve `column "version" … already exists, skipping` gibi `WARN` satırları görünür. Bunlar normaldir: tablolar ve kolonlar zaten var.

Hata olursa:

| Log'daki hata | Anlamı | Ne yapmalı |
|---|---|---|
| `password authentication failed` | `DB_PASSWORD` veya `DB_URL` yanlış | 3. adıma dön, düzelt, tekrar deploy et. |
| `JWT_SECRET is missing or too short` | Secret eksik | 3. adımdaki komutla üret, tekrar deploy et. |
| `ADMIN_PASSWORD env var is required…` veya `OFFICER_PASSWORD…` | Migration'lar bitti ama kullanıcılar oluşamadı | Değişkeni ekle, tekrar deploy et. Flyway kaldığı yerden devam eder; migration'lar tekrar çalışmaz. |
| `Default users (admin, tevfik) created.` satırı **yok** | `users` tablosu boş değildi, yani 4.6 çalışmadı | **Dur ve sor.** Eski hesaplar eski şifreleriyle duruyor olabilir. Kimseye adres verme. |
| `V2 durduruldu: … aktif plakalar var` | 4.3'teki çakışma | V2 hiçbir şey değiştirmedi. V1'in kurduğu boş tablolar kalır, zararı yok. Dur ve sor. |
| `Migration of schema "public" to version … failed` (başka) | Beklenmeyen durum | Log'daki ilk `ERROR` satırını kaydet, **dur ve sor.** 10. adıma bak. |

**Kontrol (tarayıcıdan):**

- `API/api/health` → `{"status":"UP", ...}`
- `API/api/tariff/current` → tarife JSON'u
- `API/api/streets` → 3 cadde

**Kontrol (Neon SQL Editor, salt okunur):**

```sql
SELECT installed_rank, version, type, success FROM flyway_schema_history ORDER BY 1;
```

Sonuç 8 satır olmalı: `0 BASELINE`, sonra `1`-`7 SQL`; hepsinde `success = true`.

4.4'teki anlık görüntü sorgusunu tekrar çalıştır. **Bütün sayılar 0** olmalı.

```sql
SELECT count(*) AS hatali FROM parking_records WHERE status = 'RUNAWAY' AND debt_paid;  -- 0 olmalı
SELECT username, role FROM users ORDER BY 1;  -- admin, tevfik (ve varsa gelistirici)
```

Her şey tutuyorsa Auto-Deploy'u tekrar **On** yapabilirsin. İstersen kapalı bırakıp her sürümü elle deploy edebilirsin.

---

## 7. İlk açılış: kullanıcılar ve admin şifresi

4.6'da `users` tablosu boşaltıldığı için ilk açılışta iki kullanıcı oluştu:

- `admin`: şifresi `ADMIN_PASSWORD`
- `tevfik`: memur, Tevfik Bey Bölüm 1; şifresi `OFFICER_PASSWORD`

Bu şifreler Render ekranında duruyor. Şimdi değiştir.

### 7.1 Admin şifresini değiştir

`POST /api/me/password` uç noktası mevcut şifreyi doğrular. Yeni şifre en az 8 karakter olmalı, harf ve rakam içermeli, kullanıcı adını içermemeli. İşlem denetim kaydına düşer, eski oturumlar kapanır ve yanıtta yeni bir token gelir.

PowerShell:

```powershell
$api  = "https://bilpark-api-rtdl.onrender.com"
$cred = Get-Credential -UserName admin -Message "Mevcut admin şifresi (ADMIN_PASSWORD)"
$login = Invoke-RestMethod -Method Post -Uri "$api/api/auth/login" -ContentType "application/json" `
  -Body (@{ username = "admin"; password = $cred.GetNetworkCredential().Password } | ConvertTo-Json)
$new = Read-Host "Yeni admin şifresi" -AsSecureString
$newPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($new))
Invoke-RestMethod -Method Post -Uri "$api/api/me/password" -ContentType "application/json" `
  -Headers @{ Authorization = "Bearer $($login.token)" } `
  -Body (@{ currentPassword = $cred.GetNetworkCredential().Password; newPassword = $newPlain } | ConvertTo-Json)
Remove-Variable newPlain, new, cred, login
```

Beklenen yanıt: `{"message":"Şifre değiştirildi.","token":"..."}`.

Alternatif: `API/swagger-ui.html`. Önce `/api/auth/login` ile token al, **Authorize** alanına yaz, sonra `POST /api/me/password` uç noktasını kullan.

### 7.2 Memur hesapları

- [ ] Yönetim panelinde yeni admin şifresiyle giriş yap.
- [ ] `tevfik` şifresini panelden sıfırla, ya da memur ilk girişte `POST /api/me/password` ile kendisi değiştirsin.
- [ ] Diğer memurları panelden oluştur ve her birine bölge ata.
- [ ] Render → Environment: `ADMIN_PASSWORD` ve `OFFICER_PASSWORD` artık kullanılmıyor (yalnızca `users` boşken okunur). İstersen sil. Silersen ve tablo bir gün boşalırsa uygulama açılmayı reddeder; bu bir güvenlik önlemi.

### 7.3 Lisans

- [ ] Admin token'ıyla `GET /api/license/status` çağır. `state` alanı `ACTIVE` olmalı (V4 bitişi bir yıl sonraya, ek süreyi 15 güne kurar).
- [ ] Sözleşme tarihleri farklıysa `gelistirici` hesabıyla `PUT /api/developer/license` uç noktasını çağır. Gövde: `{"expiresOn":"2027-11-01","graceDays":15}`.

### 7.4 Eski aktif araçlar

Gerekmiyor. 4.6'da bütün deneme kayıtları silindiği için bölgesiz eski araç kalmadı.

**Kontrol:** Eski admin şifresiyle giriş `401` dönüyor, yenisiyle giriş yapılıyor. `GET /api/admin/audit?action=PASSWORD_CHANGED` kaydı gösteriyor.

---

## 8. UptimeRobot: `/api/health` ve bakım penceresi

Eski monitör `/api/parking/spots` adresine gidiyordu. O adres kimlik doğrulaması istiyor, bu yüzden 401 alıyor ve Neon'u boşuna uyanık tutuyordu. `/api/health` ise veritabanına hiç dokunmuyor.

1. UptimeRobot → monitörü düzenle (yoksa **Add New Monitor**):
   - Monitor type: **HTTP(s)**
   - URL: `https://bilpark-api-rtdl.onrender.com/api/health`
   - Method: **HEAD** (seçenek yoksa GET kalabilir)
   - Interval: 5 dakika
2. **Maintenance window** ekle: her gün **22:00-07:45**, saat dilimi **Europe/Istanbul**.
   - Gece istek gelmezse Render uykuya geçer ve Neon sıfıra iner.
   - Sabah 07:45'teki ilk ping, 08:00 mesaisinden önce sistemi uyandırır.
   - Bakım penceresi ücretsiz planda yoksa ücretli plana geçme, önce sor. Seçenekler: monitörü gece elle durdurmak ya da ücretsiz bir cron ile sabah 07:45'te tek istek atmak.
3. Monitörü **Resume** et (23 Ekim'de durdurulmuştu).

**Kontrol:** Monitör "Up" görünüyor. Ertesi gün Neon → Usage'da gece saatlerinde compute kullanımı yok.

---

## 9. QR testi (uçtan uca)

1. Canlı API'ye bağlı saha uygulamasıyla `tevfik` (ya da yeni bir memur) olarak giriş yap.
2. Bölgene gerçek formatta bir test plakası gir. Plakayı not al.
3. Araç kartını aç → **QR** göster.
4. İkinci telefonla QR'ı okut. `…/web/customer-payment.html?plate=…` açılmalı ve plaka, giriş saati, süre ve tutar görünmeli.
   - Sayfa "bağlanamadı" diyorsa Render uykudan uyanıyor olabilir. 30-60 saniye bekle ve yenile.
   - Sayfa eski görünüyorsa GitHub Pages henüz güncellenmemiştir (5. adım, 4. madde).
5. Saha uygulamasında araç kartından test girişini **iptal et** (hatalı giriş). Gerekçe: `Canlı geçiş QR testi`. İptal edilen kayıt ciroya ve borca girmez, denetim kaydında kalır.
6. Panelde ciro ve son kayıtlar görünüyor. Yalnızca bu geceki test kaydı var.

**Kontrol:** QR sayfası doğru plakayı ve tutarı gösterdi. Test girişi iptal edildi.

---

## 10. Geri dönüş planı

Bilinmesi gerekenler:

- **Eski uygulamaya dönüş yok.** Mart kodu eski (sızmış) şifreyi kullanıyor ve yeni şemayla da çalışmaz: `debt_paid` zorunlu kolonunu bilmiyor. "Geri dönüş", veriyi yedeğe döndürüp yeni sürümü düzeltmek ve tekrar deploy etmek demek. Bu sürede site kapalı kalır. Henüz gerçek saha kullanımı olmadığı için kimse etkilenmez.
- **Her migration kendi transaction'ında çalışır.** Düşen migration hiçbir iz bırakmaz, öncekiler kalır. Hata düzeltilip tekrar deploy edildiğinde Flyway kaldığı yerden devam eder. Hataların çoğunda veri geri yüklemeye gerek yoktur.
- **Hiçbir zaman** `flyway_schema_history` tablosunu elle silme, migration SQL'ini elle çalıştırma ve tablo silme (`DROP`). Önce sor.

Veriyi yedeğe döndür, eğer:

- 4.6'da yanlış bir şey silindiyse (ör. yanlış dal seçiliydi),
- ya da bir migration yarıda kalıp beklenmedik bir durum bıraktıysa ve sorduktan sonra geri dönmeye karar verildiyse.

Nasıl:

1. Render → `bilpark-API` → **Suspend** (ya da Auto-Deploy kapalıyken yeni deploy yapma). Uygulama veritabanına yazmasın.
2. **A seçeneği (tercih):** Neon → Branches → `production` → **Restore** (zamana göre geri yükleme). Zaman olarak 2. adıma başlamadan hemen önceki saati seç. Bu seçenek yalnızca son **6 saat** için çalışır (History retention 6 saat). Süre geçtiyse, ekrandaki adlar farklıysa ya da seçenek yoksa B'ye geç.
3. **B seçeneği:** Yedek dalı kullan. Neon → Branches → `yedek-2026-11-14-mart` → **Connect** → host'u kopyala. Render'da `DB_URL` değerini `jdbc:postgresql://<yedek-host>/neondb?sslmode=require` yap. Bu durumda yedek dal canlı veritabanı olur; silme.
4. Log'daki ilk `ERROR` satırıyla birlikte sor. Düzeltilmiş sürüm hazır olunca 6. adımdan devam et. A seçeneğinde 4. adımdaki kontrol sorgularını da tekrar çalıştır.

---

## Sonraki deploy (V8+V9)

Canlı V7'de. Bu sürüm iki migration ekler: V8 (`users` tablosuna `last_seen_at`, `last_app_version`, `last_device` kolonları) ve V9 (`admin_notifications`, `admin_notification_reads` tabloları). İkisi de yalnızca ekler, mevcut veriye dokunmaz; gerçek bir V7 şemasında uygulandığı Postgres testiyle doğrulandı. Render'da Auto-Deploy kapalı olduğu için deploy elle yapılır.

1. **Yedek:** Neon → Branches → `production` → **Create branch** (ör. `yedek-v7-oncesi`, bugünün tarihiyle). Dalın oluştuğunu görmeden devam etme.
2. **PR'ı merge et**, sonra Render → `bilpark-API` → **Manual Deploy → Deploy latest commit**.
3. **Logs'ta beklenen satırlar:**
   - `Migrating schema "public" to version "8 - developer monitoring"`
   - `Migrating schema "public" to version "9 - admin notifications"`
   - `Successfully applied 2 migrations to schema "public", now at version v9`
   - `Started BackendApplication`

   `Default users … created.` satırı **çıkmaz** (kullanıcılar zaten var), bu normaldir. `DEVELOPER_PASSWORD` girilmişse ve henüz geliştirici hesabı yoksa `Developer account 'gelistirici' created.` görünür.
4. **Kontrol:** `API/api/health` → `UP`. Neon SQL Editor'da: `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;` son iki satır `8` ve `9`, ikisi de `success = true`. Yönetici panelinde zil simgesi açılıyor olmalı.
5. **Geri dönüş:** V8 ve V9 yalnızca ekler, ama **kodu eski sürüme döndürmek tek başına yetmez**: eski sürüm yeni enum değerlerini okuyamaz (`sync_events.type = NOTIFICATION_CREATED`; `audit_logs.action = DEV_AUDIT_VIEWED`, `DEV_ANNOUNCEMENT_CREATED`). Bu satırlar varken SSE yeniden oynatma ve yönetici denetim ekranı `500` verir.
   - **Tercih edilen yol:** veriyi geri al. Neon'da 1. adımdaki yedek dala dön (yedek dalı `DB_URL` ile bağla) ya da `production` dalını deploy öncesi bir zamana **Restore** et (son 6 saat). Önce Render'ı **Suspend** et. Deploy sonrası girilen kayıtlar kaybolur, bunu bilerek seç.
   - **Kodu eski sürüme döndürmek şart olursa**, eski sürümü açmadan önce Neon SQL Editor'da şu temizliği çalıştır. Yalnızca yeni değerlerin satırlarını siler, V8/V9 tablolarına ve kolonlarına dokunmaz (eski kod fazla tabloyu ve kolonu yok sayar):

     ```sql
     DELETE FROM sync_events WHERE type = 'NOTIFICATION_CREATED';
     DELETE FROM audit_logs  WHERE action IN ('DEV_AUDIT_VIEWED', 'DEV_ANNOUNCEMENT_CREATED');
     ```

     Silinen denetim satırları geliştiricinin denetim görüntüleme ve duyuru kayıtlarıdır; operasyonel kayıtlara dokunmaz. Sonra Render → **Events** → önceki başarılı deploy → **Rollback**. Yeni sürüme tekrar çıkınca V8/V9 zaten uygulanmış olduğundan Flyway bir şey yapmaz.
   - Bozulma olursa dur ve sor; `flyway_schema_history` ve tablolar elle silinmez (bkz. 10. adım).

---

## 11. Sonraki günler

- [ ] Saha uygulamasında giriş, çıkış, kaçak ve abonelik akışları gerçek kullanımda sorunsuz.
- [ ] `GET /api/admin/audit` çağrısında günün kayıtları var.
- [ ] Neon → Usage: compute saatleri makul (günde birkaç saat).
- [ ] Her şey yolundaysa `yedek-2026-11-14-mart` dalını birkaç gün sonra sil. Free planda dal sayısı sınırlı.

---

## Teslim öncesi

Proje onaylandıktan sonra, müşteriye teslimden önce tanıtım kayıtları tek seferlik bir SQL betiğiyle silinir ve sistem boş teslim edilir. Adımlar, yedek alma, admin şifresinin yenilenmesi ve geri dönüş için [teslim-sifirlamasi.md](teslim-sifirlamasi.md) dosyasına bak. Betik: `scripts/teslim-sifirlama.sql`. Bunu canlıya geçiş günü çalıştırma; yalnızca onaydan sonra, bir kez.
