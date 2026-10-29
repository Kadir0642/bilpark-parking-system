-- V5: vehicle types as data (vehicle_types) and per-tariff prices (tariff_vehicle_prices).
-- Safe on the live DB and on a fresh DB (IF NOT EXISTS / ON CONFLICT everywhere).

CREATE TABLE IF NOT EXISTS vehicle_types (
    code         varchar(30) PRIMARY KEY,
    display_name varchar(60) NOT NULL,
    icon_key     varchar(30) NOT NULL,
    sort_order   integer     NOT NULL,
    active       boolean     NOT NULL
);

INSERT INTO vehicle_types (code, display_name, icon_key, sort_order, active) VALUES
    ('SMALL', 'Otomobil', 'car', 10, true),
    ('LARGE', 'Kamyonet', 'truck', 20, true),
    ('MOTORCYCLE', 'Motosiklet', 'motorcycle', 30, false)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS tariff_vehicle_prices (
    tariff_id            bigint      NOT NULL,
    vehicle_type         varchar(30) NOT NULL,
    enabled              boolean     NOT NULL,
    first_hour           float(53),
    extra_hour           float(53),
    subscription_monthly float(53),
    PRIMARY KEY (tariff_id, vehicle_type),
    CONSTRAINT fk_tvp_tariff FOREIGN KEY (tariff_id) REFERENCES tariffs (id) ON DELETE CASCADE,
    CONSTRAINT fk_tvp_vehicle_type FOREIGN KEY (vehicle_type) REFERENCES vehicle_types (code)
);

-- Move the old small/large columns into rows, then drop them (only while they still exist)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'tariffs' AND column_name = 'small_first_hour') THEN
        INSERT INTO tariff_vehicle_prices (tariff_id, vehicle_type, enabled, first_hour, extra_hour, subscription_monthly)
        SELECT id, 'SMALL', true, small_first_hour, small_extra_hour, subscription_small_monthly FROM tariffs
        ON CONFLICT DO NOTHING;
        INSERT INTO tariff_vehicle_prices (tariff_id, vehicle_type, enabled, first_hour, extra_hour, subscription_monthly)
        SELECT id, 'LARGE', true, large_first_hour, large_extra_hour, subscription_large_monthly FROM tariffs
        ON CONFLICT DO NOTHING;
    END IF;
END $$;

ALTER TABLE tariffs DROP COLUMN IF EXISTS small_first_hour;
ALTER TABLE tariffs DROP COLUMN IF EXISTS small_extra_hour;
ALTER TABLE tariffs DROP COLUMN IF EXISTS large_first_hour;
ALTER TABLE tariffs DROP COLUMN IF EXISTS large_extra_hour;
ALTER TABLE tariffs DROP COLUMN IF EXISTS subscription_small_monthly;
ALTER TABLE tariffs DROP COLUMN IF EXISTS subscription_large_monthly;

-- Drop Hibernate's enum CHECK constraints on the three vehicle type columns
DO $$
DECLARE
    r record;
    spec text[];
BEGIN
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ARRAY['active_park_spots', 'current_type'],
        ARRAY['parking_records', 'vehicle_type'],
        ARRAY['subscriptions', 'vehicle_type']] LOOP
        FOR r IN
            SELECT c.conname
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = current_schema()
              AND t.relname = spec[1]
              AND c.contype = 'c'
              AND pg_get_constraintdef(c.oid) ILIKE '%' || spec[2] || '%'
        LOOP
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', spec[1], r.conname);
        END LOOP;
    END LOOP;
END $$;

-- Defensive: any value that is not a known code would break the new FK; treat it as SMALL
UPDATE active_park_spots SET current_type = 'SMALL'
    WHERE current_type IS NOT NULL AND current_type NOT IN (SELECT code FROM vehicle_types);
UPDATE parking_records SET vehicle_type = 'SMALL'
    WHERE vehicle_type IS NOT NULL AND vehicle_type NOT IN (SELECT code FROM vehicle_types);
UPDATE subscriptions SET vehicle_type = 'SMALL'
    WHERE vehicle_type IS NOT NULL AND vehicle_type NOT IN (SELECT code FROM vehicle_types);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_active_park_spots_vehicle_type') THEN
        ALTER TABLE active_park_spots ADD CONSTRAINT fk_active_park_spots_vehicle_type
            FOREIGN KEY (current_type) REFERENCES vehicle_types (code);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_parking_records_vehicle_type') THEN
        ALTER TABLE parking_records ADD CONSTRAINT fk_parking_records_vehicle_type
            FOREIGN KEY (vehicle_type) REFERENCES vehicle_types (code);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_subscriptions_vehicle_type') THEN
        ALTER TABLE subscriptions ADD CONSTRAINT fk_subscriptions_vehicle_type
            FOREIGN KEY (vehicle_type) REFERENCES vehicle_types (code);
    END IF;
END $$;
