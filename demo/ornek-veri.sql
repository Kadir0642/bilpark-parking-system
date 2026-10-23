-- BilPark YEREL DEMO örnek verisi. CANLI VERİTABANINA ASLA UYGULAMAYIN.
--
-- Yalnızca docker-compose.yml'deki "bilpark_demo" veritabanında çalışır; başka bir veritabanında
-- ilk satırdaki kontrol hata verip hiçbir şeyi değiştirmeden durur.
-- Çalıştırma: powershell -ExecutionPolicy Bypass -File demo/ornek-veri.ps1
-- Önkoşul: backend en az bir kez açılmış olmalı (Flyway tabloları, bölgeleri ve admin/tevfik'i oluşturur).
-- Tekrar çalıştırılabilir: önceki demo satırlarını silip yeniden ekler.
--
-- İçerik:
--   * 5 ek saha görevlisi (her bölgeye bir görevli; şifreleri tevfik ile aynı = OFFICER_PASSWORD)
--   * 6 bölgede 12 aktif araç (biri abone)
--   * Son 10 günün ödenmiş kayıtları (nakit / POS), bugün dahil
--   * 1 kaçak kayıt (11KCK47, ödenmemiş borç)
--   * 1 aktif abone (11CC1923)
--   * Görevli vardiyaları ve (yoksa) 2026 mali yılı

\set ON_ERROR_STOP on

BEGIN;

SET LOCAL TIME ZONE 'Europe/Istanbul';
SELECT setseed(0.42);

DO $$
BEGIN
    IF current_database() <> 'bilpark_demo' THEN
        RAISE EXCEPTION 'Bu dosya yalnizca yerel demo veritabaninda (bilpark_demo) calisir. Su anki: %', current_database();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM users WHERE username = 'tevfik') THEN
        RAISE EXCEPTION 'tevfik kullanicisi yok. Once backend''i bir kez baslatin (docker compose up).';
    END IF;
END $$;

-- Ücret: BilPark tarifesi (ilk 5 dk ücretsiz, ilk saat 25/50, sonraki her başlayan saat 15/30)
CREATE FUNCTION pg_temp.demo_fee(vtype text, minutes int) RETURNS double precision AS $$
    SELECT CASE
        WHEN minutes <= 5 THEN 0
        WHEN minutes <= 60 THEN CASE WHEN vtype = 'LARGE' THEN 50 ELSE 25 END
        ELSE CASE WHEN vtype = 'LARGE' THEN 50 ELSE 25 END
             + ceil((minutes - 60) / 60.0) * CASE WHEN vtype = 'LARGE' THEN 30 ELSE 15 END
    END::double precision
$$ LANGUAGE sql IMMUTABLE;

-- 1) Önceki demo verisini temizle
DELETE FROM parking_records WHERE device_id = 'demo-seed';
DELETE FROM shifts WHERE device_id = 'demo-seed';
DELETE FROM active_park_spots WHERE current_plate IN (
    '11ABC123', '11DE4521', '34KLM78', '11FR206', '06BNP145', '11HT3307',
    '16ZA512', '11ABS44', '26EK9012', '11MN731', '11CC1923', '11KB208');
DELETE FROM subscriptions WHERE license_plate = '11CC1923';

-- 2) Her bölgeye bir görevli (tevfik zaten Tevfik Bey - Bölüm 1'de)
INSERT INTO users (username, password, role, zone_id)
SELECT v.username, (SELECT password FROM users WHERE username = 'tevfik'), 'OFFICER', z.id
  FROM (VALUES ('tevfik2', 'TEVFIK_BEY', 2),
               ('tevfik3', 'TEVFIK_BEY', 3),
               ('ozkay1', 'ALI_RIZA_OZKAY', 1),
               ('ozkay2', 'ALI_RIZA_OZKAY', 2),
               ('cumhuriyet1', 'CUMHURIYET', 1)) AS v(username, street, zone_number)
  JOIN zones z ON z.street = v.street AND z.zone_number = v.zone_number
ON CONFLICT (username) DO NOTHING;

-- 3) Aktif abone
INSERT INTO subscriptions (license_plate, vehicle_type, start_date, end_date, monthly_fee, status, owner_name, owner_phone)
VALUES ('11CC1923', 'SMALL', current_date - 10, current_date + 20, 750, 'ACTIVE', 'Demo Abone', '0500 000 00 00');

-- 4) Şu an park halindeki araçlar
INSERT INTO active_park_spots (version, current_plate, street, zone_id, region, neighborhood, side, entry_time, current_type, status)
SELECT 0, v.plate, v.street, z.id, 'Merkez', 'Bilecik', v.side,
       localtimestamp - make_interval(mins => v.minutes_ago), v.vtype, 'ACTIVE'
  FROM (VALUES ('TEVFIK_BEY', 1, '11ABC123', 'SMALL', 'LEFT', 35),
               ('TEVFIK_BEY', 1, '11DE4521', 'LARGE', 'RIGHT', 95),
               ('TEVFIK_BEY', 1, '34KLM78', 'SMALL', 'LEFT', 12),
               ('TEVFIK_BEY', 2, '11FR206', 'SMALL', 'RIGHT', 50),
               ('TEVFIK_BEY', 2, '06BNP145', 'SMALL', 'LEFT', 140),
               ('TEVFIK_BEY', 3, '11HT3307', 'LARGE', 'LEFT', 22),
               ('TEVFIK_BEY', 3, '16ZA512', 'SMALL', 'RIGHT', 70),
               ('ALI_RIZA_OZKAY', 1, '11ABS44', 'SMALL', 'LEFT', 8),
               ('ALI_RIZA_OZKAY', 1, '26EK9012', 'SMALL', 'RIGHT', 180),
               ('ALI_RIZA_OZKAY', 2, '11MN731', 'LARGE', 'RIGHT', 41),
               ('CUMHURIYET', 1, '11CC1923', 'SMALL', 'LEFT', 63),
               ('CUMHURIYET', 1, '11KB208', 'SMALL', 'RIGHT', 15)
       ) AS v(street, zone_number, plate, vtype, side, minutes_ago)
  JOIN zones z ON z.street = v.street AND z.zone_number = v.zone_number;

-- 5) Geçmiş kayıtlar: son 10 gün, her bölgede günde 3 kayıt; bugün için her bölgede 2 kayıt
CREATE TEMP TABLE demo_history ON COMMIT DROP AS
WITH zone_officer AS (
    SELECT z.id AS zone_id, z.street,
           COALESCE((SELECT u.id FROM users u WHERE u.zone_id = z.id AND u.role = 'OFFICER' ORDER BY u.id LIMIT 1),
                    (SELECT id FROM users WHERE username = 'tevfik')) AS officer_id
      FROM zones z
), raw AS (
    -- Geçmiş günler: 08:00-18:00 arası giriş, 10-240 dk kalış
    SELECT zo.*, current_date - d + time '08:00' + make_interval(mins => (random() * 600)::int) AS entry_time,
           10 + (random() * 230)::int AS minutes
      FROM zone_officer zo, generate_series(1, 10) AS d, generate_series(1, 3) AS k
    UNION ALL
    -- Bugün: şu andan 1-5 saat önce giriş, çıkış şu andan önce
    SELECT zo.*, localtimestamp - make_interval(mins => 60 + (random() * 240)::int), 10 + (random() * 45)::int
      FROM zone_officer zo, generate_series(1, 2) AS k
)
SELECT raw.*,
       '11' || chr(65 + (random() * 25)::int) || chr(65 + (random() * 25)::int)
            || lpad(((random() * 899)::int + 100)::text, 3, '0') AS plate,
       CASE WHEN random() < 0.2 THEN 'LARGE' ELSE 'SMALL' END AS vtype,
       CASE WHEN random() < 0.6 THEN 'CASH' ELSE 'POS' END AS method
  FROM raw;

INSERT INTO parking_records (license_plate, street, zone_id, region, neighborhood, entry_time, exit_time,
                             vehicle_type, fee, status, debt_paid, payment_method,
                             collected_by_id, collected_by_username, device_id)
SELECT h.plate, h.street, h.zone_id, 'Merkez', 'Bilecik', h.entry_time, h.entry_time + make_interval(mins => h.minutes),
       h.vtype, pg_temp.demo_fee(h.vtype, h.minutes), 'PAID', true, h.method,
       u.id, u.username, 'demo-seed'
  FROM demo_history h JOIN users u ON u.id = h.officer_id;

-- Abonenin geçmiş çıkışı (ücret 0, ödeme yöntemi abonelik)
INSERT INTO parking_records (license_plate, street, zone_id, region, neighborhood, entry_time, exit_time,
                             vehicle_type, fee, status, debt_paid, payment_method,
                             collected_by_id, collected_by_username, device_id)
SELECT '11CC1923', 'CUMHURIYET', z.id, 'Merkez', 'Bilecik',
       current_date - 2 + time '09:10', current_date - 2 + time '12:40',
       'SMALL', 0, 'PAID', true, 'SUBSCRIPTION', u.id, u.username, 'demo-seed'
  FROM zones z JOIN users u ON u.username = 'cumhuriyet1'
 WHERE z.street = 'CUMHURIYET' AND z.zone_number = 1;

-- 6) Kaçak: 3 gün önce ödemeden ayrıldı (vatandaş sayfasında borç olarak görünür)
INSERT INTO parking_records (license_plate, street, zone_id, region, neighborhood, entry_time, exit_time,
                             vehicle_type, fee, status, debt_paid, payment_method,
                             collected_by_id, collected_by_username, device_id)
SELECT '11KCK47', 'ALI_RIZA_OZKAY', z.id, 'Merkez', 'Bilecik',
       current_date - 3 + time '14:05', current_date - 3 + time '16:20',
       'SMALL', pg_temp.demo_fee('SMALL', 135), 'RUNAWAY', false, NULL,
       u.id, u.username, 'demo-seed'
  FROM zones z JOIN users u ON u.username = 'ozkay2'
 WHERE z.street = 'ALI_RIZA_OZKAY' AND z.zone_number = 2;

-- 7) Vardiyalar: her görevli için son 10 günde 08:00-18:00
INSERT INTO shifts (user_id, username, zone_id, device_id, start_time, end_time, auto_closed)
SELECT u.id, u.username, u.zone_id, 'demo-seed',
       current_date - d + time '08:00', current_date - d + time '18:00', false
  FROM users u, generate_series(1, 10) AS d
 WHERE u.role = 'OFFICER';

-- 8) Mali yıl (yoksa)
INSERT INTO fiscal_years (start_date, end_date, label, is_active, archived)
SELECT date '2026-01-01', date '2026-12-31', '2026', true, false
 WHERE NOT EXISTS (SELECT 1 FROM fiscal_years);

COMMIT;

SELECT 'aktif arac' AS ne, count(*) AS adet FROM active_park_spots
UNION ALL SELECT 'gecmis kayit (demo)', count(*) FROM parking_records WHERE device_id = 'demo-seed'
UNION ALL SELECT 'kacak', count(*) FROM parking_records WHERE status = 'RUNAWAY' AND NOT debt_paid
UNION ALL SELECT 'aktif abone', count(*) FROM subscriptions WHERE status = 'ACTIVE'
UNION ALL SELECT 'gorevli', count(*) FROM users WHERE role = 'OFFICER';
