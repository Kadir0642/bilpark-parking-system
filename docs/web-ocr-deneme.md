# Deneme: vatandaş sayfasında tarayıcıda plaka okuma (ölçüm)

> **ARA SONUÇ.** Ölçüm yarıda durduruldu; sayılar sentetik sette alınmış ilk değerlerdir.
> Ürün özelliği değildir, `web/` değişmedi. Kod: `spikes/web-ocr/`.

## Düzenek
- Sentetik test seti (`gen_plates.py`, repoda yalnızca üretici): TR plakaları (beyaz zemin, mavi TR bandı) ve AB plakaları (D, NL); 8 bozulma sınıfı: temiz, eğim (±10°), bulanık, karanlık+gürültü, parlama, sert JPEG, kırpma/ölçek, karışık. Geliştirme seti 200 görüntü (tohum 7), ayrı tutulan son set 240 görüntü (tohum 2026). Plakalar rastgeledir.
- Her şey tarayıcıda çalışır (başsız Chrome, tek iş parçacığı WASM, GitHub Pages'te COOP/COEP olmadığı için). Hiçbir görüntü bir yere gönderilmez.
- Ön işleme: kılavuz çerçeve kırpımı → eğim tahmini (projeksiyon profili) → yazı bandı bulma → mavi bandı (TR / ülke kodu) kesme → kontrast germe.
- Son işleme: `mobile/lib/core/plate.dart` ve `foreign_ocr.dart` kurallarının JS karşılığı (`plate.js`, `foreign_ocr.js`; mobil test örnekleriyle sınandı, 4/4 test geçti).

## Ara sonuçlar (son set, 240 görüntü, masaüstü i7-13650HX, tek iş parçacığı)

| Aday | Tam plaka doğruluğu | Karakter doğruluğu | OCR süresi medyan / p95 | İndirme (brotli / ham) | Lisans |
|---|---|---|---|---|---|
| tesseract.js 7.0.0 (eng best_int, PSM 7, beyaz liste) | %50,8 (TR %56,3) | %80,7 | 25 / 33 ms | 3,9 / 6,7 MB | Apache-2.0 |
| PaddleOCR PP-OCRv4 en rec (onnxruntime-web 1.31.1) | **%93,8** (TR %93,7, AB %93,9) | %98,4 | 53 / 89 ms | 9,0 / 21,5 MB | Apache-2.0 (model), MIT (ORT) |
| PaddleOCR PP-OCRv4 ch rec (aynı çalışma zamanı) | %93,8 | %98,3 | 34 / 66 ms | 11,7 / 24,6 MB | Apache-2.0 |

- Ön işleme olmadan (tüm çerçeve): tesseract %34,6, Paddle en %73,8. Yazı bandı + eğim düzeltme belirleyici.
- Yanlış ama güvenli (TR, en düşük karakter güveni ≥ 0,9): Paddle en 1, Paddle ch 2, tesseract 0 (tesseract zaten az plaka öneriyor).
- Kalan hataların çoğu O/0 ve I/1 belirsizliği (ör. "27 SO 760" → "27S0760": mobil kural düzeltmesiz geçerli adayı seçiyor). Grup boşluğunu kullanan öneri kuralı (`extractPlateGrouped`) henüz kazanç göstermedi.
- Telefon tahmini (3-5 kat yavaş): Paddle en ~0,2-0,45 s / kare; ilk yükleme ~9 MB.
- Sentetik yazı tipi (Bahnschrift) gerçek TR plaka yazısından farklı; tesseract "4"ü sık "L" okudu. Gerçek plaka denemesi gerekli.

## Kalanlar
- Güven eşiği ve "yanlış ama güvenli" tablosunun tamamı, bozulma sınıfı başına ayrıntı.
- TextDetector (Shape Detection API) kullanılabilirliği raporu.
- Öneri: koşullu "git" (Paddle en; çerçeve, her zaman "Plakanız X mi?" onayı, klavyeye dönüş). Kesin karar tam sonuçtan sonra.
- Telefon deneme sayfası: `spikes/web-ocr/index.html` (`node serve.mjs` → `http://localhost:8765/index.html?local=1`). Telefonda kamera için HTTPS ya da Chrome'un USB port yönlendirmesiyle localhost gerekir; "Fotoğraf seç" her durumda çalışır.
