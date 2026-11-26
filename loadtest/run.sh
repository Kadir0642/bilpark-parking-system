#!/usr/bin/env bash
# Tek bir yük koşusu: kaynak örnekleyiciyi başlatır, yük üretecini (gen konteyneri) çalıştırır, sonra
# app günlüğünden Hikari/GC satırlarını ve pg_stat_statements'ın en pahalı sorgularını results/'a yazar.
#
# Kullanım (repo kökünden):
#   bash loadtest/run.sh <etiket> [ORTAM=DEĞER ...]
#   bash loadtest/run.sh temel OFFICERS=10 DURATION=900
#
# Sonuçlar: loadtest/results/<etiket>.json (gecikme, hata, SSE, doğruluk), <etiket>-kaynak.csv,
#           <etiket>-hikari.txt, <etiket>-gc.txt, <etiket>-sorgular.txt, <etiket>-dogruluk.txt
set -u
export MSYS_NO_PATHCONV=1
LABEL="$1"; shift
P="docker compose -p bilpark-yuk -f loadtest/docker-compose.yml"
R=loadtest/results
mkdir -p "$R"

envs=(-e "LABEL=$LABEL" -e "OUT=/results/$LABEL.json")
for kv in "$@"; do envs+=(-e "$kv"); done

docker exec bilpark-yuk-db-1 psql -U bilpark -d bilpark_yuk -qc "CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SELECT pg_stat_statements_reset();" >/dev/null
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)

bash loadtest/sampler.sh "$R/$LABEL-kaynak.csv" &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null' EXIT

$P --profile gen run --rm "${envs[@]}" gen node yuk.mjs 2>&1 | tee "$R/$LABEL.log"

kill $SAMPLER 2>/dev/null
docker logs --since "$since" bilpark-yuk-app-1 2>&1 | grep -E "cleanup stats|Timeout failure|Connection is not available|Exception|ERROR|WARN" | head -3000 >"$R/$LABEL-hikari.txt"
docker logs --since "$since" bilpark-yuk-app-1 2>&1 | grep -E "\] GC\(" > "$R/$LABEL-gc.txt"
docker exec bilpark-yuk-db-1 psql -U bilpark -d bilpark_yuk -P pager=off -c "
  SELECT calls, round(total_exec_time::numeric) AS total_ms, round(mean_exec_time::numeric, 2) AS mean_ms,
         round(max_exec_time::numeric) AS max_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 160) AS query
    FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'bilpark_yuk')
   ORDER BY total_exec_time DESC LIMIT 25;" > "$R/$LABEL-sorgular.txt"
docker exec -i bilpark-yuk-db-1 psql -U bilpark -d bilpark_yuk -P pager=off < loadtest/dogruluk.sql > "$R/$LABEL-dogruluk.txt" 2>&1
echo "bitti: $R/$LABEL.*"
