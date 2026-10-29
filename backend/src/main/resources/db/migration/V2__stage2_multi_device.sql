-- V2: stage 2 (multi-device safety). Safe on the live (Hibernate-created) DB and on a fresh DB.

-- 1) Drop every single-column unique constraint / unique index on subscriptions(license_plate)
--    (Hibernate generated a random name like ukXXXX on the live DB).
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema()
          AND t.relname = 'subscriptions'
          AND c.contype = 'u'
          AND array_length(c.conkey, 1) = 1
          AND (SELECT a.attname FROM pg_attribute a
               WHERE a.attrelid = c.conrelid AND a.attnum = c.conkey[1]) = 'license_plate'
    LOOP
        EXECUTE format('ALTER TABLE subscriptions DROP CONSTRAINT %I', r.conname);
    END LOOP;

    FOR r IN
        SELECT ic.relname AS idxname
        FROM pg_index i
        JOIN pg_class t ON t.oid = i.indrelid
        JOIN pg_class ic ON ic.oid = i.indexrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema()
          AND t.relname = 'subscriptions'
          AND i.indisunique
          AND NOT i.indisprimary
          AND i.indnatts = 1
          AND i.indpred IS NULL
          AND (SELECT a.attname FROM pg_attribute a
               WHERE a.attrelid = i.indrelid AND a.attnum = i.indkey[0]) = 'license_plate'
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %I', r.idxname);
    END LOOP;
END $$;

-- 2) Normalize plates: upper-case, no whitespace, no '-'
UPDATE parking_records
   SET license_plate = upper(regexp_replace(license_plate, '[\s-]', '', 'g'))
 WHERE license_plate IS NOT NULL
   AND license_plate <> upper(regexp_replace(license_plate, '[\s-]', '', 'g'));

UPDATE subscriptions
   SET license_plate = upper(regexp_replace(license_plate, '[\s-]', '', 'g'))
 WHERE license_plate <> upper(regexp_replace(license_plate, '[\s-]', '', 'g'));

-- active_park_spots: current_plate is unique. If two active rows normalize to the same plate
-- ("34 ABC 123" and "34ABC123") we cannot pick one automatically without losing a vehicle,
-- so stop with a readable error. Flyway runs this file in one transaction on PostgreSQL,
-- so nothing above is kept and the database stays as it was. Resolve the listed rows by hand
-- (check one of them out from the old app or delete the wrong one), then restart.
DO $$
DECLARE
    conflicts text;
BEGIN
    SELECT string_agg(norm || ' (id: ' || ids || ')', '; ')
      INTO conflicts
      FROM (SELECT upper(regexp_replace(current_plate, '[\s-]', '', 'g')) AS norm,
                   string_agg(id::text, ', ' ORDER BY id) AS ids
              FROM active_park_spots
             GROUP BY 1
            HAVING count(*) > 1) d;
    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION 'V2 durduruldu: active_park_spots icinde normalize edilince ayni olan aktif plakalar var: %', conflicts
            USING HINT = 'Cakisan kayitlardan birini kapatin veya silin, sonra uygulamayi yeniden baslatin.';
    END IF;
END $$;

UPDATE active_park_spots
   SET current_plate = upper(regexp_replace(current_plate, '[\s-]', '', 'g'))
 WHERE current_plate <> upper(regexp_replace(current_plate, '[\s-]', '', 'g'));

-- 3) At most one ACTIVE subscription per plate: keep the one with the latest end_date, cancel the rest
UPDATE subscriptions s
   SET status = 'CANCELLED'
 WHERE s.status = 'ACTIVE'
   AND EXISTS (
        SELECT 1 FROM subscriptions o
         WHERE o.license_plate = s.license_plate
           AND o.status = 'ACTIVE'
           AND o.id <> s.id
           AND (o.end_date > s.end_date OR (o.end_date = s.end_date AND o.id > s.id)));

CREATE UNIQUE INDEX IF NOT EXISTS ux_subscriptions_active_plate
    ON subscriptions (license_plate) WHERE status = 'ACTIVE';

-- 4) parking_records: who collected, how, on which device.
--    Historical rows intentionally keep payment_method NULL (the real method is unknown; no guessing).
ALTER TABLE parking_records ADD COLUMN IF NOT EXISTS payment_method varchar(20);
ALTER TABLE parking_records ADD COLUMN IF NOT EXISTS collected_by_id bigint;
ALTER TABLE parking_records ADD COLUMN IF NOT EXISTS collected_by_username varchar(255);
ALTER TABLE parking_records ADD COLUMN IF NOT EXISTS device_id varchar(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema() AND t.relname = 'parking_records'
          AND c.conname = 'fk_parking_records_collected_by') THEN
        ALTER TABLE parking_records
            ADD CONSTRAINT fk_parking_records_collected_by
            FOREIGN KEY (collected_by_id) REFERENCES users (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_parking_records_exit_time ON parking_records (exit_time);
CREATE INDEX IF NOT EXISTS ix_parking_records_license_plate ON parking_records (license_plate);
CREATE INDEX IF NOT EXISTS ix_parking_records_status ON parking_records (status);

-- 5) fiscal_years: archive flag (archiving never deletes records)
ALTER TABLE fiscal_years ADD COLUMN IF NOT EXISTS archived boolean NOT NULL DEFAULT false;
ALTER TABLE fiscal_years ADD COLUMN IF NOT EXISTS archived_at timestamp(6);

-- 6) shifts (per user + device)
CREATE TABLE IF NOT EXISTS shifts (
    id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id     bigint       NOT NULL,
    username    varchar(255) NOT NULL,
    zone_id     bigint,
    device_id   varchar(64)  NOT NULL,
    start_time  timestamp(6) NOT NULL,
    end_time    timestamp(6),
    auto_closed boolean      NOT NULL,
    CONSTRAINT fk_shifts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_shifts_zone FOREIGN KEY (zone_id) REFERENCES zones (id)
);
CREATE INDEX IF NOT EXISTS ix_shifts_user_device_open ON shifts (user_id, device_id, end_time);
