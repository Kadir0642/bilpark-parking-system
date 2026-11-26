#!/usr/bin/env bash
# Soğuk başlatma ölçümü: app konteyneri başlatılır, /api/health ilk 200 olana kadar ve ardından ilk
# başarılı giriş (bcrypt + DB) ve ilk DB'li okuma (/api/parking/spots) süresi ölçülür.
# Kullanım: bash loadtest/coldstart.sh [etiket]     (db ve dbproxy çalışıyor olmalı)
set -u
TAG="${1:-soguk}"
P="docker compose -p bilpark-yuk -f loadtest/docker-compose.yml"
$P stop app >/dev/null 2>&1
t0=$(date +%s.%N)
$P up -d app >/dev/null 2>&1
until curl -sf -o /dev/null http://127.0.0.1:8090/api/health; do sleep 0.25; done
t1=$(date +%s.%N)
tok=$(curl -s -X POST http://127.0.0.1:8090/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"test-secret-admin"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
t2=$(date +%s.%N)
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tok" http://127.0.0.1:8090/api/parking/spots)
t3=$(date +%s.%N)
started=$(docker logs bilpark-yuk-app-1 2>&1 | grep -oE 'Started BackendApplication in [0-9.]+ seconds' | tail -1)
awk -v tag="$TAG" -v a="$t0" -v b="$t1" -v c="$t2" -v d="$t3" -v code="$code" -v st="$started" \
  'BEGIN{printf "%s: health=%.1fs ilk_giris=+%.1fs ilk_db_okuma=+%.1fs (spots %s) | %s\n", tag, b-a, c-b, d-c, code, st}'
