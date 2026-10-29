-- V7: brings a database created by the March 2026 app (commit 6abc999, Hibernate ddl-auto, before Flyway)
-- up to the V1 shape. That schema has only active_park_spots and parking_records, without
-- version / zone_id / debt_paid. Flyway baselines such a database at version 0 (application.properties),
-- so V1 creates the missing tables (users, zones, subscriptions, fiscal_years) and V2-V6 run as usual;
-- this file adds the missing columns. On a fresh database and on one already at V6 it changes nothing.

-- 1) active_park_spots.version (@Version). Existing rows start at 0: a NULL version makes
--    Hibernate's optimistic-lock update match no row.
ALTER TABLE active_park_spots ADD COLUMN IF NOT EXISTS version bigint;
UPDATE active_park_spots SET version = 0 WHERE version IS NULL;

-- 2) zone_id on both tables. Old rows keep NULL: the March app had no zones, and a street can have
--    several of them, so no zone is guessed. ParkingService already falls back to the street for them.
ALTER TABLE active_park_spots ADD COLUMN IF NOT EXISTS zone_id bigint;
ALTER TABLE parking_records ADD COLUMN IF NOT EXISTS zone_id bigint;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_active_park_spots_zone') THEN
        ALTER TABLE active_park_spots ADD CONSTRAINT fk_active_park_spots_zone
            FOREIGN KEY (zone_id) REFERENCES zones (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_parking_records_zone') THEN
        ALTER TABLE parking_records ADD CONSTRAINT fk_parking_records_zone
            FOREIGN KEY (zone_id) REFERENCES zones (id);
    END IF;
END $$;

-- 3) parking_records.debt_paid. Only when the column is missing (an existing column keeps its values):
--    a March RUNAWAY record holds the unpaid debt in fee, so it is not paid; every other record is.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name = 'parking_records'
                     AND column_name = 'debt_paid') THEN
        ALTER TABLE parking_records ADD COLUMN debt_paid boolean NOT NULL DEFAULT true;
        UPDATE parking_records SET debt_paid = false WHERE status = 'RUNAWAY';
        ALTER TABLE parking_records ALTER COLUMN debt_paid DROP DEFAULT;
    END IF;
END $$;

-- 4) Hibernate's enum CHECK constraints on street / status (V4 and V5 dropped the others).
--    A fresh database has none; keeping them would block values the fresh schema accepts.
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT t.relname AS tbl, c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema()
          AND t.relname IN ('active_park_spots', 'parking_records')
          AND c.contype = 'c'
          AND (pg_get_constraintdef(c.oid) ILIKE '%street%' OR pg_get_constraintdef(c.oid) ILIKE '%status%')
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, r.conname);
    END LOOP;
END $$;
