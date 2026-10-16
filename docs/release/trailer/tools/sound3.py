#!/usr/bin/env python3
"""Original score + sound design for b47-real-life-vs-minecraft-v3.mp4 (visuals untouched, stream-copied).

Everything is synthesized here (no samples, no third-party music), so it is cleared for YouTube.
The whole soundtrack is periodic with the 11.2 s video: every voice, delay tail and effect is rendered
over three periods and the middle one is kept, so tails from the end flow into the start when it loops.

usage (repo root): python3 sound3.py [video]
  -> /mnt/project-files/release/shorts/hooks/real-life-vs-minecraft-v3-sound.mp4 (+ .wav 24-bit master)"""
import json, os, subprocess, sys
import numpy as np
from scipy.signal import butter, sosfilt, sosfiltfilt, resample_poly

SRC = sys.argv[1] if len(sys.argv) > 1 else "docs/release/videos/hooks/b47-real-life-vs-minecraft-v3.mp4"
OUT = "/mnt/project-files/release/shorts/hooks"; NAME = "real-life-vs-minecraft-v3-sound"
SR, FPS = 48000, 30
FRAMES = 336
DUR = FRAMES / FPS                     # 11.2 s
N = int(round(DUR * SR))               # samples per loop
BEAT = DUR / 24                        # 24 beats per loop -> 128.57 BPM; every cut below lands on this grid
rng = np.random.default_rng(11)

# Cue sheet measured from the video (frame-difference peaks), in frames.
CUE = dict(open=0, hook2=28, gc_photo=56, gc_morph=84, rn_photo=126, rn_morph=154,
           hd_photo=210, hd_morph=238, earth=280, fuji=294, scale=308, loop=336)
T = {k: v / FPS for k, v in CUE.items()}


# ---------------------------------------------------------------- helpers
def sos(kind, f, order=2):
    return butter(order, f, btype=kind, fs=SR, output="sos")


def filt(x, kind, f, order=2): return sosfilt(sos(kind, f, order), x, axis=0)


def place(buf, sig, t):
    """add sig at time t (s) into a 3-period buffer, wrapping, centered on the middle period."""
    s = int(round((t + DUR) * SR)); n = len(sig)
    for k in (-1, 0, 1):
        a = s + k * N
        lo, hi = max(a, 0), min(a + n, len(buf))
        if lo < hi: buf[lo:hi] += sig[lo - a:hi - a]


def env_adsr(n, a, d, s, r):
    e = np.full(n, s, float); A, D, R = int(a * SR), int(d * SR), int(r * SR)
    e[:A] = np.linspace(0, 1, A, endpoint=False) if A else e[:A]
    e[A:A + D] = np.linspace(1, s, max(D, 1))[:len(e[A:A + D])]
    if R: e[-R:] *= np.linspace(1, 0, R)
    return e


def saw(f, t, ph=0.0): x = (f * t + ph) % 1.0; return 2 * x - 1


def hz(midi): return 440.0 * 2 ** ((midi - 69) / 12)


L3 = 3 * N
tt = np.arange(L3) / SR - DUR          # time axis, middle period = [0, DUR)

# ---------------------------------------------------------------- harmony follows the picture
# sections (beats): hook 0-4 Dm | Grand Canyon 4-9 Bb | Rainier 9-15 F | Half Dome 15-20 C | end 20-22 Gm, 22-24 A -> Dm
CHORDS = [(0, 4, [50, 53, 57, 62]), (4, 9, [46, 50, 53, 58]), (9, 15, [53, 57, 60, 65]),
          (15, 20, [48, 52, 55, 60]), (20, 22, [43, 46, 50, 55]), (22, 24, [45, 49, 52, 57])]
ROOT = [38, 34, 41, 36, 31, 33]


def chord_at(beat):
    b = beat % 24
    for i, (s, e, notes) in enumerate(CHORDS):
        if s <= b < e: return i, notes
    return 0, CHORDS[0][2]


def bus():
    return np.zeros((L3, 2))


# ---------------------------------------------------------------- music
def pad():
    out = bus()
    for i, (s, e, notes) in enumerate(CHORDS):
        for rep in (-1, 0, 1):
            t0 = s * BEAT + rep * DUR; n = int((e - s) * BEAT * SR) + int(0.35 * SR)
            t = np.arange(n) / SR; sig = np.zeros((n, 2))
            for m in notes + [notes[0] + 12]:
                for k, det in enumerate((-0.11, -0.05, 0.0, 0.06, 0.12)):
                    ph = rng.random(); f = hz(m) * 2 ** (det / 12)
                    v = saw(f, t, ph) * 0.06
                    sig[:, k % 2] += v; sig[:, (k + 1) % 2] += v * 0.35
            sig *= env_adsr(n, 0.04, 0.2, 0.85, 0.32)[:, None]
            place_st(out, sig, t0)
    out = filt(out, "lowpass", 3600, 2); out = filt(out, "highpass", 160, 2)
    return out * 0.75


def place_st(buf, sig, t):
    s = int(round((t + DUR) * SR)); n = len(sig)
    for k in (-1, 0, 1):
        a = s + k * N; lo, hi = max(a, 0), min(a + n, len(buf))
        if lo < hi: buf[lo:hi] += sig[lo - a:hi - a]


def bass():
    out = np.zeros(L3)
    for b8 in range(48):                         # eighth notes
        beat = b8 / 2; i, _ = chord_at(beat); f = hz(ROOT[i])
        n = int(BEAT / 2 * SR); t = np.arange(n) / SR
        v = (0.55 * np.sin(2 * np.pi * f * t) + 0.35 * saw(f * 2, t) * 0.6 + 0.25 * np.sin(2 * np.pi * f * 3 * t)) * env_adsr(n, 0.004, 0.09, 0.55, 0.03)
        place(out, v * (1.0 if b8 % 2 == 0 else 0.7), beat * BEAT)
    out = filt(out, "lowpass", 520, 2); out = filt(out, "highpass", 52, 2)
    return np.stack([out, out], 1) * 0.3


def arp():
    out = np.zeros(L3); pat = [0, 2, 1, 3, 2, 4, 3, 1]
    for s16 in range(96):
        beat = s16 / 4; _, notes = chord_at(beat)
        tones = notes + [x + 12 for x in notes]; m = tones[pat[s16 % 8] % len(tones)] + 12
        n = int(0.16 * SR); t = np.arange(n) / SR; f = hz(m)
        v = (np.sign(np.sin(2 * np.pi * f * t)) * 0.35 + saw(f * 1.003, t) * 0.4) * np.exp(-t * 26)
        acc = 1.0 if s16 % 4 == 0 else 0.62
        place(out, v * acc, beat * BEAT)
    out = filt(out, "bandpass", [700, 5200], 2)
    st = np.stack([out, out], 1)
    # ping-pong dotted-eighth delay, periodic because the buffer wraps three periods
    d = int(0.75 * BEAT * SR); wet = np.zeros_like(st)
    for k in range(1, 6):
        g = 0.38 ** k; sh = np.roll(out, d * k) * g
        wet[:, k % 2] += sh
    wet = filt(wet, "lowpass", 3800, 1)
    return (st + wet * 0.8) * 0.3


def drums():
    kick = np.zeros(L3); hat = np.zeros(L3); clap = np.zeros(L3)
    n = int(0.32 * SR); t = np.arange(n) / SR
    k = np.sin(2 * np.pi * np.cumsum(48 + 95 * np.exp(-t * 38)) / SR) * np.exp(-t * 11)
    k += np.sin(2 * np.pi * 2 * np.cumsum(48 + 95 * np.exp(-t * 38)) / SR) * np.exp(-t * 16) * 0.35   # audible on phones
    k[:120] += rng.standard_normal(120) * np.exp(-np.arange(120) / 25) * 0.25
    for b in range(24): place(kick, k * (1.0 if b % 2 == 0 else 0.82), b * BEAT)
    hn = int(0.05 * SR); hz_ = filt(rng.standard_normal(hn), "highpass", 7500, 2) * np.exp(-np.arange(hn) / SR * 70)
    for e in range(48):
        if e % 2: place(hat, hz_ * 0.5, e * BEAT / 2)
    for s16 in range(80, 96):                     # 16th hats in the last section: lift into the loop
        if s16 % 2: place(hat, hz_ * 0.32, s16 * BEAT / 4)
    cn = int(0.18 * SR); c = filt(rng.standard_normal(cn), "bandpass", [900, 4500], 2) * np.exp(-np.arange(cn) / SR * 22)
    for b in range(1, 24, 2): place(clap, c * 0.55, b * BEAT)
    st = lambda x, w=0.0: np.stack([x, x], 1)
    return st(filt(kick, "highpass", 45, 2)) * 0.6, st(hat) * 0.75 + np.stack([np.roll(hat, 90), hat], 1) * 0.05, st(clap) * 0.35, kick


def sidechain(kick_mono, depth=0.45):
    e = np.abs(kick_mono); e = sosfilt(sos("lowpass", 18, 1), e)
    e = e / (e.max() + 1e-9)
    return (1 - depth * e)[:, None]


# ---------------------------------------------------------------- sound design
def noise(n): return rng.standard_normal(n)


def opening_impact():
    n = int(0.9 * SR); t = np.arange(n) / SR
    sub = np.sin(2 * np.pi * np.cumsum(62 * np.exp(-t * 2.2) + 36) / SR) * np.exp(-t * 4.2)
    body = np.sin(2 * np.pi * np.cumsum(124 * np.exp(-t * 3) + 70) / SR) * np.exp(-t * 7) * 0.45      # phone-speaker harmonic
    air = filt(noise(n), "highpass", 3500, 2) * np.exp(-t * 40) * 0.5
    shimmer = sum(np.sin(2 * np.pi * hz(m) * t + rng.random() * 6) for m in (86, 93, 98)) * np.exp(-t * 5) * 0.05
    m = sub * 0.9 + body + shimmer
    st = np.stack([m + air, m + np.roll(air, 37)], 1)
    return st


def swell(dur, f0, f1, rev=True):
    """Layer A: short reverse-style suction into a cut."""
    n = int(dur * SR); t = np.arange(n) / SR
    x = noise(n); x = filt(x, "bandpass", [f0, f1], 2)
    e = (t / dur) ** 3
    return x * e


def morph_fx(c, tone_root, direction, bright):
    """Layers B + C: blocky digital morph (stepped, resolution-like sine/square blips + textured noise sweep)
    and a tactile low pulse when the game resolves."""
    out = bus()
    # A: suction 140 ms before the visual center
    a = swell(0.14, 400, 3000 * bright); place_st(out, np.stack([a, a * 0.9], 1) * 0.6, c - 0.14)
    # B: 10 stepped blips over the morph (one per 'block size' step), pitch walking in the chosen direction
    steps = 10; span = 10 / FPS
    for k in range(steps):
        tk = c - span / 2 + k * span / steps
        semis = (k if direction > 0 else steps - 1 - k) * 2
        f = hz(tone_root + semis); n = int(0.045 * SR); t = np.arange(n) / SR
        v = (np.sign(np.sin(2 * np.pi * f * t)) * 0.5 + np.sin(2 * np.pi * f * 2 * t) * 0.3) * np.exp(-t * 90)
        pan = 0.5 + 0.35 * np.sin(k * 1.3)
        place_st(out, np.stack([v * (1 - pan), v * pan], 1) * 0.16 * (0.6 + 0.4 * np.sin(np.pi * k / (steps - 1))), tk)
    n = int(span * SR); t = np.arange(n) / SR
    x = noise(n); fc = np.linspace(5000 * bright, 900, n) if direction < 0 else np.linspace(900, 5000 * bright, n)
    y = np.zeros(n); blk = 480
    for i in range(0, n, blk):                              # time-varying band, block-wise
        f = fc[i]; y[i:i + blk] = filt(x[i:i + blk + 256], "bandpass", [f * 0.6, min(f * 1.5, 20000)], 1)[:len(y[i:i + blk])]
    y *= np.sin(np.pi * t / span) ** 1.5
    place_st(out, np.stack([y, np.roll(y, 23)], 1) * 0.22, c - span / 2)
    # C: tactile pulse + click as the terrain resolves (~4 frames after the center)
    m = int(0.35 * SR); t = np.arange(m) / SR
    p = np.sin(2 * np.pi * np.cumsum(90 * np.exp(-t * 18) + 58) / SR) * np.exp(-t * 13)
    p += np.sin(2 * np.pi * np.cumsum(180 * np.exp(-t * 18) + 116) / SR) * np.exp(-t * 18) * 0.3
    clk = np.zeros(m); clk[:96] = filt(noise(96), "highpass", 2500, 2) * np.exp(-np.arange(96) / 14)
    place_st(out, np.stack([p * 0.55 + clk * 0.35, p * 0.55 + clk * 0.3], 1), c + 4 / FPS)
    return out


def photo_air(t0):
    """quiet airy swish where the picture cuts from game to the real photo."""
    n = int(0.22 * SR); t = np.arange(n) / SR
    x = filt(noise(n), "bandpass", [1200, 7000], 2) * np.sin(np.pi * t / 0.22) ** 2 * 0.18
    out = bus(); place_st(out, np.stack([x, np.roll(x, 61)], 1), t0 - 0.11); return out


def tick(t0, f=1800, g=0.18):
    n = int(0.06 * SR); t = np.arange(n) / SR
    v = np.sin(2 * np.pi * f * t) * np.exp(-t * 80) + filt(noise(n), "highpass", 4000, 2) * np.exp(-t * 200) * 0.3
    out = bus(); place_st(out, np.stack([v, v], 1) * g, t0); return out


def ambience():
    """per-location wind beds, crossfaded at cuts; game segments slightly duller than the real photo
    (layer D). The end section uses the canyon bed again so it matches the opening across the loop."""
    beds = {
        "canyon": filt(noise(L3), "bandpass", [180, 1400], 2),
        "alpine": filt(noise(L3), "bandpass", [1400, 5200], 2) * 0.55 + filt(noise(L3), "bandpass", [2300, 2600], 2) * 0.25,
        "open": filt(noise(L3), "bandpass", [380, 3000], 2) * 0.8,
    }
    lfo = 0.65 + 0.35 * np.sin(2 * np.pi * tt / 3.7) * np.sin(2 * np.pi * tt / 1.9 + 1)
    segs = [(T["open"], T["gc_photo"], "canyon", 0.85), (T["gc_photo"], T["gc_morph"], "canyon", 1.0),
            (T["gc_morph"], T["rn_photo"], "canyon", 0.85), (T["rn_photo"], T["rn_morph"], "alpine", 1.0),
            (T["rn_morph"], T["hd_photo"], "alpine", 0.85), (T["hd_photo"], T["hd_morph"], "open", 1.0),
            (T["hd_morph"], T["earth"], "open", 0.85), (T["earth"], T["loop"], "canyon", 0.85)]
    out = np.zeros(L3); xf = 0.08
    for s, e, b, g in segs:
        w = np.zeros(L3)
        for k in (-1, 0, 1):
            a, z = s + k * DUR, e + k * DUR
            w += np.clip((tt - a + xf / 2) / xf, 0, 1) * np.clip((z - tt + xf / 2) / xf, 0, 1)
        out += beds[b] * w * g
    dull = filt(out, "lowpass", 2600, 1)
    out = out * 0.5 + dull * 0.5
    out *= lfo
    st = np.stack([out, np.roll(out, 211)], 1)
    return st * 0.09


# ---------------------------------------------------------------- mix + master
def limiter(x, ceiling, look=0.003, release=0.08):
    """lookahead peak limiter on a 4x-oversampled envelope (true-peak aware)."""
    over = np.abs(resample_poly(x, 4, 1, axis=0)).max(axis=1).reshape(-1, 4).max(axis=1)[:len(x)]
    need = np.minimum(1.0, ceiling / np.maximum(over, 1e-9))
    g = _minfilter(need, int(look * SR))
    rel = np.exp(-1 / (release * SR)); sm = np.empty_like(g); cur = 1.0
    for i in range(len(g)):
        cur = g[i] if g[i] < cur else cur * rel + g[i] * (1 - rel)
        sm[i] = cur
    return x * sm[:, None]


def _minfilter(a, L):
    from numpy.lib.stride_tricks import sliding_window_view
    p = np.concatenate([a, np.ones(L)])
    return sliding_window_view(p, L + 1).min(axis=1)[:len(a)]


def lufs(x):
    p = "/tmp/sound3-meter.wav"; write_wav(p, np.tile(x, (3, 1)), 16)
    r = subprocess.run(["ffmpeg", "-hide_banner", "-i", p, "-af", "ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True).stderr
    i = float(r.split("Integrated loudness:")[1].split("I:")[1].split("LUFS")[0])
    tp = float(r.split("True peak:")[1].split("Peak:")[1].split("dBFS")[0])
    return i, tp


def write_wav(path, x, bits):
    import wave
    if bits == 16:
        data = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes(); sw = 2
    else:
        v = np.ascontiguousarray((np.clip(x, -1, 1) * (2 ** 23 - 1)).astype("<i4"))
        b = v.view(np.uint8).reshape(-1, 4)[:, :3]; data = b.tobytes(); sw = 3
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(sw); w.setframerate(SR); w.writeframes(data)


def main():
    os.makedirs(OUT, exist_ok=True)
    kick, hats, clap, kick_m = drums()
    duck = sidechain(kick_m)
    music = pad() * duck + bass() * duck + arp() * (0.75 + 0.25 * duck) + kick + hats + clap
    music = filt(music, "highpass", 30, 2)
    fx = bus()
    place_st(fx, opening_impact() * 0.9, T["open"])
    place_st(fx, np.stack([swell(0.15, 300, 6000)] * 2, 1) * 0.35, T["loop"] - 0.15)   # suction into the loop impact
    fx += tick(T["hook2"], 1400, 0.10)
    for k, (key, root, d, br) in enumerate([("gc", 62, -1, 1.0), ("rn", 69, +1, 1.25), ("hd", 65, -1, 1.1)]):
        fx += photo_air(T[f"{key}_photo"])
        fx += morph_fx(T[f"{key}_morph"], root, d, br)
    fx += tick(T["earth"], 1100, 0.16) + tick(T["fuji"], 1250, 0.10)
    m = int(0.5 * SR); t = np.arange(m) / SR
    p = np.sin(2 * np.pi * np.cumsum(80 * np.exp(-t * 10) + 50) / SR) * np.exp(-t * 7) * 0.45
    place_st(fx, np.stack([p, p], 1), T["scale"])                                    # "1:1 SCALE." pulse
    # duck the music under the big moments
    env = np.zeros(L3)
    for key in ("open", "gc_morph", "rn_morph", "hd_morph", "scale"):
        for k in (-1, 0, 1):
            c = T[key] + k * DUR; env += np.exp(-np.clip(tt - c, 0, None) / 0.18) * (tt >= c - 0.03)
    music *= (1 - 0.3 * np.clip(env, 0, 1))[:, None]
    amb = ambience()
    mix = music * 1.0 + fx * 1.0 + amb
    mix = filt(mix, "highpass", 40, 2)
    mid = mix[N:2 * N]
    # master: static gain to -14 LUFS, then true-peak limit at -1.2 dBTP (render over 3 periods so the limiter state wraps)
    i, tp = lufs(mid); g = 10 ** ((-14.0 - i) / 20)
    full = limiter(mix * g, 10 ** (-1.3 / 20))
    mid = full[N:2 * N]
    i2, tp2 = lufs(mid)
    if abs(i2 + 14) > 0.3:
        full = limiter(mix * g * 10 ** ((-14 - i2) / 20), 10 ** (-1.3 / 20)); mid = full[N:2 * N]; i2, tp2 = lufs(mid)
    wavp = os.path.join(OUT, NAME + ".wav"); write_wav(wavp, mid, 24)
    mp4 = os.path.join(OUT, NAME + ".mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", SRC, "-i", wavp, "-map", "0:v", "-map", "1:a", "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-t", f"{DUR:.4f}", "-movflags", "+faststart", mp4], check=True)
    print(json.dumps(dict(cues={k: round(v, 3) for k, v in T.items()}, lufs=round(i2, 2), tp=round(tp2, 2))), flush=True)


if __name__ == "__main__": main()
