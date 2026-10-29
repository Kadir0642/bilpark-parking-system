-- BilPark teslim sifirlamasi
-- Belge: docs/teslim-sifirlamasi.md  (once oku, once Neon'da yedek dal al)
--
-- Ne yapar: tanitimda girilen operasyonel kayitlari siler, sistemi musteriye bos teslim eder.
--   SILER   : parking_records, active_park_spots, check_in_requests, shifts, subscriptions,
--             sync_events, error_logs, audit_logs, OFFICER kullanicilari, 'admin' disindaki ADMIN'ler
--   KORUR   : zones, streets, tariffs, tariff_vehicle_prices, vehicle_types, app_settings,
--             license_info, fiscal_years, flyway_schema_history, 'admin' ve DEVELOPER hesaplari
--   EKLER   : audit_logs'a tek satir (action = SETTINGS_UPDATED, actor = system)
--
-- Calistirma (ayni oturumda, onay satiri DAHIL):
--   SET bilpark.teslim_onay = 'EVET';
--   <bu dosyanin tamami>
--
-- Onay satiri yoksa ilk DO blogu hata verir; islem yarida kesilir ve hicbir sey silinmez
-- (hata sonrasi transaction "aborted" kalir, sonraki satirlar calismaz, COMMIT geri alir).
-- psql ile: psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/teslim-sifirlama.sql
--
-- Admin sifresi: uygulama kullanicilari yalnizca users tablosu BOSKEN ADMIN_PASSWORD'den olusturur.
-- Admin ve DEVELOPER korundugu icin ADMIN_PASSWORD yeniden okunmaz. Sifre iki yoldan yenilenir:
--   1) Onerilen: sifirlamadan sonra POST /api/developer/admin-password-reset (belgede anlatilir).
--   2) Istege bagli: SET bilpark.yeni_admin_hash = '<BCrypt hash>'; ayarlanirsa bu betik admin'in
--      sifre hash'ini gunceller. Duz sifre buraya YAZILMAZ, yalnizca hash verilir.

BEGIN;

-- 1) Guvenlik kontrolleri
DO $$
BEGIN
    IF coalesce(current_setting('bilpark.teslim_onay', true), '') <> 'EVET' THEN
        RAISE EXCEPTION 'Teslim sifirlamasi onaylanmadi. Ayni oturumda once SET bilpark.teslim_onay = ''EVET''; calistirin.'
            USING HINT = 'Hicbir sey silinmedi.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin' AND role = 'ADMIN') THEN
        RAISE EXCEPTION 'Ana admin hesabi (username = admin, role = ADMIN) bulunamadi; sifirlama durduruldu.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM users WHERE role = 'DEVELOPER') THEN
        RAISE EXCEPTION 'DEVELOPER hesabi bulunamadi; sifirlama durduruldu.';
    END IF;
    IF coalesce(current_setting('bilpark.yeni_admin_hash', true), '') <> ''
       AND current_setting('bilpark.yeni_admin_hash', true) !~ '^\$2[aby]\$[0-9]{2}\$.{53}$' THEN
        RAISE EXCEPTION 'bilpark.yeni_admin_hash gecerli bir BCrypt hash degil.';
    END IF;
END $$;

-- 2) Operasyonel kayitlar (FK sirasi: once bagli tablolar)
DELETE FROM check_in_requests;
DELETE FROM active_park_spots;
DELETE FROM parking_records;
DELETE FROM shifts;
DELETE FROM subscriptions;
DELETE FROM sync_events;
DELETE FROM error_logs;
DELETE FROM audit_logs;

-- 3) Kullanicilar: OFFICER'lar ve ana admin disindaki ADMIN'ler. Korunan tarifler silinen bir
--    kullaniciya FK ile bagliysa baglanti bos birakilir (created_by_username yazili kalir).
UPDATE tariffs SET created_by_id = NULL
 WHERE created_by_id IN (SELECT id FROM users WHERE role = 'OFFICER' OR (role = 'ADMIN' AND username <> 'admin'));
DELETE FROM users WHERE role = 'OFFICER' OR (role = 'ADMIN' AND username <> 'admin');

-- 4) Istege bagli: admin sifre hash'i (eski tokenlar password_changed_at ile gecersiz olur)
UPDATE users
   SET password = current_setting('bilpark.yeni_admin_hash', true),
       password_changed_at = now()
 WHERE username = 'admin' AND role = 'ADMIN'
   AND coalesce(current_setting('bilpark.yeni_admin_hash', true), '') <> '';

-- 5) Sayaclar. sync_events SIFIRLANMAZ: cihazlar ?since=<eski id> ile sorar, id geri giderse yeni olaylari kacirirlar.
ALTER TABLE parking_records   ALTER COLUMN id RESTART WITH 1;
ALTER TABLE active_park_spots ALTER COLUMN id RESTART WITH 1;
ALTER TABLE shifts            ALTER COLUMN id RESTART WITH 1;
ALTER TABLE subscriptions     ALTER COLUMN id RESTART WITH 1;
ALTER TABLE error_logs        ALTER COLUMN id RESTART WITH 1;
ALTER TABLE audit_logs        ALTER COLUMN id RESTART WITH 1;

-- 6) Tek denetim satiri (yeni AuditAction eklenmedi; mevcut SETTINGS_UPDATED kullanildi)
INSERT INTO audit_logs (created_at, actor_id, actor_username, actor_role, action, entity_type, entity_id, summary)
VALUES (now(), NULL, 'system', NULL, 'SETTINGS_UPDATED', 'System', 'teslim-sifirlamasi',
        'Teslim sıfırlaması yapıldı: tanıtım kayıtları silindi, sistem boş teslim edildi.');

COMMIT;

-- 7) Kalan satir sayilari
SELECT 'parking_records' AS tablo, count(*) AS satir FROM parking_records
UNION ALL SELECT 'active_park_spots', count(*) FROM active_park_spots
UNION ALL SELECT 'check_in_requests', count(*) FROM check_in_requests
UNION ALL SELECT 'shifts', count(*) FROM shifts
UNION ALL SELECT 'subscriptions', count(*) FROM subscriptions
UNION ALL SELECT 'sync_events', count(*) FROM sync_events
UNION ALL SELECT 'error_logs', count(*) FROM error_logs
UNION ALL SELECT 'audit_logs', count(*) FROM audit_logs
UNION ALL SELECT 'users (OFFICER)', count(*) FROM users WHERE role = 'OFFICER'
UNION ALL SELECT 'users (ADMIN)', count(*) FROM users WHERE role = 'ADMIN'
UNION ALL SELECT 'users (DEVELOPER)', count(*) FROM users WHERE role = 'DEVELOPER'
UNION ALL SELECT 'zones', count(*) FROM zones
UNION ALL SELECT 'streets', count(*) FROM streets
UNION ALL SELECT 'tariffs', count(*) FROM tariffs
UNION ALL SELECT 'tariff_vehicle_prices', count(*) FROM tariff_vehicle_prices
UNION ALL SELECT 'vehicle_types', count(*) FROM vehicle_types
UNION ALL SELECT 'app_settings', count(*) FROM app_settings
UNION ALL SELECT 'license_info', count(*) FROM license_info
UNION ALL SELECT 'fiscal_years', count(*) FROM fiscal_years;
