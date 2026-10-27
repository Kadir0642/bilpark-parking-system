"""BilPark uçtan uca testleri için host yardımcısı.

Telefondaki integration_test, bu sunucuya `adb reverse tcp:8099 tcp:8099` ile ulaşır ve
yalnızca test yığınında (compose projesi bilpark-e2e) şunları yaptırır:
  POST /backend/stop                     test backend konteynerini durdurur (ağ kesintisi)
  POST /backend/start                    başlatır, /api/health 200 olana kadar bekler
  POST /backdate?plate=11AB123&minutes=75 park hâlindeki aracın giriş zamanını geri alır
  POST /screenshot?name=M1_PASS          telefon ekranını PNG olarak kaydeder (adb screencap)
  POST /result?id=&status=&title=&message=  madde sonucunu results.jsonl'e yazar

Kullanım: python host_helper.py --out <klasör> [--project bilpark-e2e] [--api-port 8082] [--device R68RB06E53R]
"""

import argparse
import json
import os
import re
import subprocess
import time
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ARGS = None


def run(cmd, timeout=60):
    return subprocess.run(cmd, capture_output=True, timeout=timeout)


def adb():
    sdk = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    return sdk if os.path.exists(sdk) else "adb"


def health_ok():
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{ARGS.api_port}/api/health", timeout=3) as r:
            return r.status == 200
    except Exception:
        return False


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, text):
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *a):
        print(f"[{datetime.now():%H:%M:%S}] {fmt % a}", flush=True)

    def do_POST(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        backend = f"{ARGS.project}-backend-1"
        db = f"{ARGS.project}-db-1"
        try:
            if u.path == "/backend/stop":
                r = run(["docker", "stop", "-t", "5", backend])
                return self._send(200 if r.returncode == 0 else 500, r.stdout.decode() + r.stderr.decode())
            if u.path == "/backend/start":
                r = run(["docker", "start", backend])
                if r.returncode != 0:
                    return self._send(500, r.stderr.decode())
                end = time.time() + 170
                while time.time() < end:
                    if health_ok():
                        return self._send(200, "UP")
                    time.sleep(1)
                return self._send(504, "health zaman aşımı")
            if u.path == "/backdate":
                plate = q.get("plate", "")
                minutes = q.get("minutes", "")
                if not re.fullmatch(r"[A-Z0-9]{4,12}", plate) or not re.fullmatch(r"\d{1,5}", minutes):
                    return self._send(400, "geçersiz plate/minutes")
                sql = (f"UPDATE active_park_spots SET entry_time = entry_time - interval '{minutes} minutes' "
                       f"WHERE current_plate = '{plate}'")
                r = run(["docker", "exec", db, "sh", "-c", f'psql -U "$DB_USERNAME" -d bilpark_demo -c "{sql}"'])
                out = r.stdout.decode() + r.stderr.decode()
                ok = r.returncode == 0 and "UPDATE 1" in out
                return self._send(200 if ok else 500, out)
            if u.path == "/screenshot":
                name = re.sub(r"[^A-Za-z0-9_.-]", "_", q.get("name", "shot"))[:80]
                path = os.path.join(ARGS.out, f"{datetime.now():%H%M%S}_{name}.png")
                r = run([adb(), "-s", ARGS.device, "exec-out", "screencap", "-p"], timeout=20)
                if r.returncode != 0 or not r.stdout:
                    return self._send(500, r.stderr.decode())
                with open(path, "wb") as f:
                    f.write(r.stdout)
                return self._send(200, path)
            if u.path == "/result":
                rec = {"time": f"{datetime.now():%H:%M:%S}", **q}
                with open(os.path.join(ARGS.out, "results.jsonl"), "a", encoding="utf-8") as f:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                print(f"  >> {q.get('id')} {q.get('status')} {q.get('title')}", flush=True)
                return self._send(200, "ok")
            return self._send(404, "bilinmeyen uç")
        except Exception as e:  # noqa: BLE001
            return self._send(500, repr(e))


def main():
    global ARGS
    p = argparse.ArgumentParser()
    p.add_argument("--out", required=True)
    p.add_argument("--project", default="bilpark-e2e")
    p.add_argument("--api-port", default="8082")
    p.add_argument("--device", default="R68RB06E53R")
    p.add_argument("--port", type=int, default=8099)
    ARGS = p.parse_args()
    os.makedirs(ARGS.out, exist_ok=True)
    print(f"host_helper :{ARGS.port} proje={ARGS.project} çıktı={ARGS.out}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
