# BilPark yük testi

> **ARA SONUÇ / yarım.** Çalışma kullanıcının isteğiyle durduruldu; aşağıdaki sayılar kesin değildir.
> Altyapı (`loadtest/`) hazır ve doğrulandı; ana koşuların çoğu henüz yapılmadı.

## Durum

| Koşu | Durum |
|---|---|
| Altyapı: compose (app 0.1 CPU / 512 MB, db 0.25 CPU / 1 GB / max_connections=104, toxiproxy ~20 ms gidiş-dönüş), tohum verisi, Node yük üreteci, örnekleyici, doğruluk SQL | bitti |
| Duman testi (3 görevli, hızlandırılmış, 150 sn) | bitti, %0 hata |
| (f) Soğuk başlatma, boş DB + Flyway V1-V11, 0.1 CPU | bitti (1 ölçüm) |
| (a) Temel: 10 görevli, 2 yönetici, vatandaş, 15 dk | **yarıda kesildi (~4,5 dk)** |
| (b) 3 kat (30), (c) 10 kat (100) + kırılma noktası basamakları | yapılmadı |
| Vatandaş dalgaları 100/200/300/400 (10 görevli + yöneticiler üstüne) | yapılmadı |
| (d) Kesinti sonrası kuyruk boşaltma + idempotentlik, (e) dayanıklılık 30+ dk, 0.5 CPU karşılaştırması | yapılmadı |
| Kod incelemesi, EXPLAIN, öneriler | kısmen (not alındı, yazılmadı) |

## Şu ana kadarki ölçümler (kesin değil)

- **Soğuk başlatma (0.1 CPU, boş DB):** uygulama 199 sn'de başladı, ilk `/api/health` 227 sn, ilk giriş +18 sn
  (bcrypt + soğuk JIT), ilk DB okuması +3 sn. Göç edilmiş DB ile ölçüm henüz yapılmadı.
- **JVM:** 512 MB konteynerde varsayılan ergonomi: en fazla 128 MB yığın (MaxRAMPercentage=25), SerialGC.
  Temel yükte RSS ~375-416 MB (512 MB sınırına yakın), yığın GC sonrası ~75 MB / 123 MB.
- **Temel yük, ilk ~4,5 dk:** 10 sn'lik pencerelerde 27-48 istek, hata 0, p95 0,6-1,0 sn; app CPU ortalama
  ~%4,9 (sınır %10). Ana makine CPU ortalaması %22; bir Android emülatörü süreci açıktı (ölçüm tekrar edilecek).
- **Duman testi:** xlsx dönem raporu 0.1 CPU'da ~26 sn sürdü.

## Sürdürmek için

Komutlar `loadtest/README.md`'de. Kısaca (repo kökü, Git Bash): görüntüyü derle, `db dbproxy` başlat,
gecikme toksiklerini ekle, `coldstart.sh`, `seed.sql`, sonra `run.sh` koşuları.

## Notlar (inceleme sırasında)

- Hikari havuzu 5 bağlantı, `minimum-idle=0`; Neon 0.25 CU `max_connections` ~104 (varsayım, Neon belgesindeki
  tabloya göre) olduğundan sınırlayıcı Neon değil uygulama havuzu.
- Görevli uygulaması bölgedeki her araç olayında `/parking/filter` + `/parking/history` + `/vehicle-types` çekiyor;
  HEARTBEAT'te (25 sn) başka bölgelerin olayları yüzünden sürüm geride kaldığı için `/sync?since=` çağırıyor.
  Yönetim paneli her araç olayında 5 BI ucu çekiyor (`bi/shifts` açık vardiya başına ayrı ciro sorgusu yapıyor).
- `parking_records (street, entry_time)` ve `(entry_time)` dizinleri yok (geçmiş ve son 100 kayıt sorguları).
