-- V10: foreign / special (non-TR format) plates (docs/api-arac-tipleri.md, "Yabancı / özel plaka").
-- The officer marks the entry as foreign on check-in (foreignPlate=true); the flag is copied into the record on
-- check-out, runaway and cancellation. Existing rows are all Turkish plates, so the default false is correct for them.
-- ADD COLUMN IF NOT EXISTS with a constant default: safe on a database that already has data (production is at V9),
-- and safe if Hibernate ddl-auto=update created the column first.
ALTER TABLE active_park_spots ADD COLUMN IF NOT EXISTS foreign_plate boolean NOT NULL DEFAULT false;
ALTER TABLE parking_records   ADD COLUMN IF NOT EXISTS foreign_plate boolean NOT NULL DEFAULT false;
