#!/usr/bin/env bash
# Uçtan uca test dosyalarını sırayla telefonda koşar. flutter test, Android'de ara sıra uygulamayı açılış
# ekranında bırakıp test gövdesini hiç başlatmıyor; bu durumda (60 sn içinde "startApp" izi yoksa) koşu
# kapatılıp en fazla 2 kez yeniden denenir.
#
# Kullanım (mobile/ içinden):
#   E2E_ADMIN_PASSWORD=... E2E_OFFICER_PASSWORD=... E2E_NEW_OFFICER_PASSWORD=... \
#     integration_test/tools/run_e2e.sh <log-klasörü> m1_saha_akisi m2_canli_senkron m3_hesap
# İsteğe bağlı: DEVICE (varsayılan R68RB06E53R), API_BASE (varsayılan http://127.0.0.1:8082/api).

set -u
logdir=$1; shift
device=${DEVICE:-R68RB06E53R}
api=${API_BASE:-http://127.0.0.1:8082/api}
adb="${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe"
[ -x "$adb" ] || adb=adb
mkdir -p "$logdir"

run_once() { # $1 dosya adı, $2 log
  flutter test "integration_test/$1_test.dart" -d "$device" \
    --dart-define=API_BASE="$api" \
    --dart-define=E2E_ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD" \
    --dart-define=E2E_OFFICER_PASSWORD="$E2E_OFFICER_PASSWORD" \
    --dart-define=E2E_NEW_OFFICER_PASSWORD="${E2E_NEW_OFFICER_PASSWORD:-}" > "$2" 2>&1 &
  local pid=$! started=""
  while kill -0 "$pid" 2>/dev/null; do
    # Sayaç APK kurulumu bitince başlar (takılma test adı yazılmadan önce de olabiliyor)
    if [ -z "$started" ] && grep -qE "^Installing .*[0-9]s$|^00:00 \+0: [^l]" "$2"; then started=$(date +%s); fi
    if [ -n "$started" ] && ! grep -q "E2E-LOG|startApp" "$2" && [ $(( $(date +%s) - started )) -gt 75 ]; then
      echo "=== $1 açılışta takıldı, yeniden denenecek" >> "$logdir/all.log"
      kill "$pid" 2>/dev/null
      # flutter aracının alt süreçleri (Windows) ve uygulama kapatılır
      powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='dart.exe' or Name='dartvm.exe'\" | Where-Object { \$_.CommandLine -match 'integration_test|development-service' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
      "$adb" -s "$device" shell am force-stop com.example.mobile >/dev/null 2>&1
      return 2
    fi
    sleep 2
  done
  wait "$pid"
}

for f in "$@"; do
  for try in 1 2 3; do
    echo "=== $f başlıyor (deneme $try)" >> "$logdir/all.log"
    run_once "$f" "$logdir/${f}_$try.log"
    rc=$?
    [ $rc -eq 2 ] && continue
    echo "=== $f bitti rc=$rc" >> "$logdir/all.log"
    break
  done
done
