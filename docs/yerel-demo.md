# BilPark yerel demo: bilgisayarda backend, telefonda saha uygulaması

Bu rehber BilPark'ı canlı ortama (Render + Neon) hiç dokunmadan kendi bilgisayarında çalıştırmanı ve saha uygulamasını Android telefonuna kurmanı anlatır. Her şey Windows için yazıldı; Mac gerekmez.

Kurulum bittiğinde:

| Ne | Adres | Kim erişir |
| :--- | :--- | :--- |
| Backend (API) | `http://localhost:8081/api`, telefondan `http://<PC-IP>:8081/api` | Bilgisayar + aynı Wi-Fi'deki telefon |
| Yönetim paneli | http://localhost:5500/admin-panel.html | Bilgisayar |
| Vatandaş sayfası | http://localhost:5500/customer-payment.html | Bilgisayar (isteğe bağlı telefon) |
| PostgreSQL | `127.0.0.1:5433`, veritabanı `bilpark_demo` | Yalnızca bu bilgisayar |

Portlar bilerek 8080/5432 değil: makinedeki başka projeler (ör. vestify) o portları kullanıyor.

---

## 1. Önkoşullar

1. **Docker Desktop** (Windows, WSL 2 altyapısıyla). Kurduktan sonra açık ve sol altta "Engine running" yazıyor olmalı.
   Kontrol: `docker compose version`
2. **Flutter SDK** ve **Android SDK** (Android Studio ile gelir). Kontrol:
   ```bash
   flutter doctor
   ```
   "Android toolchain" satırı yeşil olmalı. Lisans uyarısı varsa: `flutter doctor --android-licenses`.
3. **Telefonda geliştirici seçenekleri ve USB hata ayıklama**:
   1. Ayarlar → Telefon hakkında → **Yapı numarası**'na 7 kez dokun ("Artık geliştiricisiniz" mesajı çıkar). Xiaomi'de "MIUI sürümü", Samsung'da Yazılım bilgileri altındadır.
   2. Ayarlar → Sistem → **Geliştirici seçenekleri** → **USB hata ayıklama**'yı aç.
   3. Xiaomi/Redmi/POCO: ayrıca **USB ile yükle** ve **USB hata ayıklama (Güvenlik ayarları)** seçeneklerini aç.
   4. Telefonu USB kablosuyla bağla. Telefonda çıkan "USB hata ayıklamaya izin verilsin mi?" sorusuna **Her zaman izin ver** de.
   5. Kontrol: `flutter devices` listesinde telefonun görünmeli.

---

## 2. Backend'i tek komutla başlat

Proje kök klasöründe (README'nin olduğu yer) PowerShell aç.

**2.1. Ortam dosyasını oluştur (bir kez):**

```bash
powershell -ExecutionPolicy Bypass -File demo/yeni-env.ps1
```

Bu komut `.env.demo` dosyasını rastgele ve güvenli değerlerle oluşturur (`JWT_SECRET`, veritabanı şifresi, admin ve görevli şifreleri) ve giriş bilgilerini ekrana yazar. Dosya git'e girmez. Elle doldurmak istersen `.env.demo.example` dosyasını `.env.demo` adıyla kopyala; güvenli bir `JWT_SECRET` için:

```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

**2.2. Başlat:**

```bash
docker compose up --build
```

İlk seferde Maven bağımlılıkları indirildiği için 3-6 dakika sürebilir; sonraki açılışlar birkaç saniyedir. Arka planda çalıştırmak için `-d` ekle (`docker compose up -d --build`). Hazır olduğunu şöyle anlarsın:

```bash
docker compose ps
```

`backend` satırında `(healthy)` görünmeli. Tarayıcıda http://localhost:8081/api/health açınca `{"status":"UP", ...}` gelmeli.

**2.3. (İsteğe bağlı) Örnek veri yükle:**

```bash
powershell -ExecutionPolicy Bypass -File demo/ornek-veri.ps1
```

Ekler: her bölgeye bir görevli (`tevfik2`, `tevfik3`, `ozkay1`, `ozkay2`, `cumhuriyet1`; şifreleri `tevfik` ile aynı), 6 bölgede 12 aktif araç, son 10 günün ödenmiş kayıtları (bugün dahil), bir kaçak (`11KCK47`, ödenmemiş borç), bir aktif abone (`11CC1923`), görevli vardiyaları. Tekrar çalıştırmak güvenlidir: önceki demo satırlarını silip yeniden ekler. SQL dosyası (`demo/ornek-veri.sql`) `bilpark_demo` dışındaki bir veritabanında çalışmayı reddeder; **canlıya asla uygulama**.

**2.4. Durdur / sıfırla:**

```bash
docker compose down
```

Veriler `bilpark_demo_pgdata` volume'unda kalır. Her şeyi silip baştan başlamak için (şifreleri değiştirdiysen de gerekir):

```bash
docker compose down -v
```

---

## 3. Yönetim paneli ve vatandaş sayfası

`docker compose up` aynı zamanda `web/` klasörünü küçük bir nginx sunucusuyla 5500 portunda yayınlar:

- Yönetim paneli: http://localhost:5500/admin-panel.html → kullanıcı `admin`, şifre `.env.demo` içindeki `ADMIN_PASSWORD`.
- Vatandaş sayfası: http://localhost:5500/customer-payment.html → park halindeki bir aracın güncel ücretini gösterir. Örnek veri yüklediysen `11DE4521` (büyük araç) veya abone `11CC1923` plakasını sorgula.

Kaçak kaydı (`11KCK47`) panelde "Açık kaçak borcu" kartında ve Kaçaklar sekmesinde görünür; telefonda bu plakayla yeni giriş yapınca birikmiş borç uyarısı çıkar.

`web/config.js` adresi kendisi seçer: sayfa `localhost`'tan açılınca `http://localhost:8081`'e, yerel ağ IP'sinden açılınca `http://<o IP>:8081`'e bağlanır. Başka bir backend denemek için adrese `?api=` ekle, ör. IDE'den 8080'de çalışan backend için `admin-panel.html?api=http://localhost:8080`.

Docker kullanmadan statik sunucu istersen (Python yüklüyse):

```bash
python -m http.server 5500 -d web
```

---

## 4. Telefonu bağla

### 4.1. Bilgisayarın yerel IP adresini bul

```bash
ipconfig
```

**Kablosuz LAN bağdaştırıcısı Wi-Fi** (İngilizce: *Wireless LAN adapter Wi-Fi*) başlığı altındaki **IPv4 Adresi** satırını al, ör. `192.168.1.34`. `vEthernet (WSL)` veya `172.x` ile başlayan Docker/WSL adreslerini kullanma. PowerShell'de kısa yol:

```powershell
Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias Wi-Fi | Select-Object IPAddress
```

Bu rehberin geri kalanında bu adrese `<PC-IP>` diyoruz.

### 4.2. Windows Güvenlik Duvarı'nda 8081 portuna izin ver

1. Wi-Fi ağının profili **Özel** olmalı: Ayarlar → Ağ ve İnternet → Wi-Fi → bağlı ağın özellikleri → Ağ profili türü → **Özel ağ**.
2. **Yönetici olarak** açılmış PowerShell'de (Başlat → "PowerShell" → sağ tık → Yönetici olarak çalıştır):

   ```powershell
   New-NetFirewallRule -DisplayName "BilPark demo 8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow -Profile Private
   ```

   Vatandaş sayfasını da telefondan açmak istersen aynı komutu `5500` için tekrarla. Demo bitince kuralı kaldırmak için:

   ```powershell
   Remove-NetFirewallRule -DisplayName "BilPark demo 8081"
   ```

### 4.3. Telefonu aynı Wi-Fi'ye bağla

Telefon, bilgisayarla **aynı Wi-Fi ağına** bağlı olmalı (mobil veri kapalı ya da önemsiz; misafir ağları cihazları birbirinden yalıttığı için çalışmaz). Kontrol: telefonun tarayıcısında `http://<PC-IP>:8081/api/health` aç. `{"status":"UP", ...}` geliyorsa ağ ve güvenlik duvarı tamam.

### 4.4. Uygulamayı telefona kur ve çalıştır

Telefon USB ile bağlıyken, `mobile` klasöründe:

```bash
cd mobile
flutter pub get
flutter run --dart-define=API_BASE=http://<PC-IP>:8081/api
```

Örnek: `flutter run --dart-define=API_BASE=http://192.168.1.34:8081/api`. Birden fazla cihaz bağlıysa `-d <cihaz-id>` ekle (`flutter devices` listeler). İlk derleme birkaç dakika sürer. Uygulama açık kaldığı sürece terminalde `r` ile hot reload yapabilirsin.

Not: `http://` adresine yalnızca **debug** derlemede izin var (`android/app/src/debug/AndroidManifest.xml`). Release APK bu demo ile çalışmaz.

### 4.5. Kalıcı kurulum: debug APK

USB kablosu çıkınca da uygulamanın telefonda kalması için debug APK üret:

```bash
cd mobile
flutter build apk --debug --dart-define=API_BASE=http://<PC-IP>:8081/api
```

Dosya: `mobile/build/app/outputs/flutter-apk/app-debug.apk`. Kurmak için ya telefon USB ile bağlıyken:

```bash
adb install -r mobile/build/app/outputs/flutter-apk/app-debug.apk
```

ya da APK'yı telefona kopyalayıp dosya yöneticisinden aç ("Bilinmeyen kaynaklardan yükleme" izni ister).

API adresi APK'nın içine gömülür: bilgisayarın IP'si değişirse (modem yeniden başlayınca olabilir) APK'yı yeni IP ile tekrar üret. Bunu önlemek için modem arayüzünden bilgisayara sabit IP (DHCP rezervasyonu) verebilirsin.

### 4.6. Wi-Fi olmadan: USB kablo ile (okul / ortak ağlar için)

Kampüs ağları (eduroam vb.) genellikle cihazların birbirini görmesini engeller; ortak ağda güvenlik duvarı açmak da önerilmez. Bunun yerine telefon backend'e USB kablosu üzerinden bağlanabilir. Telefonda internet gerekmez, ağ profili ve güvenlik duvarı kuralı da gerekmez.

1. APK'yı bir kez `127.0.0.1` adresiyle üret ve kur:

   ```bash
   cd mobile
   flutter build apk --debug --dart-define=API_BASE=http://127.0.0.1:8081/api
   ```

   ```bash
   adb install -r build/app/outputs/flutter-apk/app-debug.apk
   ```

2. Telefondaki 8081'i bilgisayarın 8081'ine yönlendir:

   ```bash
   adb reverse tcp:8081 tcp:8081
   ```

3. Telefonun tarayıcısında `http://127.0.0.1:8081/api/health` açılıyorsa hazır.

Kablo çıkarılınca veya telefon yeniden başlayınca yönlendirme silinir; kabloyu takıp yalnızca 2. adımı tekrarla. `adb` PATH'te değilse tam yolu: `%LOCALAPPDATA%\Android\Sdk\platform-toolsdb.exe`.

---

## 5. Demo kullanıcılarıyla giriş

Şifreler `.env.demo` dosyasında. Kullanıcılar, veritabanı ilk kez boşken oluşturulur; şifreyi sonradan `.env.demo`'da değiştirmek mevcut kullanıcıları değiştirmez (`docker compose down -v` ile sıfırla).

| Kullanıcı | Şifre | Nerede | Bölge |
| :--- | :--- | :--- | :--- |
| `admin` | `ADMIN_PASSWORD` | Yönetim paneli | Hepsi |
| `tevfik` | `OFFICER_PASSWORD` | Saha uygulaması | Tevfik Bey - Bölüm 1 |
| `tevfik2`, `tevfik3` | `OFFICER_PASSWORD` | Saha uygulaması | Tevfik Bey 2, 3 (örnek veriyle) |
| `ozkay1`, `ozkay2` | `OFFICER_PASSWORD` | Saha uygulaması | Ali Rıza Özkay 1, 2 (örnek veriyle) |
| `cumhuriyet1` | `OFFICER_PASSWORD` | Saha uygulaması | Cumhuriyet 1 (örnek veriyle) |

Kısa demo akışı: telefonda `tevfik` ile gir → yeni plaka ekle (ör. `11 ABC 45`) → panelde aktif araçlarda görün → telefondan nakit/POS ile çıkış yap → paneldeki ciro ve son kayıtlar güncellenir. Abone plakası `11CC1923` çıkışta ücretsiz kapanır.

---

## 6. Sık karşılaşılan hatalar

**Port çakışması** (`Bind for 0.0.0.0:8081 failed: port is already allocated` veya `ports are not available`)
Portu kimin kullandığını bul:
```bash
netstat -ano | findstr :8081
```
Son sütundaki PID'yi Görev Yöneticisi → Ayrıntılar'da ara. Kapatamıyorsan dosyayı değiştirmeden başka port seç (PowerShell):
```powershell
$env:DEMO_API_PORT=8082; $env:DEMO_DB_PORT=5434; $env:DEMO_WEB_PORT=5501; docker compose up -d
```
Bu durumda telefon için `API_BASE=http://<PC-IP>:8082/api`, panel için `admin-panel.html?api=http://localhost:8082` kullan ve güvenlik duvarı kuralını yeni porta aç. Web portunu değiştirdiysen panel adresi de `http://localhost:5501/...` olur.

**Telefon bağlanamıyor** (uygulamada zaman aşımı / "bağlantı kurulamadı")
1. Bilgisayarda http://localhost:8081/api/health açılıyor mu? Açılmıyorsa sorun backend'de: `docker compose logs backend`.
2. Telefonun tarayıcısında `http://<PC-IP>:8081/api/health` açılıyor mu? Açılmıyorsa:
   - Güvenlik duvarı kuralı eklendi mi ve Wi-Fi profili **Özel** mi? (Bölüm 4.2)
   - IP doğru mu? `vEthernet`/WSL adresi değil, Wi-Fi adaptörünün adresi olmalı.
   - Telefon misafir ağında ya da mobil veride olabilir; aynı ağa bağlan.
   - VPN veya üçüncü parti antivirüs güvenlik duvarı (ESET, Kaspersky vb.) kendi kuralını ister.
3. Tarayıcıda açılıyor ama uygulama bağlanmıyorsa: `API_BASE` sonunda `/api` var mı, `https` değil `http` mi, ve uygulama **debug** mi derlendi?

**`Set-NetConnectionProfile` veya `New-NetFirewallRule`: "Erişim engellendi" (Windows System Error 5)**
PowerShell yönetici olarak açılmamış. Başlat → "PowerShell" → sağ tık → **Yönetici olarak çalıştır**; pencere başlığında "Yönetici" yazmalı. Ağ profilini komut yerine Ayarlar → Ağ ve İnternet → Wi-Fi → ağın özellikleri → **Özel ağ** yolundan da değiştirebilirsin. Okul, yurt, kafe gibi ortak ağları Özel yapma; bunun yerine kendi ev ağını ya da telefonunun hotspot'unu kullan.

**Docker'ın "Inference manager" hatası** (Docker Desktop açılırken ya da `docker compose up` sırasında *inference manager* / *model runner* ile ilgili hata)
Bu, Docker Desktop'ın yapay zekâ modeli çalıştırma özelliğinden (Docker Model Runner) gelir; BilPark bunu kullanmaz.
1. Docker Desktop → Settings → **AI** (bazı sürümlerde *Features in development / Beta features*) → **Enable Docker Model Runner** işaretini kaldır → *Apply & restart*.
2. Hata sürerse Docker Desktop'tan tamamen çık (sistem tepsisindeki balina → *Quit Docker Desktop*), PowerShell'de `wsl --shutdown` çalıştır ve Docker Desktop'ı yeniden aç.
3. Hâlâ sürüyorsa Docker Desktop'ı güncelle (Settings → Software updates).

**`env file ... .env.demo not found`**
`.env.demo` henüz yok: Bölüm 2.1'deki komutu çalıştır.

**Backend açılmıyor, logda `password authentication failed`**
`.env.demo`'daki veritabanı şifresi, volume ilk oluşturulduğundaki şifreden farklı. Ya eski `.env.demo`'yu geri koy ya da `docker compose down -v` ile veritabanını sıfırla.

**Panelde giriş "Kullanıcı adı veya şifre hatalı" diyor**
Şifreyi `.env.demo` dosyasından kopyala. Şifreyi volume oluştuktan sonra değiştirdiysen geçerli olan eski şifredir (Bölüm 5).

**`flutter devices` telefonu görmüyor**
USB kablosunun veri kablosu olduğundan emin ol (bazı kablolar yalnızca şarj eder), USB modunu "Dosya aktarımı" yap, telefondaki izin sorusunu onayla. Windows'ta bazı markalar için üreticinin USB sürücüsü gerekir.
