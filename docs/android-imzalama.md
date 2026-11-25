# Android release imzalama

Saha telefonlarına MDM ile dağıtılan APK, debug anahtarıyla değil gerçek bir release anahtarıyla imzalanır.

## Anahtar nerede?

Anahtar repoda **yoktur** (repo herkese açık). Geliştirici makinesinde şu klasördedir:

- `C:\Users\LENOVO\.bilpark-imza\bilpark-release.jks` (keystore, alias `bilpark`, RSA 4096, 10000 gün)
- `C:\Users\LENOVO\.bilpark-imza\key.properties` (parolalar ve keystore yolu)

Parola bu belgede ve repoda yer almaz; yalnızca `key.properties` içindedir.

## Hemen yedekle

**Bu anahtar kaybolursa, daha önce kurulmuş uygulamalar bir daha güncellenemez** (imza değişince Android güncellemeyi reddeder; tek çare kaldır-yeniden kur olur).

- `bilpark-release.jks` ve `key.properties` dosyalarını en az **iki çevrimdışı kopya** olarak sakla (ör. iki ayrı USB bellek, farklı yerlerde).
- Parolayı bir parola yöneticisine de kaydet.
- Anahtarı e-postayla, bulut klasörüyle ya da repoya koyarak paylaşma.

## Derleme

```
cd mobile
set BILPARK_KEY_PROPERTIES=C:\Users\LENOVO\.bilpark-imza\key.properties
flutter build apk --release --split-per-abi
```

PowerShell'de: `$env:BILPARK_KEY_PROPERTIES = "C:\Users\LENOVO\.bilpark-imza\key.properties"`.

Alternatif olarak `mobile/android/key.properties.example` dosyasını `mobile/android/key.properties` olarak kopyalayıp doldurabilirsin (bu dosya `.gitignore`'dadır).

Doğrulama: `apksigner verify --print-certs app-arm64-v8a-release.apk` çıktısında `CN=BilPark, O=BilPark, C=TR` görünmelidir.

Anahtar dosyası yoksa (CI, başka makine) derleme **debug anahtarına** düşer ve Gradle uyarı yazar. Bu APK dağıtım için uygun değildir.

## Eski kurulumlar

Şimdiye kadar debug anahtarıyla imzalı uygulama kurulu telefonlarda imza değiştiği için **bir kez kaldırıp yeniden kurmak** gerekir (yerel veriler silinir). Bundan sonraki sürümler normal güncellenir.

## İleride: Play App Signing

Uygulama Google Play'e çıkarsa Play App Signing kullanılabilir (ücretsiz). Bu durumda bu anahtar "yükleme anahtarı" olur ve kaybı Google üzerinden sıfırlanabilir.
