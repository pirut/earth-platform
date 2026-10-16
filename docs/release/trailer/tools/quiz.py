#!/usr/bin/env python3
""""Guess the place in 3 seconds" Short: five clips with a countdown, answers at the end.
usage (repo root): python3 quiz.py   -> /mnt/project-files/release/shorts/hooks/guess-the-place.mp4"""
import os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S, loop as Lp
from PIL import Image, ImageDraw

CLIPS = os.environ.get("CLIPS", "docs/release/trailer/hooks/clips")
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/quiz-tmp"
W, H, FPS, SR = S.W, S.H, S.FPS, 48000
PFX = os.environ.get("PFX", "b47-")
SEQ = [("q-grand_canyon", "Grand Canyon"), ("q-everest", "Mount Everest"), ("q-uluru", "Uluru"),
       ("q-half_dome", "Half Dome"), ("q-fuji", "Mount Fuji")]
GUESS, ANS, END = 3.0, 0.8, 1.6   # countdown, answer flash, closing ask (no end card)


def tick(n, f0=1800, dur=0.05):
    t = np.arange(int(dur * SR)) / SR
    return np.sin(2 * np.pi * f0 * t) * np.exp(-t * 60) * 0.25


def bar(u):  # countdown bar, u = 1 -> full
    L = Image.new("RGBA", (720, 40)); d = ImageDraw.Draw(L)
    d.rounded_rectangle((0, 0, 719, 39), radius=20, fill=(0, 0, 0, 120))
    if u > 0.01: d.rounded_rectangle((4, 4, 4 + int(711 * u), 35), radius=16, fill=S.YEL + (255,))
    return L


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    ng, na, ne = round(GUESS * FPS), round(ANS * FPS), round(END * FPS); total = (ng + na) * len(SEQ) + ne; SEG = GUESS + ANS
    head = S.big([("Guess the place", S.WHITE), ("in 3 seconds", S.YEL)], 80)
    nums = {k: S.big([(str(k), S.WHITE)], 150) for k in (1, 2, 3)}
    idx = [S.soft([(f"{i + 1} / {len(SEQ)}", S.SEMI, 40, (255, 255, 255, 240), 0)], shadow=180, blur=10) for i in range(len(SEQ))]
    answers = [S.big([(t, S.YEL)], 84) for _, t in SEQ]
    ask = S.big([("How many", S.WHITE), ("did you get?", S.YEL)], 84)
    sub = S.soft([("Comment your score \u2193", S.SEMI, 44, (255, 255, 255, 250), 0)], shadow=200, blur=12)
    brand = S.brand_bar()

    amb_wav = os.path.join(TMP, "amb.wav")
    subprocess.run([sys.executable, "-I", os.path.join(Lp.TOOLS, "ambience.py"), amb_wav, f"{total / FPS + 3:.1f}", "31", "--birds", "6", "--wind", "0.6"], check=True)
    n = round(total / FPS * SR); mix = Lp.read_wav(amb_wav)[int(2.0 * SR):][:n].copy() * 0.6
    k = int(0.4 * SR); mix[-k:] *= np.linspace(1, 0, k)[:, None]
    for i in range(len(SEQ)):
        for s in range(3):
            p = int((i * SEG + s) * SR); tk = tick(1, 1800 if s < 2 else 2400); mix[p:p + len(tk)] += tk[:, None]
    mix += Lp.whooshes([i * SEG - 0.05 for i in range(1, len(SEQ))], total / FPS)[:n, None] * 0.7
    mix = np.clip(mix / max(1.0, np.max(np.abs(mix)) / 0.89), -1, 1)
    wav = os.path.join(TMP, "mix.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())

    tmp_out = os.path.join(TMP, "quiz.mp4"); enc = S.encoder(tmp_out, wav, total)
    for i, (name, _) in enumerate(SEQ):
        last = i == len(SEQ) - 1; count = ng + na + (ne if last else 0)
        src = subprocess.Popen(["ffmpeg", "-v", "error", "-i", os.path.join(CLIPS, PFX + name + ".mp4"), "-vf", f"scale={W}:{H}:flags=lanczos,tpad=stop_mode=clone:stop=-1",
                                "-frames:v", str(count), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
        for c in range(count):
            img = Image.frombytes("RGB", (W, H), src.stdout.read(W * H * 3)); t = c / FPS
            if t < GUESS:
                S.put_top(img, head, W / 2, 200)
                S.put_top(img, idx[i], W / 2, 410)
                S.put(img, bar(1 - t / GUESS), W / 2, 520)
                sec = 3 - int(t); u = t - int(t)
                S.put(img, nums[sec], W / 2, 690, scale=S.POP_SOFT[min(int(u * FPS), len(S.POP_SOFT) - 1)], alpha=1 - S.ease((u - 0.7) / 0.3))
            elif t < SEG:
                u = t - GUESS
                S.put_top(img, idx[i], W / 2, 410)
                S.put(img, answers[i], W / 2, 330, scale=S.POP[min(int(u * FPS), len(S.POP) - 1)])
            else:
                u = t - SEG
                S.put(img, ask, W / 2, 330, scale=S.POP[min(int(u * FPS), len(S.POP) - 1)])
                S.put_top(img, sub, W / 2, 470, alpha=S.ease((u - 0.2) / 0.3))
            S.put_top(img, brand, W / 2, S.BRAND_TOP)
            enc.stdin.write(img.tobytes())
        src.stdout.close(); src.wait()
    enc.stdin.close(); enc.wait()
    for p in (wav, amb_wav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, "guess-the-place.mp4")); print("wrote guess-the-place", flush=True)


if __name__ == "__main__": main()
