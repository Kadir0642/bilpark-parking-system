# Üçüncü taraf bileşenler (web/)

Bu klasördeki sayfalar aşağıdaki dış bileşenleri kullanır. Hiçbiri repoya kopyalanmadı; tarayıcı onları ilk
kullanımda sabit sürümle CDN'den indirir. Repo kodunun lisansı ayrıdır (`LICENSE`).

## Vatandaş sayfası, kamerayla plaka okuma (yalnızca `customer-payment.html?kamera=1`)

Bayrak yokken bu dosyalar hiç indirilmez. Bayrak varken de yalnızca "Kamerayla oku" düğmesine basıldığında iner.
Görüntü cihazda işlenir, hiçbir sunucuya gönderilmez.

| Bileşen | Sürüm ve adres | Lisans | Kaynak |
|---|---|---|---|
| onnxruntime-web (WASM çalışma zamanı) | 1.31.1, `https://cdn.jsdelivr.net/npm/onnxruntime-web@1.31.1/dist/` (`ort.wasm.min.mjs`, `ort-wasm-simd-threaded.*`) | MIT | npm paketindeki `license: MIT`; github.com/microsoft/onnxruntime, LICENSE (MIT, Copyright (c) Microsoft Corporation) |
| PaddleOCR PP-OCRv4 İngilizce tanıma modeli, ONNX biçimi | `paddle-ocr-onnx-models@0.2.0`, `models/en_PP-OCRv4_rec.onnx` (7.666.529 bayt, brotli ile yaklaşık 7,1 MB; SHA-384 sayfada doğrulanır) | Apache-2.0 | Aşağıdaki zincir |

### Model lisans zinciri

1. **Özgün model:** PaddleOCR (github.com/PaddlePaddle/PaddleOCR), depo lisansı Apache-2.0. PP-OCRv4 "en" tanıma
   modeli bu depoda yayımlanan modellerdendir.
2. **ONNX'e dönüştürülmüş kopya:** huggingface.co/SWHL/RapidOCR (RapidOCR projesi, github.com/RapidAI/RapidOCR,
   Apache-2.0). Model kartında `license: apache-2.0`.
3. **Kullandığımız npm paketi:** `paddle-ocr-onnx-models` 0.2.0 (github.com/ocavue/paddle-ocr-onnx-models). README'si
   paketin yukarıdaki Hugging Face deposundaki modellerin yeniden dağıtımı olduğunu ve lisansının Apache-2.0
   olduğunu yazar; depoda ve npm paketinin içinde Apache-2.0 `LICENSE` dosyası vardır (jsDelivr:
   `paddle-ocr-onnx-models@0.2.0/LICENSE`). npm `package.json`'ında `license` alanı boştur; lisans dosyası belirleyicidir.

Apache-2.0 değiştirilmeden yeniden dağıtıma ve ticari kullanıma izin verir; lisans metninin ve atıfın korunmasını
ister. Model değiştirilmeden, kendi paketinden ve lisans dosyasıyla birlikte sunulur; bu dosya atıf notudur.

### Plaka kuralları

`js/kamera/plaka-kurallari.js`, bu reponun mobil uygulamasındaki kuralların (`mobile/lib/core/plate.dart`,
`foreign_ocr.dart`) JS karşılığıdır; dış bileşen değildir.

## Diğer

- Inter yazı tipi: Google Fonts (SIL Open Font License 1.1).
- Yönetim paneli ayrıca Chart.js, SheetJS ve Font Awesome kullanır (bkz. `admin-panel.html` başlığı).
