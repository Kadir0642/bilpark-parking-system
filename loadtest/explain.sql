-- Sıcak yol sorgularının planları (yalnızca okur). Hibernate'in ürettiği SQL'in sadeleştirilmiş eşdeğerleri.
-- Çalıştırma: docker exec -i bilpark-yuk-db-1 psql -U bilpark -d bilpark_yuk < loadtest/explain.sql
\pset pager off
SET TIME ZONE 'Europe/Istanbul';

\echo '== 1) GET /parking/history: findTop50ByStreetOrderByEntryTimeDesc (her bölge olayında her görevli)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM parking_records WHERE street = 'TEVFIK_BEY' ORDER BY entry_time DESC LIMIT 50;

\echo '== 2) GET /parking/bi/last-records: findTop100ByOrderByEntryTimeDesc (her araç olayında her yönetici)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM parking_records ORDER BY entry_time DESC LIMIT 100;

\echo '== 3) GET /parking/bi/income-summary: yıllık ciro (4 toplamdan en büyüğü)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT COALESCE(SUM(fee), 0) FROM parking_records
 WHERE status = 'PAID' AND exit_time >= date_trunc('year', now()) AND exit_time < now();

\echo '== 4) GET /parking/bi/shifts: açık vardiya BAŞINA ciro (N+1)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT COALESCE(SUM(fee), 0) FROM parking_records
 WHERE status = 'PAID' AND collected_by_id = (SELECT id FROM users WHERE username = 'yuk001')
   AND device_id = 'x' AND exit_time >= now() - interval '8 hours' AND exit_time < now();

\echo '== 5) GET /parking/bi/income-by-type/details: araç tipine göre ciro (tüm tablo)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT vehicle_type, COALESCE(SUM(fee), 0), COUNT(*) FROM parking_records WHERE status = 'PAID' GROUP BY vehicle_type;

\echo '== 6) GET /sync?since: findAfter + MAX(id) + en eski olay'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM sync_events WHERE id > (SELECT max(id) - 50 FROM sync_events) ORDER BY id LIMIT 500;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT COALESCE(MAX(id), 0) FROM sync_events;

\echo '== 7) Giriş: kaçak borç (plaka + durum) ve liste borcu (IN)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM parking_records WHERE license_plate = '06ABC123' AND status = 'RUNAWAY';
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT license_plate, COALESCE(SUM(fee), 0) FROM parking_records
 WHERE license_plate IN ('06ABC123', '07DEF456', '11GHI789') AND status = 'RUNAWAY' AND debt_paid = false GROUP BY license_plate;

\echo '== 8) GET /parking/filter: aktif araçlar (cadde)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM active_park_spots s LEFT JOIN zones z ON z.id = s.zone_id WHERE s.street = 'TEVFIK_BEY';

\echo '== 9) Her kimlikli istek: kullanıcı (JwtAuthFilter + controller, 2 kez)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM users u LEFT JOIN zones z ON z.id = u.zone_id WHERE u.username = 'yuk001';

\echo '== Tablo boyutları'
SELECT relname, n_live_tup, pg_size_pretty(pg_total_relation_size(relid)) AS size
  FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
