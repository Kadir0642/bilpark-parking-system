# BilPark yük testi

Bu çalışmada canlıdaki kaynak sınırlarının (Render Free, Neon Free) yerel bir kopyası kuruldu. Saha uygulamasını,
yönetim panelini ve vatandaş QR sayfasını gerçek davranışlarıyla taklit eden bir yük üreteci yazıldı ve bu
kopyaya karşı koşuldu. **Canlı sisteme hiçbir istek gönderilmedi.** Komutlar ve dosyalar:
[`loadtest/README.md`](../loadtest/README.md). Ham çıktılar: `loadtest/results/`.

**Tekrarlanabilirlik:** Bağımsız ve kısa bir yeniden koşu, ısınmadan sonraki temel alt kümede p95 ~1,9 sn ölçtü
(burada ~1,3 sn). 200 vatandaş / 60 sn için p95 0,47 sn ölçtü (burada 0,2 sn); hata her ikisinde de %0. Fark,
kısa koşudan ve ana makine yükünden geliyor. Gecikme sayılarını ±%50 bandında, hata ve kırılma noktası
sonuçlarını ise sağlam kabul edin.

Ölçüm tarihleri: 1. tur 26 Kasım 2026, `main` @ `4842938` (plaka sorgusu hız sınırı dahil). 2. tur 28 Kasım 2026, `main` @ `4a3ec19` (iyileştirmeler sonrası, §8).

## Kısa sonuç

> **2. tur (28 Kasım, `main` @ `4a3ec19`, iyileştirmeler sonrası): ayrıntı §8'de.**
>
> - **Gerçekçi yük (10 görevli) rahat dayanıyor.** p95 0,8 sn, hata %0. 1. turda aynı soğuk JVM koşulunda 15 sn ve
>   %2,9 idi. Farkın bir kısmı istemcinin artık ~%40 daha az istek atmasından geliyor (3,3 → 2,0 istek/sn).
> - **30 görevli:** p95 1,3 sn, hata %0 (1. turda 5,7 sn).
> - **Kırılma noktası 30-40'tan 40-50 görevliye çıktı.** Ayrıca havuz `DB_POOL_SIZE=5` ve
>   `DB_CONNECTION_TIMEOUT_MS=20000` yapılınca 50 görevlide de hata %0 oldu. Bu tek koşuda görüldü ve olası bir
>   neden; Render'a kalıcı uygulanmadan önce en az 2 kez tekrar ölçülmeli (§8.6).
> - **Oturumdan atılan görevli:** bütün koşularda 0.
> - **400 vatandaş / 60 sn:** p95 0,2 sn, hata %0.
> - **Kesinti kuyruğu:** 10 görevliyle düzeldi (hata %0,06, 113 sn'de boşaldı). 30 görevliyle hâlâ çöküyor
>   (hata %64).
> - **Soğuk açılış:** 261 sn'den 69,8-83,1 sn'ye indi.
> - **Ücretli katman:** 1 CPU / 2 GB'ta tavan ~150 görevli, açılış 7 sn.
> - **Düzeltme:** iki turda da koşular arasında aktif araç ve açık vardiya birikmişti. 1. turun geç koşuları
>   kötümser (§8.0).
>
> Aşağıdaki özet 1. turundur (26 Kasım, eski kod).

**Gerçekçi yük (10 görevli + 2 yönetici + vatandaşlar): dayanıyor ama riskli.** Sıcak JVM'de hata %0, p95 2,5 sn.
Risk üç durumda ortaya çıkıyor:
- deploy ya da yeniden başlatmadan sonraki ilk 3-4 dakika: p95 15 sn'yi aşıyor, istek hatası ~%3;
- xlsx raporlarıyla aynı döneme düşen yığın tükenmesi: ~20 sn duruş;
- ağ kesintisinden sonra kuyrukların boşaltılması: 10 görevliyle bile çöküyor.

| | 10 görevli (gerçekçi) | 30 görevli (3 kat) | 100 görevli (10 kat) |
|---|---|---|---|
| p95 | 2,5 sn | 5,7 sn | 18,6 sn (çoğu 15 sn zaman aşımı) |
| hata | %0 | %0 | %87 |
| RSS (512 MB sınır) | 424 MB | 447 MB | 484 MB |

- **Kırılma noktası 30 ile 40 eşzamanlı görevli arasında.** 40 görevlide hata %59. İlk bozulan, uygulamanın
  0.1 CPU'su. Ardından 5'lik DB havuzu doluyor (190+ bekleyen), 128 MB yığın tam GC'ye giriyor ve DB'ye
  ulaşamayan istekler **geçerli oturumlara 401** dönüyor. Bu 401'ler görevliyi uygulamadan atıyor ve yeniden
  giriş (bcrypt) yükü daha da artırıyor.
- **0.5 CPU tek başına çözmüyor.** 30 görevlide p95 aynı kaldı (5,8 sn), kırılma yine 30-50 arasında; darboğaz
  havuza, DB'ye ve yığına kaydı. Açılışı ise 260 sn'den 36 sn'ye indirdi.
- **400 eşzamanlı vatandaş sorunsuz.** 60 sn'lik dalgada `/parking/debt` p95 1,0 sn, hata %0. Farklı IP'lerle hız
  sınırı devreye girmedi; tek IP'den 200 kişide 120/dk sınırının üstü 429 aldı.
- **Veri doğruluğu her koşuda tam.** Çift aktif plaka, çift istek kimliği ve tutarsız kayıt sıfır. Aşırı yük ve
  zaman aşımı altında da `checkInRequestId` idempotentliği tuttu.
- **Asıl çarpan okuma çoğaltması.** Yazma başına ~36 okuma var: her araç olayında bölgedeki her görevli 4 uç,
  her panel 5 BI ucu çekiyor. En etkili ücretsiz düzeltmeler bu çoğaltmayı azaltmak ve aşırı yükte 401 yerine
  503 dönmek (§5).
- **Hızlı okuma için:** tablolar §3'te, darboğazlar §4'te, öneriler §5'te, simüle edilmeyenler §7'de.

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
| d2 kesinti sonrası kuyruk: 10 görevli × ~22 giriş | 4.9 | 26.8 | 13.0 sn | 25.4 sn | 33.5 sn | 15.0 sn | 0.3 sn | 4.8 / 10.4 | 449 | 10 | 188 | 236 | 13 / 77 |
| e dayanıklılık: 10 görevli, 35 dk (JVM yeniden başlatılmış) | 3.4 | 0.17 | 0.5 sn | 5.0 sn | 12.3 sn | 4.5 sn | 0.2 sn | 4.6 / 12.1 | 441 | 20 | 32 | 0 | 17 / 82 |

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

**İlk bozulan uygulamanın 0.1 CPU'su.** DB değil. Bu koşuda DB CPU'su ortalama %8,3'te kaldı (sınır %25; anlık
tepe %28) ve en çok 7 bağlantı görüldü. Uygulama ise sürekli kotasının sınırındaydı. Çöküş şu zincirle ilerliyor:
1. CPU doyuyor ve istekler yavaşlıyor.
2. OSIV ve `DELAYED_ACQUISITION_AND_HOLD` yüzünden her istek DB bağlantısını yanıt bitene kadar tutuyor; 5'lik
   havuz doluyor ve 190'dan fazla istek bekliyor.
3. Bağlantı 20 sn'de alınamıyor ve `CannotCreateTransactionException` oluşuyor.
4. Bekleyen istekler 128 MB'lık yığını dolduruyor. Kırılma koşusunda 37 tam GC oldu. Her biri dünyayı durdurdu;
   en uzunu 2,5 sn sürdü, 100 görevli koşusunda 4,0 sn.
5. Bu arada `JwtAuthFilter` kullanıcıyı DB'den okuyamayınca token'ı geçersiz sayıyor ve **geçerli oturumlara 401
   dönüyor.** Uygulama 401'de görevliyi giriş ekranına atıyor, yönetim paneli de oturumu kapatıyor. Yeniden giriş
   bcrypt demek, 0.1 CPU'da tek giriş 1-15 sn sürüyor. Yani aşırı yük kendini büyütüyor.

   Sayılar: kırılma koşusunda (`k`) 2.727 zaman aşımının yanında 28 adet 401, 3 adet 500 ve 14 adet 400 döndü. Kesinti
   koşusunda (`d`) 190 adet 401, 38 adet 500 ve 55 adet 400 döndü. 500'ler `GlobalExceptionHandler`'ın veritabanı hatası
   yanıtı: app günlüğünde aynı anlarda `Connection is not available` / `Database error` satırları var. **400'lerin
   nedeni açıklanamadı.** Hepsi yalnızca aşırı yükte ve yalnızca panel uçlarında (`spots`, `bi/*`) görüldü; büyük olasılıkla havuz hatasının
   başka bir istisna türüne sarılıp 400'e eşlenmesi, ama doğrulanmadı.

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

### 3.4 Kesinti sonrası kuyruk boşaltma: dayanmıyor (30 ve 10 görevliyle)

- 2 dk'lık kesintide 30 görevlinin kuyruğunda toplam 656 giriş birikti. Ağ geri gelince hepsi 0-2 sn içinde
  akışı açtı ve kuyruklarını sırayla göndermeye başladı.
- Her başarılı giriş bölgedeki bütün görevlilere bir okuma dalgası daha yolladı. Sonuç: girişlerin %88'i 15 sn'de
  bitmedi, panele ve akışa 401'ler döndü (31 akış 401 ile kapandı). Koşu bitene kadar, yani 249 sn içinde,
  kuyruklar boşalmadı.
- **Veri doğruluğu bozulmadı.** Çift aktif plaka 0, aynı `checkInRequestId` ile iki kayıt 0, kaydı olmayan istek
  kimliği 0, girişten önce çıkış 0 (`results/*-dogruluk.txt`). İstemcinin zaman aşımına düşüp aynı kimlikle
  yeniden gönderdiği istekler sunucuda tek kayıt üretti. Yani `checkInRequestId` idempotentliği aşırı yükte de
  tuttu.
- **Gerçekçi ölçekte de dayanmıyor (`d2`, 10 görevli × ~22 = 219 giriş).** Ağ geri geldikten sonraki 10 sn'de
  hata %75'e çıktı. Sonraki 220 sn boyunca 10 sn'lik p95 çoğunlukla 15-39 sn arasındaydı, hata çoğu pencerede %30-85 oldu.
Arada birkaç pencere toparlandı (hata %0-7). Koşu bitene
  kadar (218 sn) kuyruğun ancak üçte biri onaylandı (78 + 1 yarış). Burada da çift kayıt 0. Aynı istek kimliğiyle
  aynı anda gönderilen iki istekte (yanıt kaybı yarışı) tek kayıt oluştu ve ikinci isteğe aynı kayıt ya da 409
  döndü (`raceSame=1`, `raceWrong=0`). Neden: her kuyruk girişi bölgedeki görevlilerde ve iki panelde okuma
  dalgası yaratıyor. Bir de 15 sn'de zaman aşımına düşen girişler sunucuda yine de işleniyor, istemci onları
  sonra yeniden gönderiyor; yani iş iki kez yapılıyor.

### 3.5 SSE teslimi

Sistem çökmediği sürece olaylar beklenen alıcıların hepsine 0,1-0,3 sn'de (p95) ulaştı. 30 görevlide 251 olayın
251'i bütün alıcılara gitti. Akış kendisi darboğaz değil: `SseEmitter` iş parçacığı tutmuyor, SSE yolu OSIV
dışında. Aşırı yükte sorun teslimde değil, akışı açmakta: bilet, `/sync` ve kullanıcı sorgusu havuz bekliyor,
`hello` süresi 30-56 sn'ye çıktı.

### 3.6 Dayanıklılık (35 dk, 10 görevli, yeniden başlatılmış JVM ile)

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

### 3.7 Soğuk başlatma

`coldstart.sh`: konteyner başlatılır, ilk `/api/health` 200'e kadar geçen süre ölçülür, ardından ilk giriş (admin)
ve ilk DB'li okuma (`/parking/spots`). Ham satırlar `results/f-soguk.txt`'te.

| ölçüm | app CPU | DB | Spring "Started" | ilk `/api/health` | ilk giriş | ilk DB okuması |
|---|---|---|---|---|---|---|
| 1. oturum, ilk açılış | 0.1 | boş, Flyway V1-V11 | 199 sn | 227 sn | +18 sn | +3 sn |
| 2. oturum, ilk açılış ⚠ | 0.1 | boş, Flyway V1-V11 | 404 sn | 435 sn | +20 sn | +3 sn |
| göçü yapılmış DB (1) | 0.1 | V11, 65 bin kayıt | 245 sn | ~263 sn¹ | +11 sn | +7 sn |
| göçü yapılmış DB (2) | 0.1 | V11 | 237 sn | 261 sn | +12 sn | +7 sn |
| göçü yapılmış DB | **0.5** | V11 | **32 sn** | **36 sn** | +1,6 sn | +1,0 sn |

⚠ Docker Desktop yeni açılmıştı ve başka projelerin 10 konteyneri aynı anda kalkıyordu; ölçüm kirli.
¹ Betik hatası yüzünden süre `app` günlüğündeki zaman damgalarından okundu. Bu ölçümdeki giriş, uygulama 8 dk
boşta kaldıktan sonra yapıldı.

0.1 CPU'da uygulama **~4,5 dakikada** isteklere cevap vermeye başlıyor. Açıldıktan sonra da ilk giriş 11-20 sn,
ilk DB okuması 3-7 sn sürüyor. Render'ın sağlık denetimi bu sürede zaman aşımına uğruyorsa deploy başarısız
sayılabilir. 0.5 CPU'da açılış 7 kat kısa: CPU kotası ve JIT ısınması doğrusal ölçeklenmiyor.

### 3.8 CPU duyarlılığı: 0.5 CPU

`docker compose` içinde `APP_CPUS=0.5` ile; DB, ağ ve senaryo aynı.

| koşu | istek/sn | hata % | p50 | p95 | p99 | app CPU ort/max (%, sınır 50) | DB CPU ort/max (%, sınır 25) | full GC | havuz bekleyen max | havuz zaman aşımı | ana makine CPU ort/max |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 30 görevli, 3 yönetici, 0.1 CPU (`b`) | 15.3 | 0 | 1,2 sn | 5,7 sn | 7,6 sn | 7,8 / 11,2 (sınır 10) | 11,7 / 25,9 | 2 | 94 | 0 | 19 / 82 |
| 30 görevli, 3 yönetici, **0.5 CPU** (`b05`) | 14.5 | 0 | 1,3 sn | 5,8 sn | 10,1 sn | 12,9 / 48,3 | 15,5 / 28,0 | 8 | 73 | 0 | 16 / 77 |
| basamak 30→50→75→100, 0.5 CPU (`k05`) | 15.2 | 64.2 | 15,0 sn | 60,0 sn | 60,0 sn | 24,3 / 50,5 | 8,9 / 25,1 | 883 | 197 | 493 | 17 / 79 |

`k05` basamakları (her basamağın ilk 30 sn'si hariç): 30 görevli p95 medyanı 7,2 sn ve hata %0; **50 görevli hata
%84**; 75 ve 100 görevli hata ~%100.

**5 kat CPU ne gecikmeyi ne de kırılma noktasını değiştirdi.** 30 görevlide p95 aynı (5,7 → 5,8 sn) ve uygulama
CPU'su sınırının dörtte birinde kaldı. Çöküş yine 30 ile 50 görevli arasında. Bu durumda darboğaz şunlara kaydı:
- **5 bağlantılık havuz ve ~20 ms'lik DB gidiş-dönüşleri:** 73-197 istek bağlantı bekledi. Her istek birkaç sorgu
  ve OSIV yüzünden yanıt bitene kadar bağlantıyı tutuyor.
- **0.25 CPU'luk DB:** 30 görevlide DB CPU'su %28'e kadar çıktı; sınırı %25.
- **128 MB'lık yığın:** kırılma koşusunda 883 tam GC oldu. CPU arttığı için her biri kısa sürdü (en uzunu 0,3 sn),
  ama sürekli GC yapıldı.

Sonuç: ücretli bir CPU artışı tek başına bir şey kazandırmıyor. Önce olay başına okuma sayısı (öneri 1-3),
bağlantının tutulma süresi (öneri 5) ve yığın (öneri 6) ele alınmalı. Ardından havuz büyütülebilir: Neon ~104
bağlantıya izin veriyor; `maximum-pool-size` 10-15 ücretsiz bir deneme.

### 3.9 Veritabanı

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
- En çok çağrılan sorgular ucuz ama sayıca çok (30 görevli, 10 dk, 9.164 HTTP isteği):
  - `zones` id ile 24.273 kez: kayıt ve araç listelerinde bölge satır satır yükleniyor (N+1);
  - vardiya cirosu 16.102 kez: `bi/shifts` N+1'i, yenileme başına ~30;
  - `users` 8.852 kez: kimlikli her istekte `JwtAuthFilter` bir kez okuyor, işlem uçlarında `currentUser` bir
    kez daha.

  Her biri ayrı bir Neon gidiş-dönüşü (~20 ms) ve bu süre boyunca bağlantı tutuluyor.

### 3.10 İstek dağılımı (30 görevli, 10 dk)

9.164 isteğin yalnızca 251'i yazma (giriş/çıkış/iptal/kaçak), yani **yazma başına ~36 okuma** var. Okumaların
dağılımı:
- %61'i görevlilerin olaya tepki okumaları: `/parking/filter` 2.495, `/vehicle-types` 1.315, `/parking/history`
  1.255, `/sync?since` 548.
- %29'u 3 yönetim paneli oturumunun her araç olayındaki 5 BI ucu (2.695).

Yük, görevli sayısıyla değil "görevli sayısı × bölgedeki görevli sayısı" ile büyüyor: aynı 6 sahada 10 görevli
3,5 istek/sn üretirken 30 görevli 15,3 istek/sn üretti (3 kat görevli, ~4,3 kat istek).

## 4. Darboğazlar (önem sırasıyla)

1. **Uygulamanın 0.1 CPU'su.** 0.1 CPU'daki her senaryoda ilk doyan kaynak bu. 0.5 CPU'da sıra havuza, DB'ye ve yığına geçiyor (§3.8). Sorun dört şeyin üst üste binmesi:
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
5. **Soğuk başlangıç ve soğuk JVM.** 0.1 CPU'da açılış dakikalar sürüyor (§3.7). Açılıştan sonraki ilk 3-4 dk'da
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
| 4 | **Aşırı yükte oturum düşürme:** `JwtAuthFilter`'da DB/havuz hatasını "geçersiz token"dan ayır ve 503 dön. İstemci 401'de oturumu kapatsın, 503/zaman aşımında beklesin. Kullanıcı ayrıntılarını kısa süre (ör. 30-60 sn) önbelleğe al: istek başına 1-2 kullanıcı sorgusu çoğunlukla 0'a iner. **Güvenlik notu:** önbellek, `isEnabled` (pasife alma) ve `issuedBeforePasswordChange` (şifre değişince eski token'ın düşmesi) denetimlerini önbellek süresi kadar geciktirir. Bu yüzden pasife alma ve şifre değişikliği/sıfırlamada o kullanıcının kaydı önbellekten hemen silinmeli. | `auth/JwtAuthFilter.java`, `auth/CustomUserDetailsService.java`, `controller/*` (`currentUser`), `mobile/lib/core/api_client.dart` | Çöküşteki kendini büyüten döngü (401 → yeniden giriş → bcrypt) kırılır | ücretsiz |
| 5 | **Bağlantıyı istek boyunca tutma:** `spring.jpa.open-in-view` zaten kapalı; elle eklenen `OpenEntityManagerInViewInterceptor`'ı kaldır ve tembel ilişkileri sorguda `join fetch` ile çöz. Ya da `HibernateJpaVendorAdapter`'da bağlantıyı işlem sonunda bırak (`DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`). Ardından `maximum-pool-size`'ı 10-15'e çıkarmayı dene (Neon ~104'e izin veriyor). **Risk:** OSIV kalkınca, yanıt serileştirilirken açılan tembel ilişkiler `LazyInitializationException` verir; bütün uçlar (özellikle kayıt listeleri, raporlar, `bi/*`) testle denenmeli. Render deploy sırasında eski ve yeni örnek kısa süre birlikte çalışır: havuz 10 ise 2 × 10 = 20 bağlantı eder, yine Neon sınırının çok altında. | `config/OpenEntityManagerInViewConfig.java`, ilgili repository sorguları, `application.properties` | Havuz bağlantısı JSON serileştirme ve xlsx üretimi boyunca tutulmaz; 5'lik havuz çok daha geç dolar | ücretsiz |
| 6 | **JVM bayrakları:** `-XX:+UseSerialGC -Xss512k -XX:MaxRAMPercentage=40 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1` (açılış ve 0.1 CPU için C1). Spring Boot'un CDS/AOT önbelleğiyle (`-XX:SharedArchiveFile`, eğitim koşusu Dockerfile'da) açılışı kısalt. Bayraklar 512 MB'ta RSS'i ölçerek ayarlanmalı. | `backend/Dockerfile` | Daha az tam GC (dayanıklılık koşusunda iki kez 8'er tam GC ile ~20 sn duruş görüldü), daha kısa açılış (CDS'nin etkisi burada ölçülmedi), sınıra yakın RSS kontrol altında. xlsx raporlarında satırları listeye toplamak yerine akışla yazmak (`Stream`/sayfalı sorgu) yığın baskısını da azaltır | ücretsiz |
| 7 | **Eksik dizinler ve BI sorguları:** yeni migration (V12+) ile `parking_records (street, entry_time DESC)` ve `parking_records (entry_time DESC)`, ciro için `(status, exit_time) INCLUDE (fee)`. `bi/shifts` N+1'ini tek `GROUP BY collected_by_id, device_id` sorgusuna çevir. Tipe göre ciroyu kısa süre önbelleğe al. | `backend/src/main/resources/db/migration/V12__...sql`, `repository/ParkingRecordRepository.java`, `controller/ParkingController.java` | `history` ve son 100 kayıt Seq Scan'den dizin taramasına iner (90-100 ms → birkaç ms); büyüyen tabloda doğrusal artış durur | ücretsiz |
| 8 | **Kuyruk boşaltmayı ve yeniden bağlanmayı yumuşat:** kuyruk gönderiminde girişler arasına 0,5-1 sn bekleme koy, başlangıca 0-10 sn rastgele gecikme ekle. Kuyruk kaynaklı girişler için olayları sunucuda toplu yayınla (ya da istemci kuyruk boşalırken olay başına değil sonunda bir kez yenilesin). Kuyruk isteğinin zaman aşımını uzat (ör. 30 sn): 15 sn'de vazgeçilen istek sunucuda yine de işleniyor ve sonra yeniden gönderiliyor. Üstel beklemeye ±%50 jitter ekle. | `mobile/lib/core/entry_queue.dart`, `mobile/lib/core/live_sync.dart`, `mobile/lib/screens/parking_map_screen.dart`, `web/admin-panel.html` (`liveFail`) | Gerçekçi ölçekte bile (10 görevli × 20 giriş) çöken kesinti sonrası boşaltma dağılır; aynı işin iki kez yapılması azalır | ücretsiz |
| 9 | **Giriş fırtınası:** bcrypt maliyeti 10 kalsın; ama token süresi (24 sa) vardiyayı kapsıyor, gereksiz yeniden girişe yol açan 401'leri (öneri 4) kes. Vardiya başında uygulama girişleri birkaç saniyeye yaysın. | `mobile/lib/screens/auth_screen.dart`, öneri 4 | 0.1 CPU'da tek giriş 1-5 sn sürüyor, toplu girişte 15 sn'yi aşıyor | ücretsiz |
| 10 | **Daha fazla CPU:** Render'ın ücretli planı ya da başka bir barındırma. | — | 0.5 CPU'da açılış 260 sn'den 36 sn'ye indi. Ama 30 görevlide p95 değişmedi (5,7 → 5,8 sn) ve kırılma noktası yine 30-50 görevli arasında kaldı (§3.8). Öneri 1-6 olmadan tek başına yetmez | **ücretli** (karar kullanıcının) |

Öneri 1-4 birlikte, görevli başına okuma yükünü kabaca 3-5 kat düşürür (olay başına okuma 4+5 → 0-1+0,
`/sync` catch-up yok). Bu, 0.1 CPU'da da kırılma noktasını 30-40 görevlinin epey üstüne taşıyabilir; doğrulamak
için ölçmek gerekir.

## 6. Kod incelemesi (değişiklik yapılmadı)

| Konu | Bulgu | Kanıt |
|---|---|---|
| Hikari havuzu | (1. tur; 2. turda 10 / 5 sn) `maximum-pool-size=5`, `minimum-idle=0`, `idle-timeout=30s`, `connection-timeout=20s` (`application.properties`). Neon 0.25 CU sınırı ~104 bağlantı; sınırlayan Neon değil, 5'lik havuz. 30 sn boşta kalan bağlantı kapanır, yeniden açmak Neon'da TLS + kimlik doğrulama (+ uyanma) ister. | `application.properties` |
| Bağlantı istek boyunca tutuluyor | `OpenEntityManagerInViewInterceptor` (SSE hariç her istek) + Spring'in `HibernateJpaVendorAdapter` varsayılanı `hibernate.connection.handling_mode=DELAYED_ACQUISITION_AND_HOLD`: istek DB'ye ilk dokunduğu andan **yanıt bitene kadar** (JSON serileştirme, xlsx üretimi dahil) havuz bağlantısını tutar. 0.1 CPU'da istekler yavaş olduğu için 5 bağlantı çabuk doluyor. | `OpenEntityManagerInViewConfig.java`, spring-orm 7.0.5 sınıf sabitleri, Hikari günlüğü `active=5, waiting>0` |
| İstek başına kullanıcı sorgusu | Her kimlikli istekte `JwtAuthFilter` `loadUserByUsername`; işlem uçlarında controller `currentUser` aynı kullanıcıyı bir kez daha okur (30 görevlide 10 dk'da 8.852 `users` sorgusu). | `JwtAuthFilter.java`, `ParkingController.currentUser` |
| Görevli olay çoğaltması | Bölgedeki HER araç olayında bölgedeki HER görevli: harita `/parking/filter` (300 ms debounce) + gösterge `/parking/filter` + `/parking/history` + `/vehicle-types` (500 ms debounce). `/parking/filter` caddenin tamamını döner, istemci bölgeye süzer. | `parking_map_screen.dart`, `main.dart`, `dashboard_screen.dart` |
| HEARTBEAT catch-up | Görevli yalnızca kendi bölgesinin olaylarını alır ama HEARTBEAT (25 sn) en son GLOBAL sürümü taşır; başka bölgede olay olduysa her görevli her 25 sn'de `/sync?since=` çağırır (sunucu 500'lük sayfalarla tarar, görünmeyenleri atar). | `live_sync.dart` `_onFrame`, `SyncService.heartbeat` |
| Yönetim paneli | Her araç olayında (800 ms kısma) 5 uç: `spots`, `bi/income-by-type/details`, `bi/income-summary` (4 SUM), `bi/last-records` (son 100), `bi/shifts` (açık vardiya BAŞINA ayrı ciro sorgusu, N+1). | `admin-panel.html` `scheduleVehicleRefresh`, `ParkingController.getActiveShifts` |
| Eksik dizinler | `parking_records(street, entry_time)` yok: `/parking/history` 65 bin satırda Seq Scan ~90 ms (0.25 CPU). `parking_records(entry_time)` yok: son 100 kayıt ~99 ms. Ciro toplamları ve tipe göre ciro tüm tabloyu tarıyor (116-184 ms). Süre tablo büyüdükçe doğrusal artar. | `results/explain.txt` |
| JVM bayrakları | Dockerfile'da yok: 512 MB'ta varsayılan **128 MB yığın** (MaxRAMPercentage=25), **SerialGC**, C2 JIT. RSS 330-485 MB (koşulara göre): yığın dışı (metaspace, kod önbelleği, iş parçacıkları) büyük. Başlangıç 0.1 CPU'da 3-7 dk. | `java -XX:+PrintFlagsFinal`, `coldstart.sh` |
| Tomcat | Ayar yok: 200 iş parçacığı, 8192 bağlantı. SSE `SseEmitter` (async) iş parçacığı tutmaz; DB bağlantısı da tutmaz (SSE yolu OSIV dışı). Akış sayısı sınırlayıcı değil. | `SyncService.connect`, `OpenEntityManagerInViewConfig` |
| SSE yayını | `broadcast` yazan isteğin iş parçacığında, commit sonrası, bağlı bütün istemcilere sırayla `send` yapar (bağlantı hâlâ tutulurken). Az istemciyle sorun değil; yüzlerce akışta yazma isteğinin süresine eklenir. | `SyncService.broadcast` |
| İstemci yeniden bağlanma | Mobil ve panel üstel bekleme 1-2-4-8-16-30 sn, **rastgele sapma (jitter) yok**: sunucu yeniden başlayınca bütün akışlar aynı anda kopar ve aynı saniyelerde geri bağlanır (`/sync?since` + `/events`, her biri 2 kullanıcı sorgusu + tekrar oynatma). `ApiClient` tek istekleri yeniden denemez (iyi). Kuyruk görevli başına sırayla gönderilir (iyi). | `live_sync.dart` `_defaultBackoff`, `admin-panel.html` `liveFail` |
| Ağır iş | xlsx raporları SXSSF ile akışla yazılıyor (bellek iyi) ama satırlar listeye yükleniyor ve 0.1 CPU'da 2,5-30 sn sürüyor (dönem, kayıt ve personel raporu); süre boyunca bir havuz bağlantısını tutuyor. PDF yok. | `XlsxWriter.java`, `ReportController` |
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
- **Veri büyüklüğü.** 90 günlük ~65 bin kayıt. Bir yıllık veride BI ve geçmiş sorguları ~4 kat yavaşlar (§3.9).
- **Ana makine gürültüsü.** Başka projelerin konteynerleri ve başka bir agent'ın derlemeleri aynı makinede çalıştı
  (§1). Android emülatörü hiçbir koşu sırasında açık değildi.
- **Ölçüm için açılan günlükler.** Hikari DEBUG ve `-Xlog:gc` küçük bir ek yük getiriyor.

## 8. 2. tur (iyileştirmeler sonrası)

**Ölçülen kod:** `main` @ `4a3ec19`, yani backend #29 (503 + Retry-After, kullanıcı önbelleği, OSIV kapalı,
havuz 10, JVM bayrakları, tek seferde tek rapor), mobil #28 ve panel #27. 28 Kasım'daki `c6a9064`'te backend,
mobil ve web değişmedi.

**Ortam:** 1. turla aynı. App 0.1 CPU / 512 MB, DB 0.25 CPU / 1 GB / 104 bağlantı, DB'ye ~20 ms gecikme, aynı
tohum verisi. Ölçüm 28 Kasım'da yapıldı.

**Yük üreteci** (`CLIENT=new`, varsayılan) yeni istemcileri taklit eder:
- **Görevli:**
  - SSE olayı yerelde uygulanır. Sunucudan okuma yalnızca görünen sekme için yapılır (%70 cadde, %30 gösterge)
    ve en çok 2,5 sn'de bir birleştirilir. Kendi işleminden sonraki yenileme en çok 1,5 sn'de bir.
  - HEARTBEAT catch-up en çok 90 sn'de bir.
  - Yeniden bağlanma 1-45 sn arası, jitterli; Retry-After'a uyulur.
  - GET'te 503 gelirse en çok 3 kez yeniden denenir. 401 gelirse önce `/me` ile teyit edilir.
  - Kuyruk 0-20 sn rastgele gecikmeyle başlar, kayıtlar arasında 0,4-1,2 sn bekler, zaman aşımı 30 sn'dir. Hata
    olursa 2-60 sn üstel beklemeyle yeniden dener.
  - Başarısız çıkış ya da kaçak işlemi 10-20 sn sonra en çok 2 kez yeniden denenir.
- **Panel:** etkilenen bileşenler, bileşen başına en çok ~4 sn'de bir yenilenir. Giriş olayı gelir bileşenlerini
  çekmez. GET'te 503 gelirse en çok 2 kez yeniden denenir. Rapor 503 dönerse Retry-After kadar beklenip bir kez
  daha denenir.
- **Eski profil:** `CLIENT=old` 1. turdaki davranıştır. Bir karşılaştırma koşusunda yeni sunucuya karşı
  çalıştırıldı.

**Ana makine:** Ölçümün bir kısmında başka bir agent'ın backend testleri çalıştı. Ana makine CPU'su koşu
ortalamalarında %11-41, anlık tepelerde %94'e kadar çıktı (tablolarda var). Bir Android emülatörü açılınca
`seri.sh` koşu başlatmayı emülatör kapanana kadar bekletti. Hiçbir ölçüm emülatör açıkken yapılmadı.

### 8.0 Test hijyeni: iki turda da birikim vardı (düzeltme)

2. turun ortasında, aynı yükün koşudan koşuya ağırlaştığı fark edildi. İki neden bulundu:
- **Sahada biriken araçlar.** Her koşu, çıkışı yapılmamış araçları `active_park_spots`'ta bıraktı. Koşunun sonunda
  sayı 54'ten 2.183'e çıktı; bir caddede 1.152 aktif araç vardı. `/parking/filter` ve panelin `/parking/spots` ucu
  bütün caddeyi döndüğü için bu, 0.1 CPU'da JSON üretimini büyüttü. Gerçekte cadde başına onlarca araç olur.
- **Kapanmayan vardiyalar.** Her koşu yeni cihaz kimlikleriyle giriş yapıyor. 200 görevlilik koşudan sonra
  200 açık vardiya kaldı. Panelin `bi/shifts` ucu açık vardiya başına bir sorgu atıyor (N+1), bu da DB'yi
  yapay olarak yordu.

`run.sh` artık her koşudan önce açık vardiyaları kapatıyor; aktif araçları ve istek kimliklerini siliyor
(`KEEP_SPOTS=1` ile kapatılabilir). Bu düzeltmeden önceki ve sonraki etkilenen koşular `r2-x-...` adıyla ayrıldı,
kıyaslamada kullanılmadı. Önemli koşular temiz durumda yeniden koşuldu (`r2-k-kirilma`, `r2-d-kuyruk-30`,
`r2-c-50-tekrar`, `r2-b-30-tekrar`, `r2-old-30-tekrar`, `r2-b-30-dizinli`).

**1. tura etkisi:** 1. turdaki koşular da birikimle koşuldu. Koşu sonundaki aktif araç sayısı sırayla şöyle:
a 55, a2 99, b 238, k 420, v 489, d 682, c-100 790, e 879, d2 983, b05 1.136, k05 1.333.
- Erken koşular (a, a2, b, k) çoğunlukla temiz. Bu turla kıyas için bunlar kullanıldı.
- Geç koşuların (d, c-100, e, d2, b05, k05) mutlak sayıları **kötümser**.
- Özellikle 1. turdaki "0.5 CPU tek başına çözmüyor" sonucu (§3.8) güvenilir değil: b ~100-240 araçla, b05
  ~980-1.140 araçla koşuldu. Doğru CPU karşılaştırması için §8.5'e bakın.

### 8.1 Eski ve yeni karşılaştırması (app 0.1 CPU)

"Eski", 1. turdaki koşulardır: eski sunucu ve eski istemci. "Yeni", 2. turdaki koşulardır: yeni sunucu ve yeni
istemci. "Atılan görevli" yeni istemcide `/me` ile teyit edilmiş 401 sayısıdır. Eski istemci her 401'de oturumu
kapattığı için orada görevli uçlarına dönen 401 sayısı verildi.

| senaryo | eski p95 / hata | eski: görevliye 401 | yeni p95 / hata | yeni: atılan görevli | istek/sn eski → yeni |
|---|---|---|---|---|---|
| 10 görevli, 15 dk, yeniden başlatmadan hemen sonra | 15,0 sn / %2,9 | 1 | **0,8 sn / %0** | 0 | 3,3 → 2,0 |
| 10 görevli, sıcak JVM (1. tur `a2`) | 2,5 sn / %0 | 0 | (yukarıdaki satır soğuk JVM'i de içeriyor) | | |
| 30 görevli, 10 dk | 5,7 sn / %0 | 0 | **1,3 sn / %0**⁴ | 0 | 15,3 → 6,4 |
| 50 görevli, 10 dk | 48 sn / %85¹ | – | 19,8 sn / %43⁵ | 0 | 11,4 → 12,6 |
| basamak 10→20→30→40→50 | 40'ta çöktü | 1 | **50'de çöktü** (40'ta p95 1,2 sn, %0) | 0 | |
| vatandaş dalgası 400 kişi / 60 sn (+10 görevli + 2 panel) | 1,0 sn / %0 | 0 | **0,2 sn / %0**² | 0 | |
| kesinti kuyruğu 10 görevli × ~22 giriş | 25,4 sn / %26,8, 218 sn'de bitmedi³ | 3 | **4,5 sn / %0,06, 113 sn'de bitti**² | 0 | 4,9 → 3,3 |
| kesinti kuyruğu 30 görevli × ~22 giriş | 31,0 sn / %38,6, bitmedi³ | 31 | 15,0 sn / %64, 288 sn'de bitmedi | 0 | 7,7 → 12,9 |
| dayanıklılık 30-35 dk, 10 görevli | 5,0 sn / %0,17, 2 kez ~20 sn duruş³ | 0 | **2,0 sn / %0**, duruş yok² | 0 | 3,4 → 2,2 |
| soğuk açılış, 0.1 CPU | 261 sn (göçü yapılmış DB) | – | **69,8-83,1 sn**⁶ | – | – |

- ¹ 1. turdaki basamak koşusunun 50 görevli basamağı.
- ² Bu koşu birikim düzeltmesinden önce yapıldı. Başlangıçta 400-800 aktif araç vardı, yani sonuç kötümser ve
  gerçek durum daha iyi.
- ³ 1. tur geç koşusu; o da birikimle koşuldu (yukarıya bakın).
- ⁶ `results/f2-soguk.txt`'teki 6 ölçüm: boş DB 78,9 ve 69,8 sn; göçü yapılmış DB 83,1, 75,0 ve 72,7 sn; havuz denemesi 71,1 sn.
- ⁴ Temiz tekrar (`r2-b-30-tekrar`): 10 dk boyunca 10 sn'lik p95 0,9-2,1 sn. İlk koşu (`r2-b-30`, 54 araçla) p95 8,4 sn ve %5,0 hatayla bitti. O koşuda hataların hepsi tek bir 60 sn'lik pencerede (250-310. sn)
  oldu. Bu pencere dışında 10 sn'lik p95 ~1,5 sn ve hata %0. O pencerede app CPU'su düşüktü, DB CPU'su %20-23'e
  çıktı (sınır %25). DB'nin yavaş sorgu günlüğünde `parking_records` taramaları vardı (son 100 kayıt ve geçmiş,
  0,4-2,8 sn). Aynı dakikada 3 panelin tam yenilemesi ve vatandaş tepesi üst üste geldi.
- ⁵ `r2-c-50-tekrar`, temiz durumda. İlk 5 dakika dayandı (10 sn'lik p95 1-9 sn, hata ≤%7), 300. saniyeden
  sonra çöktü. Ana makine CPU'su bu koşuda anlık %100'e çıktı ⚠. Birikim düzeltmesinden önceki ilk koşu
  (`r2-c-50`, 194 araçla) %74 hatayla bitti. O koşuda 503'ten sonraki yeniden denemeler yükü çoğalttı:
  `/parking/filter` 5.976 kez çağrıldı ve bunların 4.505'i 503'tü.

Ham tablo: `python -I loadtest/ozet.py loadtest/results --tablo <koşu adları>`. Koşu başına bütün sütunlar
aşağıdaki ayrıntı tablosunda.

Ayrıntı (app 0.1 CPU; `p05` 0.5 CPU, `p1` 1 CPU / 2 GB):

| koşu | başlangıçta aktif araç | istek/sn | hata % | p50 | p95 | p99 | giriş (check-in) p95 | SSE p95 | app CPU ort/max (%, sınır 10) | RSS max MB | full GC | havuz bekleyen max | havuz zaman aşımı | 503 | oturumdan atılan görevli | ana makine CPU ort/max |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r2-a-temel-10 | 0 | 2.04 | 0 | 0.3 sn | 0.8 sn | 1.5 sn | 0.9 sn | 0.2 sn | 2.1 / 10.8 | 337 | 3 | 0 | 0 | 0 | 0 | 35 / 94 |
| r2-b-30 | 54 | 6.46 | 5.01 | 0.4 sn | 8.4 sn | 17.2 sn | 10.1 sn | 0.4 sn | 4.9 / 10.2 | 378 | 1 | 104 | 196 | 183 | 0 | 29 / 83 |
| r2-b-30-tekrar | 0 | 6.44 | 0 | 0.3 sn | 1.3 sn | 1.9 sn | 1.1 sn | 0.2 sn | 5.0 / 10.3 | 445 | 0 | 6 | 0 | 0 | 0 | 18 / 96 |
| r2-c-50 | 194 | 18.88 | 74.41 | 6.6 sn | 15.0 sn | 19.4 sn | 13.6 sn | 1.0 sn | 9.9 / 13.7 | 466 | 4 | 137 | 980 | 8030 | 0 | 19 / 92 |
| r2-c-50-tekrar | 0 | 12.61 | 43.39 | 5.6 sn | 19.8 sn | 34.9 sn | 24.3 sn | 1.1 sn | 8.9 / 15.7 | 468 | 2 | 123 | 973 | 2642 | 0 | 35 / 100 |
| r2-k-kirilma | 0 | 6.58 | 24.76 | 0.6 sn | 10.3 sn | 15.0 sn | 8.3 sn | 0.9 sn | 5.6 / 11.8 | 456 | 0 | 112 | 974 | 1187 | 0 | 11 / 41 |
| r2-v-dalga | 419 | 4.18 | 0 | 0.1 sn | 1.4 sn | 1.7 sn | 0.5 sn | 0.1 sn | 2.9 / 9.6 | 467 | 0 | 0 | 0 | 0 | 0 | 34 / 90 |
| r2-d-kuyruk-10 | 471 | 3.25 | 0.06 | 0.8 sn | 4.5 sn | 14.7 sn | 3.9 sn | 0.9 sn | 3.1 / 10.6 | 453 | 1 | 26 | 2 | 1 | 0 | 23 / 73 |
| r2-d-kuyruk-30 | 0 | 12.86 | 63.94 | 6.3 sn | 15.0 sn | 18.0 sn | 12.7 sn | 6.7 sn | 6.3 / 11.7 | 467 | 2 | 169 | 966 | 4200 | 0 | 16 / 80 |
| r2-e-dayaniklilik | 808 | 2.16 | 0 | 0.4 sn | 2.0 sn | 2.9 sn | 0.8 sn | 0.2 sn | 3.3 / 11.2 | 470 | 2 | 0 | 0 | 0 | 0 | 33 / 90 |
| r2-old-30 | 678 | 13.05 | 23.09 | 4.1 sn | 12.7 sn | 23.8 sn | 9.2 sn | 0.8 sn | 10.1 / 11.6 | 470 | 2 | 118 | 1004 | 1766 | 0 | 41 / 82 |
| r2-p05-temel-10 | 875 | 2.12 | 0 | 0.2 sn | 1.4 sn | 1.7 sn | 0.5 sn | 0.1 sn | 3.4 / 24.8 | 300 | 2 | 0 | 0 | 0 | 0 | 17 / 90 |
| r2-p1-temel-10 | 933 | 2.25 | 0 | 0.2 sn | 1.4 sn | 1.6 sn | 0.5 sn | 0.1 sn | 3.0 / 13.2 | 299 | 2 | 0 | 0 | 0 | 0 | 22 / 85 |
| r2-p1-kirilma | 981 | 31.86 | 24.4 | 3.4 sn | 15.0 sn | 37.0 sn | 30.0 sn | 0.2 sn | 35.6 / 79.9 | 719 | 34 | 191 | 0 | 0 | 0 | 20 / 76 |
| r2-old-30-tekrar | 0 | 15.63 | 1.89 | 1.4 sn | 6.9 sn | 15.0 sn | 5.4 sn | 0.5 sn | 8.5 / 11.2 | 456 | 0 | 92 | 191 | 177 | 0 | 13 / 66 |
| r2-b-30-dizinli | 0 | 6.28 | 0 | 0.2 sn | 1.3 sn | 1.9 sn | 0.9 sn | 0.2 sn | 4.6 / 10.6 | 445 | 0 | 0 | 0 | 0 | 0 | 28 / 94 |
| r2-k-kirilma-havuz5-20sn | 0 | 5.91 | 0 | 0.5 sn | 3.3 sn | 7.7 sn | 2.5 sn | 0.3 sn | 5.5 / 11.1 | 343 | 2 | 31 | 0 | 0 | 0 | 11 / 85 |

**Veri doğruluğu her koşuda tam.**
- Çift aktif plaka, aynı `checkInRequestId` ile iki kayıt, ücretsiz PAID ve girişten önce çıkış: bütün koşularda 0.
- `r2-k-kirilma` ve `r2-d-kuyruk-30`'daki "istek kimliği var ama kayıt yok" satırı sıfırlamanın yan etkisi:
  koşu başında silinen eski araçlara ait kimlikler. Koşuların kendi kimlikleri için ayrı sorgu 0 verdi
  (`results/r2-dogruluk-notu.txt`).
- Kesinti koşusunda (`d-10`) yanıtı kaybolmuş sayılıp aynı `checkInRequestId` ile yeniden gönderilen 30 girişin
  30'unda aynı kayıt döndü. Aynı kimlikle eşzamanlı gönderilen ikililer (10 + 8) ikinci kayıt açmadı.

**Oturumdan atılma bütün koşularda sıfır.** Aşırı yükte sunucu yer yer 401 döndü (50 görevlide 13, 30 görevli
kuyrukta 13). Yeni istemci bunların hepsini `/me` ile teyit etti ve hiçbirinde oturumu kapatmadı.

### 8.2 Kırılma noktası: 40 ile 50 görevli arası

`r2-k-kirilma` temiz durumda koşuldu. Her basamak 150 sn sürdü, ilk 30 sn'si oturma süresi sayılıp çıkarıldı:

| eşzamanlı görevli | 1. tur: istek/sn, p95 medyanı, hata | 2. tur: istek/sn, p95 medyanı, hata |
|---|---|---|
| 10 | 3,4 / 1,1 sn / %0 | 1,7 / 0,5 sn / %0 |
| 20 | 9,2 / 1,1 sn / %0 | 3,9 / 0,8 sn / %0 |
| 30 | 12,9 / 1,6 sn / %0 | 5,8 / 1,1 sn / %0 |
| 40 | 16,5 / **24 sn / %59** | 7,9 / 1,2 sn / %0 |
| 50 | 11,4 / 48 sn / %85 | 14,7 / **13,4 sn / %73** |

- 1. turun kırılma koşusu ~240-420 aktif araçla, 2. turunki sıfırdan başladı; 1. tur değerleri biraz kötümser.
- İstek sayısı görevli başına yaklaşık yarıya indi, kırılma noktası 30-40'tan 40-50'ye çıktı. Havuz ayarıyla
  (§8.6) 50 görevlide de hata çıkmadı.
- 50 görevlide ilk bozulan yine 0.1 CPU. App CPU'su sürekli kotasının sınırındaydı.
- 503'ten sonra yapılan yeniden denemeler yükü çoğalttı: 50. basamakta istek/sn 7,9'dan 14,7'ye fırladı.
  `r2-c-50`'de `/parking/filter` 5.976 kez çağrıldı ve bunların 4.505'i 503'tü.

### 8.3 Eski istemciler yeni sunucuda (geçiş dönemi riski)

30 görevli eski uygulamayla (`CLIENT=old`) yeni sunucuya karşı koşuldu. Bu, telefonlar yeni APK'ya geçene
kadarki durumu temsil ediyor. Koşu temiz durumda yapıldı (`r2-old-30-tekrar`).

| 30 görevli, 3 panel, 10 dk | istek/sn | p95 | hata | 503 | havuz zaman aşımı | atılan görevli | ana makine CPU ort/max |
|---|---|---|---|---|---|---|---|
| eski sunucu + eski istemci (1. tur `b`) | 15,3 | 5,7 sn | %0 | 0 | 0 | 0 (görevliye 401: 0) | 19 / 82 |
| yeni sunucu + **eski** istemci (`r2-old-30-tekrar`) | 15,6 | 6,9 sn | **%1,9** | 177 | 191 | 0 | 13 / 66 |
| yeni sunucu + yeni istemci (`r2-b-30-tekrar`) | 6,4 | **1,3 sn** | **%0** | 0 | 0 | 0 | 18 / 96 |

- İyileştirmenin büyük kısmı istemcide. Yeni istemci aynı işi yaklaşık 2,4 kat daha az istekle yapıyor ve p95 yaklaşık 5 kat
  düşüyor.
- Eski istemciler yeni sunucuda eskisi kadar hızlı, ama hata görüyor. Yeni sunucu havuzda 5 sn bekleyip 503
  dönüyor; eski sunucu 20 sn bekliyordu. Bu yüzden yoğunlukta gecikmenin bir kısmı "işlem başarısız"a dönüşüyor
  (%1,9). Oturum düşmesi yok, çünkü 503 401 değil.
- (Birikim düzeltmesinden önceki ilk deneme `r2-old-30` ~680 aktif araçla başladı ve %23 hata verdi. Kıyasta
  kullanılmadı.)
- **Öneri:** APK güncellemesini hızlandır. Geçiş süresince istenirse `DB_CONNECTION_TIMEOUT_MS` yükseltilebilir;
  ortam değişkeni, kod gerekmiyor. Etkisi §8.6'da.

### 8.4 Dizin denemesi (yalnız yerel DB'de, kod değişikliği yok)

§5'teki öneri 7'nin üç dizini yalnız yerel test DB'sine elle eklendi ve 30 görevli koşusu (temiz durumda)
tekrarlandı. Eklenen dizinler: `parking_records (street, entry_time DESC)`, `(entry_time DESC)` ve
`(status, exit_time) INCLUDE (fee, vehicle_type)`. Kod değişmedi. Dizinler koşudan sonra silindi.

| 30 görevli, 3 panel, 10 dk | p95 | hata | DB CPU ort/max (sınır %25) | havuz bekleyen max | en pahalı sorgu (toplam DB süresi) |
|---|---|---|---|---|---|
| dizinsiz (`r2-b-30-tekrar`) | 1,3 sn | %0 | 7,4 / 25,5 | 6 | son 100 kayıt: 374 çağrı × 520 ms = **194 sn** |
| dizinli (`r2-b-30-dizinli`) | 1,3 sn | %0 | **3,3 / 14,9** | **0** | tipe göre ciro: 135 × 21 ms = **2,9 sn** |

- Bugünkü yükte istemci gecikmesi değişmedi. Darboğaz hâlâ app'in 0.1 CPU'su.
- Ama DB yükü yarıya indi ve DB tepeleri sınırın altına çekildi. 1. denemedeki tek hata penceresi (§8.1 dipnot 4)
  tam da bu sorgulardan geliyordu.
- Ücretli CPU'ya geçince (§8.5) sıra DB'ye geçtiği için asıl kazanç orada görülür.
- **Öneri:** dizinleri V12 migration'ıyla ekle. Ücretsiz ve düşük riskli.

### 8.5 Ücretli sunucuya geçince (bilgi amaçlı, satın alma yok)

Aynı DB (0.25 CPU), aynı ağ gecikmesi, yeni istemciler. ⚠ Bu koşular birikim düzeltmesinden önce yapıldı: 10
görevli koşuları ~900, kırılma koşusu ~1.000-1.600 aktif araçla başladı. Yani sonuçlar kötümser.

| app | soğuk açılış (`/api/health`) | 10 görevli p95 | 10 görevli check-in p95 | kırılma |
|---|---|---|---|---|
| 0.1 CPU / 512 MB (Render Free) | 69,8-83,1 sn | 0,8 sn | 0,9 sn | 40-50 görevli; havuz 5 / 20 sn ile 50'de hâlâ %0 (§8.6) |
| 0.5 CPU / 512 MB | 12 sn | 1,4 sn⁴ | 0,5 sn | ölçülmedi |
| 1 CPU / 2 GB | 7 sn | 1,4 sn⁴ | 0,5 sn | 100 görevli: p95 ~1,0 sn, %0; 150: p95 5,8 sn, %1,6; **200: %94** |

⁴ 10 görevlide toplam p95'i panelin ağır uçları belirliyor: `bi/shifts` (N+1) ve tüm caddeyi döndüren `spots`.
İkisi de birikimden etkilendi. Görevli uçları CPU arttıkça hızlandı: `filter` p95 0,37 sn'den 0,24 sn'ye,
`sync?since` 0,81 sn'den 0,18 sn'ye indi.

- **10 görevlide ücretli CPU'ya gerek yok.** Free katmanda p95 0,8 sn, hata %0. Ücretli katmanın asıl kazancı
  açılış süresi (70-83 sn → 7-12 sn) ve tavanın yükselmesi.
- **1 CPU / 2 GB'ta tavan ~150 görevli.** 200 görevlide DB CPU'su %28'e çıktı (0.25 CU sınırı). Bu noktada sıra
  DB'ye geçiyor: Neon'un 2 CU'ya otomatik ölçeklenmesi (burada simüle edilmedi) ya da dizinler (§8.4) gerekir.
- **RSS:** 0.1 CPU / 512 MB'ta dayanıklılık koşusunda RSS 458-470 MB'ta sabit kaldı. Sızıntı yok, ama 512 MB'a
  ~40 MB pay var. Yığın artık %45 (~230 MB). Ücretli katmanda bellek 1 GB+ seçilirse rahatlar.

### 8.6 Havuz ayarı denemesi

Aynı temiz basamak koşusu (10→20→30→40→50) iki havuz ayarıyla koşuldu. Yalnızca ortam değişkenleri
değişti, kod aynı.

| ayar | 10 | 20 | 30 | 40 | 50 görevli |
|---|---|---|---|---|---|
| `DB_POOL_SIZE=10`, `DB_CONNECTION_TIMEOUT_MS=5000` (bugünkü varsayılan) | 0,5 sn / %0 | 0,8 sn / %0 | 1,1 sn / %0 | 1,2 sn / %0 | **13,4 sn / %73** |
| `DB_POOL_SIZE=5`, `DB_CONNECTION_TIMEOUT_MS=20000` | 0,9 sn / %0 | 0,9 sn / %0 | 1,1 sn / %0 | 2,5 sn / %0 | **2,8 sn / %0** |

Değerler basamak başına 10 sn'lik p95 medyanı ve ortalama hata. Toplamda havuz 5 / 20 sn ile p95 3,3 sn, hata
%0, 503 sayısı 0, RSS en çok 343 MB. Varsayılanda p95 10,3 sn, hata %25 ve 1.187 adet 503 vardı.

- 0.1 CPU'da 10 bağlantı, aynı anda daha çok isteğin CPU'yu paylaşması demek.
- 5 sn'lik bekleme sınırı, kuyruktaki isteği 503'e çeviriyor. İstemci 503'te yeniden deniyor ve yük artıyor.
- 5 bağlantı ve 20 sn sınırla istekler sırada bekliyor, gecikme biraz uzuyor (40 görevlide 1,2 → 2,5 sn), ama
  503 kaskadı oluşmuyor.
- **Bu tek bir koşudur; yukarıdaki açıklama olası bir nedendir.** Render'a kalıcı uygulanmadan önce en az 2 kez
  tekrar ölçülmeli. Kırılma noktası bu ayarla 50'nin üstünde, nerede olduğu ölçülmedi.
- **Denenecek (ücretsiz, kod yok):** Render Free'de `DB_POOL_SIZE=5` ve `DB_CONNECTION_TIMEOUT_MS=20000`
  ortam değişkenleri. Ücretli ve çok çekirdekli katmanda bugünkü varsayılanlar (10 / 5 sn) daha uygun olabilir;
  orada yeniden ölçülmeli.

### 8.7 Kalan riskler (önem sırasıyla)

0. **Havuz ayarları 0.1 CPU için fazla agresif olabilir.** Tek koşuda 50 görevli çöküşü havuz 5 / 20 sn ile ortadan kalktı (§8.6). Olası neden; en az 2 tekrarla doğrulanmalı. Denenecek: iki ortam değişkeni, kod gerekmiyor.
1. **Kesinti sonrası kuyruk 30 görevliyle hâlâ çöküyor.** 10 görevli artık sorunsuz. 30 görevlide ~650 giriş
   288 sn'de boşalmadı ve hata %64 oldu. Nedenler:
   - kayıtlar arası 0,4-1,2 sn bekleme, 30 telefonda saniyede ~2 giriş demek;
   - her giriş bölgedeki görevlilerde ve panelde birleştirilmiş bir okuma tetikliyor;
   - 503'ten sonraki yeniden denemeler yükü artırıyor.

   **Öneriler:** bekleme aralığını 2-3 sn'ye çıkar; kuyruk boşalırken olay kaynaklı yenilemeyi beklet; sunucuda
   toplu giriş ucu ekle (`mobile/lib/core/entry_queue.dart`, `ParkingController`).
2. **503 yeniden deneme çoğaltması.** GET'te 3 yeniden deneme, aşırı yükte bir isteği en çok 4 isteğe çıkarıyor. 50 görevlide istek/sn 7,9'dan 14,7'ye fırladı (§8.2).
   **Öneri:** `ApiClient.serverBusy` sürerken yeniden denemeyi tek isteğe indir
   (`mobile/lib/core/api_client.dart`).
3. **Geçiş dönemi.** Eski APK'lar yeni sunucuda yoğunlukta 30 görevlide %1,9 hata görüyor (§8.3).
4. **DB'nin tarama yapan sorguları ve `bi/shifts` N+1** hâlâ var (§5 öneri 7, §8.4). Tablo ve açık vardiya
   sayısıyla doğrusal büyüyorlar.
5. **Bellek payı.** 512 MB'ta RSS ~470 MB.
6. **Simüle edilmeyenler** 1. turla aynı (§7). Ek olarak burada aktif araç sayısı her koşuda sıfırdan başladı;
   gün içinde bir caddede yüzlerce araç birikirse `filter` ve `spots` yanıtları büyür.
