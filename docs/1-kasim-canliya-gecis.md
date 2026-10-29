# 1 Kasım 2026: canlıya geçiş kontrol listesi

Bu liste, Neon kotası 1 Kasım'da sıfırlandığında yeni sürümü (Aşama 1-5, araç tipleri, saha kolaylıkları, çevrimdışı giriş saati) tek PR ile `main`'e alıp canlıya çıkarmak için adım adım izlenir. Her adımın sonunda bir **Kontrol** satırı var. O kontrol tutmadan sonraki adıma geçme.

Gerekenler: Neon, Render, GitHub ve UptimeRobot hesaplarına giriş, bir tarayıcı, PowerShell, canlı API'ye bağlı saha uygulaması yüklü bir telefon ve QR okutmak için ikinci bir telefon. Hiçbir adım ücretli değildir. Bir ekran ücretli plan önerirse kabul etme, önce sor.

Adresler:

- API: `https://bilpark-api-rtdl.onrender.com` (aşağıda `API` diye geçiyor)
- Swagger: `API/swagger-ui.html`
- Vatandaş sayfası (QR): `https://kadir0642.github.io/bilpark-parking-system/web/customer-payment.html`

---

## Neden bu sıra? (29 Ekim provası)

Canlı Neon veritabanında hâlâ Mart 2026 uygulamasının (commit `6abc999`) Hibernate ile kurduğu şema duruyor. Bu şemada yalnızca iki tablo var: `active_park_spots` ve `parking_records`. `users`, `zones`, `subscriptions` ve `fiscal_years` tabloları yok. `version`, `zone_id` ve `debt_paid` kolonları da yok. Enum'lar sayı olarak değil, ad olarak (`PAID`, `SMALL` gibi) saklanıyor.

Prova, bu şemanın ayrı bir Docker Postgres'te birebir kopyası üzerinde yapıldı. Kopya, Mart kodunun kendisiyle kuruldu ve veriler Mart API'si üzerinden girildi. Sonuçlar:

- **Eski ayarla düşüyordu.** `baseline-version=1` olduğu için Flyway V1'i atlıyordu. V2, `relation "subscriptions" does not exist` hatasıyla düştü. Uygulama açılmadı.
- **Düzeltme.** Ayar artık `baseline-version=0`. V1 yalnızca eksik tabloları kuruyor (`CREATE TABLE IF NOT EXISTS`). V2-V6 olduğu gibi çalışıyor. Yeni V7 eksik kolonları ekliyor:
  - `version`: eski araçlarda 0.
  - `zone_id`: eski kayıtlarda boş kalıyor. Uygulama bu kayıtlarda cadde üzerinden yetki kontrolü yapıyor.
  - `debt_paid`: `RUNAWAY` kayıtlarda `false` (borç ödenmedi), diğer kayıtlarda `true`.
  - Mart'tan kalan cadde ve durum CHECK kısıtları kaldırılıyor.
- **Sonuç.** Yükseltilen şema, boş bir veritabanından kurulan şemayla birebir aynı. Hiçbir satır kaybolmadı. Eski kaçak borcu, araç tekrar girdiğinde görünüyor. Testler: `MarchLiveSchemaUpgradePostgresTest` ve `MarchLiveDatabaseAppPostgresTest`.

> **Önemli:** 2. adımda Neon şifresi değiştiği anda şu an canlıda çalışan eski uygulama veritabanına bağlanamaz. Mart kodunda şifre koda gömülü. Site, 6. adımdaki deploy bitene kadar kapalı kalır. Bu yüzden 2-6. adımları **tek oturumda** yap: gece 22:00'den sonra, yaklaşık 1 saat ayırarak. Eski sürüme geri dönmek mümkün değil, geri dönüş planı 10. adımda.

---

## 0. Başlamadan önce (gün içinde, 10 dk)

- [ ] Yeni şifreleri bir parola yöneticisine kaydedeceksin. Hiçbir şifreyi sohbete, commit'e ya da ekran görüntüsüne koyma.
- [ ] Neon → Project → **Usage**: Compute hours sıfırlanmış olmalı.
- [ ] Neon compute ayarı hâlâ **0.25 CU sabit** ve **Scale to zero** açık olmalı.
- [ ] GitHub'da tek PR açılmış ve CI yeşil olmalı (5. adım). PR'ı önceden açmak serbest, merge etme.
- [ ] Saha ekibine haber ver: geçiş gecesi 22:00'den sabaha kadar sistem kapalı olabilir.

**Kontrol:** Neon SQL Editor'da `SELECT 1;` çalışıyor. PR'da CI yeşil.

---

## 1. Neon'da yedek dal oluştur

Her şeyden önce verinin bugünkü hâlini dondur.

1. **Saati not al** (ör. `22:05`). 10. adımda zamana göre geri yükleme için gerekebilir.
2. Neon → **Branches** → **Create branch**.
   - Ad: `yedek-2026-11-01-mart`
   - Kaynak: `main`, **Current data** (ya da "Head")
3. Dal oluşunca SQL Editor'ın üstünden bu dalı seç ve çalıştır:

   ```sql
   SELECT (SELECT count(*) FROM active_park_spots) AS aktif,
          (SELECT count(*) FROM parking_records)   AS kayit;
   ```

4. Aynı sorguyu `main` dalında da çalıştır.

**Kontrol:** İki dal aynı sayıları veriyor. Yedek dal Branches listesinde görünüyor.

---

## 2. Neon şifresini sıfırla

Eski `neondb_owner` şifresi, public repo geçmişindeki Mart commit'lerinde açıkta. Bu yüzden şifre değişmek zorunda.

1. Neon → **Roles** (dal: `main`) → `neondb_owner` → **Reset password**. Yeni şifreyi parola yöneticisine kaydet.
2. Rol şifreleri dal başınadır. Yedek dal eski şifreyle oluştu. Aynı işlemi `yedek-2026-11-01-mart` dalında da yap. Aynı yeni şifreyi kullanabilirsin.

Bu andan sonra eski canlı uygulama veritabanına bağlanamaz. Bu beklenen bir durum.

**Kontrol:** Neon'un **Connect** penceresinde yeni bağlantı bilgisi görünüyor.

---

## 3. Render'da ortam değişkenlerini ayarla

1. Render → `bilpark-API` → **Environment**.
2. `DB_PASSWORD` değerini yeni şifreyle değiştir. **Save changes**. Render "Save and deploy" sorarsa **Save only** seç.
3. Aşağıdakilerin dolu olduğunu kontrol et:

   | Değişken | Değer |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://<neon-host>/neondb?sslmode=require` (başında `jdbc:` olmalı) |
   | `DB_USERNAME` | `neondb_owner` |
   | `JWT_SECRET` | En az 32 bayt. Boşsa PowerShell'de üret: `[Convert]::ToBase64String((1..48 \| % {Get-Random -Max 256}))` |
   | `ADMIN_PASSWORD` | **Zorunlu.** Canlıda `users` tablosu yok. İlk açılışta `admin` kullanıcısı bu şifreyle oluşur. |
   | `OFFICER_PASSWORD` | **Zorunlu.** İlk açılışta örnek memur `tevfik` (Tevfik Bey, Bölüm 1) bu şifreyle oluşur. |
   | `DEVELOPER_PASSWORD` | İsteğe bağlı. `gelistirici` bakım hesabını bir kez oluşturur (bkz. `docs/sozlesme-eki-gelistirici-ve-lisans.md`). |

   Bu iki şifre de en az 8 karakter olmalı ve harf ile rakam içermeli. 7. adımda değiştirilecekler.
4. **Settings → Build & Deploy → Auto-Deploy** ayarını **Off** yap. Böylece merge hemen deploy tetiklemez.

**Kontrol:** Environment sayfasında `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD` ve `OFFICER_PASSWORD` var. Auto-Deploy kapalı.

---

## 4. Kontrol sorguları ve deneme kayıtlarının silinmesi

Neon SQL Editor'da, **`main` dalında** çalıştır. 4.1-4.5 salt okunurdur, veriyi değiştirmez. Sonuçların ekran görüntüsünü al; içlerinde şifre yok. Veriyi silen tek adım 4.6'dır.

### 4.1 Hangi tablolar var?

```sql
SELECT table_name FROM information_schema.tables
 WHERE table_schema = 'public' ORDER BY 1;
```

| Sonuç | Ne yapmalı |
|---|---|
| Yalnızca `active_park_spots` ve `parking_records` | Beklenen durum (Mart şeması). Devam et. |
| Bunlara ek olarak `users`, `zones`, `subscriptions`, `fiscal_years` | Aşama 1 şeması. Bu da destekleniyor (`FlywayBaselinePostgresTest`). Devam et, sonucu not al. |
| `flyway_schema_history` var | **Dur ve sor.** Daha önce yarım kalmış bir deneme olmuş demektir. Bu durumda yeni ayar eksik tabloları kuramaz. |
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

- Değerler yalnızca `ACTIVE`, `PAID`, `RUNAWAY`, `SMALL`, `LARGE`, `TEVFIK_BEY`, `ALI_RIZA_OZKAY`, `CUMHURIYET` ise (ya da boşsa) devam et.
- `0`, `1`, `2` gibi **sayılar** görürsen **dur ve sor.** Mart kodunda enum'lar ad olarak saklanıyordu, sayı beklenmiyor. Sayı varsa V5 bunları `SMALL` sayar ve kamyonet bilgisi kaybolur.

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

- **0 satır:** devam et.
- **Satır varsa:** dur ve sor. Hangi kaydın yanlış olduğuna birlikte karar verelim. Kendi başına silme.

### 4.4 Karşılaştırma için anlık görüntü

Deploy'dan sonra aynı sorguyu çalıştırıp sayıları karşılaştıracaksın.

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

### 4.5 Hâlâ "içeride" görünen araçlar

```sql
SELECT id, current_plate, street, current_type, entry_time
  FROM active_park_spots ORDER BY entry_time;
```

Bilgi amaçlı, satır sayısını not al. Mart'ta bölge kavramı yoktu, bu yüzden bu araçlar yeni sürümde bölgesiz görünür:

- Saha uygulaması yalnızca memurun kendi bölgesindeki araçları listeler. Bu yüzden memurlar bu araçları **göremez**.
- Aynı plaka tekrar girilmek istenirse "zaten içeride" hatası alınır.

4.6'da deneme kayıtları silineceği için bu araçlar da gider.

### 4.6 Deneme kayıtlarını sil (karar: 29 Ekim)

Canlıdaki kayıtların hepsi geliştirme sırasında yapılan denemeler. Gerçek müşteri kaydı yok. Bu yüzden yeni sürüme temiz başlanır. Denemelerin ücretleri ciro raporlarına karışmaz. Eski bölgesiz araç sorunu (7.4) da ortadan kalkar.

Önce 4.4'teki ekran görüntüsüne bak. Tarihler ve plakalar denemelere benzemiyorsa, yani gerçek bir kayıt olabilecek bir şey görürsen, silme ve sor. Silinen her şey 1. adımdaki yedek dalda durur.

```sql
DELETE FROM active_park_spots;
DELETE FROM parking_records;
SELECT (SELECT count(*) FROM active_park_spots) AS aktif,
       (SELECT count(*) FROM parking_records)   AS kayit;
```

Son sorgu `0 | 0` döndürmeli. Tablolar silinmez, yalnızca içleri boşalır. Migration'lar boş tablolarda da aynı şekilde çalışır.

**Kontrol:** 4.1 beklenen sonucu verdi, 4.2'de sayı yok, 4.3 boş döndü, 4.4'ün ekran görüntüsü alındı, 4.6 sonrası iki tablo da boş.

---

## 5. Tek PR'ı `main`'e merge et

Zincirin tamamı tek dalda: `claude/canli-gecis-provasi`. Bu dalda Aşama 2-5, araç tipleri, e2e düzeltmeleri, saha kolaylıkları, çevrimdışı giriş saati ve bu prova var. PR metninin taslağı `docs/pr-main-tek-pr-taslagi.md` dosyasında.

1. GitHub → **Pull requests** → `claude/canli-gecis-provasi` → `main` PR'ı (yoksa **New pull request** ile aç ve taslağı yapıştır).
2. CI (Java CI with Maven, Flutter CI) yeşil değilse merge etme.
3. **Create a merge commit** ile merge et. Squash yapma; dal geçmişi korunsun.
4. GitHub Pages `main`'den yayın yapıyor. Vatandaş sayfası birkaç dakika içinde güncellenir (Actions → "pages build and deployment").

**Kontrol:** `main` en son merge commit'ini gösteriyor. Auto-Deploy kapalı olduğu için Render'da yeni deploy başlamadı.

---

## 6. Deploy

1. Render → `bilpark-API` → **Manual Deploy → Deploy latest commit**.
2. **Logs** sekmesinde şu satırları bekle (sırayla):
   - `Successfully baselined schema with version: 0`
   - `Migrating schema "public" to version "1 - baseline"`, sonra `2 - stage2 multi device` … `7 - legacy march schema`
   - `Successfully applied 7 migrations to schema "public", now at version v7`
   - `Default users (admin, tevfik) created.`
   - `Started BackendApplication`

Hata olursa:

| Log'daki hata | Anlamı | Ne yapmalı |
|---|---|---|
| `password authentication failed` | `DB_PASSWORD` veya `DB_URL` yanlış | 3. adıma dön, düzelt, tekrar deploy et. |
| `JWT_SECRET is missing or too short` | Secret eksik | 3. adımdaki komutla üret, tekrar deploy et. |
| `ADMIN_PASSWORD env var is required…` veya `OFFICER_PASSWORD…` | Migration'lar bitti ama kullanıcılar oluşamadı | Değişkeni ekle, tekrar deploy et. Flyway kaldığı yerden devam eder; migration'lar tekrar çalışmaz. |
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

4.4'teki anlık görüntü sorgusunu tekrar çalıştır. 4.6'yı yaptıysan **bütün sayılar 0** olmalı. Yapmadıysan sayılar 4.4'tekiyle aynı olmalı; plakalar boşluksuz görünür, bu beklenen bir değişiklik.

```sql
SELECT count(*) AS hatali FROM parking_records WHERE status = 'RUNAWAY' AND debt_paid;  -- 0 olmalı
SELECT username, role FROM users ORDER BY 1;  -- admin, tevfik (ve varsa gelistirici)
```

Her şey tutuyorsa Auto-Deploy'u tekrar **On** yapabilirsin. İstersen kapalı bırakıp her sürümü elle deploy edebilirsin.

---

## 7. İlk açılış: kullanıcılar ve admin şifresi

İlk açılışta `users` tablosu boş olduğu için iki kullanıcı oluştu:

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

### 7.4 Mart'tan kalan aktif araçları kapat

**4.6'yı yaptıysan bu adımı atla.** Yalnızca deneme kayıtları silinmeden geçildiyse ve 4.5'te satır çıktıysa gerekir. Komutlar araçları önce listeler, ardından **bugünden önce** girmiş olanları "hatalı giriş" olarak iptal eder. İptal edilen kayıt `CANCELLED` olarak kalır, ciroya ve borca girmez, denetim kaydına düşer. Bugün girmiş bölgesiz bir araç varsa dokunulmaz; onu sahaya sor.

Komutları PowerShell penceresine yapıştır. `.ps1` dosyası olarak kaydedeceksen UTF-8 **BOM'lu** kaydet; yoksa Türkçe gerekçe bozuk görünür.

```powershell
$api  = "https://bilpark-api-rtdl.onrender.com"
$cred = Get-Credential -UserName admin -Message "Yeni admin şifresi"
$login = Invoke-RestMethod -Method Post -Uri "$api/api/auth/login" -ContentType "application/json" `
  -Body (@{ username = "admin"; password = $cred.GetNetworkCredential().Password } | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($login.token)"; "X-Device-Id" = "gecis-2026-11-01" }
$spots = Invoke-RestMethod -Uri "$api/api/parking/spots" -Headers $h
$eski = @($spots | Where-Object { $null -eq $_.zone })
$eski | Select-Object id, currentPlate, street, entryTime | Format-Table -AutoSize
```

Liste 4.5'tekiyle aynıysa iptal et:

```powershell
$bugun = (Get-Date).Date
$body = [Text.Encoding]::UTF8.GetBytes((@{ reason = "Mart sürümünden kalan kapanmamış giriş (canlı geçiş)" } | ConvertTo-Json))
foreach ($s in $eski | Where-Object { [datetime]$_.entryTime -lt $bugun }) {
  $null = Invoke-RestMethod -Method Post -Headers $h -ContentType "application/json; charset=utf-8" -Body $body `
    -Uri "$api/api/parking/spots/cancel?plate=$([uri]::EscapeDataString($s.currentPlate))"
  "{0} iptal edildi" -f $s.currentPlate
}
Remove-Variable cred, login, h
```

(29 Ekim'de prova veritabanında denendi.)

**Kontrol:** Eski admin şifresiyle giriş `401` dönüyor, yenisiyle giriş yapılıyor. `GET /api/admin/audit?action=PASSWORD_CHANGED` kaydı gösteriyor. 4.5 sorgusu artık yalnızca bugünkü araçları döndürüyor (ya da boş).

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
6. Panelde ciro ve son kayıtlar görünüyor. 4.6'yı yaptıysan yalnızca bu geceki test kaydı var.

**Kontrol:** QR sayfası doğru plakayı ve tutarı gösterdi. Test girişi iptal edildi.

---

## 10. Geri dönüş planı

Bilinmesi gerekenler:

- **Eski uygulamaya dönüş yok.** Mart kodu eski (sızmış) şifreyi kullanıyor ve yeni şemayla da çalışmaz: `debt_paid` zorunlu kolonunu bilmiyor. "Geri dönüş", veriyi yedeğe döndürüp yeni sürümü düzeltmek ve tekrar deploy etmek demek. Bu sürede site kapalı kalır. Gece olduğu için saha etkilenmez.
- **Her migration kendi transaction'ında çalışır.** Düşen migration hiçbir iz bırakmaz, öncekiler kalır. Hata düzeltilip tekrar deploy edildiğinde Flyway kaldığı yerden devam eder. Hataların çoğunda veri geri yüklemeye gerek yoktur.
- **Hiçbir zaman** `flyway_schema_history` tablosunu elle silme, migration SQL'ini elle çalıştırma ve tablo silme (`DROP`). Önce sor.

Veriyi yedeğe döndür, eğer:

- 6. adımdaki anlık görüntü sayıları tutmuyorsa (kayıt eksik ya da tutar farklı),
- ya da bir migration yarıda kalıp beklenmedik bir durum bıraktıysa ve sorduktan sonra geri dönmeye karar verildiyse.

Nasıl:

1. Render → `bilpark-API` → **Suspend** (ya da Auto-Deploy kapalıyken yeni deploy yapma). Uygulama veritabanına yazmasın.
2. **A seçeneği (tercih):** Neon → Branches → `main` → **Restore** (zamana göre geri yükleme). Zaman olarak 1. adımda not aldığın saati seç. Ekrandaki adlar farklıysa ya da seçenek yoksa B'ye geç.
3. **B seçeneği:** Yedek dalı kullan. Neon → Branches → `yedek-2026-11-01-mart` → **Connect** → host'u kopyala. Render'da `DB_URL` değerini `jdbc:postgresql://<yedek-host>/neondb?sslmode=require` yap. Bu durumda yedek dal canlı veritabanı olur; silme.
4. Log'daki ilk `ERROR` satırıyla birlikte sor. Düzeltilmiş sürüm hazır olunca 6. adımdan devam et. A seçeneğinde 4. adımdaki kontrol sorgularını da tekrar çalıştır.

---

## 11. Sonraki günler

- [ ] Saha uygulamasında giriş, çıkış, kaçak ve abonelik akışları gerçek kullanımda sorunsuz.
- [ ] `GET /api/admin/audit` çağrısında günün kayıtları var.
- [ ] Neon → Usage: compute saatleri makul (günde birkaç saat).
- [ ] Her şey yolundaysa `yedek-2026-11-01-mart` dalını birkaç gün sonra sil. Free planda dal sayısı sınırlı.

---

## Teslim öncesi

Proje onaylandıktan sonra, müşteriye teslimden önce tanıtım kayıtları tek seferlik bir SQL betiğiyle silinir ve sistem boş teslim edilir. Adımlar, yedek alma, admin şifresinin yenilenmesi ve geri dönüş için [teslim-sifirlamasi.md](teslim-sifirlamasi.md) dosyasına bak. Betik: `scripts/teslim-sifirlama.sql`. Bunu canlıya geçiş günü çalıştırma; yalnızca onaydan sonra, bir kez.
