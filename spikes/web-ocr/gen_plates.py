"""Sentetik plaka test seti üretici (ölçüm denemesi, ürün kodu değil).

Türk plakaları (beyaz zemin, solda mavi TR bandı) ve birkaç AB plakası (D, NL) çizer; ardından
kılavuz çerçeveyle kırpılmış bir kamera karesine benzetmek için bozulmalar uygular:
perspektif eğim (+-10 derece), bulanıklık, düşük ışık + gürültü, parlama, sert JPEG,
farklı kırpma / ölçek ve karışık. Plakalar rastgele üretilir (gerçek plaka değildir).

Yazı tipi: sistemdeki Bahnschrift (DIN tabanlı, Windows) varsa o; yoksa DejaVu/Arial. Yazı tipi
dosyası repoya girmez. Çıktı klasörü (testset/) .gitignore'dadır.

Kullanım:  python gen_plates.py --out testset --n 200 --seed 7
Gereken:   Pillow, numpy, opencv-python
"""

import argparse
import io
import json
import math
import os
import random

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

PX_PER_MM = 2
PLATE_W, PLATE_H = 520 * PX_PER_MM, 110 * PX_PER_MM
BAND_W = 40 * PX_PER_MM

TR_LETTERS = "ABCDEFGHIJKLMNOPRSTUVYZ"  # TR plakalarında Q, W, X kullanılmaz
DE_CITIES = ["B", "M", "K", "F", "S", "HH", "HB", "DO", "KA", "MS", "BN", "WOB", "LEV", "GÖ"]
EN_LETTERS = "ABCDEFGHJKLMNPRSTUVWXYZ"

CATEGORIES = ["temiz", "egim", "bulanik", "karanlik_gurultu", "parlama", "jpeg", "kirpma_olcek", "karisik"]

FONT_CANDIDATES = [
    "C:/Windows/Fonts/bahnschrift.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
]


def load_font(size, variation=None):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            f = ImageFont.truetype(path, size)
            if variation:
                try:
                    f.set_variation_by_name(variation)
                except Exception:
                    pass
            return f
    return ImageFont.load_default()


# ---------------------------------------------------------------- plaka metinleri

def tr_plate(rng):
    il = rng.randint(1, 81)
    letters, digits = rng.choice([(1, 4), (2, 3), (2, 4), (3, 2), (3, 3), (1, 5)] + [(3, 3), (2, 3)])
    l = "".join(rng.choice(TR_LETTERS) for _ in range(letters))
    d = "".join(rng.choice("0123456789") for _ in range(digits))
    if d[0] == "0":
        d = str(rng.randint(1, 9)) + d[1:]
    return f"{il:02d}", l, d


def de_plate(rng):
    city = rng.choice(DE_CITIES)
    l = "".join(rng.choice(EN_LETTERS) for _ in range(rng.randint(1, 2)))
    d = str(rng.randint(1, 9999 if len(city) + len(l) <= 3 else 999))
    return city, l, d


def nl_plate(rng):
    L = lambda n: "".join(rng.choice("BDFGHJKLNPRSTVXZ") for _ in range(n))
    D = lambda n: "".join(rng.choice("0123456789") for _ in range(n))
    return rng.choice([
        lambda: f"{L(2)}-{D(3)}-{L(1)}",
        lambda: f"{D(1)}-{L(3)}-{D(2)}",
        lambda: f"{D(2)}-{L(3)}-{D(1)}",
        lambda: f"{L(1)}-{D(3)}-{L(2)}",
        lambda: f"{L(3)}-{D(2)}-{L(1)}",
    ])()


def alnum(s):
    return "".join(ch for ch in s.upper().replace("Ö", "O").replace("Ü", "U").replace("Ä", "A") if ch.isalnum())


# ---------------------------------------------------------------- plaka çizimi

def draw_band(img, code, eu):
    d = ImageDraw.Draw(img)
    blue = (0, 51, 153) if eu else (0, 56, 168)
    d.rectangle([6, 6, BAND_W, PLATE_H - 6], fill=blue)
    if eu:  # 12 sarı yıldız (nokta) çemberi
        cx, cy, r = BAND_W // 2 + 3, int(PLATE_H * 0.33), 22
        for k in range(12):
            a = 2 * math.pi * k / 12
            x, y = cx + r * math.cos(a), cy + r * math.sin(a)
            d.ellipse([x - 3.5, y - 3.5, x + 3.5, y + 3.5], fill=(255, 204, 0))
    f = load_font(46 if len(code) <= 2 else 34, "Bold")
    bb = d.textbbox((0, 0), code, font=f)
    tw = bb[2] - bb[0]
    d.text((BAND_W // 2 + 3 - tw // 2 - bb[0], PLATE_H - 72 - bb[1]), code, font=f, fill=(255, 255, 255))


def draw_groups(img, groups, x0, x1, condensed, gap_mm=14, seals=None):
    """Grupları (arada boşlukla) x0..x1 alanına ortalayarak, ~75 mm karakter yüksekliğiyle yazar."""
    d = ImageDraw.Draw(img)
    target_h = 75 * PX_PER_MM
    var = "SemiBold Condensed" if condensed else "SemiBold SemiCondensed"
    size = 220
    for _ in range(12):
        f = load_font(size, var)
        bb = d.textbbox((0, 0), "0", font=f)
        h = bb[3] - bb[1]
        size = int(size * target_h / max(h, 1))
    f = load_font(size, var)
    gap = gap_mm * PX_PER_MM
    seal_w = 34 * PX_PER_MM if seals else 0
    widths = [d.textbbox((0, 0), g, font=f)[2] - d.textbbox((0, 0), g, font=f)[0] for g in groups]
    total = sum(widths) + gap * (len(groups) - 1) + seal_w
    avail = x1 - x0
    if total > avail:  # sığmazsa küçült
        size = int(size * avail / total)
        f = load_font(size, var)
        widths = [d.textbbox((0, 0), g, font=f)[2] - d.textbbox((0, 0), g, font=f)[0] for g in groups]
        gap = int(gap * avail / total)
        seal_w = int(seal_w * avail / total)
        total = sum(widths) + gap * (len(groups) - 1) + seal_w
    x = x0 + (avail - total) / 2
    bb0 = d.textbbox((0, 0), "0", font=f)
    y = (PLATE_H - (bb0[3] - bb0[1])) / 2 - bb0[1]
    for i, g in enumerate(groups):
        bb = d.textbbox((0, 0), g, font=f)
        d.text((x - bb[0], y), g, font=f, fill=(15, 15, 15))
        x += widths[i] + gap
        if seals and i == 0:  # D: şehir kodundan sonra iki yuvarlak mühür (üst üste)
            cx = x - gap / 2 + seal_w / 2 - gap / 2
            r = 15 * PX_PER_MM / 2
            for cy, col in ((PLATE_H * 0.33, (90, 140, 200)), (PLATE_H * 0.7, (200, 160, 60))):
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col, outline=(60, 60, 60), width=2)
            x += seal_w - gap / 2


def render_plate(kind, rng):
    if kind == "TR":
        il, l, dg = tr_plate(rng)
        img = Image.new("RGB", (PLATE_W, PLATE_H), (248, 248, 244))
        d = ImageDraw.Draw(img)
        d.rounded_rectangle([1, 1, PLATE_W - 2, PLATE_H - 2], radius=14, outline=(20, 20, 20), width=6)
        draw_band(img, "TR", eu=False)
        draw_groups(img, [il, l, dg], BAND_W + 14, PLATE_W - 18, condensed=len(il + l + dg) >= 8)
        return img, il + l + dg, f"{il} {l} {dg}"
    if kind == "D":
        city, l, dg = de_plate(rng)
        img = Image.new("RGB", (PLATE_W, PLATE_H), (250, 250, 250))
        d = ImageDraw.Draw(img)
        d.rounded_rectangle([1, 1, PLATE_W - 2, PLATE_H - 2], radius=14, outline=(20, 20, 20), width=6)
        draw_band(img, "D", eu=True)
        draw_groups(img, [city, l, dg], BAND_W + 12, PLATE_W - 16, condensed=True, gap_mm=10, seals=True)
        return img, alnum(city + l + dg), f"{city} {l} {dg}"
    # NL
    txt = nl_plate(rng)
    img = Image.new("RGB", (PLATE_W, PLATE_H), (247, 196, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([1, 1, PLATE_W - 2, PLATE_H - 2], radius=14, outline=(30, 30, 30), width=4)
    draw_band(img, "NL", eu=True)
    draw_groups(img, [txt], BAND_W + 12, PLATE_W - 16, condensed=True)
    return img, alnum(txt), txt


# ---------------------------------------------------------------- sahne ve bozulmalar

def background(w, h, rng):
    base = np.array([rng.randint(20, 230) for _ in range(3)], dtype=np.float32)
    yy = np.linspace(0, 1, h)[:, None, None]
    grad = base * (0.75 + 0.5 * yy)  # dikey ışık gradyanı (araç gövdesi / tampon)
    bg = np.broadcast_to(grad, (h, w, 3)).copy()
    for _ in range(rng.randint(0, 3)):  # tampon çizgileri
        y = rng.randint(0, h - 1)
        th = rng.randint(2, max(3, h // 20))
        bg[y:y + th] *= rng.uniform(0.4, 0.8)
    bg += np.random.normal(0, 4, bg.shape)
    return np.clip(bg, 0, 255).astype(np.uint8)


def place_plate(plate, rng, cat):
    """Plakayı perspektifle bir kareye yerleştirir ve kılavuz çerçeve kırpımını döner."""
    p = np.array(plate)
    tilt = cat in ("egim", "karisik")
    yaw = math.radians(rng.uniform(-10, 10) if tilt else rng.uniform(-3, 3))
    pitch = math.radians(rng.uniform(-10, 10) if tilt else rng.uniform(-3, 3))
    roll = math.radians(rng.uniform(-10, 10) if tilt else rng.uniform(-2, 2))

    # Çıktı (kılavuz çerçeve) boyutu ve plakanın çerçevedeki genişlik payı
    if cat == "kirpma_olcek":
        out_w = rng.choice([rng.randint(170, 260), rng.randint(900, 1200)])
        frac = rng.uniform(0.55, 0.98)
    else:
        out_w = rng.randint(420, 900)
        frac = rng.uniform(0.7, 0.92)
    out_h = int(out_w / rng.uniform(2.8, 3.6))
    pw = out_w * frac
    s = pw / PLATE_W
    ph = PLATE_H * s

    # Köşeler: yaw bir kenarı kısaltır, pitch üst/alt kenarı; sonra roll ile döndür
    k = 0.9
    hl, hr = ph * (1 + k * math.sin(yaw)) / 2, ph * (1 - k * math.sin(yaw)) / 2
    wt, wb = pw * math.cos(yaw) * (1 - k * math.sin(pitch) * 0.5) / 2, pw * math.cos(yaw) * (1 + k * math.sin(pitch) * 0.5) / 2
    hl *= math.cos(pitch)
    hr *= math.cos(pitch)
    pts = np.array([[-wt, -hl], [wt, -hr], [wb, hr], [-wb, hl]], dtype=np.float32)
    rot = np.array([[math.cos(roll), -math.sin(roll)], [math.sin(roll), math.cos(roll)]], dtype=np.float32)
    pts = pts @ rot.T
    cx = out_w / 2 + rng.uniform(-0.06, 0.06) * out_w * (2 if cat == "kirpma_olcek" else 1)
    cy = out_h / 2 + rng.uniform(-0.08, 0.08) * out_h * (2 if cat == "kirpma_olcek" else 1)
    pts += np.array([cx, cy], dtype=np.float32)

    src = np.array([[0, 0], [PLATE_W, 0], [PLATE_W, PLATE_H], [0, PLATE_H]], dtype=np.float32)
    M = cv2.getPerspectiveTransform(src, pts)
    scene = background(out_w, out_h, rng)
    warped = cv2.warpPerspective(p, M, (out_w, out_h), flags=cv2.INTER_AREA)
    mask = cv2.warpPerspective(np.full((PLATE_H, PLATE_W), 255, np.uint8), M, (out_w, out_h), flags=cv2.INTER_AREA)
    a = (mask.astype(np.float32) / 255)[..., None]
    out = scene * (1 - a) + warped * a
    return out.astype(np.float32)


def degrade(img, cat, rng):
    h, w = img.shape[:2]
    char_h = h * 0.45  # yaklaşık karakter yüksekliği (çerçevede)
    if cat in ("bulanik", "karisik"):
        if cat == "bulanik" and rng.random() < 0.5:
            L = max(3, int(char_h * rng.uniform(0.06, 0.14)))  # hareket bulanıklığı
            kern = np.zeros((L, L), np.float32)
            kern[L // 2, :] = 1
            M = cv2.getRotationMatrix2D((L / 2 - 0.5, L / 2 - 0.5), rng.uniform(-30, 30), 1)
            kern = cv2.warpAffine(kern, M, (L, L))
            kern /= kern.sum()
            img = cv2.filter2D(img, -1, kern)
        else:
            sig = char_h * (rng.uniform(0.03, 0.07) if cat == "bulanik" else rng.uniform(0.015, 0.03))
            img = cv2.GaussianBlur(img, (0, 0), max(0.6, sig))
    if cat in ("karanlik_gurultu", "karisik"):
        dark = rng.uniform(0.22, 0.45) if cat == "karanlik_gurultu" else rng.uniform(0.5, 0.75)
        img = 255 * ((img / 255) ** rng.uniform(1.1, 1.5)) * dark
        tint = np.array([rng.uniform(0.9, 1.1), 1, rng.uniform(0.85, 1.05)], np.float32)
        img = img * tint
        sigma = rng.uniform(7, 16) if cat == "karanlik_gurultu" else rng.uniform(3, 7)
        img = img + np.random.normal(0, sigma, img.shape)
    if cat == "parlama":
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        for _ in range(rng.randint(1, 2)):
            gx, gy = rng.uniform(0.2, 0.8) * w, rng.uniform(0.2, 0.8) * h
            rx, ry = rng.uniform(0.08, 0.22) * w, rng.uniform(0.25, 0.6) * h
            g = np.exp(-(((xx - gx) / rx) ** 2 + ((yy - gy) / ry) ** 2))
            strength = rng.uniform(0.6, 0.95)
            img = img * (1 - g[..., None] * strength) + 255 * g[..., None] * strength
    # Tüm görüntülere hafif temel gürültü
    img = img + np.random.normal(0, 2.5, img.shape)
    img = np.clip(img, 0, 255).astype(np.uint8)
    if cat == "kirpma_olcek" and w > 600 and rng.random() < 0.5:
        pass  # büyük kare olduğu gibi kalır
    q = {"jpeg": rng.randint(8, 22), "karisik": rng.randint(25, 45)}.get(cat, rng.randint(65, 90))
    buf = io.BytesIO()
    Image.fromarray(img).save(buf, "JPEG", quality=q)
    return buf.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="testset")
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    np.random.seed(args.seed)
    os.makedirs(args.out, exist_ok=True)
    labels = []
    for i in range(args.n):
        cat = CATEGORIES[i % len(CATEGORIES)]
        r = rng.random()
        kind = "TR" if r < 0.72 else ("D" if r < 0.86 else "NL")
        plate, truth, shown = render_plate(kind, rng)
        scene = place_plate(plate, rng, cat)
        jpg = degrade(scene, cat, rng)
        name = f"{i:03d}_{kind}_{cat}.jpg"
        with open(os.path.join(args.out, name), "wb") as f:
            f.write(jpg)
        labels.append({"file": name, "kind": kind, "category": cat, "truth": truth, "shown": shown})
    with open(os.path.join(args.out, "labels.json"), "w", encoding="utf-8") as f:
        json.dump(labels, f, ensure_ascii=False, indent=1)
    kinds = {k: sum(1 for x in labels if x["kind"] == k) for k in ("TR", "D", "NL")}
    print(f"{len(labels)} görüntü yazıldı -> {args.out}  {kinds}")


if __name__ == "__main__":
    main()
