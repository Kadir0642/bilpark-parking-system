# Deneme: vatandaş sayfasında tarayıcıda plaka okuma (ölçüm)

Bu bir ölçüm denemesidir, ürün özelliği değildir. `web/`, `backend/` ve `mobile/` değişmedi. Kod `spikes/web-ocr/` altında.
Sorulan soru şuydu: statik vatandaş ödeme sayfası (GitHub Pages) telefon kamerasından plakayı tarayıcının içinde,
cihazda ve ücretsiz olarak işe yarar doğrulukta okuyabilir mi?

**Kısa cevap: koşullu "git".** PaddleOCR'ın küçük tanıma modeli (onnxruntime-web ile) sentetik sette %94 tam plaka
doğruluğu veriyor. tesseract.js %51'de kalıyor. Okunan plaka her zaman "Plakanız X mi?" diye onaylatılmalı ve
klavyeyle girişe dönüş hep açık durmalı. Ayrıntılar aşağıda.

## 1. Düzenek

- **Test seti** (`gen_plates.py`; repoda yalnızca üretici var, görüntüler `.gitignore`'da):
  - Plaka türleri: TR plakaları (beyaz zemin, solda mavi TR bandı; 34 ABC 123, 06 A 1234, 35 AB 123 gibi biçimler) ve AB plakaları (D ve NL, ülke kodlu mavi bant).
  - 8 bozulma sınıfı, her biri eşit sayıda: temiz, eğim (yaw/pitch/roll ±10°), bulanık (Gauss ya da hareket), karanlık+gürültü, parlama, sert JPEG (kalite 8-22), kırpma/ölçek (170-260 px genişliğinde küçük kare ya da gevşek kırpım), karışık.
  - Her görüntü kılavuz çerçeveyle kırpılmış bir kamera karesine benzetildi.
  - Geliştirme seti 200 görüntü (tohum 7). **Raporlanan sayılar ayrı tutulan 240 görüntülük setten** (tohum 2026: 174 TR, 28 D, 38 NL).
  - Plakalar rastgele üretildi, gerçek plaka kullanılmadı. İnternetten fotoğraf indirilmedi.
- **Çalışma ortamı:** başsız Chrome 158 (puppeteer-core ile sistemdeki Chrome). WASM tek iş parçacığında çalıştı, çünkü GitHub Pages COOP/COEP başlığı vermiyor ve çok iş parçacığı orada açılmaz. Masaüstü işlemci: i7-13650HX.
- **Ön işleme** (`ocr.js`, iki motor için aynı):
  - kılavuz çerçeve kırpımı;
  - projeksiyon profiliyle eğim tahmini ve döndürme;
  - dikey kenar yoğunluğundan yazı bandının bulunması;
  - mavi bandın (TR ya da ülke kodu) kesilmesi;
  - kontrast germe.
- **Son işleme:** `mobile/lib/core/plate.dart` ve `foreign_ocr.dart` kurallarının JS karşılığı (`plate.js`, `foreign_ocr.js`).
  - JS karşılığı mobil test örnekleriyle sınandı: `node --test test_plate.mjs`, 4 test, 4'ü geçti.
  - TR plakalar `extractPlate` ile değerlendirildi: konuma göre O→0 gibi düzeltmeler, il kodu ve biçim denetimi.
  - AB plakalar yabancı modda değerlendirildi: tesseract'ta kelime kutularıyla `readForeignPlate`, Paddle'da `extractRawPlate`.
- **Güven değeri:** okunan karakterlerin en düşük güveni. Paddle'da CTC olasılığından, tesseract'ta sembol güveninden alındı. Beyaz liste açıkken tesseract kelime güvenini 0 döndürdüğü için sembol güveni kullanıldı.

## 2. Sonuçlar (240 görüntü, ön işleme açık)

| Aday | Tam plaka | TR / D / NL | Karakter | OCR medyan / p95 (masaüstü) | Telefon tahmini (3-5 kat) | İndirme brotli (ham) | Lisans |
|---|---|---|---|---|---|---|---|
| **PaddleOCR PP-OCRv4 en rec** + onnxruntime-web 1.31.1 | **%93,8** | %94 / %96 / %92 | %98,4 | 53 / 89 ms | 0,15-0,45 s | 9,0 MB (21,5) | Model Apache-2.0, ORT MIT |
| PaddleOCR PP-OCRv4 ch rec + aynı çalışma zamanı | %93,8 | %94 / %96 / %92 | %98,3 | 34 / 66 ms | 0,1-0,35 s | 11,7 MB (24,6) | Apache-2.0 |
| tesseract.js 7.0.0 (eng best_int, PSM 7, A-Z0-9 beyaz liste) | %50,8 | %56 / %57 / %21 | %80,7 | 25 / 33 ms | 0,1-0,2 s | 3,9 MB (6,7) | Apache-2.0 |

- **Ön işleme belirleyici.** Ön işleme kapalıyken (çerçevenin tamamı motora verildiğinde) Paddle en %73,8'e, tesseract %34,6'ya düşüyor.
- **İndirme boyutu:**
  - Paddle'ın 9 MB'ının yaklaşık 2,3 MB'ı onnxruntime WASM, 6,7 MB'ı model.
  - Model sıkıştırılınca pek küçülmüyor. int8 nicemleme ile yarıya inebilir; bu ölçülmedi.
  - Dosyalar jsDelivr'den tam sürümle gelir ve ilk kullanımdan sonra tarayıcı önbelleğinde kalır.
- **Yükleme süresi:** yerel diskten modeli açmak 0,3-0,5 s sürdü. Telefonda buna ağdan 9 MB indirme eklenir: 4G'de birkaç saniye. Bu yüzden indirme, "Kamerayla oku"ya basılınca kamera açılırken arka planda başlamalı.
- **Kalan hatalar:**
  - **Belirsiz harf/rakam (O/0, I/1):** "27 SO 760" bitişik "27S0760" diye okunuyor. Mobil kural düzeltme gerektirmeyen geçerli adayı ("27 S 0760") seçiyor ve bu kural mobildeki ML Kit yolunda da aynı.
  - **Parlamada kaybolan baş harf:** parlama ilk karakteri sildiğinde kalan kısım bazen yine geçerli bir plaka oluyor.
  - Grup boşluğuna bakan öneri kuralı (`extractPlateGrouped`) bu sette kazanç göstermedi, çünkü model boşluğu güvenilir vermiyor.

### Bozulma sınıfına göre tam plaka doğruluğu (her sınıf 30 görüntü)

| Aday | Temiz | Eğim | Bulanık | Karanlık | Parlama | JPEG | Kırpma/ölçek | Karışık |
|---|---|---|---|---|---|---|---|---|
| Paddle en | %100 | %100 | %90 | %87 | %87 | %97 | %93 | %97 |
| Paddle ch | %93 | %100 | %90 | %90 | %93 | %100 | %93 | %90 |
| tesseract | %73 | %50 | %53 | %40 | %30 | %53 | %53 | %53 |

## 3. Yanlış ama emin okuma (onay ekranı için kritik)

Arayüz "Plakanız X mi?" diye soracak. Tehlikeli durum, yanlış bir plakanın yüksek güvenle gösterilmesi: kullanıcı
düşünmeden "Evet" diyebilir ve borç sorgusu başka bir plakaya gider. Tablo yalnızca 174 TR plakası üzerinden hesaplandı.
"Gösterilen", geçerli TR plakası çıkan ve güveni eşiğin üstünde kalan okumalardır; kalanlar klavyeye düşer.

| Eşik (en düşük karakter güveni) | Paddle en: gösterilen / isabet / yanlış adet | Paddle ch | tesseract |
|---|---|---|---|
| 0 (her geçerli okuma) | %97,7 / %95,9 / 7 | %98,3 / %95,3 / 8 | %89,1 / %63,2 / 57 |
| 0,5 | %96,0 / %96,4 / 6 | %97,7 / %95,3 / 8 | %31,0 / %66,7 / 18 |
| 0,7 | %89,1 / %98,1 / 3 | %95,4 / %95,8 / 7 | %8,0 / %64,3 / 5 |
| **0,8** | **%83,3 / %99,3 / 1** | %93,7 / %96,3 / 6 | %4,6 / %37,5 / 5 |
| 0,9 | %67,2 / %99,1 / 1 | %90,2 / %98,7 / 2 | %1,1 / %100 / 0 |
| 0,95 | %51,1 / %100 / 0 | %85,1 / %99,3 / 1 | %0,6 / %100 / 0 |

- **Paddle en'in güveni iyi ayarlı:** 0,8 eşiğinde plakaların %83'ü önerilir ve önerilenlerin %99,3'ü doğrudur (174'te 1 yanlış). Paddle ch'nin güveni daha iyimser: 0,95'te bile 1 yanlış kalıyor (`29OI4043` → `29O14043`, güven 0,99).
- **tesseract'ın güveni neredeyse hiçbir şey söylemiyor:** eşik yükseldikçe isabet artmıyor.
- **TR modunda yabancı plaka riski:** AB plakalarının %7,6'sında TR modu geçerli ama yanlış bir TR plakası üretti (Paddle). Yabancı plaka anahtarı ve onay adımı bu yüzden de gerekli.

## 4. TextDetector (Shape Detection API)

Masaüstü Chrome 158'de (başsız) `TextDetector` yok. Bu API Android Chrome'da bayrak arkasında, Safari'de hiç yok. Ürün
buna dayanmamalı. Varsa ileride ek bir yol olarak denenebilir.

## 5. Öneri: koşullu "git"

Motor olarak **PaddleOCR PP-OCRv4 en rec** (onnxruntime-web, tek iş parçacığı) öneriyorum. tesseract.js'i önermiyorum:
doğruluğu yarıda kalıyor ve güveni anlamsız. Paddle ch daha hızlı ama 2,7 MB daha büyük ve güveni aşırı iyimser.
"Git" kararı şu koşullara bağlı:

1. **Tembel yükleme.** Kütüphane ve model yalnızca "Kamerayla oku"ya basılınca yüklenir. Sayfanın ilk açılışı hiç ağırlaşmaz. İndirme kamera açılırken arka planda başlar.
2. **Plaka şeklinde kılavuz çerçeve.** Yaklaşık 3,2:1 oranında, ekran genişliğinin %85'i. Yalnızca çerçevenin içi okunur. Ön işleme (eğim, yazı bandı, mavi bant) zorunlu.
3. **Her zaman onay.** "Plakanız 34 ABC 123 mi?" sorulur. Plaka otomatik gönderilmez. Okunan plaka düzenlenebilir alana yazılır.
4. **Güven eşiği 0,8.** Altında kalan ya da geçerli plaka çıkmayan okumalarda "Okuyamadık, lütfen yazın" denir ve klavye açılır.
5. **Klavye her zaman görünür.** Kamera bir kısayoldur, zorunlu değildir. Kamera izni reddedildiğinde, HTTPS olmadığında ya da model inmediğinde sayfa bugünkü gibi çalışır.
6. **Yabancı plaka anahtarı** TR kuralını kapatır. Mobildeki gibi düzeltmesiz okuma yapılır.
7. **Gizlilik.** Görüntü cihazdan çıkmaz, saklanmaz. Sayfada bunu söyleyen tek bir satır yer alır.
8. **Gerçek plaka denemesi.** Sentetik yazı tipi (Bahnschrift) gerçek TR plaka yazısından farklı. Ürüne geçmeden önce telefonda gerçek plakalarla en az 30-50 deneme yapılmalı (bkz. bölüm 7).

## 6. Vatandaş sayfasının yeni tasarımında kamera akışı

- **Düğme yerleşimi:** plaka giriş alanı sayfanın ana öğesi olarak kalır. Hemen yanında ya da altında ikincil görünümde "Kamerayla oku" düğmesi durur (kamera simgesi, tam genişlik mobil düğme). Ana düğme "Borcu sorgula" olarak kalır.
- **Kamera görünümü:** tam ekran bir katmanda açılır. Ortada sarı, köşeleri yuvarlak plaka çerçevesi, çerçeve dışı karartılmış. Altta tek bir "Çek" düğmesi ve "Vazgeç / Elle yaz" bağlantısı bulunur. Model inerken ince bir ilerleme çubuğu görünür.
- **Onay adımı:** katman kapanır ve okunan plaka giriş alanına yazılır. Alan vurgulanır, üstünde "Plakanız 34 ABC 123 mi?" yazar, yanında "Evet, sorgula" ve "Düzelt" düğmeleri bulunur. "Düzelt" klavyeyi açar.
- **Dönüş yolları:** okunamazsa, güven 0,8'in altındaysa ya da izin reddedilirse katman kapanır, giriş alanı odaklanır ve kısa bir uyarı gösterilir. Akış her zaman bugünkü klavye akışına düşer.
- Görsel dil yönetim paneliyle aynı olur (renk değişkenleri, açık/koyu tema, kart yapısı). Kamera katmanı da bu değişkenleri kullanır.

## 7. Telefonda gerçek plakayla deneme

Deneme sayfası `spikes/web-ocr/index.html`:
- motor seçimi, yabancı plaka anahtarı, "Kamerayla oku" (sarı çerçeve) ve "Fotoğraf seç" seçenekleri var;
- sonuç, güven ve süre gösterilir; doğru/yanlış sayacı yalnızca sekmede tutulur;
- **hiçbir şey yüklenmez ve saklanmaz.** Başsız Chrome'da sınandı: sayfa yalnızca GET isteği yaptı (dosyaların kendisi) ve üç denemede de doğru okudu.

Çalıştırma:

- Masaüstünde `cd spikes/web-ocr && npm install && node serve.mjs` komutunu çalıştırıp `http://localhost:8765/index.html?local=1` adresini açın. `localhost` güvenli bağlam sayıldığı için kamera çalışır. `?local=1` olmadan dosyalar jsDelivr'den gelir.
- Telefonda kamera için HTTPS ya da localhost gerekir. Ücretsiz seçenekler:
  - **(a)** Telefonu USB ile bağlayın, masaüstü Chrome'da `chrome://inspect` → "Port forwarding" ile 8765'i `localhost:8765`'e yönlendirin. Telefonda `http://localhost:8765/index.html?local=1` açılır ve kamera çalışır. Bu adımı kullanıcı kendisi yapar.
  - **(b)** "Fotoğraf seç" HTTPS olmadan da çalışır (yerel ağda `http://<bilgisayar-ip>:8765`). Bunun için sunucunun 127.0.0.1 yerine ağa açılması gerekir. Bunu bilerek ve yalnızca deneme süresince yapın.
  - **(c)** Bir dalı GitHub Pages'te yayımlamak HTTPS verir ama repo ayarı ister. Bu yalnızca kullanıcının kararıdır, yapılmadı.
- **Denenecekler:**
  - gündüz/gece, yakın/uzak, eğik açı ve parlayan plakalar;
  - Paddle en ile tesseract'ı karşılaştırın;
  - özellikle güveni 0,8'in üstünde olduğu halde yanlış çıkan okumaları not edin;
  - ilk açılıştaki indirme süresine bakın.

## 8. Yeniden üretme

```
cd spikes/web-ocr
npm install                     # yerel geliştirme paketleri (git'e girmez)
python gen_plates.py --out testset --n 240 --seed 2026
node --test test_plate.mjs      # JS kural karşılığı
node run_measure.mjs            # 5 yapılandırma, results/summary.json
```

## 9. Sınırlar

- **Sentetik set:** yazı tipi, mühür, perspektif ve ışık gerçek kamera karesinin yaklaşık bir benzetimi. Gerçek dünyadaki doğruluk muhtemelen daha düşüktür.
- **Kapsam dışı:** iki satırlı (kare) plakalar ve kirli ya da hasarlı plakalar.
- **Süreler:** aynı makinede başka işler de çalışırken ölçüldü. Telefon süreleri tahmindir. Deneme sayfasında ilk okuma (ısınma) 0,6-1 s sürdü.
- **Plakaya özel modeller:** fast-plate-ocr gibi plakaya özel küçük modeller (MIT) daha iyi ve daha küçük olabilir. Modelleri brief'te izin verilen CDN'lerde olmadığı için denenmedi; sonraki adım olarak önerilir.
