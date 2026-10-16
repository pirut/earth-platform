#!/usr/bin/env python3
""""Real life vs Minecraft" Short, v3 (refines rvm.py): curiosity hook on the game from frame one,
each NPS photo "turns into blocks" (mosaic morph) into the matching in-game shot on the beat of
"Heroic Age" (Kevin MacLeod, CC BY 4.0), two-beat montage + "THE ENTIRE EARTH. 1:1 SCALE.", and a last
shot that runs straight into the first frame so the Short loops.
usage (repo root): python3 rvm3.py   -> /mnt/project-files/release/shorts/hooks/real-life-vs-minecraft-v3.mp4"""
import os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S, loop as Lp
from PIL import Image, ImageDraw

CLIPS = os.environ.get("CLIPS", "docs/release/trailer/hooks/clips"); PFX = os.environ.get("PFX", "b47-")
PHOTOS = "docs/release/trailer/hooks/photos"; MUSIC = "docs/release/trailer/edit/heroic-age.mp3"
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/rvm3-tmp"
W, H, FPS, SR = S.W, S.H, S.FPS, 48000
BEAT = 60 / 129; MUSIC_IN = 4.17
GC0 = 1.25        # Grand Canyon clip second where the opening starts; the last shot ends exactly here
MORPH = 10        # frames of the photo -> blocks -> game morph, centered on the beat cut

# game framing: clip -> (cx, cy, zoom) so landmarks sit where they sit in the photo
FRAME = {"c-grand_canyon": (0.5, 0.5, 1.0), "c-rainier": (0.5, 0.30, 1.42), "q-half_dome": (0.62, 0.44, 1.55)}
# (kind, beats, source, start/framing, label)
SEQ = [("game", 4, "c-grand_canyon", GC0, "open"),
       ("real", 2, "grand_canyon-mather_point.jpg", (0.60, 0.55), "Grand Canyon, Arizona"),
       ("game", 3, "c-grand_canyon", None, "Grand Canyon, Arizona"),
       ("real", 2, "rainier-tipsoo.jpg", (0.55, 0.40), "Mount Rainier, Washington"),
       ("game", 4, "c-rainier", 0.4, "Mount Rainier, Washington"),
       ("real", 2, "half_dome-snow_creek.jpg", (0.72, 0.45), "Half Dome, California"),
       ("game", 3, "q-half_dome", 0.6, "Half Dome, California"),
       ("game", 1, "q-everest", 1.0, "end"), ("game", 1, "q-fuji", 1.0, "end"),
       ("game", 2, "c-grand_canyon", None, "end")]


def nf(b): return round(b * BEAT * FPS)


def pill(text, sub=None):
    f = S.font(S.BLACK, 44); fs = S.font(S.SEMI, 24)
    w = S.tw(text, f) + 56; h = 76 + (36 if sub else 0)
    L = Image.new("RGBA", (max(w, (S.tw(sub, fs) + 20) if sub else 0), h)); d = ImageDraw.Draw(L)
    d.rounded_rectangle((0, 0, w, 76), radius=18, fill=(0, 0, 0, 170))
    d.text((28, 38), text, font=f, fill=(255, 221, 0, 255) if text == "MINECRAFT" else (255, 255, 255, 255), anchor="lm")
    if sub: d.text((6, 96), sub, font=fs, fill=(255, 255, 255, 225), anchor="lm", stroke_width=2, stroke_fill=(0, 0, 0, 170))
    return L


def place(text): return S.soft([(text, S.BOLD, 54, (255, 255, 255, 255), 0)], shadow=235, blur=9)


def photo(path, cx, cy, zoom):
    im = Image.open(path).convert("RGB"); w, h = im.size
    ch = h / zoom; cw = ch * W / H
    if cw > w: cw = w; ch = cw * H / W
    x = min(max(cx * w - cw / 2, 0), w - cw); y = min(max(cy * h - ch / 2, 0), h - ch)
    return im.resize((W, H), Image.LANCZOS, box=(x, y, x + cw, y + ch))


def game_frames(name, start, n):
    cx, cy, zoom = FRAME.get(name, (0.5, 0.5, 1.0))
    vw, vh = 1008 / zoom, 1792 / zoom
    x = min(max(cx * 1008 - vw / 2, 0), 1008 - vw); y = min(max(cy * 1792 - vh / 2, 0), 1792 - vh)
    vf = f"crop={vw:.0f}:{vh:.0f}:{x:.0f}:{y:.0f},scale={W}:{H}:flags=lanczos,tpad=stop_mode=clone:stop=-1"
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", f"{start:.3f}", "-i", os.path.join(CLIPS, PFX + name + ".mp4"),
                          "-vf", vf, "-frames:v", str(n), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    for _ in range(n): yield Image.frombytes("RGB", (W, H), p.stdout.read(W * H * 3))
    p.stdout.close(); p.wait()


def mosaic(img, block):
    if block <= 1: return img
    small = img.resize((max(1, W // block), max(1, H // block)), Image.BOX)
    return small.resize((W, H), Image.NEAREST)


def morph(ph, game, k):
    """frame k of MORPH: photo breaks into blocks, blocks resolve into the game shot."""
    u = (k + 0.5) / MORPH
    block = int(round(2 + 46 * (1 - abs(2 * u - 1)) ** 1.4))
    mix = S.ease((u - 0.35) / 0.3)
    return mosaic(Image.blend(ph, game, mix), block)


def thump(times, total):
    n = int(total * SR); out = np.zeros(n); L = int(0.35 * SR); t = np.arange(L) / SR
    hit = np.sin(2 * np.pi * (52 + 40 * np.exp(-t * 30)) * t) * np.exp(-t * 9)
    for t0 in times:
        s = int(t0 * SR); e = min(n, s + L); out[s:e] += hit[:e - s]
    return out * 0.5


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    # Grand Canyon clip runs continuously: last shot [GC0 - end, GC0], opening [GC0, ...], comparison after it.
    end_n = nf(SEQ[-1][1]); open_n = nf(SEQ[0][1])
    SEQ[2] = SEQ[2][:3] + (GC0 + open_n / FPS,) + SEQ[2][4:]
    SEQ[-1] = SEQ[-1][:3] + (GC0 - end_n / FPS,) + SEQ[-1][4:]
    total = sum(nf(b) for _, b, *_ in SEQ); dur = total / FPS

    h1 = S.big([("This isn't a", S.WHITE), ("Minecraft build.", S.WHITE)], 86)
    h2 = S.big([("It's the REAL", S.WHITE), ("Grand Canyon.", S.YEL)], 86)
    h3 = S.big([("1:1 SCALE.", S.YEL)], 70)
    end1 = S.big([("THE ENTIRE EARTH.", S.WHITE)], 82); end2 = S.big([("1:1 SCALE.", S.YEL)], 104)
    REAL, MC = pill("REAL LIFE", "photo: National Park Service"), pill("MINECRAFT")
    tag = S.soft([("Earth Platform  ·  Coming Soon", S.SEMI, 34, (255, 255, 255, 240), 0)], shadow=200, blur=8)
    fine = S.soft([(S.DISCLAIMER.replace("\n", " "), S.REG, 18, (230, 234, 240, 190), 0)], shadow=170, blur=6)
    names = {lab: place(lab) for *_, lab in SEQ if lab not in ("open", "end")}
    PILL_Y, TEXT_Y, NAME_Y = 250, 350, 1270

    # timeline: frame index where each segment starts, morph cut points
    starts, t = [], 0
    for seg in SEQ: starts.append(t); t += nf(seg[1])
    cuts = [starts[i] / FPS for i, s in enumerate(SEQ) if s[0] == "game" and i and SEQ[i - 1][0] == "real"]
    end_text = starts[-3] / FPS; scale_hit = starts[-1] / FPS

    wav = os.path.join(TMP, "music.wav")
    S.make_audio(MUSIC, MUSIC_IN, dur, wav, -14, -3.0, 0.01, 0.12)
    mus = Lp.read_wav(wav); n = len(mus)
    sfx = Lp.whooshes([c - 0.16 for c in cuts] + [end_text - 0.1], dur)[:n] * 0.9 + thump([scale_hit, cuts[0]], dur)[:n] * 0.6
    mix = mus + sfx[:, None]; mix = np.clip(mix / max(1.0, np.max(np.abs(mix)) / 0.89), -1, 1)
    mixwav = os.path.join(TMP, "mix.wav")
    with wave.open(mixwav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())

    tmp_out = os.path.join(TMP, "rvm3.mp4"); enc = S.encoder(tmp_out, mixwav, total)
    half = MORPH // 2; last_photo = None
    for i, (kind, beats, src, extra, label) in enumerate(SEQ):
        n = nf(beats)
        if kind == "real":
            cx, cy = extra; pp = os.path.join(PHOTOS, src)
            nxt = SEQ[i + 1]; tail = list(game_frames(nxt[2], nxt[3], half))   # game frames the morph blends into
            for f in range(n):
                img = photo(pp, cx, cy, 1.0 + 0.05 * f / n); last_photo = img
                k = f - (n - half)
                if k >= 0: img = morph(img, tail[min(k, len(tail) - 1)], k)
                if k < 2:
                    S.put_top(img, REAL, REAL.width / 2 + 70, PILL_Y); S.put(img, names[label], W / 2, NAME_Y)
                enc.stdin.write(img.tobytes())
            continue
        for f, img in enumerate(game_frames(src, extra, n)):
            gt = (starts[i] + f) / FPS
            if last_photo is not None and f < half:
                img = morph(last_photo, img, half + f)
            show_ui = last_photo is None or f >= half - 2
            if label == "open":
                S.put_top(img, MC, MC.width / 2 + 70, PILL_Y)
                if gt < 2 * BEAT: S.put_top(img, h1, W / 2, TEXT_Y)
                else:
                    u = gt - 2 * BEAT; pop = S.POP[min(int(u * FPS), len(S.POP) - 1)]
                    S.put_top(img, h2, W / 2, TEXT_Y, scale=pop)
                    S.put_top(img, h3, W / 2, TEXT_Y + h2.height - 8, scale=S.POP[min(max(0, int((u - 0.15) * FPS)), len(S.POP) - 1)])
            elif label == "end":
                S.put_top(img, MC, MC.width / 2 + 70, PILL_Y)
                e = gt - end_text; S.put_top(img, end1, W / 2, TEXT_Y, scale=S.POP[min(int(e * FPS), len(S.POP) - 1)])
                e2 = gt - scale_hit
                if e2 >= 0: S.put_top(img, end2, W / 2, TEXT_Y + end1.height - 6, scale=S.POP[min(int(e2 * FPS), len(S.POP) - 1)])
                S.put_top(img, tag, W / 2, 1290); S.put_top(img, fine, W / 2, 1370)
            elif show_ui:
                S.put_top(img, MC, MC.width / 2 + 70, PILL_Y); S.put(img, names[label], W / 2, NAME_Y)
            enc.stdin.write(img.tobytes())
        last_photo = None
    enc.stdin.close(); enc.wait()
    for p in (wav, mixwav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, "real-life-vs-minecraft-v3.mp4")); print("wrote real-life-vs-minecraft-v3", round(dur, 2), flush=True)


if __name__ == "__main__": main()
