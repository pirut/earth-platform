#!/usr/bin/env python3
""""Real photo vs Minecraft" Short: public-domain NPS photos (top) over the same view in game (bottom).
usage (repo root): python3 compare.py   -> /mnt/project-files/release/shorts/hooks/real-vs-minecraft.mp4"""
import os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S, loop as Lp
from PIL import Image, ImageDraw

CLIPS = os.environ.get("CLIPS", "docs/release/trailer/hooks/clips"); PFX = os.environ.get("PFX", "b47-")
PHOTOS = "docs/release/trailer/hooks/photos"
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/compare-tmp"
W, H, FPS, SR = S.W, S.H, S.FPS, 48000
HALF = H // 2
# name: (title, photo, photo center x, photo center y, clip center y)  centers as 0..1 fractions
SEQ = [("c-grand_canyon", "Grand Canyon", "grand_canyon-mather_point.jpg", 0.62, 0.55, 0.5),
       ("c-half_dome", "Half Dome", "half_dome-snow_creek.jpg", 0.72, 0.42, 0.33),
       ("c-rainier", "Mount Rainier", "rainier-tipsoo.jpg", 0.55, 0.35, 0.3)]
SEG, END = 4.6, 2.2


def photo_half(path, cx, cy, zoom):
    im = Image.open(path).convert("RGB"); w, h = im.size
    ch = h / zoom; cw = ch * W / HALF
    if cw > w: cw = w; ch = cw * HALF / W
    x = min(max(cx * w - cw / 2, 0), w - cw); y = min(max(cy * h - ch / 2, 0), h - ch)
    return im.resize((W, HALF), Image.LANCZOS, box=(x, y, x + cw, y + ch))


def label(text, sub=None):
    rows = [(text, S.BOLD, 40, (255, 255, 255, 250), 6 if sub else 0)] + ([(sub, S.REG, 22, (235, 238, 245, 220), 0)] if sub else [])
    return S.soft(rows, shadow=210, blur=8)


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    ns, ne = round(SEG * FPS), round(END * FPS); total = ns * len(SEQ) + ne
    hook = S.big([("Real photo", S.WHITE), ("vs Minecraft", S.YEL)], 78)
    real, mc = label("REAL", "photo: National Park Service"), label("MINECRAFT", "Earth Platform, 1:1 scale")
    names = [S.big([(t, S.WHITE)], 58) for _, t, *_ in SEQ]
    ask = S.big([("Which place", S.WHITE), ("should I try next?", S.YEL)], 64)
    sub = S.soft([("Comment it ↓", S.SEMI, 40, (255, 255, 255, 250), 0)], shadow=200, blur=10)
    brand = S.brand_bar()
    line = Image.new("RGBA", (W, 6), (255, 255, 255, 230))

    amb_wav = os.path.join(TMP, "amb.wav")
    subprocess.run([sys.executable, "-I", os.path.join(Lp.TOOLS, "ambience.py"), amb_wav, f"{total / FPS + 3:.1f}", "51", "--birds", "6", "--wind", "0.5"], check=True)
    n = round(total / FPS * SR); mix = Lp.read_wav(amb_wav)[int(2.0 * SR):][:n].copy() * 0.8
    k = int(0.3 * SR); mix[-k:] *= np.linspace(1, 0, k)[:, None]
    mix += Lp.whooshes([i * SEG - 0.05 for i in range(1, len(SEQ))], total / FPS)[:n, None] * 0.8
    mix = np.clip(mix / max(1.0, np.max(np.abs(mix)) / 0.89), -1, 1)
    wav = os.path.join(TMP, "mix.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())

    tmp_out = os.path.join(TMP, "compare.mp4"); enc = S.encoder(tmp_out, wav, total)
    for i, (name, title, photo, pcx, pcy, ccy) in enumerate(SEQ):
        last = i == len(SEQ) - 1; count = ns + (ne if last else 0)
        src = subprocess.Popen(["ffmpeg", "-v", "error", "-i", os.path.join(CLIPS, PFX + name + ".mp4"), "-vf", f"scale={W}:{H}:flags=lanczos,tpad=stop_mode=clone:stop=-1",
                                "-frames:v", str(count), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
        pp = os.path.join(PHOTOS, photo)
        for c in range(count):
            t = c / FPS
            game = Image.frombytes("RGB", (W, H), src.stdout.read(W * H * 3))
            y0 = int(min(max(ccy * H - HALF / 2, 0), H - HALF))
            img = Image.new("RGB", (W, H))
            img.paste(photo_half(pp, pcx, pcy, 1.0 + 0.04 * t / SEG), (0, 0))   # slow push-in so the photo half moves too
            img.paste(game.crop((0, y0, W, y0 + HALF)), (0, HALF))
            S.put(img, line, W / 2, HALF)
            S.put_top(img, real, real.width / 2 - 10, HALF - real.height + 10); S.put_top(img, mc, mc.width / 2 - 10, HALF - 10)
            if i == 0 and t < 2.4: S.put_top(img, hook, W / 2, 130, alpha=1 - S.ease((t - 2.1) / 0.3))
            a = S.ease((t - (2.2 if i == 0 else 0.1)) / 0.25) * (1 - S.ease((t - SEG + 0.3) / 0.25)) if not (last and t > SEG) else 0
            if a > 0: S.put_top(img, names[i], W / 2, 150, alpha=a)
            if last and t > SEG - 0.2:
                u = t - SEG + 0.2
                S.put(img, ask, W / 2, 260, scale=S.POP[min(int(u * FPS), len(S.POP) - 1)])
                S.put_top(img, sub, W / 2, 380, alpha=S.ease((u - 0.3) / 0.3))
            S.put_top(img, brand, W / 2, S.BRAND_TOP)
            enc.stdin.write(img.tobytes())
        src.stdout.close(); src.wait()
    enc.stdin.close(); enc.wait()
    for p in (wav, amb_wav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, "real-vs-minecraft.mp4")); print("wrote real-vs-minecraft", flush=True)


if __name__ == "__main__": main()
