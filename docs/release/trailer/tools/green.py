#!/usr/bin/env python3
""""Green canion pls" Short: a viewer's comment, JR's character asking an AI chat at an in-game desk,
three wrong guesses (Greenland, Grand Canyon, Waimea), then Green Canyon, Utah.
usage (repo root): python3 green.py   -> /mnt/project-files/release/shorts/hooks/green-canyon.mp4
env: CLIPS (dir with PFX + g-*.mp4), PFX (default b48-)"""
import os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S, loop as Lp
from PIL import Image, ImageDraw, ImageFilter

CLIPS = os.environ.get("CLIPS", "docs/release/trailer/hooks/clips"); PFX = os.environ.get("PFX", "b48-")
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/green-tmp"
W, H, FPS, SR = S.W, S.H, S.FPS, 48000
HANDLE = "@axoltwatel"

# (clip, clip start s, length s, beat). Chat beats: (question, answer). Place beats: (caption, punchline).
SEQ = [("desk-walk", 0.0, 3.4, ("comment",)),
       ("desk-type", 0.0, 2.7, ("chat", 'what does "green canion" mean', "Something green? Like Greenland!")),
       ("greenland", 0.5, 3.4, ("place", "Greenland?", "...it's all ice.")),
       ("desk-type", 2.0, 2.5, ("chat", "no. a CANYON", "Oh! The Grand Canyon!")),
       ("grand_canyon", 0.3, 3.0, ("place", "Grand Canyon?", "...that's red.")),
       ("desk-type", 3.5, 2.5, ("chat", "a GREEN canyon", "Waimea Canyon, Hawaii!")),
       ("waimea", 0.3, 3.0, ("place", "Very green.", "Not a canion though.")),
       ("desk-type", 1.0, 2.9, ("chat", "is a canyon literally NAMED green canyon", "Yes! Green Canyon, Utah.")),
       ("green_canyon", 0.0, 8.5, ("final",))]
TYPE_CPS = 24          # typed characters per second
DOTS = 0.35            # "AI is typing" dots before the answer


def rr(size, radius, fill):
    L = Image.new("RGBA", size); ImageDraw.Draw(L).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=fill)
    return L


def shadowed(L, r=24, a=150):
    pad = r * 2; out = Image.new("RGBA", (L.width + pad * 2, L.height + pad * 2))
    sh = Image.new("RGBA", out.size); sh.paste((0, 0, 0, a), (pad, pad + 8), L.getchannel("A"))
    out.alpha_composite(sh.filter(ImageFilter.GaussianBlur(r))); out.alpha_composite(L, (pad, pad))
    return out


def comment_card(reply=False):
    """Recreated YouTube-style comment (dark) with JR's hearted "On it" reply; no real avatar images."""
    w, h = 860, 372 if reply else 222; L = rr((w, h), 34, (33, 33, 33, 245)); d = ImageDraw.Draw(L)
    def row(y, x0, av, letter, who, when, body, foot):
        d.ellipse((x0, y, x0 + 92, y + 92), fill=av)
        d.text((x0 + 46, y + 46), letter, font=S.font(S.BLACK, 48), fill=(255, 255, 255, 255), anchor="mm")
        d.text((x0 + 124, y + 2), f"{who}  \u00b7  {when}", font=S.font(S.REG, 30), fill=(170, 170, 170, 255))
        d.text((x0 + 124, y + 50), body, font=S.font(S.SEMI, 48), fill=(241, 241, 241, 255))
        f = S.font(S.SEMI, 25); x = x0 + 124
        for t in foot:
            d.text((x, y + 128), t, font=f, fill=(170, 170, 170, 255)); x += S.tw(t, f) + 44
    row(36, 32, (236, 150, 190, 255), "a", HANDLE, "7 hours ago", "Green canion pls", ("\u25b2 1", "Reply"))
    d.ellipse((520, 156, 556, 192), fill=(90, 90, 96, 255)); S_heart(d, 554, 184, 14)
    if reply:
        d.line((32, 222, w - 32, 222), fill=(55, 55, 58, 255), width=2)
        row(250, 80, (60, 110, 200, 255), "h", "@herobydefault31", "0 seconds ago", "On it", ())
    return shadowed(L)


def S_heart(d, cx, cy, r=13):
    d.ellipse((cx - r, cy - r, cx, cy), fill=(230, 40, 60, 255)); d.ellipse((cx, cy - r, cx + r, cy), fill=(230, 40, 60, 255))
    d.polygon([(cx - r, cy - r / 2), (cx + r, cy - r / 2), (cx, cy + r)], fill=(230, 40, 60, 255))


PW = 860
FQ, FA, FH = S.font(S.SEMI, 38), S.font(S.SEMI, 38), S.font(S.SEMI, 26)


def bubble(text, user, cursor=False):
    lines = S.wrap(text + (" |" if cursor else ""), FQ, 560) if text else [" "]
    lh = 50; w = max(S.tw(l, FQ) for l in lines) + 56; h = lh * len(lines) + 34
    L = rr((max(w, 90), h), 30, (37, 99, 235, 255) if user else (64, 66, 74, 255)); d = ImageDraw.Draw(L)
    for i, l in enumerate(lines): d.text((28, 17 + i * lh), l, font=FQ, fill=(255, 255, 255, 255))
    return L


def dots(t):
    L = rr((130, 68), 30, (64, 66, 74, 255)); d = ImageDraw.Draw(L)
    for i in range(3):
        a = 120 + int(135 * max(0, np.sin((t * 7 - i * 0.9))))
        d.ellipse((26 + i * 30, 26, 44 + i * 30, 44), fill=(255, 255, 255, a))
    return L


def chat_panel(q, a, u):
    """Generic chat window at time u (s) into the beat: typed question, typing dots, answer."""
    nq = min(len(q), int(u * TYPE_CPS)); typed = nq >= len(q)
    tq = (len(q) / TYPE_CPS) + 0.15
    ub = bubble(q[:nq], True, cursor=not typed and int(u * 4) % 2 == 0)
    ab = None
    if u > tq + DOTS: ab = bubble(a, False)
    elif u > tq: ab = dots(u)
    h = 90 + ub.height + 26 + (ab.height if ab else 68) + 36
    P = rr((PW, h), 36, (22, 23, 27, 238)); d = ImageDraw.Draw(P)
    # header: generic four-point spark + "AI chat"
    cx, cy, r = 52, 45, 16
    d.polygon([(cx, cy - r), (cx + 5, cy - 5), (cx + r, cy), (cx + 5, cy + 5), (cx, cy + r), (cx - 5, cy + 5), (cx - r, cy), (cx - 5, cy - 5)], fill=(255, 221, 0, 255))
    d.text((82, 45), "AI chat", font=FH, fill=(200, 202, 210, 255), anchor="lm")
    d.line((24, 82, PW - 24, 82), fill=(60, 62, 70, 255), width=2)
    P.alpha_composite(ub, (PW - 28 - ub.width, 104))
    if ab: P.alpha_composite(ab, (28, 104 + ub.height + 26))
    return shadowed(P), nq


def clicks(times, total):
    rng = np.random.default_rng(7); n = int(total * SR); out = np.zeros(n)
    k = int(0.018 * SR); env = np.exp(-np.linspace(0, 9, k))
    for t in times:
        i = int(t * SR)
        if i + k < n: out[i:i + k] += rng.normal(0, 1, k) * env * rng.uniform(0.18, 0.3)
    return out


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    brand = S.brand_bar(); card = comment_card(); card2 = comment_card(True)
    total = sum(round(L * FPS) for _, _, L, _ in SEQ); dur = total / FPS
    caps = {}
    for _, _, _, b in SEQ:
        if b[0] == "place": caps[b[1]] = (S.big([(b[1], S.WHITE)], 82), S.big([(b[2], S.YEL)], 64))
    fin1 = S.big([("Green Canyon,", S.WHITE), ("Utah.", S.YEL)], 88)
    fin2 = S.soft([(f"for {HANDLE}", S.SEMI, 44, (255, 255, 255, 250), 14),
                   ("every block is real, at 1:1 scale", S.REG, 34, (235, 238, 244, 235), 0)], shadow=210, blur=10)

    # sound: ambience, a whoosh into every cut, key clicks while typing
    t0, cuts, keys = 0.0, [], []
    for name, _, L, b in SEQ:
        if t0 > 0: cuts.append(t0 - 0.05)
        if b[0] == "chat": keys += [t0 + i / TYPE_CPS for i in range(len(b[1]))]
        t0 += round(L * FPS) / FPS
    amb_wav = os.path.join(TMP, "amb.wav")
    subprocess.run([sys.executable, "-I", os.path.join(Lp.TOOLS, "ambience.py"), amb_wav, f"{dur + 3:.1f}", "62", "--birds", "3", "--wind", "0.6"], check=True)
    n = round(dur * SR); mix = Lp.read_wav(amb_wav)[int(2.0 * SR):][:n].copy() * 0.8
    k = int(0.3 * SR); mix[-k:] *= np.linspace(1, 0, k)[:, None]
    mix += Lp.whooshes(cuts, dur)[:n, None] * 0.5
    mix += clicks(keys, dur)[:n, None]
    mix = np.clip(mix / max(1.0, np.max(np.abs(mix)) / 0.89), -1, 1)
    wav = os.path.join(TMP, "mix.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype(np.int16).tobytes())

    tmp_out = os.path.join(TMP, "green.mp4"); enc = S.encoder(tmp_out, wav, total)
    for name, cs, L, b in SEQ:
        nf = round(L * FPS); clip = os.path.join(CLIPS, f"{PFX}g-{name}.mp4")
        src = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", f"{cs:.3f}", "-i", clip, "-vf", f"scale={W}:{H}:flags=lanczos",
                                "-frames:v", str(nf), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
        last = None
        for f in range(nf):
            raw = src.stdout.read(W * H * 3)
            img = Image.frombytes("RGB", (W, H), raw) if len(raw) == W * H * 3 else last.copy(); last = img.copy(); u = f / FPS
            if b[0] == "comment":
                if u < 1.1: S.put_top(img, card, W / 2, 230, scale=S.POP[min(f, len(S.POP) - 1)])
                else: S.put_top(img, card2, W / 2, 230, scale=S.POP_SOFT[min(int((u - 1.1) * FPS), len(S.POP_SOFT) - 1)])
            elif b[0] == "chat":
                p, _ = chat_panel(b[1], b[2], u); S.put_top(img, p, W / 2, 200, alpha=S.ease(u / 0.12))
            elif b[0] == "place":
                a, c = caps[b[1]]
                S.put_top(img, a, W / 2, 230, scale=S.POP[min(f, len(S.POP) - 1)])
                if u > 1.2: S.put_top(img, c, W / 2, 230 + a.height - 10, scale=S.POP[min(int((u - 1.2) * FPS), len(S.POP) - 1)])
            elif b[0] == "final" and u > 0.6:
                S.put_top(img, fin1, W / 2, 210, scale=S.POP[min(int((u - 0.6) * FPS), len(S.POP) - 1)])
                S.put_top(img, fin2, W / 2, 210 + fin1.height, alpha=S.ease((u - 1.4) / 0.4))
            S.put_top(img, brand, W / 2, S.BRAND_TOP)
            enc.stdin.write(img.tobytes())
        src.stdout.close(); src.wait()
    enc.stdin.close(); enc.wait()
    for p in (wav, amb_wav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, "green-canyon.mp4")); print("wrote green-canyon", flush=True)


if __name__ == "__main__": main()
