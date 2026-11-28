# BilPark ölçekleme planı

İleriye dönük, kısa bir yol haritası: sistem büyürse hangi sırayla, hangi gösterge görülünce, ne değiştirilir.
Sayılar yalnızca [`yuk-testi.md`](yuk-testi.md)'den alınmıştır (yerel kopyada, 26 Kasım 2026 ölçümü; gecikme
sayıları ±%50 okunmalı). Burada yeni ölçüm yok. **Ücretli her adım için satın alma kararı proje sahibine aittir**;
bu belge karar vermez, seçenekleri ve tetikleyicileri anlatır.

## 1. Bugünkü durum

- **Uygulama:** ücretsiz katmanda tek örnek (Docker). Yük testindeki sınır: 512 MB bellek, ~0,1 CPU.
- **Veritabanı:** ücretsiz katmanda yönetilen PostgreSQL, doğrudan bağlantı (havuzlayıcı yok), küçük işlem gücü.
- **Yük testinin gösterdiği (`yuk-testi.md` Kısa sonuç ve §3):**
  - 10 eşzamanlı görevli + 2 yönetici + vatandaşlar: sıcak JVM'de hata %0, p95 2,5 sn.
  - Kırılma noktası 30 ile 40 eşzamanlı görevli arası (40'ta hata %59). İlk doyan uygulamanın CPU'su; ardından DB
    havuzu, küçük yığın ve aşırı yükte geçerli oturumlara dönen 401'ler geldi.
  - 400 eşzamanlı vatandaş sorgusu sorunsuz (p95 1,0 sn, hata %0). Veri doğruluğu her koşuda tam.
  - Riskli bölgeler: deploy/yeniden başlatma sonrası ilk 3-4 dk, kesinti sonrası kuyruk boşaltma, rapor üretimi.
- **O ölçümden sonra mimaride yapılanlar** (ayrıntı için kod ve `yuk-testi.md` §5): istemcide olay
  yenilemelerinin birleştirilmesi (coalescing), kuyruk boşaltma ve yeniden bağlanmada rastgele sapma (jitter),
  DB hatasında 401 yerine 503 + `Retry-After`, 5 sn bağlantı zaman aşımı, ayarlanabilir havuz (`DB_POOL_SIZE`),
  OSIV'nin kapatılması, JVM bayrakları (CDS, SerialGC, yığın yüzdesi), rapor kapısı (aynı anda 1 rapor). Bunların
  etkisi, yeni ölçüm yapılana kadar "beklenen" sayılır.

Sonuç: bugünkü kurulum pilot ve birkaç saha için uygun görünüyor; sınırı CPU, havuz ve yığın birlikte belirliyor.

## 2. Dikey ölçekleme (ücretli tek örnek)

Aynı mimari, daha büyük kutu. Plan adları ve fiyatlar zamanla değişir; satın almadan önce sağlayıcının güncel
fiyat ve limit sayfası kontrol edilmeli. Burada yalnızca adım türleri ve çözdükleri darboğaz var.

| Adım | Hangi darboğazı hafifletir | Ayar | Not |
|---|---|---|---|
| Ücretli tek örnek, daha çok CPU | Uygulama CPU'su; açılış (0,5 CPU'da 260 sn → 36 sn, §3.7) | — | Tek başına yetmez: 0,5 CPU'da 30 görevlide p95 değişmedi, kırılma yine 30-50 arası (§3.8) |
| Daha çok RAM | Yığın boyutu, tam GC duraklamaları | `JDK_JAVA_OPTIONS` içinde `MaxRAMPercentage` (bugün 45) | Yığın dışı bellek (CDS, metaspace, kod önbelleği) kabaca sabit; ek RAM yığına verilebilir. Yüzdeyi artırınca RSS izlenmeli |
| Daha yüksek DB işlem gücü | DB CPU'su (30 görevlide sınıra dayandı, §3.9) ve bağlantı sınırı | `DB_POOL_SIZE` (varsayılan 10) | Havuz, DB'nin izin verdiği bağlantı sınırına göre büyütülür; sınır sağlayıcıda boyuta bağlı |
| Tomcat iş parçacığı | Yalnızca çok yüksek eşzamanlılıkta | `TOMCAT_MAX_THREADS` (varsayılan 200) | Çoğu zaman değiştirmek gerekmez (bellek etkisi yük testinde ölçülmedi) |
| Uygulama ile DB aynı bölgede | Her sorgudaki ~20 ms gidiş-dönüş (§1) | — | Bağlantının tutulma süresini kısaltır |

Sıra: önce ücretsiz ayarlar (havuz, yığın yüzdesi); sonra DB işlem gücü ve CPU birlikte, çünkü §3.8'e göre biri
tek başına darboğazı yalnızca bir sonrakine kaydırıyor.

**Her adımdan sonra, canlıya almadan önce** yeni katmanın bir kopyasında `loadtest/` yeniden koşulmalı
([`loadtest/README.md`](../loadtest/README.md)): temel senaryo, basamaklı kırılma ve kesinti sonrası kuyruk
senaryosu. Canlı sisteme yük bindirilmez.

## 3. Yatay ölçekleme (birden çok örnek)

Bugünkü kod **tek örnek varsayar**. İkinci örnek açılmadan önce aşağıdaki bellek içi durumlar çözülmeli; yoksa
davranış sessizce bozulur. Liste kod taramasından çıkarıldı; yeni bileşen eklenince güncellenmeli.

| Bileşen | Bugün | Birden çok örnekte | Gereken |
|---|---|---|---|
| `AuthUserCache` (30 sn kullanıcı önbelleği, değişimde silinir) | Örnek içi | Bir örnekte yapılan değişiklik (ör. kullanıcıyı pasife alma) diğerlerinde en çok TTL kadar görünmez | TTL'yi kabul etmek ya da silme olayını paylaşılan kanaldan yaymak; ya da paylaşılan önbellek (Redis benzeri) |
| `PlateLookupRateLimiter` (IP başına sabit pencere) | Örnek içi sayaç | Etkin sınır örnek sayısıyla çarpılır; "farklı plaka" sınırları da | Paylaşılan sayaç (Redis benzeri) ya da vekil katmanında hız sınırı |
| SSE yayını (`SyncService`) | Bağlı istemciler örnek içinde; olaylar DB'de (`sync_events`) | Yazan örnek yalnızca kendi istemcilerine yayar; diğer örnekteki görevli olayı ancak `/sync?since` ile görür | pub/sub ile her örneğin kendi istemcilerine dağıtması (DB bildirimi ya da Redis benzeri) veya aynı bölgeyi aynı örneğe sabitleme (sticky). Olay günlüğü DB'de olduğu için tekrar oynatma zaten paylaşımlı |
| `TicketService` (SSE bağlantı biletleri) | Bellekte, kısa ömürlü | Bilet bir örnekte alınıp akış başka örneğe açılırsa reddedilir | Paylaşılan depo, imzalı (durumsuz) bilet ya da sticky |
| `DeviceTraceService` (2 dk'da bir yazma sınırı) | Örnek içi | Biraz fazla yazma; zararsız | Değişiklik gerekmez |
| Zamanlanmış işler: `ShiftAutoCloseJob`, `RetentionJob`, `SubscriptionService`, `SyncService` temizliği | Gecede bir, tek örnekte | Her örnek aynı işi çalıştırır: çift vardiya kapatma, çift abonelik işlemi riski | Tek-çalıştırıcı kilidi (ShedLock benzeri ya da DB tavsiye kilidi). `HEARTBEAT` örnek başınadır, doğru |
| Rapor kapısı (`ReportGate`, aynı anda 1 rapor) | Örnek içi | Toplamda örnek sayısı kadar rapor paralel koşar | Sınır örnek başına (yığın için) yeterliyse değişiklik gerekmez; DB'yi de korumak için dağıtık kilit ya da kuyruk |
| Flyway göçleri | Açılışta | Flyway DB kilidiyle çakışmayı engeller | Göçlü deploy'u örnekleri tek tek yenileyerek yapın |

**DB bağlantı bütçesi:** `örnek sayısı × DB_POOL_SIZE + yönetim/göç payı ≤ DB'nin bağlantı sınırı`. Ücretsiz katman
için (0,25 CU) ~104 doğrudan bağlantı alındı (canlı hesapta doğrulanmadı, `yuk-testi.md` §1). Sınıra yaklaşılırsa: örnek
başına havuzu küçültmek, DB önünde bağlantı havuzlayıcı (pooler) kullanmak (oturum durumu ve hazırlanmış ifade
uyumu önce denenmeli), ya da daha büyük DB. `minimum-idle=0` ücretsiz katmanda DB'nin uyuyabilmesi içindir;
ücretli katmanda sıcak bağlantı tutmak değerlendirilebilir.

Yük dengeleyici, açık SSE akışlarını (30 dk zaman aşımı) kesmeyecek şekilde ayarlanmalı; sağlık denetimi
(`/api/health`, DB'ye dokunmaz) örnek bazında yapılmalı.

## 4. Gözlem ve uyarılar

Eşikler başlangıç önerisidir; gerçek veriyle ayarlanır. Referanslar yük testindendir.

| Gösterge | Nereden | Uyarı eşiği (öneri) | Referans |
|---|---|---|---|
| İstek p95 | Sağlayıcı ölçümü / günlük | 2 dk boyunca > 3 sn | Gerçekçi yükte 2,5 sn; 30 görevlide 5,7 sn |
| 503 oranı (`Retry-After`'lı) | Günlük / izleyici | 5 dk'da > %1 | Aşırı yükte artık 401 yerine 503 beklenir |
| Yığın ve tam GC sıklığı | JVM günlüğü / metrik | Üst üste tam GC; GC sonrası yığın > %85 | Yük altında 2-4 sn duraklama (§3.2) |
| RSS / bellek sınırı | Sağlayıcı paneli | Sınırın > %90'ı | 512 MB'ta 424-484 MB (§3) |
| DB bağlantıları, havuz bekleyen | Hikari günlüğü, DB paneli | Bekleyen sürekli > 0; DB'de sınırın > %70'i | 190+ bekleyen çöküşten önce görüldü |
| DB CPU | DB paneli | Sürekli > %70 | 30 görevlide sınıra dayandı (§3.9) |
| `REPORT_BUSY` 503 | Günlük | Günde birkaç kezden fazla | Raporlar yığın tükenmesiyle aynı döneme düştü (§3.6) |
| Soğuk başlangıç | Deploy sonrası `/api/health` | İlk yanıt > 60 sn | 0,1 CPU'da ~4,5 dk, 0,5 CPU'da ~36 sn (§3.7) |

Hangi gösterge hangi adımı tetikler:
- p95 ya da CPU yüksek, bellek ve DB rahat: CPU artışı (ücretli tek örnek). Önce istemci tarafı okuma azaltmanın
  uygulandığını doğrulayın.
- Yığın/RSS yüksek, tam GC sık: RAM artışı ve `MaxRAMPercentage` ayarı.
- Havuz bekleyen ya da DB CPU yüksek: `DB_POOL_SIZE` ve DB işlem gücü; sorgu ve dizin işleri (§3.9, öneri 7).
- Tek örneğin dikeyde sığdığı en büyük boya yaklaşılıyor ya da kesintisiz çalışma (örnekleri tek tek yenileme)
  gerekiyor: §3'teki yatay ölçekleme; önce o tablodaki bileşenler giderilir.

## 5. Karar tablosu

Aralıklar niteliksel: yük testi 10 görevlide güvenli, 30-40 arasında kırılma gösterdi; saha başına düşen görevli
sayısı arttıkça yük daha hızlı büyür (`yuk-testi.md` §2). Gerçek saha dağılımıyla yeniden ölçülmeli.

| Durum | Yaklaşık ölçek | Önerilen adım |
|---|---|---|
| **Pilot** | Bir iki saha, ~10 eşzamanlı görevli | Mevcut ücretsiz kurulum; gözlem uyarıları açık; deploy'u yoğun saat dışına koy |
| **Tüm sahalar** | ~10-30 eşzamanlı görevli | Ücretsiz ayarlar (havuz, yığın), ardından ücretli tek örnek ve daha yüksek DB işlem gücü; sonra `loadtest/` yeniden koşusu. Satın alma sahibin onayıyla |
| **Tüm sahalar, bol pay** | 30+ eşzamanlı görevli ya da kesintisiz çalışma beklentisi | Önce dikey adımları ve okuma azaltmayı doğrula; sınır görünüyorsa yatay ölçekleme (§3 tablosu tamamlanmadan ikinci örnek açılmaz) |
| **Birden çok işletme** | Birden çok bağımsız müşteri | Yatay ölçekleme ön koşul; veri yalıtımı ve işletme başına sınırlar ayrıca tasarlanmalı (bugün kod tek işletme varsayar) |

Bu tablo taahhüt değil, karar yardımıdır. Her geçişte sıra: ölçüm, sahibin onayı, `loadtest/` yeniden koşusu, geçiş.
