#!/usr/bin/env python3
"""Original Earth Platform score; only mathematical oscillators and seeded noise.

Usage: python3 docs/release/explainer/tools/audio.py --out /tmp/earth-explainer-audio
Dependencies: Python, numpy, scipy, ffmpeg/ffprobe. No samples or external assets.
Produces 48 kHz stereo 24-bit WAV, a two-pass loudnorm report and AAC verification.
Shorts: 54-second circular arrangement with circular tails/delays; full: 300 seconds
with evolving instrumentation and a six-second ending fade. Re-run with the same
seed for identical synthesis. Masters and intermediate media belong outside git.
"""
import argparse
import json
import subprocess
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

SR = 48000
SEED = 20261017
CHORDS = ([50, 57, 62, 65, 69], [46, 53, 58, 62, 65],
          [53, 60, 65, 69, 72], [48, 55, 60, 64, 67])


def run(args):
    return subprocess.run(args, capture_output=True, text=True, check=True)


def meter(path):
    result = run(['ffmpeg', '-hide_banner', '-i', str(path), '-af',
                  'loudnorm=I=-14:TP=-1.5:LRA=9:print_format=json', '-f', 'null', '-'])
    return json.loads(result.stderr[result.stderr.rfind('{'):result.stderr.rfind('}') + 1])


def hz(midi):
    return 440 * 2 ** ((midi - 69) / 12)


def filter_audio(x, kind, cutoff):
    return sosfilt(butter(2, cutoff, btype=kind, fs=SR, output='sos'), x, axis=0)


def synthesize(duration, looping, seed):
    rng = np.random.default_rng(seed)
    n = round(duration * SR)
    mix = np.zeros((n, 2), dtype=np.float32)
    beat = duration / 96 if looping else 5 / 9
    beats = round(duration / beat)

    def put(x, time, gain=1, pan=.5):
        start = round(time * SR)
        stereo = x[:, None] * np.array([np.cos(pan * np.pi / 2), np.sin(pan * np.pi / 2)]) * gain
        if looping:
            start %= n
            end = min(n, start + len(x))
            mix[start:end] += stereo[:end-start]
            if end-start < len(x):
                mix[:len(x)-(end-start)] += stereo[end-start:]
        else:
            lo, hi = max(0, start), min(n, start + len(x))
            if lo < hi:
                mix[lo:hi] += stereo[lo-start:hi-start]

    def tone(note, length, kind='pluck'):
        t = np.arange(round(length * SR)) / SR
        f = hz(note)
        if kind == 'pad':
            x = (np.sin(2*np.pi*f*t) + .27*np.sin(2*np.pi*f*2*t) + .09*np.sin(2*np.pi*f*3*t))
            env = np.minimum(1, t/.32) * np.minimum(1, (length-t)/.65)
            x *= env * (.92+.08*np.sin(2*np.pi*.7*t))
        elif kind == 'bass':
            x = np.sin(2*np.pi*f*t) + .24*np.sin(2*np.pi*f*2*t)
            x *= (1-np.exp(-t*180))*np.exp(-t*3.5)*np.minimum(1,(length-t)/.04)
        else:
            # Gentle bell/wood hybrid, rather than a game sample or square-wave lead.
            x = np.sin(2*np.pi*f*t) + .24*np.sin(2*np.pi*f*2*t)*np.exp(-t*8)
            x += .07*np.sin(2*np.pi*f*3*t)*np.exp(-t*15)
            x *= (1-np.exp(-t*400))*np.exp(-t*5)*np.minimum(1,(length-t)/.04)
        return x

    # Harmonic beds, sparse octave motifs and a rounded pulse leave space for text.
    for bar in range((beats+3)//4):
        notes = CHORDS[(bar//4) % 4]
        phase = (bar//8) % 5
        activity = [0.85, 1.0, .72, .92, 1.05][phase] if not looping else 1.0
        for j, note in enumerate(notes):
            put(tone(note, 4*beat+.75, 'pad'), bar*4*beat, .038*activity, .15+j*.175)
        for b in range(4):
            index = bar*4+b
            if index >= beats: break
            when = index*beat
            put(tone(notes[0]-12, beat*.82, 'bass'), when, .20*activity)
            # Soft two-beat kick/snare pattern, with quieter offbeat hats.
            t = np.arange(round(.26*SR))/SR
            kick = np.sin(2*np.pi*np.cumsum(48+75*np.exp(-t*38))/SR)*np.exp(-t*18)
            kick *= np.minimum(1,t/.002)*np.minimum(1,(.26-t)/.02)
            if b % 2 == 0:
                put(kick, when, .28*activity)
            else:
                noise = filter_audio(rng.normal(size=len(t)), 'bandpass', [950, 4500])
                snare = noise*np.exp(-t*35)*(1-np.exp(-t*900))
                put(snare, when, .065*activity, .52)
            t = np.arange(round(.075*SR))/SR
            hat = filter_audio(rng.normal(size=len(t)), 'highpass', 7000)
            hat *= np.sin(np.pi*t/.075)**2*np.exp(-t*45)
            put(hat, when+beat*.5, .035*activity, .65)
            for eighth in range(2):
                if not looping and phase == 2 and eighth: continue
                pattern = [0, 2, 1, 3, 2, 4, 3, 1]
                note = notes[pattern[(2*b+eighth+bar%2) % 8]]+12
                pluck = tone(note, 1.2)
                start = when+eighth*beat/2
                pan = .30 if (b+eighth)%2 else .70
                put(pluck, start, .057*activity, pan)
                put(pluck, start+.75*beat, .018*activity, 1-pan)
                put(pluck, start+1.5*beat, .006*activity, pan)
        # Subtle airy swells announce large sections, not every cut.
        if bar % 8 == 0:
            t = np.arange(round(.55*SR))/SR
            air = filter_audio(rng.normal(size=len(t)), 'bandpass', [700, 3800])
            put(air*np.sin(np.pi*t/.55)**2, bar*4*beat-.40, .035, .5)
    # Circular filtering preserves the Short's exact periodic signal.
    if looping:
        padding = SR
        extended = np.concatenate((mix[-padding:], mix, mix[:padding]))
        mix = filter_audio(extended, 'highpass', 32)[padding:padding+n]
    else:
        mix = filter_audio(mix, 'highpass', 32)
        fade = round(6*SR)
        mix[-fade:] *= np.linspace(1,0,fade)[:,None]**1.5
        mix[:round(.35*SR)] *= np.linspace(0,1,round(.35*SR))[:,None]
    # Mild saturation controls crest factor before static loudness normalization.
    mix = np.tanh(mix*1.7)/1.7
    return mix.astype(np.float32), {'seed': seed, 'bpm': 60/beat, 'beats': beats,
                                    'duration_seconds':duration, 'loop':looping}


def master(raw, output, looping=False):
    measured = meter(raw)
    fields = ':'.join(f'{target}={measured[source]}' for target, source in
                      [('measured_I','input_i'), ('measured_TP','input_tp'),
                       ('measured_LRA','input_lra'), ('measured_thresh','input_thresh')])
    af = f'loudnorm=I=-14:TP=-1.5:LRA=9:{fields}:offset={measured["target_offset"]}:linear=true:print_format=json'
    result = run(['ffmpeg','-y','-hide_banner','-i',str(raw),'-af',af,'-ar',str(SR),
                  '-c:a','pcm_s24le',str(output)])
    second = json.loads(result.stderr[result.stderr.rfind('{'):result.stderr.rfind('}')+1])
    if looping and second['normalization_type'] != 'linear':
        raise RuntimeError('Unexpected dynamic normalization would compromise a seamless loop')
    return {'first_pass':measured, 'second_pass':second}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=Path('/tmp/earth-explainer-audio'))
    parser.add_argument('--only', choices=['short','full','both'], default='both')
    parser.add_argument('--full-duration', type=float, default=300)
    parser.add_argument('--seed', type=int, default=SEED)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    from audio_qa import inspect
    for name, seconds, looping in [('short',54,True),('full',args.full_duration,False)]:
        if args.only not in ('both', name): continue
        audio, metadata = synthesize(seconds, looping, args.seed)
        raw, output = args.out/f'{name}-raw.wav', args.out/f'{name}.wav'
        wavfile.write(raw, SR, audio)
        report = master(raw, output, looping)
        report.update(metadata)
        report['provenance'] = 'Original code-synthesized oscillators and seeded noise; no third-party samples or compositions.'
        report['qa'] = inspect(output, seconds, looping)
        report['ffmpeg_version'] = run(['ffmpeg','-version']).stdout.splitlines()[0]
        report['numpy_version'] = np.__version__
        (args.out/f'{name}-audio-report.json').write_text(json.dumps(report, indent=2)+'\n')
        print(json.dumps({'master':str(output), 'qa':report['qa']}), flush=True)


if __name__ == '__main__': main()
