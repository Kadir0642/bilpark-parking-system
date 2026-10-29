-- V8: developer monitoring (docs/api-gelistirici-izleme.md, section 3).
-- Device trace per user: last time the user was seen, and the app version / device the app reported
-- (X-App-Version / X-Device headers). Nullable: nobody has been seen before this migration.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at timestamp(6);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_app_version varchar(40);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_device varchar(80);
