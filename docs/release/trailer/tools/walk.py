#!/usr/bin/env python3
""""How far down is the Grand Canyon?" Short: the low, fast descent from the South Rim toward the river.
The distance counter is measured along the camera path read from hooks.cine (shot walk).
usage (repo root): python3 walk.py   -> /mnt/project-files/release/shorts/hooks/grand-canyon-descent.mp4"""
import math, os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S, loop as Lp
from PIL import Image

CLIP = os.environ.get("CLIP", "docs/release/trailer/hooks/clips/b47-walk.mp4")
CINE = "docs/release/trailer/hooks/hooks.cine"
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/walk-tmp"
W, H, FPS, SR = S.W, S.H, S.FPS, 48000


def path():
    keys, on = [], False
    for ln in open(CINE):
        p = ln.split()
        if p[:1] == ["shot"]: on = p[1] == "walk"
        elif on and p[:1] == ["cam"]: keys.append((float(p[1]), float(p[2]), float(p[3])))
    return keys


def dist_km(a, b):
    R = 6371.0; la1, la2 = math.radians(a[1]), math.radians(b[1]); dl = math.radians(b[2] - a[2])
    x = dl * math.cos((la1 + la2) / 2); y = la2 - la1
    return R * math.hypot(x, y)


def walked(keys, u):
    """km along the (piecewise linear) path at fraction u of the shot."""
    d = 0.0
    for a, b in zip(keys, keys[1:]):
        if u >= b[0]: d += dist_km(a, b)
        elif u > a[0]: d += dist_km(a, b) * (u - a[0]) / (b[0] - a[0]); break
    return d


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    keys = path()
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", CLIP], capture_output=True, text=True).stdout)
    total = round(dur * FPS)
    hook = S.big([("How far down is", S.WHITE), ("the Grand Canyon?", S.YEL)], 74)
    sub = S.soft([("In Minecraft, at real 1:1 scale", S.SEMI, 40, (255, 255, 255, 245), 0)], shadow=200, blur=10)
    pay = S.big([("About 1,400 meters", S.WHITE), ("(4,500 ft) deep.", S.YEL)], 72)
    pay2 = S.soft([("Every block of it is real.", S.SEMI, 42, (255, 255, 255, 250), 0)], shadow=200, blur=10)
    brand = S.brand_bar()

    amb_wav = os.path.join(TMP, "amb.wav")
    subprocess.run([sys.executable, "-I", os.path.join(Lp.TOOLS, "ambience.py"), amb_wav, f"{dur + 3:.1f}", "61", "--birds", "3", "--wind", "0.75"], check=True)
    n = round(dur * SR); mix = Lp.read_wav(amb_wav)[int(2.0 * SR):][:n].copy()
    k = int(0.3 * SR); mix[-k:] *= np.linspace(1, 0, k)[:, None]
    mix += Lp.whooshes([0.05], dur)[:n, None] * 0.6
    mix = np.clip(mix / max(1.0, np.max(np.abs(mix)) / 0.89), -1, 1)
    wav = os.path.join(TMP, "mix.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())

    tmp_out = os.path.join(TMP, "walk.mp4"); enc = S.encoder(tmp_out, wav, total)
    src = subprocess.Popen(["ffmpeg", "-v", "error", "-i", CLIP, "-vf", f"scale={W}:{H}:flags=lanczos", "-frames:v", str(total),
                            "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    end_at = dur - 4.2
    for f in range(total):
        img = Image.frombytes("RGB", (W, H), src.stdout.read(W * H * 3)); t = f / FPS
        a = 1 - S.ease((t - 3.3) / 0.3)
        if a > 0:
            S.put_top(img, hook, W / 2, 200, alpha=a); S.put_top(img, sub, W / 2, 400, alpha=a * S.ease((t - 0.3) / 0.3))
        if 3.6 < t < end_at + 0.3:
            km = walked(keys, f / max(1, total - 1))
            ctr = S.big([(f"{km:.1f} km ({km * 0.621371:.1f} mi)", S.WHITE)], 64)
            S.put_top(img, ctr, W / 2, 230, alpha=S.ease((t - 3.6) / 0.3) * (1 - S.ease((t - end_at) / 0.3)))
        if t > end_at:
            u = t - end_at
            S.put_top(img, pay, W / 2, 200, scale=S.POP[min(int(u * FPS), len(S.POP) - 1)])
            S.put_top(img, pay2, W / 2, 420, alpha=S.ease((u - 0.4) / 0.3))
        S.put_top(img, brand, W / 2, S.BRAND_TOP)
        enc.stdin.write(img.tobytes())
    src.stdout.close(); src.wait(); enc.stdin.close(); enc.wait()
    for p in (wav, amb_wav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, "grand-canyon-descent.mp4")); print("wrote grand-canyon-descent", flush=True)


if __name__ == "__main__": main()
