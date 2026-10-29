#!/usr/bin/env bash
# Telefon bağlanınca tek komutla uçtan uca doğrulama:
#   1) ayrı test yığınını kurar (compose projesi bilpark-e2e, portlar 5434/8082/5501; demo yığınına dokunmaz),
#   2) örnek veriyi ve test hazırlığını yükler, telefonu adb reverse ile bağlar, host yardımcısını açar,
#   3) testleri sırayla telefonda koşar (varsayılan: m0, m4, m1, m2, m3; m3 için panelmemur API ile açılır),
#   4) test yığınını kaldırır ve telefona demo (8081) APK'sını geri kurar.
#
# Kullanım (worktree kökünden, Git Bash):
#   bash mobile/integration_test/tools/telefon_dogrula.sh                 # varsayılan dosyalar
#   bash mobile/integration_test/tools/telefon_dogrula.sh m4_bulgular     # yalnızca bulgu testleri
# İsteğe bağlı: DEVICE (yoksa ilk bağlı cihaz), KEEP_STACK=1 (yığını kaldırma), NO_DEMO_APK=1.

set -u
root=$(git rev-parse --show-toplevel) || exit 1
cd "$root" || exit 1
adb="${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe"
[ -x "$adb" ] || adb=adb
files=("$@")
[ ${#files[@]} -eq 0 ] && files=(m0_baglanti m4_bulgular m1_saha_akisi m2_canli_senkron m3_hesap)
logdir="$root/mobile/build/e2e-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$logdir"

device=${DEVICE:-$("$adb" devices | awk 'NR>1 && $2=="device" {print $1; exit}')}
if [ -z "$device" ]; then
  echo "Telefon bulunamadı: USB hata ayıklamayı açıp kabloyu takın ('adb devices' cihazı göstermeli)." >&2
  exit 1
fi
echo "Cihaz: $device · kayıtlar: $logdir"

# --- 1) Test yığını ---
if [ ! -f .env.demo ]; then
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/yeni-env.ps1 || exit 1
fi
envval() { grep -E "^$1=" .env.demo | head -1 | cut -d= -f2- | tr -d '\r'; }
export E2E_ADMIN_PASSWORD=$(envval ADMIN_PASSWORD)
export E2E_OFFICER_PASSWORD=$(envval OFFICER_PASSWORD)
export E2E_NEW_OFFICER_PASSWORD=${E2E_NEW_OFFICER_PASSWORD:-}
compose() { DEMO_DB_PORT=5434 DEMO_API_PORT=8082 DEMO_WEB_PORT=5501 docker compose -p bilpark-e2e "$@"; }

cleanup() {
  [ -n "${helper_pid:-}" ] && kill "$helper_pid" 2>/dev/null
  "$adb" -s "$device" reverse --remove tcp:8082 >/dev/null 2>&1
  "$adb" -s "$device" reverse --remove tcp:8099 >/dev/null 2>&1
  [ "${KEEP_STACK:-0}" = 1 ] || compose down -v >/dev/null 2>&1
}
trap cleanup EXIT

compose up -d --build || exit 1
echo -n "Test backend bekleniyor"
for _ in $(seq 1 90); do
  curl -sf http://127.0.0.1:8082/api/health >/dev/null && break
  echo -n "."; sleep 2
done
echo
curl -sf http://127.0.0.1:8082/api/health >/dev/null || { echo "Test backend 8082'de açılmadı." >&2; exit 1; }
docker cp demo/ornek-veri.sql bilpark-e2e-db-1:/tmp/ornek-veri.sql
MSYS_NO_PATHCONV=1 docker exec bilpark-e2e-db-1 sh -c 'psql -U "$DB_USERNAME" -d bilpark_demo -v ON_ERROR_STOP=1 -q -f /tmp/ornek-veri.sql' || exit 1
MSYS_NO_PATHCONV=1 docker exec -i bilpark-e2e-db-1 sh -c 'psql -U "$DB_USERNAME" -d bilpark_demo -v ON_ERROR_STOP=1 -q' \
  < mobile/integration_test/tools/e2e-hazirlik.sql || exit 1

# m3'ün ön koşulu: panelde açılmış gibi 'panelmemur' (Tevfik Bey - Bölüm 2), rastgele şifreyle API üzerinden
export E2E_NEW_OFFICER_PASSWORD=${E2E_NEW_OFFICER_PASSWORD:-$(python -c "import secrets,string;a=string.ascii_letters+string.digits;print('Pm7'+''.join(secrets.choice(a) for _ in range(11)))")}
python - <<'EOF' || exit 1
import json, os, urllib.request, urllib.error
base = 'http://127.0.0.1:8082/api'
def call(path, body, token=None):
    h = {'Content-Type': 'application/json', 'X-Device-Id': 'e2e-hazirlik'}
    if token: h['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, json.dumps(body).encode(), h, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=15) as r: return json.loads(r.read() or b'null')
    except urllib.error.HTTPError as e:
        if e.code == 409: return None  # zaten var
        raise SystemExit(f'{path}: HTTP {e.code} {e.read().decode()}')
tok = call('/auth/login', {'username': 'admin', 'password': os.environ['E2E_ADMIN_PASSWORD']})['token']
call('/admin/users', {'username': 'panelmemur', 'fullName': 'Panel Memur', 'password': os.environ['E2E_NEW_OFFICER_PASSWORD'],
                      'role': 'OFFICER', 'zoneId': 2}, tok)
print('panelmemur hazır')
EOF

# --- 2) Telefon ve host yardımcısı ---
"$adb" -s "$device" reverse tcp:8082 tcp:8082 >/dev/null
"$adb" -s "$device" reverse tcp:8099 tcp:8099 >/dev/null
python mobile/integration_test/tools/host_helper.py --out "$logdir" --device "$device" > "$logdir/host_helper.log" 2>&1 &
helper_pid=$!
sleep 2

# --- 3) Testler ---
(cd mobile && DEVICE="$device" bash integration_test/tools/run_e2e.sh "$logdir" "${files[@]}")

echo
echo "=== Sonuçlar ($logdir/results.jsonl)"
if [ -f "$logdir/results.jsonl" ]; then
  PYTHONIOENCODING=utf-8 python -c "import json,sys
rows=[json.loads(l) for l in open(sys.argv[1],encoding='utf-8') if l.strip()]
for r in rows: print(('GEÇTİ ' if r.get('status')=='PASS' else 'KALDI ')+r.get('id','?')+' '+r.get('title',''))
print(f\"{sum(r.get('status')=='PASS' for r in rows)}/{len(rows)} madde geçti\")" "$logdir/results.jsonl"
fi
grep -hE "^[0-9:]+ \+[0-9]+.*(All tests passed|Some tests failed)" "$logdir"/*.log 2>/dev/null | tail -5

# --- 4) Demo APK'sını geri kur ---
if [ "${NO_DEMO_APK:-0}" != 1 ]; then
  (cd mobile && flutter build apk --debug --dart-define=API_BASE=http://127.0.0.1:8081/api >/dev/null \
    && "$adb" -s "$device" install -r build/app/outputs/flutter-apk/app-debug.apk >/dev/null \
    && "$adb" -s "$device" reverse tcp:8081 tcp:8081 >/dev/null \
    && echo "Demo APK (8081) telefona geri kuruldu.")
fi
