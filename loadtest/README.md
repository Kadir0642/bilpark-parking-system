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

```bash
# (a) gerçekçi temel: 10 görevli, 2 yönetici, vatandaş 1-10/dk (+ 5 dk'da bir 30/dk), geliştirici sayfası, 15 dk
bash loadtest/run.sh a-temel-10 OFFICERS=10 DURATION=900 RUN_ID=2
# (b) 3 kat: 30 görevli, 10 dk
bash loadtest/run.sh b-3kat-30 OFFICERS=30 DURATION=600 RUN_ID=3 ADMINS=3
# (c) 10 kat ve kırılma noktası: 100 -> 150 -> 200 -> 300 görevli, her basamak 150 sn
bash loadtest/run.sh c-10kat-basamak OFFICER_STEPS=100,150,200,300 STEP_SEC=150 DURATION=600 RUN_ID=4 ADMINS=3
# vatandaş dalgaları: 10 görevli + 2 yönetici üstüne 100/200/300/400 vatandaş, her dalga 60 sn içinde
bash loadtest/run.sh v-dalga OFFICERS=10 DURATION=840 WAVES=100,200,300,400 WAVE_START=90 WAVE_GAP=180 RUN_ID=5
# (d) kesinti sonrası kuyruk boşaltma: 3. dk'da ağ gider, 2 dk boyunca her görevli 20 giriş kuyruklar, sonra hepsi aynı anda döner
bash loadtest/run.sh d-ani-kuyruk-30 SCENARIO=spike OFFICERS=30 DURATION=480 SPIKE_AT=120 OUTAGE=120 QUEUE=20 RUN_ID=6
# (e) dayanıklılık (bellek sızıntısı): temel yük 30+ dk
bash loadtest/run.sh e-dayaniklilik OFFICERS=10 DURATION=2100 RUN_ID=7
# (f) soğuk başlatma (0.1 CPU)
bash loadtest/coldstart.sh soguk-0.1cpu
# CPU duyarlılığı: app 0.5 CPU ile yeniden oluşturulup aynı koşu
APP_CPUS=0.5 $P up -d app && bash loadtest/coldstart.sh soguk-0.5cpu
bash loadtest/run.sh b-3kat-30-cpu05 OFFICERS=30 DURATION=600 RUN_ID=8 ADMINS=3
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
- Tek IP'den gelinir. `main`'de `/api/parking/debt` için hız sınırı yoktur; sınır eklenirse vatandaş dalgaları
  tek IP yüzünden 429 alır (gerçekte vatandaşlar farklı IP'lerdendir).

## Temizlik

```bash
docker compose -p bilpark-yuk -f loadtest/docker-compose.yml --profile gen down -v
docker image rm bilpark-yuk-backend:latest    # isteğe bağlı
```
