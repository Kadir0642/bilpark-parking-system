# BilPark yük testi

Canlı sistemin (Render Free + Neon Free) kaynak sınırlarını yerelde taklit eden yığın ve gerçek uygulama
davranışını taklit eden yük üreteci. Sonuçlar ve öneriler: [`docs/yuk-testi.md`](../docs/yuk-testi.md).

**Canlı sisteme hiçbir istek gönderilmez.** Her şey bu bilgisayardaki `bilpark-yuk` Docker projesinde koşar.

## Parçalar

| Dosya | Ne yapar |
|---|---|
| `docker-compose.yml` | `db` (Postgres 16, 0.25 CPU, 1 GB, `max_connections=104`), `dbproxy` (toxiproxy, DB'ye ağ gecikmesi), `app` (repo `backend/Dockerfile`, 0.1 CPU, 512 MB), `gen` (Node 24 yük üreteci) |
| `seed.sql` | 300 kurgusal görevli (`yuk001..yuk300`, 6 bölgeye sırayla), 90 günlük ~65 bin kayıt, 5 bin sync olayı, 65 bin denetim satırı |
| `gen/yuk.mjs` | Yük üreteci (bağımlılık yok). Görevli, yönetici, vatandaş ve geliştirici davranışı `mobile/lib` ve `web/` koduna göre |
| `run.sh` | Bir koşu: örnekleyici + üreteç + Hikari/GC günlüğü + `pg_stat_statements` + doğruluk kontrolü |
| `sampler.sh` | 5 sn'de bir ana makine CPU'su, emülatör var mı, konteyner CPU/RAM, JVM RSS/iş parçacığı, DB bağlantıları |
| `coldstart.sh` | Konteyner başlangıcından ilk `/api/health`, ilk giriş ve ilk DB okumasına kadar geçen süre |
| `dogruluk.sql` | Koşu sonrası: çift aktif plaka, çift istek kimliği, tutarsız kayıt (her satır 0 olmalı) |
| `seri.sh` | Koşuları art arda çalıştırır, emülatör açıksa bekler |
| `ozet.py` | Sonuç JSON + kaynak CSV + Hikari/GC günlüklerinden özet ve tablo |
| `explain.sql` | Sıcak yol sorgularının `EXPLAIN ANALYZE` çıktısı |
| `results/` | Koşu çıktıları (JSON özet + CSV kaynak + günlük kesitleri) |

## Neden k6 değil

Saha uygulaması SSE olaylarına tepki verir: bölgedeki her araç olayında her görevli `/parking/filter`,
`/parking/history` ve `/vehicle-types` çeker; HEARTBEAT'te sürüm gerideyse `/sync?since=` çeker. Yönetim
paneli her araç olayında 5 BI ucu çeker. Bu yükün gerçekçi olması için her sanal kullanıcının gerçek bir SSE
akışı tutup olaylara uygulamadaki gibi tepki vermesi gerekir. k6 çekirdeği SSE akışı okuyamaz (xk6 eklentisiyle
özel derleme gerekir), bu yüzden üreteç Node 24'ün yerleşik `fetch`'iyle yazıldı; ek paket yok.

## Kurulum (bir kez)

Repo kökünden, Git Bash ile:

```bash
export MSYS_NO_PATHCONV=1
P="docker compose -p bilpark-yuk -f loadtest/docker-compose.yml"
docker build -t bilpark-yuk-backend:latest backend          # repo Dockerfile'ı, değiştirilmeden
$P up -d db dbproxy
# Neon gecikmesi: her yönde 10 ms (+-2), gidiş-dönüş ~20 ms
docker exec bilpark-yuk-dbproxy-1 /toxiproxy-cli toxic add -t latency -a latency=10 -a jitter=2 --upstream -n lat_up neon
docker exec bilpark-yuk-dbproxy-1 /toxiproxy-cli toxic add -t latency -a latency=10 -a jitter=2 --downstream -n lat_down neon
bash loadtest/coldstart.sh ilk-acilis                        # Flyway V1..V11 + ilk kullanıcılar
docker exec -i bilpark-yuk-db-1 psql -U bilpark -d bilpark_yuk < loadtest/seed.sql
```

Sahte sırlar compose dosyasındadır (`test-secret-...`), gerçek değer yoktur.

## Koşular

Belgedeki sonuçları üreten komutlar (sırayla, aynı yığında koşuldu). `seri.sh` her koşudan önce ana makinede
Android emülatörü açıksa bitmesini bekler.

```bash
# (a) temel, yeniden başlatma sonrası soğuk JVM: 10 görevli, 2 yönetici, vatandaş 1-10/dk (+5 dk'da bir 30/dk), geliştirici sayfası, 15 dk
bash loadtest/run.sh a-temel-10 OFFICERS=10 DURATION=900 ADMINS=2 RUN_ID=12
# (a2) aynı yük, sıcak JVM, 10 dk
bash loadtest/run.sh a2-temel-10-sicak OFFICERS=10 DURATION=600 ADMINS=2 RUN_ID=13 REPORT_EVERY=400
bash loadtest/seri.sh   "b-3kat-30 OFFICERS=30 DURATION=600 ADMINS=3 RUN_ID=14"   "k-kirilma-basamak OFFICER_STEPS=10,20,30,40,50 STEP_SEC=150 DURATION=750 ADMINS=2 RUN_ID=15"   "v-dalga OFFICERS=10 DURATION=840 ADMINS=2 WAVES=100,200,300,400 WAVE_START=90 WAVE_GAP=180 RUN_ID=16"   "v2-dalga-tek-ip OFFICERS=10 DURATION=240 ADMINS=2 WAVES=200 WAVE_SAME_IP=1 WAVE_START=60 RUN_ID=17"   "d-kuyruk-30 SCENARIO=spike OFFICERS=30 DURATION=480 ADMINS=2 SPIKE_AT=120 OUTAGE=120 QUEUE=20 RUN_ID=18"   "c-10kat-100 OFFICERS=100 DURATION=600 ADMINS=3 RAMP=90 RUN_ID=19"
bash loadtest/coldstart.sh soguk-0.1cpu-gocmus-db-1
bash loadtest/seri.sh   "e-dayaniklilik OFFICERS=10 DURATION=2100 ADMINS=2 RUN_ID=20"   "d2-kuyruk-10 SCENARIO=spike OFFICERS=10 DURATION=420 ADMINS=2 SPIKE_AT=90 OUTAGE=120 QUEUE=20 RUN_ID=21"
bash loadtest/coldstart.sh soguk-0.1cpu-gocmus-db-2
# CPU duyarlılığı: app 0.5 CPU ile yeniden oluşturulur
$P stop app && APP_CPUS=0.5 bash loadtest/coldstart.sh soguk-0.5cpu-gocmus-db
bash loadtest/seri.sh   "b05-3kat-30-cpu05 OFFICERS=30 DURATION=600 ADMINS=3 RUN_ID=22"   "k05-kirilma-cpu05 OFFICER_STEPS=30,50,75,100 STEP_SEC=150 DURATION=600 ADMINS=3 RUN_ID=23"
# özet tablo ve ayrıntı
python -I loadtest/ozet.py loadtest/results --tablo a2-temel-10-sicak b-3kat-30 c-10kat-100
python -I loadtest/ozet.py loadtest/results k-kirilma-basamak
docker exec -i bilpark-yuk-db-1 psql -U bilpark -d bilpark_yuk < loadtest/explain.sql
```

Üretecin bütün ayarları `gen/yuk.mjs` başındaki açıklamadadır. Her koşu `RUN_ID`'yi farklı versin
(plakanın il kodu ondan türer, önceki koşunun aktif araçlarıyla çakışmaz).

## Ölçüm notları

- `p50/p95/p99` istemci tarafında, yük üretecinin gördüğü uçtan uca süredir (ağ dahil, 15 sn zaman aşımı = mobil uygulama).
- SSE gecikmesi: olay gövdesindeki `at` (sunucunun olayı yazdığı an) ile üretecin olayı aldığı an arasındaki fark.
  Tüm konteynerler aynı Docker VM saatini kullanır.
- Hikari havuz durumu `app` günlüğünde (`LOGGING_LEVEL_COM_ZAXXER_HIKARI=DEBUG`), GC `-Xlog:gc` ile; ikisi de
  ENTRYPOINT'i değiştirmeden `JAVA_TOOL_OPTIONS`/ortamla açılır. Actuator yok.
- Ana makinede başka iş (ör. Android emülatörü) varsa `*-kaynak.csv`'deki `host_cpu` ve `emulator` sütunlarına bakın.
- Tek makineden gelinir. `/api/parking/debt` IP başına 120/dk ile sınırlı ve IP X-Forwarded-For'un en sağındaki
  girdiden alınıyor. Üreteç bu yüzden her sanal vatandaşa ayrı bir en-sağ XFF verir (`WAVE_SAME_IP=1` ile hepsi aynı IP).

## Temizlik

```bash
docker compose -p bilpark-yuk -f loadtest/docker-compose.yml --profile gen down -v
docker image rm bilpark-yuk-backend:latest    # isteğe bağlı
```

## 2. tur (iyileştirmeler sonrası, main @ 4a3ec19)

- `CLIENT=new` (varsayılan) yeni mobil ve panel davranışını, `CLIENT=old` 1. turdaki davranışı taklit eder.
- `HOME_TAB=0.3`: görevlilerin gösterge sekmesinde olan oranı (kalanı cadde sekmesinde).
- 1 CPU / 2 GB için `APP_CPUS=1 APP_MEM=2g`. Havuz denemesi için `DB_POOL_SIZE=5 DB_CONNECTION_TIMEOUT_MS=20000`
  (ikisi de app yeniden oluşturulunca etkili olur, ör. `coldstart.sh` ile).
- **Koşu hijyeni:** `run.sh` her koşudan önce açık vardiyaları kapatır; aktif araçları ve istek kimliklerini
  siler (`KEEP_SPOTS=1` ile atlanır). Bu yapılmazsa yük koşudan koşuya yapay olarak büyür.
- `seri.sh` her koşudan önce emülatör açıksa bekler. Ağır koşuların arasına ~90 sn soğuma konuldu.

Belgedeki 2. tur sonuçlarını üreten koşular (ilk açılış ve `seed.sql`'den sonra). İlk satırdaki dört koşu birikim düzeltmesinden önce yapıldı; başlangıçtaki aktif araç sayıları belgede yazılı:

```bash
bash loadtest/seri.sh "r2-a-temel-10 OFFICERS=10 DURATION=900 ADMINS=2 RUN_ID=31" "r2-v-dalga OFFICERS=10 DURATION=660 ADMINS=2 WAVES=100,200,400 WAVE_START=90 WAVE_GAP=180 RUN_ID=34" "r2-d-kuyruk-10 SCENARIO=spike OFFICERS=10 DURATION=480 ADMINS=2 SPIKE_AT=90 OUTAGE=120 QUEUE=20 RUN_ID=35" "r2-e-dayaniklilik OFFICERS=10 DURATION=1800 ADMINS=2 RUN_ID=37"
bash loadtest/seri.sh "r2-k-kirilma OFFICER_STEPS=10,20,30,40,50 STEP_SEC=150 DURATION=750 ADMINS=2 RUN_ID=56"
bash loadtest/seri.sh "r2-d-kuyruk-30 SCENARIO=spike OFFICERS=30 DURATION=540 ADMINS=2 SPIKE_AT=120 OUTAGE=120 QUEUE=20 RUN_ID=57"
bash loadtest/seri.sh "r2-c-50-tekrar OFFICERS=50 DURATION=600 ADMINS=3 RAMP=90 RUN_ID=58" "r2-b-30-tekrar OFFICERS=30 DURATION=600 ADMINS=3 RUN_ID=59"
# dizin denemesi: explain.sql'deki sorgular için üç dizin elle eklenir, koşudan sonra silinir (bkz. docs §8.4)
bash loadtest/seri.sh "r2-b-30-dizinli OFFICERS=30 DURATION=600 ADMINS=3 RUN_ID=60"
bash loadtest/seri.sh "r2-old-30-tekrar CLIENT=old OFFICERS=30 DURATION=600 ADMINS=3 RUN_ID=61"
DB_POOL_SIZE=5 DB_CONNECTION_TIMEOUT_MS=20000 bash loadtest/coldstart.sh 2tur-soguk-havuz5-20sn
bash loadtest/seri.sh "r2-k-kirilma-havuz5-20sn OFFICER_STEPS=10,20,30,40,50 STEP_SEC=150 DURATION=750 ADMINS=2 RUN_ID=63"
# 0.5 / 1 CPU (bilgi amaçlı)
$P stop app && APP_CPUS=0.5 bash loadtest/coldstart.sh 2tur-soguk-0.5cpu
bash loadtest/seri.sh "r2-p05-temel-10 OFFICERS=10 DURATION=600 ADMINS=2 RUN_ID=39"
$P stop app && APP_CPUS=1 APP_MEM=2g bash loadtest/coldstart.sh 2tur-soguk-1cpu-2gb
bash loadtest/seri.sh "r2-p1-temel-10 OFFICERS=10 DURATION=600 ADMINS=2 RUN_ID=40" "r2-p1-kirilma OFFICER_STEPS=50,100,150,200 STEP_SEC=150 DURATION=600 ADMINS=3 RUN_ID=41"
```

`results/r2-x-*` dosyaları birikim düzeltmesinden önce koşulmuş, kıyasta kullanılmayan koşulardır.
