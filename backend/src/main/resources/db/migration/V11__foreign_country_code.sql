-- V11: optional country code of a foreign / special plate (docs/api-arac-tipleri.md, "Yabancı plaka ülke kodu").
-- The vehicle's international distinguishing sign, 1-3 upper-case letters A-Z (D, NL, GB, BG, RO, CH ...).
-- Set only on check-in with foreignPlate=true and copied into the record on check-out, runaway and cancellation.
-- Nullable without a default: existing rows and foreign entries without a code stay NULL.
-- ADD COLUMN IF NOT EXISTS: safe on a database that already has data, and safe if Hibernate ddl-auto=update
-- created the column first.
ALTER TABLE active_park_spots ADD COLUMN IF NOT EXISTS foreign_country_code varchar(3);
ALTER TABLE parking_records   ADD COLUMN IF NOT EXISTS foreign_country_code varchar(3);
