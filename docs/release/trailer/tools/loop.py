#!/usr/bin/env python3
"""Looping "stop scrolling" Shorts: a feed-like flick of clips that stops dead on a calm scene,
a breathing cue, then the scene scrolls away into the same flick, so the Short loops seamlessly.

usage: CLIPS=dir python3 loop.py NAME [NAME ...]     (CLIPS holds NAME.mp4, 1008x1792 vertical renders)
"""
import os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S
from PIL import Image

CLIPS = os.environ.get("CLIPS", "docs/release/trailer/ambient/clips")
FEED = os.environ.get("FEED", "/mnt/project-files/release/trailer/shots")
OUT = os.environ.get("OUT", "/mnt/project-files/release/shorts/loop")
TMP = os.environ.get("TMP", "/tmp/loop-tmp")
TOOLS = os.path.dirname(os.path.abspath(__file__))
W, H, FPS, SR = S.W, S.H, S.FPS, 48000
INTRO, OUTRO, D = 1.4, 0.3, 0.45   # flick seconds at start/end; strip offset where the loop seam sits
CALM = 18.0                         # seconds of the calm clip used (2026-10-16: 15-22 s Shorts)
FEEDS = ["everest", "jet", "iguazu", "sahara", "grand_canyon", "kilimanjaro"]
if os.environ.get("FEED_B47"):  # build 47 vertical hook clips as the flick panels
    FEED, FEEDS = "docs/release/trailer/ambient/clips", ["b47-yosemite", "b47-matterhorn", "b47-lauterbrunnen", "b47-rainier", "b47-fuji"]
VERT_FEED = bool(os.environ.get("FEED_B47"))
PLACES = {  # name: (title, region, seed, birds, wind)
    "yosemite": ("Yosemite Valley", "California", 11, 10, 0.45),
    "matterhorn": ("The Matterhorn", "Switzerland", 12, 6, 0.6),
    "lauterbrunnen": ("Lauterbrunnen Valley", "Switzerland", 13, 14, 0.35),
    "rainier": ("Mount Rainier", "Washington", 14, 4, 0.7),
    "teton": ("Grand Teton", "Wyoming", 15, 8, 0.55),
    "fuji": ("Mount Fuji", "Japan", 16, 9, 0.4),
}
for k in list(PLACES): PLACES["scene-" + k] = PLACES["b44-" + k] = PLACES["b47-" + k] = PLACES[k]


def still(path, t):
    if VERT_FEED:  # vertical 1008x1792 renders: just scale to the Short's frame
        raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t}", "-i", path, "-frames:v", "1", "-vf", f"scale={W}:{H}:flags=lanczos",
                              "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
        return Image.frombytes("RGB", (W, H), raw)
    p = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t}", "-i", path, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                       capture_output=True, check=True).stdout
    return S.vcrop(Image.frombytes("RGB", (S.SW, S.SH), p), 0.5)


def frames(path, n):
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", path, "-frames:v", str(n), "-vf", f"scale={W}:{H}:flags=lanczos",
                          "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    last = None
    for _ in range(n):
        b = p.stdout.read(W * H * 3)
        if len(b) < W * H * 3: break
        last = Image.frombytes("RGB", (W, H), b); yield last
    p.stdout.close(); p.wait()
    while True: yield last


def strip_frame(panels, pos, speed):
    """Panels stacked vertically; pos in panel heights from the top of panel 0. Vertical motion blur by speed."""
    i = int(pos); off = round((pos - i) * H)
    img = Image.new("RGB", (W, H))
    img.paste(panels[min(i, len(panels) - 1)], (0, -off))
    if off: img.paste(panels[min(i + 1, len(panels) - 1)], (0, H - off))
    blur = min(int(abs(speed) * 0.006 * H / FPS), 120)  # px of smear
    if blur > 2:
        small = img.resize((W, max(8, H // blur)), Image.BILINEAR)
        img = Image.blend(img, small.resize((W, H), Image.BILINEAR), 0.75)
    return img


def whooshes(times, total):
    rng = np.random.default_rng(7); n = int(total * SR); out = np.zeros(n)
    for t0 in times:
        L = int(0.16 * SR); x = rng.standard_normal(L)
        X = np.fft.rfft(x); f = np.fft.rfftfreq(L, 1 / SR)
        x = np.fft.irfft(X / (1 + (f / 2500) ** 2) / (1 + (500 / np.maximum(f, 1)) ** 2), L)
        env = np.sin(np.linspace(0, np.pi, L)) ** 2
        s = int(t0 * SR) % n; e = min(n, s + L)
        out[s:e] += (x * env)[:e - s]
    return out / (np.max(np.abs(out)) + 1e-9) * 0.35


def read_wav(path):
    with wave.open(path) as w:
        a = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, 2) / 32768.0
    return a


def build(name):
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    title, region, seed, birds, wind = PLACES[name]
    clip = os.path.join(CLIPS, name + ".mp4")
    n_in, n_calm, n_out = round(INTRO * FPS), round(CALM * FPS), round(OUTRO * FPS)
    total = n_in + n_calm + n_out
    def grab(t):
        raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t:.4f}", "-i", clip, "-frames:v", "1", "-vf", f"scale={W}:{H}:flags=lanczos",
                              "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
        return Image.frombytes("RGB", (W, H), raw)
    first, last = grab(0), grab((n_calm - 1) / FPS)
    calm = frames(clip, n_calm)
    feed = [still(os.path.join(FEED, f + ".mp4"), 12.0 if VERT_FEED else 3.0) for f in FEEDS if f != name]
    # Strip: calm(last) | feed... | calm(first). Intro runs pos D -> len-1 easing out; outro runs 0 -> D easing in.
    end = len(feed) + 1

    def intro_pos(u):  # u 0..1, fast then a soft stop with a tiny overshoot
        e = 1 - (1 - u) ** 3
        return D + (end - D) * e + 0.025 * np.sin(np.pi * u) * u ** 4

    hook = S.big([("This is the real", S.WHITE), (title.removeprefix("The ") + ".", S.WHITE), ("In Minecraft.", S.YEL)], 74)
    txt_in = S.soft([("breathe in", S.REG, 54, (255, 255, 255, 235), 0)], shadow=160, blur=12)
    txt_out = S.soft([("breathe out", S.REG, 54, (255, 255, 255, 235), 0)], shadow=160, blur=12)
    txt_place = S.soft([(region + ", at real 1:1 scale", S.SEMI, 40, (255, 255, 255, 240), 0)], shadow=170, blur=12)
    bar = S.brand_bar()
    # (layer, start, end, top, fade) in calm-clip seconds; text sits in the sky band above the landmark.
    # The hook is on screen from the first calm frame; no end card, the outro flick loops into the intro.
    cues = [(hook, 0.0, 4.2, 250, 0.15), (txt_place, 0.6, 4.2, 470, 0.3),
            (txt_in, 6.0, 9.4, 420, 1.0), (txt_out, 10.2, 13.6, 420, 1.0)]

    # Audio: whoosh per panel passing during the flicks, ambience under the calm part with the loop seam inside the whoosh.
    pass_times = [n_in / FPS * (1 - (1 - (k - D) / (end - D)) ** (1 / 3)) for k in range(1, end)]
    pass_times += [(n_in + n_calm) / FPS + OUTRO * 0.6]
    sfx = whooshes(pass_times, total / FPS)
    amb_wav = os.path.join(TMP, name + "-amb.wav")
    subprocess.run([sys.executable, "-I", os.path.join(TOOLS, "ambience.py"), amb_wav, f"{CALM + 2:.1f}", str(seed), "--birds", str(birds), "--wind", str(wind)], check=True)
    amb = read_wav(amb_wav)
    mix = np.zeros((round(total / FPS * SR), 2))
    s = int(n_in / FPS * SR) - int(0.15 * SR)
    seg = amb[int(2.0 * SR): int(2.0 * SR) + int(CALM * SR) + int(0.3 * SR)]  # skip ambience.py's own fade-in
    ramp = np.ones(len(seg)); k = int(0.25 * SR); ramp[:k] = np.linspace(0, 1, k); ramp[-k:] = np.linspace(1, 0, k)
    seg = seg * ramp[:, None]
    mix[s:s + len(seg)] += seg[: len(mix) - s]
    mix += sfx[:len(mix), None]
    mix = np.clip(mix / max(1.0, np.max(np.abs(mix)) / 0.89), -1, 1)
    wav = os.path.join(TMP, name + ".wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())

    tmp_out = os.path.join(TMP, f"loop-{name}.mp4"); enc = S.encoder(tmp_out, wav, total)
    panels = [last] + feed + [first]
    prev = D
    for f in range(total):
        if f < n_in:
            pos = intro_pos(f / (n_in - 1)); img = strip_frame(panels, pos, (pos - prev) * FPS); prev = pos
        elif f < n_in + n_calm:
            c = f - n_in; t = c / FPS; img = next(calm).copy()
            for layer, a0, a1, top, fd in cues:
                a = S.ease((t - a0) / fd) * (1 - S.ease((t - (a1 - fd)) / fd))
                if a > 0: S.put_top(img, layer, W / 2, top, alpha=a)
            S.put_top(img, bar, W / 2, S.BRAND_TOP)
        else:
            u = (f - n_in - n_calm + 1) / n_out; pos = D * u ** 2
            pos_prev = D * ((f - n_in - n_calm) / n_out) ** 2
            img = strip_frame(panels, pos, (pos - pos_prev) * FPS)
        if not (n_in <= f < n_in + n_calm): S.put_top(img, bar, W / 2, S.BRAND_TOP)
        enc.stdin.write(img.tobytes())
    enc.stdin.close(); enc.wait()
    for p in (wav, amb_wav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, f"loop-{name}.mp4")); print("wrote", name, flush=True)


if __name__ == "__main__":
    for nm in sys.argv[1:]: build(nm)
