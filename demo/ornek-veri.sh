#!/bin/sh
# Yerel demo veritabanina ornek veri yukler (demo/ornek-veri.sql).
# Kullanim (proje kokunden, 'docker compose up' calisirken): sh demo/ornek-veri.sh
# Yalnizca compose icindeki 'db' konteynerine baglanir; SQL dosyasi da bilpark_demo disinda calismayi reddeder.
set -e
cd "$(dirname "$0")/.."
docker compose cp demo/ornek-veri.sql db:/tmp/ornek-veri.sql
docker compose exec -T db sh -c 'psql -U "$DB_USERNAME" -d bilpark_demo -v ON_ERROR_STOP=1 -f /tmp/ornek-veri.sql'
echo "Ornek veri yuklendi. Paneli yenileyin: http://localhost:5500/admin-panel.html"
