#!/usr/bin/env python3
"""Vertical YouTube Shorts (1080x1920, 30 fps) for Earth Platform.

usage: python3 -I shorts.py fast | ambient [name ...] | all | sheet OUT.jpg
env:   SHOTS (clip dir), MUSIC (dir with the Kevin MacLeod mp3s), OUT (output dir), TMP (scratch dir)

Every frame is built in Pillow (sub-pixel crop/zoom, text layers) and piped raw into ffmpeg/libx264.
"""
import json, math, os, re, shutil, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SHOTS = os.environ.get("SHOTS", "/mnt/project-files/release/trailer/shots")
MUSIC = os.environ.get("MUSIC", "music")
OUT = os.environ.get("OUT", "/mnt/project-files/release/shorts")
TMP = os.environ.get("TMP", "/tmp/shorts-tmp")
BRAND = "/mnt/project-files/release/branding"
FD = "/usr/share/fonts/opentype/inter/"
BLACK, BOLD, SEMI, REG = (FD + f for f in ("InterDisplay-Black.otf", "InterDisplay-Bold.otf", "Inter-SemiBold.otf", "Inter-Regular.otf"))
W, H, FPS = 1080, 1920, 30
SW, SH = 1920, 1080
YEL, WHITE, INK, NAVY, BLUE = (255, 221, 0), (255, 255, 255), (8, 8, 12), (12, 24, 48), (44, 104, 190)
CX = dict(everest=0.72, geiranger=0.45, fuji=0.48, rainier=0.54)
SAFE_R = W - 140            # Shorts UI: right 140 px
SAFE_B = H - 380            # Shorts UI: bottom 380 px
MAXW = 2 * (SAFE_R - W // 2)  # text centered on x=540 must stay left of the right UI zone -> 800 px
DISCLAIMER = "Not an official Minecraft product.\nNot approved by or associated with Mojang or Microsoft."


def credit(track):
    return f'Music: "{track}" by Kevin MacLeod\n(incompetech.com), CC BY 4.0'


# ---------------------------------------------------------------- video io
def clip_path(n): return os.path.join(SHOTS, n + ".mp4")


def read_clip(name, t0, n, vf=None):
    cmd = ["ffmpeg", "-v", "error", "-ss", f"{t0:.3f}", "-i", clip_path(name), "-frames:v", str(n)]
    if vf: cmd += ["-vf", vf]
    p = subprocess.Popen(cmd + ["-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    sz, last, got = SW * SH * 3, None, 0
    while got < n:
        b = p.stdout.read(sz)
        if len(b) < sz: break
        last = Image.frombytes("RGB", (SW, SH), b); got += 1
        yield last
    p.stdout.close(); p.wait()
    for _ in range(n - got): yield last  # pad if the clip ran short


def vcrop(img, cx, zoom=1.0, cy=0.5):
    """608x1080 window (scaled by 1/zoom) centered at cx*width -> 1080x1920, lanczos, sub-pixel box."""
    w, h = 608 / zoom, 1080 / zoom
    x = min(max(cx * SW - w / 2, 0), SW - w)
    y = min(max(cy * SH - h / 2, 0), SH - h)
    return img.resize((W, H), Image.LANCZOS, box=(x, y, x + w, y + h))


def encoder(out, audio, frames):
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
           "-i", "-", "-i", audio, "-map", "0:v", "-map", "1:a",
           "-vf", "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p",
           "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-maxrate", "28M", "-bufsize", "56M",
           "-profile:v", "high", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
           "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-t", f"{frames / FPS:.4f}", "-movflags", "+faststart", out]
    return subprocess.Popen(cmd, stdin=subprocess.PIPE)


def make_audio(src, start, dur, out, I, TP, fin, fout):
    af = f"afade=t=in:d={fin},afade=t=out:st={dur - fout:.3f}:d={fout}"
    base = ["ffmpeg", "-v", "info", "-hide_banner", "-ss", f"{start:.3f}", "-t", f"{dur:.3f}", "-i", src]
    r = subprocess.run(base + ["-af", f"{af},loudnorm=I={I}:TP={TP}:LRA=11:print_format=json", "-f", "null", "-"],
                       capture_output=True, text=True)
    m = json.loads(re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", r.stderr).group(0))
    ln = (f"loudnorm=I={I}:TP={TP}:LRA=11:measured_I={m['input_i']}:measured_TP={m['input_tp']}:"
          f"measured_LRA={m['input_lra']}:measured_thresh={m['input_thresh']}:offset={m['target_offset']}:linear=true")
    subprocess.run(base[:2] + ["error"] + base[3:] + ["-af", f"{af},{ln}", "-ar", "48000", "-ac", "2", "-y", out], check=True)
    return m


# ---------------------------------------------------------------- text layers
def font(path, size): return ImageFont.truetype(path, size)


def tw(t, f, stroke=0):
    b = ImageDraw.Draw(Image.new("L", (1, 1))).textbbox((0, 0), t, font=f, stroke_width=stroke)
    return b[2] - b[0]


def fit(lines, path, size, maxw, stroke_div=11):
    while size > 20:
        f = font(path, size)
        if max(tw(t, f, max(6, size // stroke_div)) for t, _ in lines) <= maxw: return size
        size -= 2
    return size


def big(lines, size, gap=0.08):
    """Bold YouTube style: InterDisplay-Black, thick black outline, hard drop shadow. lines=[(text, rgb)]"""
    f = font(BLACK, size); st = max(6, size // 11); pad = size // 2
    d0 = ImageDraw.Draw(Image.new("L", (1, 1)))
    boxes = [d0.textbbox((0, 0), t, font=f, stroke_width=st) for t, _ in lines]
    lh = [b[3] - b[1] for b in boxes]; wmax = max(b[2] - b[0] for b in boxes)
    w = wmax + pad * 2; h = sum(lh) + int(size * gap) * (len(lines) - 1) + pad * 2
    L = Image.new("RGBA", (w, h)); sh = Image.new("RGBA", (w, h))
    d, ds = ImageDraw.Draw(L), ImageDraw.Draw(sh); y = pad
    for (t, col), b, hh in zip(lines, boxes, lh):
        x = (w - (b[2] - b[0])) // 2 - b[0]; yy = y - b[1]
        ds.text((x + size // 14, yy + size // 12), t, font=f, fill=(0, 0, 0, 200), stroke_width=st, stroke_fill=(0, 0, 0, 200))
        d.text((x, yy), t, font=f, fill=col + (255,), stroke_width=st, stroke_fill=INK + (255,))
        y += hh + int(size * gap)
    out = sh.filter(ImageFilter.GaussianBlur(3)); out.alpha_composite(L)
    return out


def sticker(t, size, bg=YEL, fg=INK, angle=-6):
    f = font(BLACK, size); b = ImageDraw.Draw(Image.new("L", (1, 1))).textbbox((0, 0), t, font=f)
    w, h = b[2] - b[0] + size, b[3] - b[1] + size * 0.7
    L = Image.new("RGBA", (int(w) + 44, int(h) + 44)); d = ImageDraw.Draw(L)
    d.rounded_rectangle((26, 26, w + 26, h + 26), radius=int(size * 0.3), fill=(0, 0, 0, 170))
    d.rounded_rectangle((14, 14, w + 14, h + 14), radius=int(size * 0.3), fill=bg + (255,), outline=INK + (255,), width=max(4, size // 12))
    d.text((14 + w / 2, 14 + h / 2), t, font=f, fill=fg + (255,), anchor="mm")
    return L.rotate(angle, resample=Image.BICUBIC, expand=True)


def soft(lines, shadow=150, blur=10):
    """Elegant text: [(text, fontpath, size, rgba, gap_after)] centered, white with a soft shadow."""
    d0 = ImageDraw.Draw(Image.new("L", (1, 1)))
    rows = [(t, font(p, s), col, g) for t, p, s, col, g in lines]
    bbs = [d0.textbbox((0, 0), t, font=f) for t, f, _, _ in rows]
    pad = 40; w = max(b[2] - b[0] for b in bbs) + pad * 2
    h = sum(b[3] - b[1] + g for b, (_, _, _, g) in zip(bbs, rows)) + pad * 2
    L = Image.new("RGBA", (w, h)); S = Image.new("RGBA", (w, h))
    d, ds = ImageDraw.Draw(L), ImageDraw.Draw(S); y = pad
    for (t, f, col, g), b in zip(rows, bbs):
        x = (w - (b[2] - b[0])) // 2 - b[0]
        ds.text((x + 2, y - b[1] + 3), t, font=f, fill=(0, 0, 0, shadow))
        d.text((x, y - b[1]), t, font=f, fill=col)
        y += b[3] - b[1] + g
    out = S.filter(ImageFilter.GaussianBlur(blur))
    out.alpha_composite(S.filter(ImageFilter.GaussianBlur(2)))
    out.alpha_composite(L)
    return out


def wrap(text, f, maxw):
    out, cur = [], ""
    for wd in text.split():
        nxt = (cur + " " + wd).strip()
        if tw(nxt, f) <= maxw or not cur: cur = nxt
        else: out.append(cur); cur = wd
    return out + [cur]


def small_block(c, texts, y, size=25, col=(200, 210, 228), lead=1.42):
    f = font(REG, size); d = ImageDraw.Draw(c)
    for t in texts:
        for ln in [w for part in t.split("\n") for w in wrap(part, f, MAXW)]:
            d.text((W // 2, y), ln, font=f, fill=col, anchor="ma"); y += int(size * lead)
        y += int(size * 0.35)
    return y


def globe_icon(size):
    g = Image.open(f"{BRAND}/icon/icon-1024.png").convert("RGBA").resize((size, size), Image.NEAREST)
    g.putalpha(g.getchannel("A").point(lambda v: 255 if v > 200 else 0))
    return g


def put(frame, layer, cx, cy, scale=1.0, alpha=1.0):
    """Paste RGBA layer centered at (cx, cy) onto an RGB frame."""
    if alpha <= 0: return
    if scale != 1.0:
        layer = layer.resize((max(1, round(layer.width * scale)), max(1, round(layer.height * scale))), Image.BICUBIC)
    m = layer.getchannel("A")
    if alpha < 1: m = m.point(lambda v: int(v * alpha))
    frame.paste(layer, (round(cx - layer.width / 2), round(cy - layer.height / 2)), m)


def put_top(frame, layer, cx, top, **kw): put(frame, layer, cx, top + layer.height / 2, **kw)


def ease(x): x = min(max(x, 0.0), 1.0); return x * x * (3 - 2 * x)


POP = [0.55, 0.82, 1.04, 1.12, 1.08, 1.03, 1.0]
POP_SOFT = [0.86, 0.98, 1.06, 1.03, 1.0]


def finish(tmp_out, name):
    os.makedirs(OUT, exist_ok=True)
    shutil.copyfile(tmp_out, os.path.join(OUT, name)); os.remove(tmp_out)
    print("wrote", os.path.join(OUT, name), flush=True)


# ---------------------------------------------------------------- 1. fast montage
BEAT = 60 / 129          # Heroic Age, 129 BPM
MUSIC_IN = 4.17          # first strong beat of the energetic section
def bf(k): return round(k * BEAT * FPS)  # beat k -> frame index


# (clip, source in-point s, beats, crop center, place sticker, feature caption)
FAST = [
    ("yosemite", 0.4, 6, .50, "YOSEMITE", "hook"),
    ("grand_canyon", 1.6, 3, .50, "GRAND CANYON", "scale"),
    ("grand_canyon", 4.6, 3, .50, "GRAND CANYON", "scale"),
    ("matterhorn", 0.6, 3, .50, "MATTERHORN", None),
    ("everest", 0.3, 3, .72, "MOUNT EVEREST", "height"),
    ("everest", 5.2, 3, .50, "MOUNT EVEREST", "height"),
    ("geiranger", 4.3, 3, .45, "GEIRANGERFJORD", None),
    ("climb", 6.2, 3, .50, None, "200km"),
    ("climb", 8.8, 3, .50, None, "200km"),
    ("jet", 2.5, 3, .50, None, "jet"),
    ("travel", 2.35, 5, .50, None, "anywhere"),
    ("fuji", 0.4, 2, .48, "MOUNT FUJI", None),
    ("iguazu", 0.5, 2, .50, "IGUAZU FALLS", None),
    ("sahara", 0.5, 2, .50, "SAHARA", None),
    ("rainier", 0.5, 2, .54, "MOUNT RAINIER", None),
    ("uluru", 0.8, 2, .50, "ULURU", None),
]
CAPS = {
    "hook": [("THE REAL WORLD", WHITE), ("IN MINECRAFT", YEL)],
    "scale": [("1:1 SCALE", YEL)],
    "height": [("8,849 METERS", YEL), ("TALL", WHITE)],
    "200km": [("SEE 200 KM", WHITE), ("(125 MILES)", YEL)],
    "jet": [("RIDE THE", WHITE), ("JET STREAM", YEL)],
    "anywhere": [("GO", WHITE), ("ANYWHERE", YEL)],
}
SUBCAP = {"height": ("29,032 FT", WHITE)}
END_SEC = 3.17


def fast_card():
    c = Image.new("RGB", (W, H), NAVY)
    m = Image.new("L", (W, H)); ImageDraw.Draw(m).ellipse((-300, 120, W + 300, 1500), fill=255)
    c.paste((30, 62, 118), (0, 0), m.filter(ImageFilter.GaussianBlur(220)).point(lambda v: int(v * 0.8)))
    return c


def fast_card_layers():
    g = globe_icon(400)
    gl = Image.new("RGBA", (600, 600)); a = Image.new("L", (600, 600)); a.paste(g.getchannel("A"), (100, 100))
    glow = Image.new("RGBA", (600, 600), (120, 190, 255, 0)); glow.putalpha(a.filter(ImageFilter.GaussianBlur(30)).point(lambda v: int(v * 0.5)))
    gl.alpha_composite(glow); gl.alpha_composite(g, (100, 100))
    sz = fit([("EARTH PLATFORM", WHITE)], BLACK, 120, MAXW - 40)
    title = big([("EARTH PLATFORM", WHITE)], sz)
    f = font(BLACK, 58); t = "COMING SOON"; b = ImageDraw.Draw(Image.new("L", (1, 1))).textbbox((0, 0), t, font=f)
    pw, ph = b[2] - b[0] + 110, 118
    pill = Image.new("RGBA", (pw + 20, ph + 20)); d = ImageDraw.Draw(pill)
    d.rounded_rectangle((10, 16, pw + 10, ph + 16), radius=ph // 2, fill=(0, 0, 0, 110))
    d.rounded_rectangle((10, 8, pw + 10, ph + 8), radius=ph // 2, fill=BLUE + (255,))
    d.text((10 + pw / 2, 8 + ph / 2), t, font=f, fill=WHITE + (255,), anchor="mm")
    small = Image.new("RGBA", (W, 260)); small_block(small, [credit("Heroic Age"), DISCLAIMER], 0, size=27)
    return gl, title, pill, small


def build_fast():
    os.makedirs(TMP, exist_ok=True)
    starts, k = [], 0
    for s in FAST: starts.append(bf(k)); k += s[2]
    card_f = bf(k); total = card_f + round(END_SEC * FPS)
    wav = os.path.join(TMP, "fast.wav")
    m = make_audio(os.path.join(MUSIC, "Heroic Age.mp3"), MUSIC_IN, total / FPS, wav, -14, -2.3, 0.01, 1.5)  # TP -2.3 so the AAC encode lands under -1.5 dBTP
    print("fast: music", MUSIC_IN, "->", round(MUSIC_IN + total / FPS, 2), "input_i", m["input_i"], flush=True)

    caps, stick = {}, {}
    for _, _, _, _, st, cp in FAST:
        if cp and cp not in caps:
            sz = fit(CAPS[cp], BLACK, 132 if cp != "hook" else 120, MAXW - 20)
            caps[cp] = big(CAPS[cp], sz)
            if cp in SUBCAP:
                sub = big([SUBCAP[cp]], fit([SUBCAP[cp]], BLACK, 76, MAXW - 20))
                L = Image.new("RGBA", (max(caps[cp].width, sub.width), caps[cp].height + sub.height - 60))
                L.alpha_composite(caps[cp], ((L.width - caps[cp].width) // 2, 0))
                L.alpha_composite(sub, ((L.width - sub.width) // 2, caps[cp].height - 60))
                caps[cp] = L
        if st and st not in stick:
            i = len(stick)
            bg, fg = ((YEL, INK), (WHITE, INK))[i % 2]
            stick[st] = sticker(st, 70, bg=bg, fg=fg, angle=(-6, 5)[i % 2])
            if stick[st].width > MAXW + 40: stick[st] = sticker(st, 60, bg=bg, fg=fg, angle=(-6, 5)[i % 2])
    grad = Image.new("L", (W, 760)); gd = ImageDraw.Draw(grad)
    for y in range(760): gd.line([(0, y), (W, y)], fill=int(105 * (1 - y / 760) ** 1.6))
    blk = Image.new("RGB", (W, 760), (0, 0, 0))

    tmp_out = os.path.join(TMP, "short-fast.mp4"); enc = encoder(tmp_out, wav, total)
    prev_st = prev_cp = None; st_f0 = cp_f0 = 0
    for i, (clip, tin, beats, cx, st, cp) in enumerate(FAST):
        f0 = starts[i]; n = (starts[i + 1] if i + 1 < len(FAST) else card_f) - f0
        if st != prev_st: st_f0 = f0 + (bf(2) if cp == "hook" else 0)
        if cp != prev_cp: cp_f0 = f0
        prev_st, prev_cp = st, cp
        for j, fr in enumerate(read_clip(clip, tin, n, "eq=saturation=1.12:contrast=1.04")):
            f = f0 + j
            img = vcrop(fr, cx, 1.0 + 0.06 * j / max(1, n - 1))
            img.paste(blk, (0, 0), grad)
            if cp:
                e = f - cp_f0; s = 1.0 if cp == "hook" else POP_SOFT[min(e, len(POP_SOFT) - 1)]
                put_top(img, caps[cp], W / 2, 210, scale=s)
            if st and f >= st_f0:
                e = f - st_f0; put(img, stick[st], W / 2, 1330, scale=POP[min(e, len(POP) - 1)])
            enc.stdin.write(img.tobytes())
        print(f"  {clip} {tin}s x{n}f", flush=True)

    bg = fast_card(); gl, title, pill, small = fast_card_layers()
    for e in range(total - card_f):
        img = bg.copy()
        put(img, gl, W / 2, 560, scale=POP[min(e, 6)])
        if e >= 3: put(img, title, W / 2, 900, scale=POP_SOFT[min(e - 3, 4)])
        if e >= 7: put(img, pill, W / 2, 1075, scale=POP[min(e - 7, 6)])
        put_top(img, small, W / 2, 1360, alpha=ease(e / 8))
        enc.stdin.write(img.tobytes())
    enc.stdin.close(); enc.wait(); os.remove(wav)
    finish(tmp_out, "short-fast.mp4")


# ---------------------------------------------------------------- 2. ambient series
# name: (title, region, track, music start s, crop-center keyframes [(t, cx)])
AMB = {
    "yosemite": ("Yosemite Valley", "California, USA", "Ebbs and Flows", 1440.0, None),
    "grand_canyon": ("Grand Canyon", "Arizona, USA", "Ancient Winds", 120.0, None),
    "matterhorn": ("Matterhorn", "Swiss–Italian Alps", "Ebbs and Flows", 720.0, None),
    "geiranger": ("Geirangerfjord", "Møre og Romsdal, Norway", "Ancient Winds", 1200.0, None),
    "everest": ("Mount Everest", "Himalaya · 8,849 meters (29,032 ft)", "Ebbs and Flows", 1100.0, [(0, .72), (2.0, .72), (4.0, .50)]),
    "climb": ("From the valley floor to\n9,000 meters (5.6 miles) up", "Real terrain at 1:1 scale", "Ancient Winds", 2520.0, None),
    "jet": ("Above the Himalaya", "A high-altitude glide at 1:1 scale", "Ebbs and Flows", 2400.0, None),
}
CARD_SEC, XFADE_SEC, FADEIN_SEC = 2.5, 1.5, 0.6


def cx_at(name, t, keys):
    if not keys: return CX.get(name, 0.5)
    if t <= keys[0][0]: return keys[0][1]
    for (t0, a), (t1, b) in zip(keys, keys[1:]):
        if t <= t1: return a + (b - a) * ease((t - t0) / (t1 - t0))
    return keys[-1][1]


def ambient_card(track):
    c = Image.new("RGB", (W, H), NAVY)
    m = Image.new("L", (W, H)); ImageDraw.Draw(m).ellipse((-200, 260, W + 200, 1400), fill=255)
    c.paste((22, 42, 78), (0, 0), m.filter(ImageFilter.GaussianBlur(240)).point(lambda v: int(v * 0.7)))
    logo = Image.open(f"{BRAND}/logo/logo-stacked-white.png").convert("RGBA")
    lw = 640; logo = logo.resize((lw, round(logo.height * lw / logo.width)), Image.LANCZOS)
    c.paste(logo, ((W - lw) // 2, 520), logo)
    d = ImageDraw.Draw(c)
    y = 520 + logo.height + 90
    d.text((W // 2, y), "Coming soon", font=font(SEMI, 58), fill=(235, 241, 250), anchor="ma")
    d.line([(W // 2 - 40, y + 110), (W // 2 + 40, y + 110)], fill=(90, 120, 170), width=3)
    small_block(c, [credit(track), DISCLAIMER], 1360, size=27, col=(160, 176, 204))
    return c


def title_layer(title, region):
    maxw = MAXW - 40; size = 76; f = font(BOLD, size)
    lines = title.split("\n") if "\n" in title else [title] if tw(title, f) <= maxw else wrap(title, font(BOLD, 70), maxw)
    size = 76 if len(lines) == 1 else 70
    while size > 50 and max(tw(t, font(BOLD, size)) for t in lines) > maxw: size -= 2
    rows = [(t, BOLD, size, (255, 255, 255, 255), 18) for t in lines]
    rows[-1] = rows[-1][:4] + (30,)
    rows.append((region, REG, 38, (236, 240, 246, 235), 0))
    return soft(rows)


def build_ambient(name):
    os.makedirs(TMP, exist_ok=True)
    title, region, track, mstart, keys = AMB[name]
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", clip_path(name)]))
    n = round(dur * FPS); total = n + round(CARD_SEC * FPS); xf = round(XFADE_SEC * FPS)
    wav = os.path.join(TMP, f"amb-{name}.wav")
    m = make_audio(os.path.join(MUSIC, track + ".mp3"), mstart, total / FPS, wav, -18, -2, 1.0, 2.0)
    print(f"ambient {name}: {track} {mstart}->{mstart + total / FPS:.1f}s input_i {m['input_i']}", flush=True)
    card = ambient_card(track); txt = title_layer(title, region)
    t_in0, t_in1 = 1.0, 1.9
    t_out1 = (n - xf) / FPS - 0.2; t_out0 = t_out1 - 0.9
    black = Image.new("RGB", (W, H))
    tmp_out = os.path.join(TMP, f"ambient-{name}.mp4"); enc = encoder(tmp_out, wav, total)
    frames = read_clip(name, 0, n)
    last = None
    for f in range(total):
        t = f / FPS
        if f < n:
            fr = next(frames)
            img = vcrop(fr, cx_at(name, t, keys), 1.0 + 0.08 * f / (n - 1)); last = img
            a = ease((t - t_in0) / (t_in1 - t_in0)) * (1 - ease((t - t_out0) / (t_out1 - t_out0)))
            if a > 0: put_top(img, txt, W / 2, 300, alpha=a)
            if f < FADEIN_SEC * FPS: img = Image.blend(black, img, ease(f / (FADEIN_SEC * FPS)))
            if f >= n - xf: img = Image.blend(img, card, ease((f - (n - xf)) / xf))
        else:
            img = card
        enc.stdin.write(img.tobytes())
    enc.stdin.close(); enc.wait(); os.remove(wav)
    finish(tmp_out, f"ambient-{name}.mp4")


# ---------------------------------------------------------------- contact sheet
def sheet(out):
    os.makedirs(TMP, exist_ok=True)
    vids = ["short-fast.mp4"] + [f"ambient-{n}.mp4" for n in AMB]
    tw_, th_ = 216, 384; cols = 8
    rows = []
    for v in vids:
        p = os.path.join(OUT, v)
        if not os.path.exists(p): continue
        d = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p]))
        ts = [0.05] + [d * k / (cols - 1) for k in range(1, cols - 1)] + [d - 0.3]
        if v == "short-fast.mp4":
            ts = [0.1, 1.6, 4.0, 7.5, 10.5, 13.0, 18.0, 23.5]
        row = Image.new("RGB", (cols * tw_, th_ + 28), (20, 20, 20)); d0 = ImageDraw.Draw(row)
        for i, t in enumerate(ts):
            fp = os.path.join(TMP, "sheet.png")
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{t:.2f}", "-i", p, "-frames:v", "1", "-vf", f"scale={tw_}:{th_}", fp], check=True)
            row.paste(Image.open(fp).convert("RGB"), (i * tw_, 28)); os.remove(fp)
            d0.text((i * tw_ + 4, 4), f"{v} @{t:.1f}s", font=font(REG, 14), fill=(230, 230, 230))
        rows.append(row)
    S = Image.new("RGB", (cols * tw_, sum(r.height for r in rows)))
    y = 0
    for r in rows: S.paste(r, (0, y)); y += r.height
    S.save(out, quality=88); print("sheet", out, S.size)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd in ("fast", "all"): build_fast()
    if cmd in ("ambient", "all"):
        for nm in (sys.argv[2:] if cmd == "ambient" and len(sys.argv) > 2 else AMB): build_ambient(nm)
    if cmd == "sheet": sheet(sys.argv[2])


# ---------------------------------------------------------------- persistent brand line (2026-10-16 plan: no end cards)
def brand_bar():
    """Small "Earth Platform · coming soon" plus the disclaimer, shown the whole Short just above the Shorts UI zone."""
    return soft([("Earth Platform  ·  coming soon", SEMI, 30, (255, 255, 255, 235), 8),
                 (DISCLAIMER.replace("\n", " "), REG, 18, (230, 234, 240, 190), 0)], shadow=170, blur=6)
BRAND_TOP = 1430
