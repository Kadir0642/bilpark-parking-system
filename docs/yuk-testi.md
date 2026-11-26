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

<!--SONUCLAR-->
