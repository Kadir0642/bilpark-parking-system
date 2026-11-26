"""Koşu özetleri: results/<etiket>.json + -kaynak.csv + -hikari.txt + -gc.txt -> kısa metin.

Kullanım: python -I loadtest/ozet.py loadtest/results a-temel-10 b-3kat-30 ...
"""
import csv
import json
import re
import sys
from pathlib import Path

KEY = [
    "POST /api/parking/check-in", "POST /api/parking/check-out", "GET /api/parking/filter",
    "GET /api/parking/history", "GET /api/sync?since", "GET /api/parking/bi/shifts",
    "GET /api/parking/bi/last-records", "GET /api/parking/bi/income-summary", "GET /api/parking/debt",
    "POST /api/auth/login", "GET /api/events (bağlantı)",
]


def mem_mb(s):
    m = re.match(r"([\d.]+)(\w+)", s or "")
    if not m:
        return None
    v, u = float(m.group(1)), m.group(2)
    return v * {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "B": 1 / 1048576}.get(u, 1)


def resources(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8"))) if path.exists() else []
    f = lambda k: [float(r[k]) for r in rows if r.get(k) not in (None, "")]
    out = {}
    for k in ("host_cpu", "app_cpu", "db_cpu", "db_conns", "db_active", "app_threads"):
        v = f(k)
        out[k] = (sum(v) / len(v), max(v)) if v else (None, None)
    rss = [float(r["app_rss_kb"]) / 1024 for r in rows if r.get("app_rss_kb")]
    out["app_rss"] = (min(rss), max(rss)) if rss else (None, None)
    dbm = [mem_mb(r["db_mem"]) for r in rows if r.get("db_mem")]
    out["db_mem_max"] = max([x for x in dbm if x] or [0])
    out["emulator"] = max([int(r["emulator"] or 0) for r in rows] or [0])
    out["samples"] = len(rows)
    return out


def hikari(path):
    act, wait, timeouts = 0, 0, 0
    if path.exists():
        for line in open(path, encoding="utf-8", errors="replace"):
            m = re.search(r"active=(\d+), waiting=(\d+)", line)
            if m:
                act, wait = max(act, int(m.group(1))), max(wait, int(m.group(2)))
            if "Connection is not available" in line:
                timeouts += 1
    return act, wait, timeouts


def gc(path):
    after, pause, full = [], [], 0
    if path.exists():
        for line in open(path, encoding="utf-8", errors="replace"):
            m = re.search(r"(\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms", line)
            if m:
                after.append(int(m.group(2)))
                pause.append(float(m.group(4)))
                full += "Pause Full" in line
    if not after:
        return None
    n = len(after)
    third = max(1, n // 3)
    return {"heap_after_first": sum(after[:third]) / third, "heap_after_last": sum(after[-third:]) / third,
            "heap_after_max": max(after), "pause_max": max(pause), "full": full, "n": n}


def main():
    d = Path(sys.argv[1])
    for label in sys.argv[2:]:
        j = json.load(open(d / f"{label}.json", encoding="utf-8"))
        t, s = j["total"], j["sse"]
        r = resources(d / f"{label}-kaynak.csv")
        h = hikari(d / f"{label}-hikari.txt")
        g = gc(d / f"{label}-gc.txt")
        print(f"### {label}")
        print(f"toplam: n={t['n']} hata=%{t['errPct']} rps={t['rps']} p50={t['p50']:.0f} p95={t['p95']:.0f} p99={t['p99']:.0f} ms")
        print(f"SSE: teslim={s['deliveries']} p50={s['p50']} p95={s['p95']} p99={s['p99']} max={s['max']} ms; "
              f"olay={s['events']} hepsine-ulaşan={s['eventsReachedAllExpected']} olay-max-p95={s['eventMaxP95']} "
              f"bağlanma={s['connects']} kopma={s['drops']} reset={s['resets']} hello-p95={s['helloP95']}")
        fm = lambda p: "-" if p[0] is None else f"ort {p[0]:.1f} / max {p[1]:.1f}"
        print(f"kaynak ({r['samples']} örnek): host_cpu {fm(r['host_cpu'])} emülatör={r['emulator']} | app_cpu {fm(r['app_cpu'])} "
              f"| db_cpu {fm(r['db_cpu'])} | app_rss {r['app_rss'][0] and round(r['app_rss'][0])}-{r['app_rss'][1] and round(r['app_rss'][1])} MB "
              f"| thread {fm(r['app_threads'])} | db_conns {fm(r['db_conns'])} aktif {fm(r['db_active'])} | db_mem max {r['db_mem_max']:.0f} MB")
        print(f"hikari: max aktif={h[0]} max bekleyen={h[1]} bağlantı-zaman-aşımı={h[2]}")
        if g:
            print(f"gc: {g['n']} duraklama, heap(GC sonrası) ilk-üçte-bir {g['heap_after_first']:.0f}M -> son {g['heap_after_last']:.0f}M, "
                  f"max {g['heap_after_max']}M, en uzun duraklama {g['pause_max']:.0f} ms, full GC {g['full']}")
        print(f"doğruluk: {json.dumps(j['correctness'])}")
        print("| uç nokta | n | hata % | p50 | p95 | p99 | max | kodlar |")
        for e in j["endpoints"]:
            if e["name"] in KEY or e["name"].startswith("GET /api/parking/debt") or "xlsx" in e["name"] or e["errPct"] > 0:
                print(f"| {e['name']} | {e['n']} | {e['errPct']} | {e['p50']} | {e['p95']} | {e['p99']} | {e['max']} | {e['codes']} |")
        print()


if __name__ == "__main__":
    main()
