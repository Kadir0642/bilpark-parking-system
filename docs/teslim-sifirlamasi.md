# Teslim sıfırlaması

Tanıtım canlı sistemde yapıldı. Proje onaylanınca tanıtımda girilen kayıtlar silinir ve sistem müşteriye boş teslim edilir. Bu işlem bir panel düğmesi ya da API uç noktası **değildir** (sözleşmede "gizli silme uç noktası yok" sözü var). Operatörün elle ve bilerek çalıştırdığı bir SQL betiğidir: `scripts/teslim-sifirlama.sql`.

## Ne zaman çalıştırılır

- Yalnızca **proje onaylandıktan sonra**, **müşteriye teslimden önce**, **bir kez**.
- Onaydan önce çalıştırma. Geri alınamayan tek yol yedek daldır (Adım 1).
- Teslimden sonra canlıda **asla** çalıştırma: gerçek müşteri kayıtlarını siler.

## Ne silinir, ne korunur

| Silinir | Korunur |
|---|---|
| `parking_records` (araç kayıtları) | `zones`, `streets` (sahalar ve caddeler) |
| `active_park_spots` (aktif park yerleri) | `tariffs`, `tariff_vehicle_prices` (tarifler ve fiyatlar) |
| `check_in_requests` | `vehicle_types` (araç tipleri) |
| `shifts` (mesailer) | `app_settings` (ayarlar, kira dahil) |
| `subscriptions` (abonelikler) | `license_info` (lisans) |
| `sync_events` | `fiscal_years` (mali yıl tanımları) |
| `error_logs` | `flyway_schema_history` |
| `admin_notifications`, `admin_notification_reads` (bildirim kutusu) | `app_settings` içindeki `app.last-version` (son sürüm) da korunur: teslimden sonra gereksiz bir "Sistem güncellendi" bildirimi çıkmaz |
| `audit_logs` (yerine tek satır eklenir) | `users`: `admin` ve `DEVELOPER` hesabı |
| `users`: tüm OFFICER'lar ve `admin` dışındaki ADMIN'ler | |

Ayrıntılar ve gerekçeler:

- **Mali yıllar:** `fiscal_years` yalnızca yıl tanımıdır (tarih aralığı, etiket, aktif/arşiv bayrağı). Hiçbir kayıt tablosu ona bağlı değildir ve kira ayarları `app_settings` içinde yıl anahtarıyla durur. Silmek kira hesabını bozar, korumak zarar vermez. Tanıtımda sırf deneme için açılmış bir mali yıl varsa teslimden sonra panelden elle düzenle.
- **Tarifler:** Korunur. Silinen bir kullanıcının açtığı tarifin `created_by_id` alanı boşaltılır (`created_by_username` yazılı kalır), yoksa silme yabancı anahtara takılır.
- **Denetim kaydı:** Tamamı silinir, yerine tek satır eklenir: eylem `SETTINGS_UPDATED`, aktör `system`, özet "Teslim sıfırlaması yapıldı…". Yeni bir eylem türü eklenmedi.
- **Sayaçlar:** Boşaltılan tabloların `id` sayaçları 1'e döner. `sync_events` sayacı **sıfırlanmaz**: cihazlar `?since=<eski id>` ile sorar, id geri giderse yeni olayları kaçırırlar.

## Admin şifresi: önemli

Uygulama açılışta kullanıcıları yalnızca `users` tablosu **tamamen boşken** `ADMIN_PASSWORD` / `OFFICER_PASSWORD` değerlerinden oluşturur (`DataInitializer`). Betik `admin` ve `DEVELOPER` hesaplarını koruduğu için tablo boş değildir. Yani:

- Render'da `ADMIN_PASSWORD` değerini değiştirip yeniden başlatmak şifreyi **değiştirmez**.
- Örnek memur `tevfik` silinir ve **yeniden oluşmaz**. Memurları teslimden sonra yönetim panelinden açarsın.

Admin şifresini yenilemenin iki güvenli yolu var:

**Yol A (önerilen): geliştirici hesabıyla, betikten sonra.** `POST /api/developer/admin-password-reset` yalnızca ADMIN hesapları için çalışır, denetim kaydına `DEV_ADMIN_PASSWORD_RESET` yazar ve eski oturumları kapatır. Bu, betiğin yazdığı tek denetim satırının yanına ikinci bir satır ekler; bu beklenen bir durumdur.

```powershell
$api = "https://bilpark-api-rtdl.onrender.com"
$cred = Get-Credential -UserName gelistirici -Message "Geliştirici şifresi"
$login = Invoke-RestMethod -Method Post -Uri "$api/api/auth/login" -ContentType "application/json" `
  -Body (@{ username = $cred.UserName; password = $cred.GetNetworkCredential().Password } | ConvertTo-Json)
$new = Read-Host "Yeni admin şifresi" -AsSecureString
$newPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($new))
Invoke-RestMethod -Method Post -Uri "$api/api/developer/admin-password-reset" -ContentType "application/json" `
  -Headers @{ Authorization = "Bearer $($login.token)" } `
  -Body (@{ username = "admin"; newPassword = $newPlain } | ConvertTo-Json)
Remove-Variable newPlain, new, cred, login
```

(Geliştirici kullanıcı adı varsayılan `gelistirici`; farklıysa onu yaz.)

**Yol B (isteğe bağlı): betiğe BCrypt hash vermek.** Betiği çalıştırmadan önce aynı oturumda `SET bilpark.yeni_admin_hash = '<BCrypt hash>';` ver. Betik `admin` şifre hash'ini bu değerle değiştirir ve `password_changed_at` alanını günceller (eski tokenlar geçersiz olur). Düz şifre betiğe ya da komut geçmişine yazılmaz, yalnızca hash. Hash'i güvenli bir yerde üret (ör. yerelde `BCryptPasswordEncoder`). Hash formatı `$2a$`, `$2b$` ya da `$2y$` ile başlamalı, yoksa betik durur.

Her iki yolda da yeni şifreyi müşteriye güvenli kanaldan ilet ve ilk girişte `POST /api/me/password` ile kendisinin değiştirmesini iste.

## Adımlar

### 1. Yedek: Neon'da dal al

- [ ] Neon → Branches → `main` → **Create branch**. Ad: `yedek-teslim-oncesi-YYYY-AA-GG`. Parent: `main`, "Current data".
- [ ] Dalın oluştuğunu ve saatini not al. Geri dönüşte gerekir.
- [ ] Render → `bilpark-API` → **Suspend** (ya da cihazların yazmadığından emin ol). Sıfırlama sırasında kayıt girilmemeli.

### 2. Betiği çalıştır

Bağlantı dizesini (`DB_URL`'in psql karşılığı) Neon → Connect ekranından al. Şifreyi komut satırına ya da repoya yazma.

**Seçenek 1: Neon SQL Editor.** `main` dalını seç (yedek dalı değil). Önce şu satırı çalıştır, sonra betiğin **tamamını** aynı oturumda çalıştır:

```sql
SET bilpark.teslim_onay = 'EVET';
```

Editör her çalıştırmada yeni oturum açıyorsa onay satırı kaybolur ve betik "onaylanmadı" hatasıyla durur; bu güvenli taraftır. Bu durumda onay satırını betiğin en üstüne yapıştırıp hepsini tek seferde çalıştır.

**Seçenek 2: psql** (aynı oturum, ayrı `-c` ile):

```bash
psql "<neon-baglanti-dizesi>" -v ON_ERROR_STOP=1 \
  -c "SET bilpark.teslim_onay = 'EVET'" \
  -f scripts/teslim-sifirlama.sql
```

Yol B için `-c "SET bilpark.yeni_admin_hash = '<hash>'"` satırını ekle.

Betik tek transaction'dır: onay yoksa, `admin` ya da `DEVELOPER` hesabı yoksa ya da bir adım hata verirse hiçbir şey silinmez. Başarılıysa en sonda kalan satır sayıları tablosu çıkar.

### 3. Kontrol sorguları

```sql
-- Operasyonel tablolar 0 olmalı; audit_logs tam 1
SELECT 'parking_records' t, count(*) FROM parking_records
UNION ALL SELECT 'active_park_spots', count(*) FROM active_park_spots
UNION ALL SELECT 'subscriptions', count(*) FROM subscriptions
UNION ALL SELECT 'shifts', count(*) FROM shifts
UNION ALL SELECT 'sync_events', count(*) FROM sync_events
UNION ALL SELECT 'error_logs', count(*) FROM error_logs
UNION ALL SELECT 'admin_notifications', count(*) FROM admin_notifications
UNION ALL SELECT 'admin_notification_reads', count(*) FROM admin_notification_reads
UNION ALL SELECT 'audit_logs', count(*) FROM audit_logs;

-- Yalnızca admin ve geliştirici kalmalı
SELECT username, role, active FROM users ORDER BY username;

-- Korunanlar: 6 saha, tarifler, kira ayarı, lisans, mali yıllar
SELECT count(*) AS saha FROM zones;
SELECT id, status, note FROM tariffs ORDER BY id;
SELECT setting_key FROM app_settings ORDER BY 1;
SELECT licensee, expires_on, grace_days FROM license_info;
SELECT label, is_active, archived FROM fiscal_years ORDER BY start_date;

-- Tek denetim satırı
SELECT created_at, action, actor_username, summary FROM audit_logs;
```

### 4. Şifreyi yenile ve uygulamayı başlat

- [ ] Render → `bilpark-API` → **Resume**, açılışı bekle. Log'da `ERROR` olmamalı. Kullanıcılar mevcut olduğu için seed çalışmaz; bu normaldir.
- [ ] Admin şifresini yenile: yukarıdaki **Yol A** (ya da betiğe Yol B ile hash verdiysen bu adım gerekmez).
- [ ] Render → Environment: `ADMIN_PASSWORD` ve `OFFICER_PASSWORD` artık hiçbir şeyi etkilemez. Eski tanıtım şifrelerinin orada durmaması için değerleri yeni, rastgele değerlerle değiştir ya da sil (silersen ve `users` bir gün boşalırsa uygulama açılmayı reddeder; bu bir güvenlik önlemi). Değiştirdiysen Manual Deploy → **Restart** ile yeniden başlat.
- [ ] Yönetim panelinde yeni admin şifresiyle giriş yap. Eski şifre `401` vermeli.
- [ ] Memurları panelden oluştur, her birine saha ata (`tevfik` artık yok).
- [ ] Tanıtımdan kalan geliştirici şifresini değiştirmek istiyorsan `DEVELOPER_PASSWORD` yalnızca hesap yokken okunur; şifreyi `POST /api/me/password` ile değiştir.

### 5. Son kontrol

- [ ] Panelde araç listesi, mesai, abonelik ve rapor ekranları boş.
- [ ] `GET /api/admin/audit` yalnızca sıfırlama satırını (ve Yol A kullandıysan `DEV_ADMIN_PASSWORD_RESET` satırını) gösteriyor.
- [ ] Tarifler, sahalar ve kira ayarı yerinde.
- [ ] Yedek dalı en az birkaç hafta sakla, sonra sil (free planda dal sayısı sınırlı).

## Geri dönüş

Sıfırlamadan sonra bir şeyin yanlış silindiğini fark edersen:

1. Render → `bilpark-API` → **Suspend**.
2. **A seçeneği:** Neon → Branches → `main` → **Restore** → sıfırlamadan önceki saati (Adım 1'de not aldığın) seç.
3. **B seçeneği:** Neon → Branches → `yedek-teslim-oncesi-…` → **Connect** → host'u kopyala. Render'da `DB_URL` değerini `jdbc:postgresql://<yedek-host>/neondb?sslmode=require` yap. Yedek dal canlı veritabanı olur; silme.
4. Render → **Resume**, kontrol sorgularını (Adım 3) tekrar çalıştır.
5. Neyin yanlış olduğunu düzelt, ancak ondan sonra betiği yeniden çalıştır.

Betik tekrar çalıştırılabilir (zaten boş veritabanında yine tek denetim satırı bırakır), ama geri dönüş gereken durumda önce yedeğe dön.
