#!/usr/bin/env python3
""""Green canion pls" Short, v2 (~21 s): faster hook, escalating guesses (Grand Canyon -> Greenland -> Utah),
comedy sound design, music only for the reveal, and an ending that asks for the next place (loops into
the comment on frame 1). Reuses the comment card and chat window from green.py.
usage (repo root): python3 green2.py   -> /mnt/project-files/release/shorts/hooks/green-canyon-v2.mp4"""
import os, shutil, subprocess, sys, wave
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shorts as S, loop as Lp, green as G
from PIL import Image, ImageDraw, ImageFont
from scipy.signal import butter, sosfilt

CLIPS = G.CLIPS; PFX = G.PFX
OUT = "/mnt/project-files/release/shorts/hooks"; TMP = "/tmp/green2-tmp"
W, H, FPS, SR = S.W, S.H, S.FPS, 48000
G.TYPE_CPS = 30; G.DOTS = 0.25
rng = np.random.default_rng(5)

# (clip, clip start, seconds, beat)
SEQ = [("desk-walk", 0.4, 2.1, ("hook",)),
       ("desk-type", 0.2, 1.75, ("chat", "what's a green canion", "Grand Canyon!")),
       ("grand_canyon", 0.6, 1.9, ("place", "Grand Canyon?", "...it's RED.")),
       ("desk-type", 2.4, 1.5, ("chat", "no. GREEN", "Greenland!")),
       ("greenland", 1.0, 2.5, ("place", "Greenland?", "...that's ICE.")),
       ("desk-type", 4.0, 2.3, ("chat", "a canyon literally NAMED green", "Green Canyon, Utah.")),
       ("green_canyon", 2.6, 6.4, ("final",))]
PUNCH = 0.85      # place beats: punchline pops this long after the cut


def nf(s): return round(s * FPS)


def sos(kind, f, o=2): return butter(o, f, btype=kind, fs=SR, output="sos")


def filt(x, kind, f, o=2): return sosfilt(sos(kind, f, o), x)


def add(buf, sig, t):
    i = int(t * SR); e = min(len(buf), i + len(sig))
    if 0 <= i < len(buf): buf[i:e] += sig[:e - i]


# ------------------------------------------------------------------ sounds (all synthesized here)
def keyclicks(times, n_total):
    out = np.zeros(n_total); k = int(0.022 * SR); env = np.exp(-np.linspace(0, 10, k))
    for t in times:
        s = filt(rng.normal(0, 1, k), "bandpass", [1800, 6500]) * env * rng.uniform(0.25, 0.4)
        add(out, s, t + rng.uniform(-0.008, 0.008))
    return out


def ai_blip():                       # soft two-note "message received"
    n = int(0.16 * SR); t = np.arange(n) / SR
    a = np.sin(2 * np.pi * 880 * t) * np.exp(-t * 30) * (t < 0.07)
    b = np.sin(2 * np.pi * 1320 * (t - 0.07)) * np.exp(-(t - 0.07) * 28) * (t >= 0.07)
    return (a + b) * 0.16


def error_buzz():                    # short comedic "wrong" (descending square pair)
    n = int(0.42 * SR); t = np.arange(n) / SR
    f = np.where(t < 0.18, 330, 247)
    x = np.sign(np.sin(2 * np.pi * np.cumsum(f) / SR)) * 0.5
    x *= np.where(t < 0.18, np.exp(-t * 4), np.exp(-(t - 0.18) * 6)) * ((t < 0.16) | (t > 0.19))
    return filt(x, "lowpass", 2400) * 0.22


def record_scratch():                # back-and-forth pitch sweep on filtered noise + tone
    n = int(0.38 * SR); t = np.arange(n) / SR
    rate = 1 + 0.9 * np.sin(2 * np.pi * 5.2 * t) * np.exp(-t * 2)
    ph = np.cumsum(420 * rate) / SR
    tone = np.sin(2 * np.pi * ph) * 0.3 + np.sin(2 * np.pi * 2.03 * ph) * 0.15
    nz = filt(rng.normal(0, 1, n), "bandpass", [700, 3800]) * 0.5
    x = (tone + nz * np.abs(np.sin(2 * np.pi * 5.2 * t))) * np.exp(-t * 4)
    return x * 0.45


def whoosh(dur=0.5):
    n = int(dur * SR); t = np.arange(n) / SR; x = rng.normal(0, 1, n); y = np.zeros(n); blk = 480
    for i in range(0, n, blk):
        fc = 400 + 4200 * (i / n) ** 1.6
        y[i:i + blk] = filt(x[i:i + blk + 200], "bandpass", [fc * 0.6, fc * 1.4], 1)[:len(y[i:i + blk])]
    return y * (t / dur) ** 2.2 * 0.6


def impact():
    n = int(1.0 * SR); t = np.arange(n) / SR
    sub = np.sin(2 * np.pi * np.cumsum(60 * np.exp(-t * 2.5) + 38) / SR) * np.exp(-t * 3.5)
    body = np.sin(2 * np.pi * np.cumsum(125 * np.exp(-t * 3) + 72) / SR) * np.exp(-t * 6) * 0.45
    air = filt(rng.normal(0, 1, n), "highpass", 3500) * np.exp(-t * 35) * 0.4
    return (sub * 0.8 + body + air) * 0.7


def hz(m): return 440 * 2 ** ((m - 69) / 12)


def reveal_music(dur):
    """subtle cinematic bed for the reveal: supersaw pad Dm-Bb-F-C, soft pulse, plucked arp with delay."""
    n = int(dur * SR); t = np.arange(n) / SR; out = np.zeros(n)
    chords = [[50, 53, 57, 62], [46, 50, 53, 58], [53, 57, 60, 65], [48, 52, 55, 60]]
    seg = dur / 4
    for i, ch in enumerate(chords):
        a, b = int(i * seg * SR), int(min(n, (i + 1) * seg * SR + 0.3 * SR)); tt = t[a:b] - t[a]
        v = np.zeros(b - a)
        for m in ch + [ch[0] + 12]:
            for det in (-0.1, 0.0, 0.1):
                v += (2 * ((hz(m) * 2 ** (det / 12) * tt + rng.random()) % 1) - 1) * 0.05
        e = np.minimum(1, tt / 0.25) * np.minimum(1, (len(tt) / SR - tt) / 0.3)
        out[a:b] += v * e
    out = filt(filt(out, "lowpass", 2600), "highpass", 150)
    arp = np.zeros(n); step = 60 / 128 / 4; pat = [0, 2, 1, 3]
    for k in range(int(dur / step)):
        ch = chords[min(3, int(k * step / seg))]; m = ch[pat[k % 4]] + 24
        m_n = int(0.15 * SR); tt = np.arange(m_n) / SR
        add(arp, (np.sign(np.sin(2 * np.pi * hz(m) * tt)) * 0.3) * np.exp(-tt * 24) * (1 if k % 4 == 0 else 0.6), k * step)
    arp = filt(arp, "bandpass", [800, 5000]); d = int(0.75 * 60 / 128 * SR)
    wet = sum(np.roll(arp, d * j) * 0.4 ** j for j in range(1, 5))
    pulse = np.zeros(n); kn = int(0.25 * SR); kt = np.arange(kn) / SR
    kick = np.sin(2 * np.pi * np.cumsum(50 + 80 * np.exp(-kt * 35)) / SR) * np.exp(-kt * 12)
    for k in range(int(dur / (60 / 128))): add(pulse, kick * 0.5, k * 60 / 128)
    swell = np.minimum(1, t / 0.6)
    return (out * 0.8 + arp * 0.22 + wet * 0.12 + filt(pulse, "highpass", 45) * 0.35) * swell


# ------------------------------------------------------------------ text
def emoji(ch, size):
    f = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf", 109)
    L = Image.new("RGBA", (160, 160)); ImageDraw.Draw(L).text((10, 10), ch, font=f, embedded_color=True)
    L = L.crop(L.getbbox()); return L.resize((size, size), Image.LANCZOS)


def title_with_emoji():
    t = S.big([("GREEN CANYON, UTAH", S.WHITE)], 66); e = emoji("\U0001F30E", 62)
    L = Image.new("RGBA", (t.width + e.width + 4, max(t.height, e.height)))
    L.alpha_composite(t, (0, 0)); L.alpha_composite(e, (t.width - 30, (t.height - e.height) // 2 - 4))
    return L


def main():
    os.makedirs(TMP, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    total = sum(nf(L) for _, _, L, _ in SEQ); dur = total / FPS
    starts, t0 = [], 0
    for seg in SEQ: starts.append(t0 / FPS); t0 += nf(seg[2])
    hook = S.big([("Someone asked for", S.WHITE), ("GREEN CANYON", S.YEL)], 80)
    hook2 = S.soft([("so I asked AI where that is...", S.SEMI, 42, (255, 255, 255, 250), 0)], shadow=220, blur=9)
    card, card2 = G.comment_card(), G.comment_card(True)
    caps = {b[1]: (S.big([(b[1], S.WHITE)], 82), S.big([(b[2], S.YEL)], 76)) for _, _, _, b in SEQ if b[0] == "place"}
    fin1 = title_with_emoji()
    fin2 = S.soft([("real terrain, every block at 1:1 scale", S.SEMI, 38, (255, 255, 255, 245), 0)], shadow=220, blur=9)
    ask = S.big([("Where should I go next?", S.WHITE)], 66)
    ask2 = S.soft([("comment a real place ↓", S.SEMI, 42, (255, 255, 255, 250), 0)], shadow=220, blur=9)
    brand = S.brand_bar()

    # ---------------- audio
    n = int(round(dur * SR)); sfx = np.zeros(n); keys = []
    amb_wav = os.path.join(TMP, "amb.wav")
    subprocess.run([sys.executable, "-I", os.path.join(Lp.TOOLS, "ambience.py"), amb_wav, f"{dur + 3:.1f}", "41", "--birds", "4", "--wind", "0.5"], check=True)
    amb = Lp.read_wav(amb_wav)[int(2 * SR):][:n].mean(axis=1) * 0.55
    reveal_t = starts[-1]
    for (name, cs, L, b), s in zip(SEQ, starts):
        if b[0] == "chat":
            q = b[1]; keys += [s + i / G.TYPE_CPS for i in range(len(q))]
            add(sfx, ai_blip(), s + len(q) / G.TYPE_CPS + 0.15 + G.DOTS)
        if b[0] == "place":
            add(sfx, whoosh(0.28) * 0.5, s - 0.25)
            if b[1].startswith("Greenland"):
                add(sfx, record_scratch(), s + PUNCH - 0.05)            # the funniest miss
            else:
                add(sfx, error_buzz(), s + PUNCH)
    sfx += keyclicks(keys, n)
    add(sfx, ai_blip() * 1.3, starts[-2] + len(SEQ[-2][3][1]) / G.TYPE_CPS + 0.15 + G.DOTS)
    add(sfx, whoosh(0.55), reveal_t - 0.52)
    add(sfx, impact(), reveal_t)
    mus = reveal_music(dur - reveal_t); mbed = np.zeros(n); add(mbed, mus, reveal_t)
    # ambience dips under the scratch (comedic pause) and gives way to the music at the reveal
    g = np.ones(n); tt = np.arange(n) / SR
    gl = starts[4] + PUNCH
    g *= 1 - 0.85 * np.exp(-np.clip(tt - gl, 0, None) / 0.5) * (tt >= gl)
    g *= np.clip(1 - (tt - reveal_t) / 0.4, 0.25, 1)
    mix = amb * g + sfx + mbed * 0.9
    k = int(0.12 * SR); mix[-k:] *= np.linspace(1, 0.6, k)          # soft into the loop, never silent
    mix = filt(mix, "highpass", 40)
    st = np.stack([mix, mix], 1)
    raw = os.path.join(TMP, "raw.wav")
    with wave.open(raw, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((np.clip(st / max(1, np.abs(st).max() / 0.9), -1, 1) * 32767).astype(np.int16).tobytes())
    wav = os.path.join(TMP, "mix.wav")
    S.make_audio(raw, 0, dur, wav, -14, -2.0, 0.005, 0.01)

    # ---------------- video
    tmp_out = os.path.join(TMP, "green2.mp4"); enc = S.encoder(tmp_out, wav, total)
    for (name, cs, L, b), s in zip(SEQ, starts):
        n_f = nf(L); clip = os.path.join(CLIPS, f"{PFX}g-{name}.mp4")
        src = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", f"{cs:.3f}", "-i", clip, "-vf", f"scale={W}:{H}:flags=lanczos,tpad=stop_mode=clone:stop=-1",
                                "-frames:v", str(n_f), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
        for f in range(n_f):
            img = Image.frombytes("RGB", (W, H), src.stdout.read(W * H * 3)); u = f / FPS
            if b[0] == "hook":
                S.put_top(img, hook, W / 2, 200)
                c = card if u < 0.8 else card2
                S.put_top(img, c, W / 2, 200 + hook.height - 10, scale=S.POP_SOFT[min(int(max(0, u - 0.8) * FPS), len(S.POP_SOFT) - 1)] if u >= 0.8 else 1.0)
                if u > 1.35: S.put_top(img, hook2, W / 2, 200 + hook.height + c.height - 20, alpha=S.ease((u - 1.35) / 0.2))
            elif b[0] == "chat":
                p, _ = G.chat_panel(b[1], b[2], u); S.put_top(img, p, W / 2, 200)
            elif b[0] == "place":
                a, c2 = caps[b[1]]
                S.put_top(img, a, W / 2, 230, scale=S.POP[min(f, len(S.POP) - 1)])
                if u >= PUNCH: S.put_top(img, c2, W / 2, 230 + a.height - 12, scale=S.POP[min(int((u - PUNCH) * FPS), len(S.POP) - 1)])
            elif b[0] == "final":
                if u > 0.15 and u < L - 1.7:
                    S.put_top(img, fin1, W / 2, 230, scale=S.POP[min(int((u - 0.15) * FPS), len(S.POP) - 1)])
                    S.put_top(img, fin2, W / 2, 230 + fin1.height, alpha=S.ease((u - 0.8) / 0.3))
                if u >= L - 1.7:
                    v = u - (L - 1.7)
                    S.put_top(img, ask, W / 2, 230, scale=S.POP[min(int(v * FPS), len(S.POP) - 1)])
                    S.put_top(img, ask2, W / 2, 230 + ask.height, alpha=S.ease((v - 0.2) / 0.2))
            S.put_top(img, brand, W / 2, S.BRAND_TOP)
            enc.stdin.write(img.tobytes())
        src.stdout.close(); src.wait()
    enc.stdin.close(); enc.wait()
    for p in (raw, wav, amb_wav): os.remove(p)
    shutil.move(tmp_out, os.path.join(OUT, "green-canyon-v2.mp4")); print("wrote green-canyon-v2", round(dur, 2), flush=True)


if __name__ == "__main__": main()
