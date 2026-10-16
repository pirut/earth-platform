#!/usr/bin/env python3
""""Comment a place" Short: five real places in quick cuts, then "Where should I go next?".
usage (repo root): python3 comment.py   -> /mnt/project-files/release/shorts/hooks/comment-a-place.mp4"""
import os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S, loop as Lp
from PIL import Image, ImageEnhance

CLIPS = os.environ.get("CLIPS", "docs/release/trailer/ambient/clips")
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/comment-tmp"
W, H, FPS, SR = S.W, S.H, S.FPS, 48000
PFX = os.environ.get("PFX", "b47-")
SEQ = [("{P}yosemite", "Yosemite Valley", "California", 9.0), ("{P}matterhorn", "The Matterhorn", "Switzerland", 9.0),
       ("{P}lauterbrunnen", "Lauterbrunnen", "Switzerland", 9.0), ("{P}rainier", "Mount Rainier", "Washington", 9.0),
       ("{P}fuji", "Mount Fuji", "Japan", 6.0)]
SEQ = [(n.replace("{P}", PFX), t, r, s) for n, t, r, s in SEQ]
CUT, END = 2.5, 2.6   # seconds per place; the last place holds for END more seconds under the ask


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    nc, ne = round(CUT * FPS), round(END * FPS); total = nc * len(SEQ) + ne
    hook = S.big([("This is the real world", S.WHITE), ("in Minecraft.", S.YEL)], 76)
    labels = [S.soft([(t, S.SEMI, 54, (255, 255, 255, 245), 10), (r, S.REG, 32, (236, 240, 246, 225), 0)], shadow=180, blur=12) for _, t, r, _ in SEQ]
    ask = S.big([("Where should I", S.WHITE), ("go next?", S.YEL)], 92)
    sub = S.soft([("Comment a real place ↓", S.SEMI, 46, (255, 255, 255, 250), 0)], shadow=190, blur=12)
    bar = S.brand_bar()

    # audio: ambience bed + a soft whoosh on every cut
    amb_wav = os.path.join(TMP, "amb.wav")
    subprocess.run([sys.executable, "-I", os.path.join(Lp.TOOLS, "ambience.py"), amb_wav, f"{total / FPS + 3:.1f}", "21", "--birds", "10", "--wind", "0.5"], check=True)
    amb = Lp.read_wav(amb_wav)[int(2.0 * SR):]
    n = round(total / FPS * SR); mix = amb[:n].copy() * 0.9
    k = int(0.4 * SR); mix[-k:] *= np.linspace(1, 0, k)[:, None]
    mix += Lp.whooshes([i * CUT - 0.05 for i in range(1, len(SEQ))], total / FPS)[:n, None] * 0.8
    mix = np.clip(mix / max(1.0, np.max(np.abs(mix)) / 0.89), -1, 1)
    wav = os.path.join(TMP, "mix.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())

    tmp_out = os.path.join(TMP, "comment.mp4"); enc = S.encoder(tmp_out, wav, total); f = 0
    for i, (name, _, _, t0) in enumerate(SEQ):
        last = i == len(SEQ) - 1; count = nc + (ne if last else 0)
        src = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", f"{t0}", "-i", os.path.join(CLIPS, name + ".mp4"), "-frames:v", str(count),
                                "-vf", f"scale={W}:{H}:flags=lanczos", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
        for c in range(count):
            img = Image.frombytes("RGB", (W, H), src.stdout.read(W * H * 3)); t = c / FPS
            if i == 0: S.put_top(img, hook, W / 2, 250, alpha=S.ease(t / 0.25))
            la = S.ease((t - 0.15) / 0.3) * (1 - S.ease((t - CUT + 0.35) / 0.3)) if not last else S.ease((t - 0.15) / 0.3) * (1 - S.ease((t - CUT + 0.2) / 0.3))
            if la > 0: S.put_top(img, labels[i], W / 2, 470 if i == 0 else 300, alpha=la)
            if last and t > CUT - 0.1:
                u = t - CUT + 0.1
                pop = S.POP[min(int(u * FPS), len(S.POP) - 1)]
                S.put(img, ask, W / 2, 470, scale=pop)
                S.put_top(img, sub, W / 2, 640, alpha=S.ease((u - 0.3) / 0.3))
            S.put_top(img, bar, W / 2, S.BRAND_TOP)
            enc.stdin.write(img.tobytes()); f += 1
        src.stdout.close(); src.wait()
    enc.stdin.close(); enc.wait()
    for p in (wav, amb_wav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, "comment-a-place.mp4"))
    print("wrote", f, "frames", flush=True)


if __name__ == "__main__": main()
