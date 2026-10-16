#!/usr/bin/env python3
""""Real life vs Minecraft" Short, v2: opens on the game, full-screen real photo -> game wipes cut on the
beat of "Heroic Age" (Kevin MacLeod, CC BY 4.0), ends on a quick montage + "THE ENTIRE EARTH. 1:1 SCALE."
The last shot is the Grand Canyon clip running straight into the first frame, so the Short loops.
usage (repo root): python3 rvm.py   -> /mnt/project-files/release/shorts/hooks/real-life-vs-minecraft.mp4"""
import os, shutil, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S
from PIL import Image, ImageDraw, ImageFilter

CLIPS = os.environ.get("CLIPS", "docs/release/trailer/hooks/clips"); PFX = os.environ.get("PFX", "b47-")
PHOTOS = "docs/release/trailer/hooks/photos"; MUSIC = "docs/release/trailer/edit/heroic-age.mp3"
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/rvm-tmp"
W, H, FPS = S.W, S.H, S.FPS
BEAT = 60 / 129; MUSIC_IN = 4.17
GC = 2.6          # clip second where the opening starts; the ending runs up to exactly here
WIPE = 7          # frames for the real -> game wipe

# (kind, beats, source, extra)
#   game: clip name, clip start s     real: photo, (cx, cy)
SEQ = [("game", 3, "c-grand_canyon", GC, "open"),
       ("real", 2, "grand_canyon-mather_point.jpg", (0.60, 0.55), "Grand Canyon"),
       ("game", 2, "c-grand_canyon", GC + 3 * BEAT, "Grand Canyon"),
       ("real", 2, "rainier-tipsoo.jpg", (0.55, 0.40), "Mount Rainier"),
       ("game", 4, "c-rainier", 0.6, "Mount Rainier"),
       ("real", 2, "half_dome-snow_creek.jpg", (0.72, 0.45), "Half Dome"),
       ("game", 3, "q-half_dome", 0.8, "Half Dome"),
       ("game", 1, "q-everest", 1.0, "end"), ("game", 1, "q-uluru", 1.0, "end"),
       ("game", 1, "q-fuji", 1.0, "end"), ("game", 1, "scale", 6.0, "end"),
       ("game", 4, "c-grand_canyon", None, "end")]
GCY = {"c-rainier": 0.36, "c-half_dome": 0.42}   # vertical framing for clips whose subject sits high


def nf(b): return round(b * BEAT * FPS)


def pill(text, sub=None):
    f = S.font(S.BLACK, 44); fs = S.font(S.SEMI, 24)
    w = S.tw(text, f) + 56; h = 76 + (34 if sub else 0)
    L = Image.new("RGBA", (max(w, S.tw(sub, fs) + 56 if sub else 0), h)); d = ImageDraw.Draw(L)
    d.rounded_rectangle((0, 0, w, 76), radius=18, fill=(0, 0, 0, 165))
    d.text((28, 38), text, font=f, fill=(255, 221, 0, 255) if text == "MINECRAFT" else (255, 255, 255, 255), anchor="lm")
    if sub: d.text((6, 92), sub, font=fs, fill=(255, 255, 255, 220), anchor="lm", stroke_width=2, stroke_fill=(0, 0, 0, 160))
    return L


def place(text):
    return S.soft([(text, S.BOLD, 56, (255, 255, 255, 255), 0)], shadow=230, blur=9)


def photo(path, cx, cy, zoom):
    im = Image.open(path).convert("RGB"); w, h = im.size
    ch = h / zoom; cw = ch * W / H
    if cw > w: cw = w; ch = cw * H / W
    x = min(max(cx * w - cw / 2, 0), w - cw); y = min(max(cy * h - ch / 2, 0), h - ch)
    return im.resize((W, H), Image.LANCZOS, box=(x, y, x + cw, y + ch))


def game_frames(name, start, n):
    path = os.path.join(CLIPS, PFX + name + ".mp4")
    cy = GCY.get(name, 0.5); zoom = 1.12 if name in GCY else 1.0
    vw, vh = 1008 / zoom, 1792 / zoom; y = min(max(cy * 1792 - vh / 2, 0), 1792 - vh); x = (1008 - vw) / 2
    vf = f"crop={vw:.0f}:{vh:.0f}:{x:.0f}:{y:.0f},scale={W}:{H}:flags=lanczos,tpad=stop_mode=clone:stop=-1"
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", f"{start:.3f}", "-i", path, "-vf", vf, "-frames:v", str(n),
                          "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    for _ in range(n): yield Image.frombytes("RGB", (W, H), p.stdout.read(W * H * 3))
    p.stdout.close(); p.wait()


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    total = sum(nf(b) for _, b, *_ in SEQ); dur = total / FPS
    end_n = nf(SEQ[-1][1]); SEQ[-1] = SEQ[-1][:3] + (GC - end_n / FPS,) + SEQ[-1][4:]
    hook = S.big([("This is REAL terrain", S.WHITE), ("in Minecraft.", S.YEL)], 80)
    end1 = S.big([("THE ENTIRE EARTH.", S.WHITE)], 82); end2 = S.big([("1:1 SCALE.", S.YEL)], 104)
    REAL, MC = pill("REAL LIFE", "photo: National Park Service"), pill("MINECRAFT")
    tag = S.soft([("Earth Platform  ·  Coming Soon", S.SEMI, 34, (255, 255, 255, 240), 0)], shadow=200, blur=8)
    fine = S.soft([(S.DISCLAIMER.replace("\n", " "), S.REG, 18, (230, 234, 240, 190), 0)], shadow=170, blur=6)
    names = {}
    edge = Image.new("RGBA", (10, H), (255, 255, 255, 235))

    # audio: music from the first strong beat, a whoosh into every wipe, short fade at the loop point
    wav = os.path.join(TMP, "mix.wav")
    S.make_audio(MUSIC, MUSIC_IN, dur, wav, -14, -2.3, 0.01, 0.25)

    tmp_out = os.path.join(TMP, "rvm.mp4"); enc = S.encoder(tmp_out, wav, total)
    prev_real = None; t0 = 0
    for kind, beats, src, extra, label in SEQ:
        n = nf(beats)
        if label not in ("open", "end") and label not in names: names[label] = place(label)
        if kind == "real":
            cx, cy = extra; pp = os.path.join(PHOTOS, src)
            for f in range(n):
                img = photo(pp, cx, cy, 1.0 + 0.05 * f / n)
                S.put_top(img, REAL, REAL.width / 2 + 70, 250)
                S.put(img, names[label], W / 2, 1270)
                enc.stdin.write(img.tobytes())
            prev_real = (pp, cx, cy); t0 += n
            continue
        frames = game_frames(src, extra, n)
        for f, img in enumerate(frames):
            gt = (t0 + f) / FPS
            if prev_real and f < WIPE:      # left-to-right wipe from the photo (held at its last zoom)
                u = S.ease((f + 1) / WIPE); xw = int(W * u)
                ph = photo(prev_real[0], prev_real[1], prev_real[2], 1.05)
                ph.paste(img.crop((0, 0, xw, H)), (0, 0)); img = ph
                S.put(img, edge, xw, H / 2)
            if label == "open":
                S.put_top(img, MC, MC.width / 2 + 70, 250)
                S.put_top(img, hook, W / 2, 350)
            elif label == "end":
                e = gt - (total - nf(8)) / FPS   # last 8 beats: montage + Grand Canyon
                if e >= 0: S.put_top(img, end1, W / 2, 350, scale=S.POP[min(int(e * FPS), len(S.POP) - 1)])
                e2 = gt - (total - nf(4)) / FPS
                if e2 >= 0: S.put_top(img, end2, W / 2, 350 + end1.height - 6, scale=S.POP[min(int(e2 * FPS), len(S.POP) - 1)])
                S.put_top(img, MC, MC.width / 2 + 70, 250)
                S.put_top(img, tag, W / 2, 1290); S.put_top(img, fine, W / 2, 1370)
            else:
                S.put_top(img, MC, MC.width / 2 + 70, 250)
                S.put(img, names[label], W / 2, 1270)
            enc.stdin.write(img.tobytes())
        prev_real = None; t0 += n
        continue
    enc.stdin.close(); enc.wait()
    os.remove(wav)
    shutil.move(tmp_out, os.path.join(OUT, "real-life-vs-minecraft.mp4")); print("wrote real-life-vs-minecraft", round(dur, 2), flush=True)


if __name__ == "__main__": main()
