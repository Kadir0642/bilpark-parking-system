-- BilPark YÜK TESTİ verisi. CANLI VERİTABANINA ASLA UYGULAMAYIN.
-- Yalnızca loadtest/docker-compose.yml'deki "bilpark_yuk" veritabanında çalışır (ilk kontrol başka veritabanında durur).
-- Önkoşul: backend bir kez açılmış olmalı (Flyway tabloları, 6 bölgeyi ve admin/tevfik'i oluşturur).
--
-- İçerik (tamamı kurgusal):
--   * yuk001..yuk300 görevlileri, 6 gerçek bölgeye sırayla (yuk001 -> 1. bölge, yuk007 -> yine 1. bölge) atanır;
--     şifre = OFFICER_PASSWORD (tevfik'in bcrypt özeti kopyalanır). 10 görevli = 6 sahada 1-2 görevli.
--   * Son 90 günün geçmişi: ~65 bin parking_records (6 gerçek bölge, günde ~120 kayıt/bölge), ~%3 kaçak
--   * Son 7 günün sync_events (~5 bin) ve audit_logs (~65 bin) satırları: üretimdeki birkaç aylık tablo boyutu

\set ON_ERROR_STOP on

BEGIN;
SET LOCAL TIME ZONE 'Europe/Istanbul';
SELECT setseed(0.17);

DO $$
BEGIN
    IF current_database() <> 'bilpark_yuk' THEN
        RAISE EXCEPTION 'Bu dosya yalnizca yuk testi veritabaninda (bilpark_yuk) calisir. Su anki: %', current_database();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM users WHERE username = 'tevfik') THEN
        RAISE EXCEPTION 'tevfik kullanicisi yok. Once backend''i bir kez baslatin.';
    END IF;
END $$;

-- 1) Görevliler (6 gerçek bölgeye sırayla)
WITH z AS (SELECT id, row_number() OVER (ORDER BY id) - 1 AS idx FROM (SELECT id FROM zones ORDER BY id LIMIT 6) t)
INSERT INTO users (username, password, role, zone_id, full_name)
SELECT 'yuk' || lpad(i::text, 3, '0'), (SELECT password FROM users WHERE username = 'tevfik'), 'OFFICER',
       (SELECT id FROM z WHERE z.idx = (i - 1) % 6), 'Yuk Gorevli ' || i
  FROM generate_series(1, 300) AS i
ON CONFLICT (username) DO NOTHING;

-- 2) Geçmiş kayıtlar (yalnızca bir kez)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM parking_records WHERE device_id = 'yuk-seed') THEN
        RAISE NOTICE 'Gecmis kayitlar zaten var, atlandi.';
        RETURN;
    END IF;
    INSERT INTO parking_records (license_plate, street, zone_id, region, neighborhood, entry_time, exit_time,
                                 vehicle_type, fee, status, debt_paid, payment_method, collected_by_id,
                                 collected_by_username, device_id, paid_minutes)
    SELECT plate, z.street, z.id, 'Merkez', 'Bilecik', t, t + (dur || ' minutes')::interval,
           CASE WHEN random() < 0.85 THEN 'SMALL' ELSE 'LARGE' END,
           CASE WHEN dur <= 5 THEN 0 WHEN dur <= 60 THEN 25 ELSE 25 + ceil((dur - 60) / 60.0) * 15 END,
           CASE WHEN r < 0.03 THEN 'RUNAWAY' ELSE 'PAID' END,
           r >= 0.03,
           CASE WHEN r < 0.03 THEN NULL WHEN r < 0.6 THEN 'CASH' ELSE 'POS' END,
           (SELECT id FROM users WHERE username = 'tevfik'), 'tevfik', 'yuk-seed', GREATEST(dur - 5, 0)
      FROM (SELECT id, street FROM zones ORDER BY id LIMIT 6) z,
           LATERAL (
               SELECT now() - (d || ' days')::interval - ((random() * 600)::int || ' minutes')::interval AS t,
                      (5 + random() * 180)::int AS dur,
                      random() AS r,
                      lpad((1 + (random() * 80)::int)::text, 2, '0')
                          || chr(65 + (random() * 25)::int) || chr(65 + (random() * 25)::int)
                          || (100 + (random() * 899)::int)::text AS plate
                 FROM generate_series(1, 90) AS d, generate_series(1, 120) AS n
           ) x;

    INSERT INTO sync_events (type, scope, zone_id, street, payload, created_at)
    SELECT CASE WHEN g % 2 = 0 THEN 'VEHICLE_ENTERED' ELSE 'VEHICLE_EXITED' END, 'ZONE', z.id, z.street,
           '{"plate":"06YUK' || (100 + g % 899) || '"}', now() - interval '7 days' + (g || ' minutes')::interval * 2
      FROM generate_series(1, 5000) AS g
      JOIN LATERAL (SELECT id, street FROM zones ORDER BY id LIMIT 1 OFFSET (g % 6)) z ON true;

    INSERT INTO audit_logs (created_at, actor_username, actor_role, action, entity_type, entity_id, summary)
    SELECT now() - (g || ' minutes')::interval * 2, 'tevfik', 'OFFICER',
           CASE WHEN g % 2 = 0 THEN 'VEHICLE_CHECK_IN' ELSE 'VEHICLE_CHECK_OUT' END, 'ParkSpot', g::text, 'yuk seed'
      FROM generate_series(1, 65000) AS g;
END $$;

COMMIT;

ANALYZE;

SELECT (SELECT count(*) FROM zones) AS zones,
       (SELECT count(*) FROM users WHERE username LIKE 'yuk%') AS yuk_officers,
       (SELECT count(*) FROM parking_records) AS records,
       (SELECT count(*) FROM sync_events) AS sync_events,
       (SELECT count(*) FROM audit_logs) AS audit_logs;
