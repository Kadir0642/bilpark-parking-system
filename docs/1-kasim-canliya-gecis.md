# 1 Kasım 2026: canlıya geçiş kontrol listesi

Bu liste, Neon kotası 1 Kasım'da sıfırlandığında sistemi Aşama 2-5 ile canlıya almak için sırayla izlenecek adımlardır. Her adımın sonunda **Kontrol** satırı var; o kontrol tutmadan sonraki adıma geçme.

Gerekenler: Neon, Render, GitHub ve UptimeRobot hesaplarına giriş, bir tarayıcı ve PowerShell. Hiçbir adım ücretli değildir. Bir ekranda ücretli plan önerilirse kabul etme, önce sor.

Adresler:

- API: `https://bilpark-api-rtdl.onrender.com` (aşağıda `API` diye geçiyor)
- Swagger: `API/swagger-ui.html`

---

## 0. Başlamadan önce (5 dk)

- [ ] Bu listeyi ve bir not dosyasını aç. Yeni şifreleri bir parola yöneticisine kaydet; hiçbir şifreyi sohbete, commit'e ya da ekran görüntüsüne koyma.
- [ ] Neon'da kotanın sıfırlandığını doğrula: Neon → Project → **Usage**. Compute hours sıfırlanmış olmalı.
- [ ] Neon compute ayarının hâlâ **0.25 CU sabit** olduğunu ve **Scale to zero** açık olduğunu kontrol et.

**Kontrol:** Neon SQL Editor'da `SELECT 1;` çalışıyor.

---

## 1. Neon şifresini sıfırla

Eski `neondb_owner` şifresi public repo geçmişinde açıkta kaldı. Bu yüzden önce şifre değişir.

1. Neon → Project → **Roles** → `neondb_owner` → **Reset password**.
2. Yeni şifreyi parola yöneticisine kaydet.

**Kontrol:** Eski şifre artık çalışmamalı. Yeni bağlantı dizesi Neon'un **Connect** penceresinde görünüyor olmalı.

---

## 2. Render'da `DB_PASSWORD` değerini güncelle

1. Render → `bilpark-API` → **Environment**.
2. `DB_PASSWORD` değerini yeni şifreyle değiştir. **Save changes**'e bas ama henüz deploy etme. Render "Save and deploy" sorarsa **Save only** seç.
3. Aşağıdaki değişkenlerin dolu olduğunu kontrol et:
   - `DB_URL`: `jdbc:postgresql://<neon-host>/neondb?sslmode=require`. `postgresql://` değil, başında `jdbc:` olmalı.
   - `DB_USERNAME`: `neondb_owner`
   - `JWT_SECRET`: en az 32 bayt. Boşsa PowerShell'de üret:
     `[Convert]::ToBase64String((1..48 | % {Get-Random -Max 256}))`
   - `ADMIN_PASSWORD` ve `OFFICER_PASSWORD`: yalnızca kullanıcı tablosu boşsa kullanılır. Canlıda kullanıcılar var, yine de dolu kalabilir.
4. İsteğe bağlı: bakım hesabı için `DEVELOPER_PASSWORD` ekle. Bu, `gelistirici` adlı geliştirici hesabını bir kez oluşturur. Bu hesabın yetkileri `docs/sozlesme-eki-gelistirici-ve-lisans.md` belgesinde yazılıdır. Şifre en az 8 karakter olmalı ve harf ile rakam içermeli.
5. **Settings → Build & Deploy → Auto-Deploy** ayarını **Off** yap. Sonraki adımdaki merge'ler hemen deploy tetiklemesin; yedek ve kontrol sorgusu deploy'dan önce yapılmalı.

**Kontrol:** Environment sayfasında `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` ve `JWT_SECRET` var. Auto-Deploy kapalı.

---

## 3. Dalları sırayla `main`'e merge et

Dallar üst üste kurulu ve her biri bir öncekini içeriyor. GitHub'da her dal için `main`'e bir PR aç ve sırayla **Create a merge commit** ile merge et. Her PR yalnızca kendi farkını gösterir.

| Sıra | Dal | İçerik |
|---|---|---|
| 1 | `claude/dreamy-mccarthy-41ab1e` | Aşama 2: kilitli giriş/çıkış, Flyway V1+V2, vardiya, ödeme yöntemi |
| 2 | `claude/musing-mahavira-fd4b78` | UI yenileme (mobile/ ve web/) |
| 3 | `claude/lucid-roentgen-58dc28` | Aşama 3 backend: V3, sürümlü tarife, SSE, `/api/health` |
| 4 | `claude/wonderful-ellis-5ce2ee` | Aşama 3 istemci |
| 5 | `claude/funny-meninsky-fcc351` | Yerel demo (docker-compose) |
| 6 | `claude/frosty-morse-b8aa87` | Aşama 5 backend: V4, kullanıcı yönetimi, raporlar, denetim kaydı, lisans, şifre değiştirme |

- [ ] Her PR'da CI yeşil olmadan merge etme. Çakışma çıkarsa dur ve sor.
- [ ] PR #2 (`claude/app-screens`, yalnızca ekran görüntüleri) 2. sıradan sonra merge edilebilir. Bekletilmesi de sorun olmaz.

**Kontrol:** `main` en son 6. dalın commit'ini içeriyor. Auto-Deploy kapalı olduğu için Render'da yeni deploy başlamadı.

---

## 4. Aşama 2 öncesi yedek ve çakışma kontrol sorgusu

Deploy açılışta Flyway V2, V3 ve V4'ü canlı veritabanına uygular. Önce veritabanının yedeğini al.

1. **Neon yedeği:** Neon → **Branches** → **Create branch**. Ad: `yedek-2026-11-01-asama2-oncesi`, kaynak: `main`, **Current data**. Ücretsiz plan bunu destekler. Bu dal, deploy bozulursa dönülecek kopyadır. Compute'u kullanılmadığında uyusun.
2. **Git yedeği (isteğe bağlı):** merge öncesi `main`'i işaretlemek için GitHub'da `main`'in 3. adımdan önceki commit'inden (`0c4900d`) `yedek/main-2026-11-01` dalı oluştur.
3. **Çakışma kontrol sorgusu:** Neon SQL Editor'da, ana (`main`) Neon dalında çalıştır:

   ```sql
   -- Normalize edilince aynı olan aktif plakalar (V2 bunları bulursa durur ve hiçbir şey değiştirmez)
   SELECT upper(regexp_replace(current_plate, '[\s-]', '', 'g')) AS plaka,
          count(*)                                               AS adet,
          string_agg(id::text, ', ' ORDER BY id)                  AS idler
     FROM active_park_spots
    GROUP BY 1
   HAVING count(*) > 1;
   ```

   - **0 satır:** devam et.
   - **Satır varsa:** her plaka için yanlış olan aktif kaydı belirle. Hangisinin doğru olduğundan emin değilsen dur ve sor. Emin olduğunda yanlış kaydı sil: `DELETE FROM active_park_spots WHERE id = <id>;`. Sonra sorguyu tekrar çalıştır.
4. Bilgi amaçlı, plaka başına birden fazla aktif abonelik var mı diye bak. V2 bunları kendisi düzeltir: en geç biteni tutar, diğerlerini `CANCELLED` yapar.

   ```sql
   SELECT upper(regexp_replace(license_plate, '[\s-]', '', 'g')) AS plaka, count(*)
     FROM subscriptions WHERE status = 'ACTIVE' GROUP BY 1 HAVING count(*) > 1;
   ```

**Kontrol:** Yedek dal Neon'da görünüyor. Çakışma sorgusu 0 satır döndürüyor.

---

## 5. Deploy

1. Render → `bilpark-API` → **Manual Deploy → Deploy latest commit**.
2. **Logs** sekmesini izle. Beklenen satırlar:
   - `Migrating schema "public" to version "2 - stage2 multi device"`, ardından `3 - stage3 ...` ve `4 - stage5 admin audit license`
   - `Started BackendApplication`
3. Deploy başarılıysa Auto-Deploy'u tekrar **On** yap. İstersen kapalı da bırakabilirsin; o zaman her sürümü elle deploy edersin.

Hata olursa:

- `V2 durduruldu: ... aktif plakalar var`: 4. adımdaki sorguya dön. V2 tek transaction'dır, veritabanında hiçbir şey değişmemiştir.
- Bağlantı hatası (`password authentication failed`): `DB_PASSWORD` veya `DB_URL` yanlıştır. 2. adıma dön.
- `JWT_SECRET is missing or too short`: 2. adımdaki üretme komutunu kullan.
- Başka bir hata: logun ilk `ERROR` satırını kaydet, dur ve sor. Gerekirse Neon'da `yedek-...` dalından geri dönülür.

**Kontrol (tarayıcıdan):**

- `API/api/health` → `{"status":"UP", ...}`
- `API/api/tariff/current` → tarife JSON'u
- `API/api/streets` → 3 cadde

---

## 6. Admin şifresini yeni uç noktayla değiştir

Yeni uç nokta: `POST /api/me/password`. Mevcut şifre doğrulanır. Yeni şifre en az 8 karakter olmalı, harf ve rakam içermeli, kullanıcı adını içermemeli. İşlem denetim kaydına düşer. Eski oturumlar (token'lar) geçersiz olur ve yanıtta yeni bir token gelir.

PowerShell:

```powershell
$api  = "https://bilpark-api-rtdl.onrender.com"
$cred = Get-Credential -UserName admin -Message "Mevcut admin şifresi"
$login = Invoke-RestMethod -Method Post -Uri "$api/api/auth/login" -ContentType "application/json" `
  -Body (@{ username = "admin"; password = $cred.GetNetworkCredential().Password } | ConvertTo-Json)
$new = Read-Host "Yeni admin şifresi" -AsSecureString
$newPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($new))
Invoke-RestMethod -Method Post -Uri "$api/api/me/password" -ContentType "application/json" `
  -Headers @{ Authorization = "Bearer $($login.token)" } `
  -Body (@{ currentPassword = $cred.GetNetworkCredential().Password; newPassword = $newPlain } | ConvertTo-Json)
Remove-Variable newPlain, cred, login
```

Beklenen yanıt: `{"message":"Şifre değiştirildi.","token":"..."}`.

Alternatif: `API/swagger-ui.html` → **Authorize** alanına önce `/api/auth/login` ile alınan token'ı yaz, sonra `POST /api/me/password` uç noktasını kullan.

- [ ] Yeni şifreyi parola yöneticisine kaydet.
- [ ] Yönetim panelinden yeni şifreyle giriş yap.
- [ ] Saha personeli (`tevfik` vb.) şifreleri zayıfsa: panel ekranı gelene kadar `POST /api/admin/users/{id}/reset-password` uç noktasıyla değiştirilebilir (bkz. `docs/api-asama5.md`).

**Kontrol:** Eski şifreyle giriş `401` dönüyor, yenisiyle giriş yapılıyor. Admin token'ıyla `GET /api/admin/audit?action=PASSWORD_CHANGED` çağrısında kayıt görünüyor.

---

## 7. Lisans tarihini kontrol et

V4 lisans bitişini deploy gününden 1 yıl sonraya, ek süreyi 15 güne ayarlar.

- [ ] Admin token'ıyla `GET /api/license/status` çağır. `state` alanı `ACTIVE` olmalı.
- [ ] Sözleşmedeki tarihler farklıysa `gelistirici` hesabıyla `PUT /api/developer/license` uç noktasını kullan. Gövde: `{"expiresOn":"2027-11-01","graceDays":15}`. Bu işlem denetim kaydına düşer.

---

## 8. UptimeRobot'u `/api/health`'e çevir ve bakım penceresini tanımla

Eski monitör `/api/parking/spots` adresine gidiyordu. O adres kimlik istediği için 401 alıyor ve Neon'u uyanık tutuyordu. Yeni adres veritabanına hiç dokunmaz.

1. UptimeRobot → monitörü düzenle (yoksa **Add New Monitor**):
   - Monitor type: **HTTP(s)**
   - URL: `https://bilpark-api-rtdl.onrender.com/api/health`
   - Method: **HEAD**. Seçenek yoksa varsayılan GET kalabilir.
   - Interval: 5 dakika
2. **Maintenance window** ekle: her gün **22:00-07:45**, saat dilimi **Europe/Istanbul**. Gece istek gelmeyince Render uykuya geçer, Neon da sıfıra iner. Sabah 07:45'te ilk ping, 08:00 mesaisinden önce sistemi uyandırır.
   - Bakım penceresi ücretsiz planda yoksa ücretli plana geçme. Bunun yerine bana sor; seçenekler: monitörü gece elle durdurmak veya ücretsiz bir cron ile sabah 07:45'te tek istek atmak.
3. Monitörü **Resume** et (23 Ekim'de durdurulmuştu).

**Kontrol:** Monitör "Up" görünüyor. Ertesi gün Neon → Usage'da gece saatlerinde compute kullanımı yok.

---

## 9. Son kontroller

- [ ] Saha uygulamasıyla (canlı API'ye bağlı sürüm) giriş, araç girişi, çıkış.
- [ ] Panelde ciro ve son kayıtlar görünüyor.
- [ ] `GET /api/admin/audit` çağrısında bugünkü giriş/çıkış kayıtları var.
- [ ] Neon → Usage: bir gün sonra compute saatleri makul (günde birkaç saat).
- [ ] Neon'daki `yedek-...` dalı birkaç gün sonra, her şey yolundaysa silinebilir. Free planda dal sayısı sınırlıdır.
