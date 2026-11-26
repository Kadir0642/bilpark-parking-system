#!/usr/bin/env bash
# Kaynak örnekleyici: her ~5 sn'de bir CSV satırı.
#   host_cpu     : Windows ana makine CPU yükü (%), başka iş (ör. Android emülatörü) ölçümü bozuyor mu diye
#   emulator     : qemu/emulator süreci var mı (1/0)
#   app_* / db_* : docker stats (CPU % tek çekirdeğe göre; 0.1 CPU sınırı = en fazla ~10%)
#   app_rss_kb, app_threads : JVM sürecinin /proc/1/status değerleri
#   db_conns, db_active     : pg_stat_activity (bilpark kullanıcısı), aktif = sorgu çalıştıran
# Kullanım: bash loadtest/sampler.sh loadtest/results/temel-kaynak.csv   (Ctrl+C / kill ile durur)
set -u
OUT="${1:-loadtest/results/kaynak.csv}"
APP=bilpark-yuk-app-1
DB=bilpark-yuk-db-1
echo "ts,host_cpu,emulator,app_cpu,app_mem,db_cpu,db_mem,app_rss_kb,app_threads,db_conns,db_active" > "$OUT"
while true; do
  ts=$(date +%s)
  host=$(powershell -NoProfile -Command "(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average" 2>/dev/null | tr -d '\r')
  emu=$(tasklist 2>/dev/null | grep -ciE "qemu-system|emulator\.exe")
  stats=$(docker stats --no-stream --format "{{.Name}} {{.CPUPerc}} {{.MemUsage}}" $APP $DB 2>/dev/null)
  app_cpu=$(echo "$stats" | awk -v n=$APP '$1==n{gsub("%","",$2);print $2}')
  app_mem=$(echo "$stats" | awk -v n=$APP '$1==n{print $3}')
  db_cpu=$(echo "$stats" | awk -v n=$DB '$1==n{gsub("%","",$2);print $2}')
  db_mem=$(echo "$stats" | awk -v n=$DB '$1==n{print $3}')
  st=$(docker exec $APP sh -c "grep -E 'VmRSS|Threads' /proc/1/status" 2>/dev/null)
  rss=$(echo "$st" | awk '/VmRSS/{print $2}')
  thr=$(echo "$st" | awk '/Threads/{print $2}')
  conns=$(docker exec $DB psql -U bilpark -d bilpark_yuk -tAc "SELECT count(*) || ',' || count(*) FILTER (WHERE state = 'active') FROM pg_stat_activity WHERE usename = 'bilpark' AND pid <> pg_backend_pid()" 2>/dev/null | tr -d '\r')
  echo "$ts,${host:-},${emu:-0},${app_cpu:-},${app_mem:-},${db_cpu:-},${db_mem:-},${rss:-},${thr:-},${conns:-,}" >> "$OUT"
  sleep 3
done
