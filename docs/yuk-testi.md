# BilPark yük testi

Bu çalışmada canlıdaki kaynak sınırlarının (Render Free, Neon Free) yerel bir kopyası kuruldu. Saha uygulamasını,
yönetim panelini ve vatandaş QR sayfasını gerçek davranışlarıyla taklit eden bir yük üreteci yazıldı ve bu
kopyaya karşı koşuldu. **Canlı sisteme hiçbir istek gönderilmedi.** Komutlar ve dosyalar:
[`loadtest/README.md`](../loadtest/README.md). Ham çıktılar: `loadtest/results/`.

Ölçüm tarihi: 26 Kasım 2026. Ölçülen kod: `main` @ `4842938` (plaka sorgusu hız sınırı dahil).

## Kısa sonuç

<!--OZET-->

## 1. Ortam (üretime benzetme)

| Parça | Canlı | Yerel kopya |
|---|---|---|
| Uygulama | Render Free: 512 MB, ~0.1 CPU (paylaşımlı), tek örnek, repo Dockerfile'ı | Aynı Dockerfile, `cpus: 0.1`, `mem_limit: 512m` (swap yok). Karşılaştırma: `cpus: 0.5` |
| Veritabanı | Neon Free 0.25 CU (~1 GB, ¼ vCPU, 2 CU'ya kadar ölçeklenebilir), doğrudan bağlantı | Postgres 16, `cpus: 0.25`, 1 GB, `max_connections=104`. Otomatik ölçekleme **yok**: hep en küçük boy (en kötü durum) |
| Ağ (app → DB) | Neon AB bölgesi | toxiproxy, her yönde 10 ms ±2 (gidiş-dönüş ~20 ms) |
| Ağ (istemci → app) | Mobil veri / Render vekil sunucusu | Aynı Docker ağı (gecikme yok, TLS yok) |
| JVM | Dockerfile'da bayrak yok | Aynı; yalnızca `-Duser.timezone=Europe/Istanbul` ve `-Xlog:gc` eklendi. 512 MB'ta ergonomi: **en fazla 128 MB yığın, SerialGC** |
| Veri | Birkaç aylık | 90 günlük ~65 bin kayıt, 5 bin sync olayı, 65 bin denetim satırı (`seed.sql`) |

Neon 0.25 CU için `max_connections` değeri olarak Neon belgelerindeki boy tablosunda gördüğüm 104 alındı;
canlı hesapta doğrulanmadı. Uygulama havuzu zaten 5 bağlantı olduğundan bu sınıra hiçbir koşuda yaklaşılmadı
(en çok 8 bağlantı görüldü).

**Ölçüm temizliği:** Her koşu sırasında ana makine CPU'su ve Android emülatörü süreci 5 sn'de bir kaydedildi
(`*-kaynak.csv`). Koşulardan hiçbirinde emülatör açık değildi. Ana makinede başka projelerin işleri (başka bir
agent'ın Maven/Java süreci, başka bir projenin Java konteyneri) ara ara %80-99 CPU'ya çıktı; ortalama %20-36
kaldı. Konteynerlerin CPU payı (`cpus`) sabit olduğu ve 20 çekirdekten hep boş çekirdek kaldığı için etkisi
sınırlı. Yine de bu tepelerin üst yüzdelikleri biraz şişirmiş olabileceği her tabloda "ana makine" sütununda
belirtildi.

## 2. Senaryo ve varsayımlar

Sanal kullanıcıların davranışı `mobile/lib` ve `web/` kodundan çıkarıldı (uydurma bir oran değil):

- **Görevli** (`yuk001..`, 6 sahaya sırayla dağıtılır; 10 görevli = sahalarda 1-2 kişi):
  - Vardiya başında giriş yapar (`POST /auth/login`, bcrypt). Ardından açılış istekleri gelir: `/me`, `/tariff/current`,
    `/vehicle-types`, `/license/status`, gösterge ve harita.
  - Canlı akışı açar: önce `GET /sync` ile sürümü alır, sonra `GET /events?since=` ile SSE'ye bağlanır. 60 sn kare
    gelmezse akışı koparır. Yeniden bağlanırken 1-2-4-8-16-30 sn bekler ve araya `/sync?since=` sokar.
  - Her **30-90 sn**'de bir işlem yapar: giriş (%52), çıkış (%38), yanlış giriş iptali (%6) ya da kaçak (%4).
    Çıkış ve iptalden önce araç ekranını açar (`/fee-preview`), 3-8 sn bakar ve 5 sn geri alma süresini bekler.
  - **Bölgesinde herhangi bir araç olayı olunca** uygulamadaki gibi tepki verir. Harita 300 ms sonra
    `/parking/filter`, gösterge 500 ms sonra `/parking/filter` + `/parking/history` + `/vehicle-types` çeker.
  - HEARTBEAT'teki sürüm kendi sürümünden ilerideyse `/sync?since=` çeker.
  - Plaka okuma telefonda ML Kit ile yapılır; sunucuya **fotoğraf gitmez**, yalnızca plaka metni gider.
- **Yönetici** (2-3 oturum):
  - Açılışta yaklaşık 15 istek atar, ardından bilet alıp SSE'ye bağlanır.
  - Her araç olayında, 800 ms'lik kısmayla 5 uç çeker: `spots`, `bi/income-by-type/details`, `bi/income-summary`,
    `bi/last-records`, `bi/shifts`.
  - ~4 dk'da bir tam yenileme yapar, ~10 dk'da bir xlsx rapor indirir (dönem, kayıt ya da personel raporu).
- **Vatandaş:** QR sayfası GitHub Pages'ten gelir; backend'e yalnızca `/tariff/current` ve `/parking/debt` düşer.
  - Arka plan trafiği dakikada 1-10 kişi; 5 dk'da bir, bir dakikalık 30/dk tepe.
  - **Dalga koşusunda** 100/200/300/400 kişi 60 sn içinde gelir.
  - Sorguların %60'ı sahadaki gerçek bir plakaya, %40'ı olmayan bir plakaya yapılır (404).
- **Geliştirici sayfası:** dakikada bir `/developer/activity`, 5 dk'da bir `health` + `errors`.
- **Hız sınırı:** `/parking/debt` IP başına 120/dk ile sınırlı. İstemci IP'si, X-Forwarded-For başlığının en
  sağındaki girdiden alınıyor. Üreteç her sanal vatandaşa ayrı bir en-sağ XFF verir; bu, her telefonun ayrı bir
  IP'den gelmesine karşılık gelir. Ayrıca bir koşuda dalganın tamamı tek IP'den gönderildi; bu da tek bir
  operatör NAT'ının arkasındaki kalabalığı temsil ediyor.
- **Ölçek:** 3 kat = 30 görevli, 10 kat = 100 görevli. İkisinde de görevliler aynı 6 sahaya dağılıyor, yani saha
  başına düşen görevli sayısı artıyor. Bu, en kötü durum. Bölge olayının yayılma yükü bölgedeki görevli sayısıyla
  çarpılarak büyüyor.

## 3. Sonuçlar (app 0.1 CPU)

Süreler istemcinin gördüğü uçtan uca süredir. Mobil uygulamanın zaman aşımı 15 sn; 15 sn'yi geçen istek hata
sayıldı. Panel ve vatandaş sayfasında zaman aşımı yok, üreteç bunlar için 60/30 sn bekledi. "Havuz bekleyen"
değeri Hikari'nin 30 sn'de bir yazdığı anlık değerlerin en büyüğü; "havuz zaman aşımı", 20 sn içinde DB
bağlantısı alamayıp hata dönen istek sayısı. Kaynak: `python -I loadtest/ozet.py loadtest/results --tablo ...`.

| koşu | istek/sn | hata % | p50 | p95 | p99 | check-in p95 | SSE p95 | app CPU ort/max (%, sınır 10) | RSS max MB | full GC | havuz bekleyen max | havuz zaman aşımı | ana makine CPU ort/max |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **a2** temel 10 görevli, sıcak JVM, 10 dk | 3.5 | **0** | 0.6 sn | **2.5 sn** | 6.8 sn | 2.9 sn | 0.2 sn | 4.1 / 11.2 | 424 | 0 | 13 | 0 | 36 / 99 ⚠ |
| a temel 10 görevli, yeniden başlatma sonrası (soğuk JVM), 15 dk | 3.3 | 2.9 | 1.5 sn | 15.0 sn | 21.0 sn | 15.0 sn | 0.5 sn | 7.8 / 11.3 | 432 | 2 | 45 | 17 | 32 / 90 ⚠ |
| **b** 3 kat: 30 görevli, 3 yönetici, 10 dk | 15.3 | **0** | 1.2 sn | **5.7 sn** | 7.6 sn | 5.6 sn | 0.2 sn | 7.8 / 11.2 | 447 | 2 | 94 | 0 | 19 / 82 |
| **c** 10 kat: 100 görevli, 3 yönetici, 10 dk | 6.9 | **86.9** | 15.0 sn | 18.6 sn | 32.2 sn | 15.0 sn | 0.3 sn | 9.8 / 11.4 | 484 | 11 | 196 | 850 | 16 / 71 |
| k kırılma: 10→20→30→40→50 görevli, 150 sn'lik basamaklar | 11.1 | 33.3 | 1.5 sn | 24.7 sn | 45.8 sn | 15.0 sn | 0.2 sn | 5.8 / 11.6 | 466 | 37 | 197 | 563 | 19 / 80 |
| v vatandaş dalgaları 100/200/300/400 + 10 görevli + 2 yönetici | 5.8 | 0.2 | 0.1 sn | 1.8 sn | 7.2 sn | 0.8 sn | 0.1 sn | 3.5 / 10.3 | 456 | 8 | 196¹ | 174¹ | 17 / 74 |
| v2 200 vatandaş tek IP'den (NAT) | 5.2 | 6.5² | 0.1 sn | 1.3 sn | 1.8 sn | 0.7 sn | 0.1 sn | 2.5 / 9.5 | 450 | 0 | 0 | 0 | 11 / 69 |
| d kesinti sonrası kuyruk: 30 görevli × ~22 giriş | 7.7 | 38.6 | 13.0 sn | 31.0 sn | 45.0 sn | 15.0 sn | 0.2 sn | 5.7 / 11.5 | 462 | 8 | 195 | 637 | 15 / 70 |
<!--EK-SATIRLAR-->

⚠ Ana makinede başka işler kısa süreli %90-99 CPU'ya çıktı (emülatör yoktu). Konteyner payları sabit olduğundan
ortalamaları etkilemedi ama tek tük uç değerleri büyütmüş olabilir. ¹ Hataların hepsi koşunun ilk 50 sn'sinde: 10
kişi aynı anda girdi ve büyük olasılıkla önceki kırılma koşusunun birikimi de sürüyordu. Dalgaların kendisinde hata yok.
² Hataların tamamı beklenen 429 (IP başına 120/dk sınırı).

### 3.1 Gerçekçi yük (10 görevli): dayanıyor, ama riskli

- Sıcak JVM'de sonuç: hata %0, p95 2,5 sn, p99 6,8 sn. Bir araç olayı, bölgedeki bütün görevlilere ve yöneticilere
  p95 0,2 sn'de ulaştı (en kötü 0,4 sn). 86 olayın 85'i beklenen herkese ulaştı. Eksik kalan tek olay büyük
  olasılıkla koşu kapanırken yolda kaldı; bu doğrulanmadı.
- Uygulama CPU'su ortalamada sınırın yarısında. Ama her araç olayı yaklaşık 15 okumayı tetikliyor (bölgedeki 1-2
  görevli × 4, artı 2 panel × 5) ve olaylarda 0.1 CPU sınırına dayanıyor. Havuzda anlık 13 bekleyen istek görüldü.
- **Yeniden başlatma ya da deploy sonrası ilk 3-4 dakika asıl risk.** JVM soğukken 10 görevli aynı anda girip
  akış açınca 10 sn'lik pencerelerde p95 15-37 sn'ye, hata oranı %44'e çıktı. 17 istek DB bağlantısını 20 sn
  içinde alamadı. Girişlerin %45'i 15 sn'de bitmedi. Dördüncü dakikadan sonra p95 ~2,4 sn'ye indi ve hata
  neredeyse sıfırlandı. Render Free 15 dk boşta kalınca uyuyor; ping bunu engelliyor ama her deploy ve her
  yeniden başlatma bu soğuk başlangıcı yaşatır.

### 3.2 Kırılma noktası: 30 ile 40 görevli arası

Basamak koşusunda (`k`), her basamağın ilk 30 sn'si oturma süresi sayılıp çıkarıldı:

| eşzamanlı görevli | istek/sn | 10 sn'lik p95 (medyan / en kötü) | hata (ort / en kötü) |
|---|---|---|---|
| 10 | 3.4 | 1,1 / 1,8 sn | %0 / %0 |
| 20 | 9.2 | 1,1 / 2,1 sn | %0 / %0 |
| 30 | 12.9 | 1,6 / 2,2 sn | %0 / %0 |
| **40** | 16.5 | **24 / 31 sn** | **%59 / %78** |
| 50 | 11.4 | 48 / 60 sn | %85 / %93 |

**İlk bozulan uygulamanın 0.1 CPU'su.** DB değil: DB CPU'su sınırının yarısının altında kaldı ve en çok 8
bağlantı görüldü. Çöküş şu zincirle ilerliyor:
1. CPU doyuyor ve istekler yavaşlıyor.
2. OSIV ve `DELAYED_ACQUISITION_AND_HOLD` yüzünden her istek DB bağlantısını yanıt bitene kadar tutuyor; 5'lik
   havuz doluyor ve 190'dan fazla istek bekliyor.
3. Bağlantı 20 sn'de alınamıyor ve `CannotCreateTransactionException` oluşuyor.
4. Bekleyen istekler 128 MB'lık yığını dolduruyor. Kırılma koşusunda 37 tam GC oldu. Her biri dünyayı durdurdu;
   en uzunu 2,5 sn sürdü, 100 görevli koşusunda 4,0 sn.
5. Bu arada `JwtAuthFilter` kullanıcıyı DB'den okuyamayınca token'ı geçersiz sayıyor ve **geçerli oturumlara 401
   dönüyor.** Uygulama 401'de görevliyi giriş ekranına atıyor, yönetim paneli de oturumu kapatıyor. Yeniden giriş
   bcrypt demek, 0.1 CPU'da tek giriş 1-15 sn sürüyor. Yani aşırı yük kendini büyütüyor.

10 kat yükte (100 görevli) giriş fırtınasının kendisi sistemi çökertti: 1.560 giriş denemesinin %95'i 15 sn'de
bitmedi ve görevlilerin çoğu akışa hiç bağlanamadı.

### 3.3 Vatandaş QR dalgaları: dayanıyor

`/parking/debt` kimlik istemiyor ve tek bir sorgu atıyor, bu yüzden ucuz:

| dalga (60 sn içinde) | p50 | p95 | p99 | hata |
|---|---|---|---|---|
| 100 vatandaş | 63 ms | 96 ms | 0,4 sn | %0 |
| 200 | 64 ms | 0,2 sn | 0,4 sn | %0 |
| 300 | 63 ms | 0,2 sn | 0,4 sn | %0 |
| 400 | 67 ms | 1,0 sn | 1,5 sn | %0 |
| 200, hepsi tek IP'den | 24 ms | 0,1 sn | 0,4 sn | 80 istek 429 (sınır 120/dk) |

400'lük dalga sırasında görevli isteklerinin 10 sn'lik p95'i 3,1 sn'ye çıktı (dalga dışında ~1,5 sn). Hız sınırı
farklı IP'lerle hiç devreye girmedi. Tek bir operatör NAT'ı arkasından dakikada 120'den fazla vatandaş gelirse
fazlası 429 alır. Bu, sınırın bilinçli bir sonucu.

### 3.4 Kesinti sonrası kuyruk boşaltma (30 görevli × ~22 giriş): dayanmıyor

- 2 dk'lık kesintide 30 görevlinin kuyruğunda toplam 656 giriş birikti. Ağ geri gelince hepsi 0-2 sn içinde
  akışı açtı ve kuyruklarını sırayla göndermeye başladı.
- Her başarılı giriş bölgedeki bütün görevlilere bir okuma dalgası daha yolladı. Sonuç: girişlerin %88'i 15 sn'de
  bitmedi, panele ve akışa 401'ler döndü (31 akış 401 ile kapandı). Koşu bitene kadar, yani 249 sn içinde,
  kuyruklar boşalmadı.
- **Veri doğruluğu bozulmadı.** Çift aktif plaka 0, aynı `checkInRequestId` ile iki kayıt 0, kaydı olmayan istek
  kimliği 0, girişten önce çıkış 0 (`results/*-dogruluk.txt`). İstemcinin zaman aşımına düşüp aynı kimlikle
  yeniden gönderdiği istekler sunucuda tek kayıt üretti. Yani `checkInRequestId` idempotentliği aşırı yükte de
  tuttu.
<!--D2-->

### 3.5 SSE teslimi

Sistem çökmediği sürece olaylar beklenen alıcıların hepsine 0,1-0,3 sn'de (p95) ulaştı. 30 görevlide 251 olayın
251'i bütün alıcılara gitti. Akış kendisi darboğaz değil: `SseEmitter` iş parçacığı tutmuyor, SSE yolu OSIV
dışında. Aşırı yükte sorun teslimde değil, akışı açmakta: bilet, `/sync` ve kullanıcı sorgusu havuz bekliyor,
`hello` süresi 30-56 sn'ye çıktı.

### 3.7 Dayanıklılık (35 dk, 10 görevli, yeniden başlatılmış JVM ile)

Toplam hata %0,17, p95 5,0 sn. Bu p95 ilk 5 dakikanın soğuk JVM'ini de içeriyor; 10. dakikadan sonra 5 dk'lık
dilimlerin p95 medyanı 2,6-2,8 sn.

| 5 dk'lık dilim | 0-5 | 5-10 | 10-15 | 15-20 | 20-25 | 25-30 | 30-35 |
|---|---|---|---|---|---|---|---|
| RSS (MB, ortalama) | 365 | 399 | 415 | 421 | 423 | 424 | 425 |
| JVM iş parçacığı | 65 | 65 | 66 | 66 | 65 | 67 | 67 |
| 10 sn'lik p95 medyanı | 7,5 sn | 3,3 sn | 2,7 sn | 2,7 sn | 2,6 sn | 2,8 sn | 2,7 sn |

- **Sızıntı belirtisi yok.** İş parçacığı sayısı sabit kaldı, DB bağlantısı 6-7'de durdu. RSS ilk 15 dk'da ısındı
  ve ~425 MB'ta düzleşti. GC sonrası yığın 55-75 MB arasında gidip geldi, artış eğilimi göstermedi.
- SSE akışları 30. dakikada sunucunun `EMITTER_TIMEOUT_MS` süresiyle kapandı ve hepsi yeniden bağlandı (12
  kopma, 0 RESET).
- **Yığın iki kez tükendi.** 1490-1513. ve 1675-1696. saniyelerde 8'er tam GC arka arkaya geldi
  (`123M->117M(123M)`, her biri 1-2 sn). Uygulama ~20 sn boyunca herkes için durdu; o 10 sn'lik pencerelerde hata
  %8-24 oldu. Bu iki an, ~10 dk'da bir indirilen xlsx raporlarıyla (kayıt ve personel raporu 12-24 sn sürdü)
  aynı döneme düşüyor. Ancak istek zaman damgasıyla birebir eşleştirme yapılmadı, bu yüzden "olası neden" olarak
  okunmalı.

<!--SOGUK-->

<!--CPU05-->

### 3.6 Veritabanı

- 30 görevlide DB CPU'su ortalama %11,7, tepe %26; sınır %25. DB çalışma süresinin çoğunu 4 sorgu alıyor
  (`*-sorgular.txt`):
  - `bi/last-records` (son 100 kayıt): ortalama 157 ms;
  - `/parking/history` (caddenin son 50 kaydı): 42 ms;
  - `bi/income-summary` toplamları: 17 ms × 4;
  - araç tipine göre ciro: 49 ms.
- EXPLAIN (`results/explain.txt`, 65 bin kayıt, 0.25 CPU) şunu gösteriyor:
  - `history` sorgusu `parking_records` üzerinde Seq Scan ve top-N sort yapıyor (90 ms);
  - son 100 kayıt sorgusu Seq Scan yapıyor (99 ms);
  - yıllık ciro (116 ms) ve tipe göre ciro (184 ms) tablonun tamamını tarıyor.

  Bu süreler kayıt sayısıyla doğrusal büyür. Bir yıllık veride (~260 bin kayıt) yaklaşık 4 katına çıkar.
- `bi/shifts` her açık vardiya için ayrı bir ciro sorgusu atıyor (N+1). Sorgu dizinli olduğu için tek başına ucuz
  (0,06 ms), ama 30 görevlide yönetici yenilemesi başına 30 ek gidiş-dönüş demek. Her biri ~20 ms Neon gecikmesi
  taşıyor ve bağlantıyı tutuyor.
- Kullanıcı (`users`) ve bölge (`zones`) sorguları en çok çağrılanlar: 30 görevlide 10 dk'da 24 bin ve 16 bin
  çağrı. Her kimlikli istekte kullanıcı 2 kez okunuyor, kayıt listelerinde bölge tek tek yükleniyor. Tek tek
  ucuzlar ama her biri bir Neon gidiş-dönüşü.

### 3.7 İstek dağılımı (30 görevli, 10 dk)

9.164 isteğin yalnızca 251'i yazma (giriş/çıkış/iptal/kaçak), yani **yazma başına ~36 okuma** var. Okumaların
dağılımı:
- %61'i görevlilerin olaya tepki okumaları: `/parking/filter` 2.495, `/vehicle-types` 1.315, `/parking/history`
  1.255, `/sync?since` 548.
- %29'u 3 yönetim paneli oturumunun her araç olayındaki 5 BI ucu (2.695).

Yük, görevli sayısıyla değil "görevli sayısı × bölgedeki görevli sayısı" ile büyüyor: aynı 6 sahada 10 görevli
3,5 istek/sn üretirken 30 görevli 15,3 istek/sn üretti (3 kat görevli, ~4,3 kat istek).
## 4. Darboğazlar (önem sırasıyla)

1. **Uygulamanın 0.1 CPU'su.** Ölçülen her senaryoda ilk doyan kaynak bu. Sorun dört şeyin üst üste binmesi:
   olay başına çok sayıda okuma, JSON serileştirme, bcrypt ve SerialGC'nin CFS kısıtında 10 kat uzayan
   duraklamaları.
2. **Olay çoğaltması.** Tek bir yazma, bölgedeki her görevlide 4 okuma ve her yönetici panelinde 5 BI okuması
   tetikliyor. Ayrıca başka bölgelerin olayları her 25 sn'de herkese `/sync?since` çağırtıyor. Yük "görevli ×
   bölgedeki görevli" ile büyüyor.
3. **İstek boyunca tutulan DB bağlantısı (OSIV + HOLD) ve 5'lik havuz.** CPU yavaşlayınca bağlantılar uzun süre
   tutuluyor, havuz doluyor ve 20 sn sonra hata başlıyor. Neon'un bağlantı sınırı (~104) sorun değil.
4. **Aşırı yükte 401, oturum düşmesi ve giriş fırtınası.** `JwtAuthFilter` DB hatasını "geçersiz token" sayıyor.
   İstemci de 401'de oturumu kapatıp yeniden girişe (bcrypt) zorluyor. Aşırı yük böylece kendini büyütüyor. Vardiya
   başı ya da deploy sonrası toplu giriş de aynı yolu kullanıyor.
5. **Soğuk başlangıç ve soğuk JVM.** 0.1 CPU'da açılış dakikalar sürüyor (§3.8). Açılıştan sonraki ilk 3-4 dk'da
   p95 15 sn'yi aşıyor.
6. **Küçük yığın (128 MB) ve SerialGC.** Yük altında yığın dolup tam GC'ye giriyor (2-4 sn duraklama). RSS
   420-480 MB ile 512 MB sınırına yakın.
7. **Tarama yapan BI ve geçmiş sorguları.** Bugün DB'yi doyurmuyor, ama kayıt sayısıyla doğrusal büyüyor ve
   bağlantıyı uzun tutuyor.

## 5. Öneriler (önce ücretsiz olanlar)

Hiçbiri bu PR'da uygulanmadı. "Etki" sütunu ölçümden çıkarılan beklentidir, ölçülmüş sonuç değildir; uygulanınca
`loadtest/` ile yeniden ölçülmeli.

| # | Öneri | Dokunduğu dosya | Beklenen etki | Ücret |
|---|---|---|---|---|
| 1 | **Panelin olay başına yenilemesini seyrelt:** 800 ms kısma yerine 5-10 sn'lik debounce. `bi/income-*` ve `bi/shifts`'i olayda değil 30-60 sn'de bir ya da sekme görünürken çek. Olay yükünü kullan (`spots` listesine eklemek/çıkarmak için yeniden çekme gerekmez). | `web/admin-panel.html` (`scheduleVehicleRefresh`) | İsteklerin ~%29'u ve DB süresinin büyük kısmı (en ağır 4 sorgunun 3'ü) panelden geliyor; bunun çoğu kalkar | ücretsiz |
| 2 | **Görevli tepkisini incelt:** gösterge ve harita aynı `/parking/filter`'ı iki kez çekiyor, birleştir. `/vehicle-types`'ı her olayda değil yalnızca `VEHICLE_TYPES_UPDATED`'ta yükle. `/parking/history`'yi olayda değil gösterge görünürken ve debounce ile çek. Daha iyisi: `VEHICLE_ENTERED/EXITED` yükündeki plaka/yer bilgisiyle listeyi istemcide güncelle. | `mobile/lib/main.dart`, `screens/dashboard_screen.dart`, `screens/parking_map_screen.dart` | Olay başına görevli okuması 4'ten 0-1'e iner; isteklerin ~%60'ı olay tepkisi | ücretsiz (APK) |
| 3 | **HEARTBEAT'te bölge sürümü:** HEARTBEAT görevliye "kendi görebildiği son sürümü" taşısın ya da istemci yalnızca görünür olay kaçırdığında catch-up yapsın. | `sync/SyncService.java` (heartbeat), `mobile/lib/core/live_sync.dart` | Her görevlinin 25 sn'de bir yaptığı `/sync?since` çağrısı biter (30 görevlide 548/10 dk) | ücretsiz |
| 4 | **Aşırı yükte oturum düşürme:** `JwtAuthFilter`'da DB/havuz hatasını "geçersiz token"dan ayır ve 503 dön. İstemci 401'de oturumu kapatsın, 503/zaman aşımında beklesin. Kullanıcı ayrıntılarını kısa süre (ör. 30-60 sn) önbelleğe al: istek başına 2 kullanıcı sorgusu 1'e ya da 0'a iner. | `auth/JwtAuthFilter.java`, `auth/CustomUserDetailsService.java`, `controller/*` (`currentUser`), `mobile/lib/core/api_client.dart` | Çöküşteki kendini büyüten döngü (401 → yeniden giriş → bcrypt) kırılır | ücretsiz |
| 5 | **Bağlantıyı istek boyunca tutma:** `spring.jpa.open-in-view` zaten kapalı; elle eklenen `OpenEntityManagerInViewInterceptor`'ı kaldır ve tembel ilişkileri sorguda `join fetch` ile çöz. Ya da `HibernateJpaVendorAdapter`'da bağlantıyı işlem sonunda bırak (`DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`). | `config/OpenEntityManagerInViewConfig.java`, ilgili repository sorguları | Havuz bağlantısı JSON serileştirme ve xlsx üretimi boyunca tutulmaz; 5'lik havuz çok daha geç dolar | ücretsiz |
| 6 | **JVM bayrakları:** `-XX:+UseSerialGC -Xss512k -XX:MaxRAMPercentage=40 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1` (açılış ve 0.1 CPU için C1). Spring Boot'un CDS/AOT önbelleğiyle (`-XX:SharedArchiveFile`, eğitim koşusu Dockerfile'da) açılışı kısalt. Bayraklar 512 MB'ta RSS'i ölçerek ayarlanmalı. | `backend/Dockerfile` | Daha az tam GC, daha kısa açılış (CDS ile tipik olarak %30-50), sınıra yakın RSS kontrol altında | ücretsiz |
| 7 | **Eksik dizinler ve BI sorguları:** yeni migration (V12+) ile `parking_records (street, entry_time DESC)` ve `parking_records (entry_time DESC)`, ciro için `(status, exit_time) INCLUDE (fee)`. `bi/shifts` N+1'ini tek `GROUP BY collected_by_id, device_id` sorgusuna çevir. Tipe göre ciroyu kısa süre önbelleğe al. | `backend/src/main/resources/db/migration/V12__...sql`, `repository/ParkingRecordRepository.java`, `controller/ParkingController.java` | `history` ve son 100 kayıt Seq Scan'den dizin taramasına iner (90-100 ms → birkaç ms); büyüyen tabloda doğrusal artış durur | ücretsiz |
| 8 | **Yeniden bağlanmaya jitter:** üstel beklemeye ±%50 rastgele sapma ekle. Kesinti sonrası kuyruğu tek seferde değil aralıklı (ör. 0,5-1 sn) ve rastgele gecikmeyle başlat. | `mobile/lib/core/live_sync.dart`, `mobile/lib/core/entry_queue.dart`, `web/admin-panel.html` (`liveFail`) | Deploy veya kesinti sonrası herkesin aynı saniyede bağlanması ve kuyruk boşaltması dağılır | ücretsiz |
| 9 | **Giriş fırtınası:** bcrypt maliyeti 10 kalsın; ama token süresi (24 sa) vardiyayı kapsıyor, gereksiz yeniden girişe yol açan 401'leri (öneri 4) kes. Vardiya başında uygulama girişleri birkaç saniyeye yaysın. | `mobile/lib/screens/auth_screen.dart`, öneri 4 | 0.1 CPU'da tek giriş 1-5 sn sürüyor, toplu girişte 15 sn'yi aşıyor | ücretsiz |
| 10 | **Daha fazla CPU:** 0.5 CPU'da kırılma noktası (§3.9). Render'ın ücretli planı ya da başka bir barındırma. | — | Bkz. §3.9 | **ücretli** (karar kullanıcının) |

Öneri 1-4 birlikte, görevli başına okuma yükünü kabaca 3-5 kat düşürür (olay başına okuma 4+5 → 0-1+0,
`/sync` catch-up yok). Bu, 0.1 CPU'da da kırılma noktasını 30-40 görevlinin epey üstüne taşıyabilir; doğrulamak
için ölçmek gerekir.

## 6. Kod incelemesi (değişiklik yapılmadı)

| Konu | Bulgu | Kanıt |
|---|---|---|
| Hikari havuzu | `maximum-pool-size=5`, `minimum-idle=0`, `idle-timeout=30s`, `connection-timeout=20s` (`application.properties`). Neon 0.25 CU sınırı ~104 bağlantı; sınırlayan Neon değil, 5'lik havuz. 30 sn boşta kalan bağlantı kapanır, yeniden açmak Neon'da TLS + kimlik doğrulama (+ uyanma) ister. | `application.properties` |
| Bağlantı istek boyunca tutuluyor | `OpenEntityManagerInViewInterceptor` (SSE hariç her istek) + Spring'in `HibernateJpaVendorAdapter` varsayılanı `hibernate.connection.handling_mode=DELAYED_ACQUISITION_AND_HOLD`: istek DB'ye ilk dokunduğu andan **yanıt bitene kadar** (JSON serileştirme, xlsx üretimi dahil) havuz bağlantısını tutar. 0.1 CPU'da istekler yavaş olduğu için 5 bağlantı çabuk doluyor. | `OpenEntityManagerInViewConfig.java`, spring-orm 7.0.5 sınıf sabitleri, Hikari günlüğü `active=5, waiting>0` |
| İstek başına kullanıcı sorgusu | Her kimlikli istekte `JwtAuthFilter` `loadUserByUsername` + controller `currentUser` = aynı kullanıcı için 2 sorgu. | `JwtAuthFilter.java`, `ParkingController.currentUser` |
| Görevli olay çoğaltması | Bölgedeki HER araç olayında bölgedeki HER görevli: harita `/parking/filter` (300 ms debounce) + gösterge `/parking/filter` + `/parking/history` + `/vehicle-types` (500 ms debounce). `/parking/filter` caddenin tamamını döner, istemci bölgeye süzer. | `parking_map_screen.dart`, `main.dart`, `dashboard_screen.dart` |
| HEARTBEAT catch-up | Görevli yalnızca kendi bölgesinin olaylarını alır ama HEARTBEAT (25 sn) en son GLOBAL sürümü taşır; başka bölgede olay olduysa her görevli her 25 sn'de `/sync?since=` çağırır (sunucu 500'lük sayfalarla tarar, görünmeyenleri atar). | `live_sync.dart` `_onFrame`, `SyncService.heartbeat` |
| Yönetim paneli | Her araç olayında (800 ms kısma) 5 uç: `spots`, `bi/income-by-type/details`, `bi/income-summary` (4 SUM), `bi/last-records` (son 100), `bi/shifts` (açık vardiya BAŞINA ayrı ciro sorgusu, N+1). | `admin-panel.html` `scheduleVehicleRefresh`, `ParkingController.getActiveShifts` |
| Eksik dizinler | `parking_records(street, entry_time)` yok: `/parking/history` 65 bin satırda Seq Scan ~90 ms (0.25 CPU). `parking_records(entry_time)` yok: son 100 kayıt ~99 ms. Ciro toplamları ve tipe göre ciro tüm tabloyu tarıyor (116-184 ms). Süre tablo büyüdükçe doğrusal artar. | `results/explain.txt` |
| JVM bayrakları | Dockerfile'da yok: 512 MB'ta varsayılan **128 MB yığın** (MaxRAMPercentage=25), **SerialGC**, C2 JIT. RSS 375-420 MB: yığın dışı (metaspace, kod önbelleği, iş parçacıkları) büyük. Başlangıç 0.1 CPU'da 3-7 dk. | `java -XX:+PrintFlagsFinal`, `coldstart.sh` |
| Tomcat | Ayar yok: 200 iş parçacığı, 8192 bağlantı. SSE `SseEmitter` (async) iş parçacığı tutmaz; DB bağlantısı da tutmaz (SSE yolu OSIV dışı). Akış sayısı sınırlayıcı değil. | `SyncService.connect`, `OpenEntityManagerInViewConfig` |
| SSE yayını | `broadcast` yazan isteğin iş parçacığında, commit sonrası, bağlı bütün istemcilere sırayla `send` yapar (bağlantı hâlâ tutulurken). Az istemciyle sorun değil; yüzlerce akışta yazma isteğinin süresine eklenir. | `SyncService.broadcast` |
| İstemci yeniden bağlanma | Mobil ve panel üstel bekleme 1-2-4-8-16-30 sn, **rastgele sapma (jitter) yok**: sunucu yeniden başlayınca bütün akışlar aynı anda kopar ve aynı saniyelerde geri bağlanır (`/sync?since` + `/events`, her biri 2 kullanıcı sorgusu + tekrar oynatma). `ApiClient` tek istekleri yeniden denemez (iyi). Kuyruk görevli başına sırayla gönderilir (iyi). | `live_sync.dart` `_defaultBackoff`, `admin-panel.html` `liveFail` |
| Ağır iş | xlsx raporları SXSSF ile akışla yazılıyor (bellek iyi) ama satırlar listeye yükleniyor ve 0.1 CPU'da 20+ sn sürüyor; süre boyunca bir havuz bağlantısını tutuyor. PDF yok. | `XlsxWriter.java`, `ReportController` |
| Giriş | bcrypt (maliyet 10) 0.1 CPU'da tek giriş ~1-5 sn, soğuk JVM'de ~18 sn; vardiya başında herkes aynı anda girerse sıraya girer. | koşu çıktıları |

## 7. Simüle edilmeyenler ve sınırlar

- **Neon'un otomatik ölçeklemesi (0.25 → 2 CU) ve sıfıra inip uyanması.** DB hep 0.25 CPU'daydı ve hiç uyumadı.
  Gerçekte boşta kalan Neon'un ilk sorguya uyanması birkaç yüz ms ile birkaç sn ekler. `minimum-idle=0` olduğu
  için 30 sn boşta kalan bağlantılar kapanır, yeniden açılır.
- **TLS.** İstemci ile Render arası ve Render ile Neon arası TLS yok. Neon bağlantısının kurulması (TLS + kimlik
  doğrulama) yerelde çok daha ucuz.
- **Render'ın vekil sunucusu**, paylaşımlı CPU'nun gerçek davranışı (0.1 CPU'nun "patlama" payı) ve Render'ın
  uyku/uyanma süresi. Burada `--cpus=0.1` katı bir CFS kotasıdır.
- **Mobil ağ.** Gecikme, kopma ve paket kaybı yok, yalnızca kesinti senaryosunda tam kopma var. Dart `http`'nin her
  istekte yeni bağlantı açması taklit edilmedi; üreteç bağlantıları yeniden kullanıyor.
- **Tarayıcı tarafı.** Panelin sekme görünürlüğüne göre yavaşlaması ve QR sayfasının statik dosyaları (GitHub
  Pages'ten gelir, backend'e yük değil).
- **Yük üretecinin konumu.** Tek makineden ve tek IP'den çalıştı. Vatandaşların IP'si X-Forwarded-For ile
  ayrıştırıldı.
- **Veri büyüklüğü.** 90 günlük ~65 bin kayıt. Bir yıllık veride BI ve geçmiş sorguları ~4 kat yavaşlar (§3.6).
- **Ana makine gürültüsü.** Başka projelerin konteynerleri ve başka bir agent'ın derlemeleri aynı makinede çalıştı
  (§1). Android emülatörü hiçbir koşu sırasında açık değildi.
- **Ölçüm için açılan günlükler.** Hikari DEBUG ve `-Xlog:gc` küçük bir ek yük getiriyor.
