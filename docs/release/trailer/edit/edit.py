#!/usr/bin/env python3
"""Cuts the Earth Platform trailer from per-shot 1080p30 clips, titles and music.

usage: edit.py SHOTS_DIR MUSIC.mp3 LOGO_STACKED_WHITE.png OUT.mp4 [--silent]
SHOTS_DIR holds <shot>.mp4 for every shot named below.
"""
import os, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SHOTS, MUSIC, LOGO, OUT = sys.argv[1:5]
SILENT = "--silent" in sys.argv
def opt(name, default):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default
BADGE = opt("--badge", "COMING SOON")  # end-card pill, e.g. "OUT NOW"
SUBLINE = opt("--subline", "")  # optional line under the pill
W, H, FPS = 1920, 1080, 30
FONTS = os.environ.get("EARTH_FONTS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts")) + "/"
BLACK, BOLD, SEMI, REG = (FONTS + n for n in ("InterDisplay-Black.otf", "InterDisplay-Bold.otf", "Inter-SemiBold.otf", "Inter-Regular.otf"))
NAVY = (12, 24, 48)

# (shot, start in shot s, length s, lower-third place or None, big centred line or None, fade in/out to black)
CUT = [
    ("yosemite", 0.0, 8.0, ("Yosemite Valley", "California"), "What if your world was the whole planet?", "in"),
    ("grand_canyon", 0.3, 6.0, ("Grand Canyon", "Arizona"), None, None),
    ("matterhorn", 0.5, 5.5, ("Matterhorn", "Switzerland"), None, None),
    ("geiranger", 0.5, 5.5, ("Geirangerfjord", "Norway"), None, None),
    ("everest", 0.5, 7.0, ("Mount Everest", "8,849 meters (29,032 ft). Its real height."), None, "out"),
    ("climb", 0.0, 11.0, None, ["The real Earth at true scale", "See up to 200 km (125 miles) away"], "in"),
    ("travel", 0.0, None, None, "Pick anywhere. Go there.", None),
    ("jet", 0.0, 9.0, None, "Ride the jet stream across continents", None),
    ("tahoe", 0.6, 2.0, ("Lake Tahoe", "California"), None, None),
    ("teton", 0.6, 2.0, ("Grand Teton", "Wyoming"), None, None),
    ("fuji", 0.6, 2.0, ("Mount Fuji", "Japan"), None, None),
    ("kilimanjaro", 0.6, 2.0, ("Kilimanjaro", "Tanzania"), None, None),
    ("sahara", 0.6, 2.0, ("Erg Chebbi", "Sahara, Morocco"), None, None),
    ("rainier", 0.6, 2.0, ("Mount Rainier", "Washington"), None, None),
    ("louise", 0.6, 2.0, ("Lake Louise", "Canadian Rockies"), None, None),
    ("iguazu", 0.6, 2.0, ("Iguazu Falls", "Argentina and Brazil"), None, None),
    ("uluru", 0.6, 2.4, ("Uluru", "Australia"), None, "out"),
]
if "--cut" in sys.argv:  # JSON list of [shot, start, length, [place, sub] or null, line(s) or null, fade]
    import json
    CUT = [tuple(c) for c in json.load(open(sys.argv[sys.argv.index("--cut") + 1]))]
END_CARD = 8.0
MUSIC_CREDIT = 'Music: "%s" by Kevin MacLeod (incompetech.com), licensed under CC BY 4.0' % opt("--music-title", "Heroic Age")
DISCLAIMER = "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft."


def font(path, size): return ImageFont.truetype(path, size)


def shadow_text(canvas, xy, text, f, fill=(255, 255, 255), anchor="la", blur=10, strength=200):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).text(xy, text, font=f, fill=NAVY + (strength,), anchor=anchor)
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(layer)
    ImageDraw.Draw(canvas).text(xy, text, font=f, fill=fill + (255,), anchor=anchor)


def lower_third(name, sub, path):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    x, y = 110, H - 210
    shadow_text(im, (x, y), name.upper(), font(BLACK, 64))
    shadow_text(im, (x + 3, y + 84), sub, font(SEMI, 34), fill=(235, 240, 248))
    d = ImageDraw.Draw(im)
    d.rectangle((x - 30, y + 8, x - 22, y + 118), fill=(44, 104, 190, 255))
    im.save(path)


def centre_line(text, path):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow_text(im, (W // 2, H // 2), text, font(BLACK, 84), anchor="mm", blur=16, strength=220)
    im.save(path)


def end_card(path):
    im = Image.new("RGBA", (W, H), NAVY + (255,))
    logo = Image.open(LOGO).convert("RGBA")
    s = 620 / logo.width
    logo = logo.resize((int(logo.width * s), int(logo.height * s)), Image.LANCZOS)
    im.alpha_composite(logo, ((W - logo.width) // 2, 170))
    d = ImageDraw.Draw(im)
    f = font(BOLD, 52)
    y = 170 + logo.height + 70
    tw = d.textlength(BADGE, font=f)
    pad = 44
    d.rounded_rectangle(((W - tw) / 2 - pad, y - 10, (W + tw) / 2 + pad, y + 76), radius=43, fill=(44, 104, 190, 255))
    d.text((W / 2, y + 33), BADGE, font=f, fill=(255, 255, 255), anchor="mm")
    if SUBLINE:
        d.text((W / 2, y + 150), SUBLINE, font=font(SEMI, 40), fill=(235, 240, 248), anchor="mm")
    d.text((W / 2, H - 92), MUSIC_CREDIT, font=font(REG, 24), fill=(170, 182, 204), anchor="mm")
    d.text((W / 2, H - 56), DISCLAIMER, font=font(REG, 24), fill=(170, 182, 204), anchor="mm")
    im.save(path)


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("ffmpeg failed: " + " ".join(cmd) + "\n" + r.stderr[-3000:])


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True).stdout
    return float(out.strip())


tmp = tempfile.mkdtemp(prefix="trailer-")
parts = []
for i, (shot, start, length, place, lines, fade) in enumerate(CUT):
    src = os.path.join(SHOTS, shot + ".mp4")
    if length is None:
        length = duration(src) - start
    inputs = ["-ss", f"{start:.3f}", "-t", f"{length:.3f}", "-i", src]
    overlays = []  # (png, t0, t1)
    if place:
        p = os.path.join(tmp, f"{i}-place.png")
        lower_third(*place, p)
        t0 = (0.5 if length > 3 else 0.15) + (length * 0.5 if lines else 0)
        overlays.append((p, t0, length - (0.6 if length > 3 else 0.15)))
    if lines:
        lines = [lines] if isinstance(lines, str) else lines
        span = (length * 0.47 if place else length) / len(lines)
        for k, text in enumerate(lines):
            p = os.path.join(tmp, f"{i}-line{k}.png")
            centre_line(text, p)
            overlays.append((p, k * span + 0.4, (k + 1) * span - 0.5))
    graph, last = [], "[0:v]"
    for k, (p, t0, t1) in enumerate(overlays):
        inputs += ["-loop", "1", "-t", f"{length:.3f}", "-i", p]
        fd = min(0.45, (t1 - t0) / 3)
        graph.append(f"[{k + 1}:v]format=rgba,fade=t=in:st={t0:.2f}:d={fd:.2f}:alpha=1,fade=t=out:st={t1 - fd:.2f}:d={fd:.2f}:alpha=1[o{k}]")
        graph.append(f"{last}[o{k}]overlay=0:0:format=auto[v{k}]")
        last = f"[v{k}]"
    tail = []
    if fade in ("in",):
        tail.append("fade=t=in:st=0:d=0.8")
    if fade in ("out",):
        tail.append(f"fade=t=out:st={length - 0.6:.2f}:d=0.6")
    tail.append(f"scale={W}:{H},fps={FPS},format=yuv420p")
    graph.append(f"{last}{','.join(tail)}[out]")
    out = os.path.join(tmp, f"part{i:02d}.mp4")
    run(["ffmpeg", "-y", "-v", "error", *inputs, "-filter_complex", ";".join(graph), "-map", "[out]",
         "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "14", out])
    parts.append(out)
    print(f"{shot}: {length:.1f} s", flush=True)

card = os.path.join(tmp, "end.png")
end_card(card)
out = os.path.join(tmp, "part99.mp4")
run(["ffmpeg", "-y", "-v", "error", "-loop", "1", "-t", f"{END_CARD}", "-i", card,
     "-vf", f"fade=t=in:st=0:d=0.8,fade=t=out:st={END_CARD - 1.0}:d=1.0,fps={FPS},format=yuv420p",
     "-c:v", "libx264", "-preset", "medium", "-crf", "14", out])
parts.append(out)

lst = os.path.join(tmp, "list.txt")
with open(lst, "w") as f:
    for p in parts: f.write(f"file '{p}'\n")
video = os.path.join(tmp, "video.mp4")
run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", video])
total = duration(video)
if SILENT:
    run(["ffmpeg", "-y", "-v", "error", "-i", video, "-c", "copy", "-movflags", "+faststart", OUT])
else:
    run(["ffmpeg", "-y", "-v", "error", "-i", video, "-i", MUSIC, "-map", "0:v", "-map", "1:a",
         "-af", f"afade=t=out:st={total - 2.5:.2f}:d=2.5,loudnorm=I=-14:TP=-1.5", "-c:v", "copy",
         "-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-t", f"{total:.3f}", "-movflags", "+faststart", OUT])
print(f"{OUT}: {total:.1f} s")
