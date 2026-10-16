#!/usr/bin/env python3
""""This is 1:1 scale" Short: pull back from a person-sized figure until the whole Matterhorn fits.
usage (repo root): python3 scale.py   -> /mnt/project-files/release/shorts/hooks/one-to-one-scale.mp4"""
import os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S, loop as Lp
from PIL import Image, ImageDraw, ImageEnhance

CLIP = os.environ.get("CLIP", "docs/release/trailer/hooks/clips/b47-scale.mp4")
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/scale-tmp"
W, H, FPS, SR = S.W, S.H, S.FPS, 48000
FREEZE, HOLD = 0.0, 0.0   # 2026-10-16 plan: moving from frame 1, no end hold; the cut back to the figure is the loop
MOVE = float(os.environ.get("MOVE", "10.0"))
PEAK_AT = MOVE * 0.62   # the whole mountain fits about here


def find_stand(img):
    """Centroid of the figure's orange leather armor, or None once it is too small to see."""
    a = np.asarray(img.resize((W // 4, H // 4)), dtype=np.int16)
    m = (a[..., 0] > 120) & (a[..., 1] > 45) & (a[..., 1] < 120) & (a[..., 2] < 70) & (a[..., 0] - a[..., 1] > 50)
    if m.sum() < 6: return None
    ys, xs = np.nonzero(m)
    return xs.mean() * 4, ys.min() * 4


def arrow():
    L = Image.new("RGBA", (60, 110)); d = ImageDraw.Draw(L)
    d.line((30, 0, 30, 80), fill=(0, 0, 0, 140), width=12); d.polygon([(6, 72), (54, 72), (30, 108)], fill=(0, 0, 0, 140))
    d.line((30, 0, 30, 78), fill=(255, 255, 255, 255), width=7); d.polygon([(12, 74), (48, 74), (30, 102)], fill=(255, 255, 255, 255))
    return L


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    nf, nm, nh = round(FREEZE * FPS), round(MOVE * FPS), round(HOLD * FPS); total = nf + nm + nh
    head = S.big([("This is", S.WHITE), ("1:1 scale.", S.YEL)], 96)
    you = S.soft([("this is you", S.SEMI, 44, (255, 255, 255, 250), 0)], shadow=200, blur=10); arr = arrow()
    peak = S.big([("The Matterhorn", S.WHITE)], 70)
    height = S.soft([("4,478 meters (14,692 ft)", S.SEMI, 46, (255, 255, 255, 250), 10), ("every block is one meter", S.REG, 34, (240, 244, 250, 235), 0)], shadow=200, blur=12)
    amb_wav = os.path.join(TMP, "amb.wav")
    subprocess.run([sys.executable, "-I", os.path.join(Lp.TOOLS, "ambience.py"), amb_wav, f"{total / FPS + 3:.1f}", "41", "--birds", "0", "--wind", "0.9"], check=True)
    n = round(total / FPS * SR); mix = Lp.read_wav(amb_wav)[int(2.0 * SR):][:n].copy()
    k = int(0.5 * SR); mix[-k:] *= np.linspace(1, 0, k)[:, None]
    mix += Lp.whooshes([0.1], total / FPS)[:n, None] * 0.6
    mix = np.clip(mix / max(1.0, np.max(np.abs(mix)) / 0.89), -1, 1)
    wav = os.path.join(TMP, "mix.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())

    tmp_out = os.path.join(TMP, "scale.mp4"); enc = S.encoder(tmp_out, wav, total)
    src = subprocess.Popen(["ffmpeg", "-v", "error", "-i", CLIP, "-vf", f"scale={W}:{H}:flags=lanczos,tpad=stop_mode=clone:stop=-1",
                            "-frames:v", str(nm + nh), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    bar = S.brand_bar(); spot = None
    for f in range(total):
        img = Image.frombytes("RGB", (W, H), src.stdout.read(W * H * 3)); t = f / FPS
        a = S.ease(t / 0.12) * (1 - S.ease((t - PEAK_AT + 1.2) / 0.4))
        if a > 0: S.put_top(img, head, W / 2, 190, alpha=a)
        if t < 1.3:
            found = find_stand(img)
            if found: spot = found if spot is None else (0.6 * found[0] + 0.4 * spot[0], 0.6 * found[1] + 0.4 * spot[1])
            a = 1 - S.ease((t - 1.0) / 0.3)
            if spot and found and a > 0:
                S.put(img, arr, spot[0], spot[1] - 70 - 6 * np.sin(t * 9), alpha=a)
                S.put(img, you, spot[0], spot[1] - 170, alpha=a)
        if t > PEAK_AT:
            u = t - PEAK_AT
            S.put_top(img, peak, W / 2, 210, scale=S.POP[min(int(u * FPS), len(S.POP) - 1)])
            S.put_top(img, height, W / 2, 360, alpha=S.ease((u - 0.3) / 0.3))
        S.put_top(img, bar, W / 2, S.BRAND_TOP)
        enc.stdin.write(img.tobytes())
    src.stdout.close(); src.wait(); enc.stdin.close(); enc.wait()
    for p in (wav, amb_wav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, "one-to-one-scale.mp4")); print("wrote one-to-one-scale", flush=True)


if __name__ == "__main__": main()
