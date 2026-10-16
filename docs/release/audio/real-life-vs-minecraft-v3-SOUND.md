# Real Life vs Minecraft v3: sound design

Files:
- Short: `docs/release/videos/hooks/b47-real-life-vs-minecraft-v3-sound.mp4` (1080x1920, 30 fps, H.264 stream-copied from v3, so the picture is bit-identical; AAC 256 kb/s, 48 kHz)
- Audio master: `docs/release/audio/real-life-vs-minecraft-v3-sound.wav` (48 kHz, 24-bit, stereo, 11.200 s)
- Loop test: `docs/release/videos/hooks/b47-real-life-vs-minecraft-v3-sound-5x-loop.mp4` (the Short five times back to back)
- Source: `docs/release/trailer/tools/sound3.py` (everything is synthesized in code; rerun to rebuild)

## Music and licensing
The score is original, written and synthesized for this Short in `sound3.py`. There are no samples, no stock library and no third-party track, so there is nothing to license or credit and nothing for Content ID to match. It replaces "Heroic Age", so the CC BY music credit is no longer needed in the description.

Style: modern hybrid electronic. It uses a detuned supersaw pad, a pulsing side-chained bass, a plucked arpeggio through a ping-pong delay, a tight kick, off-beat hats and a soft clap. It starts at full energy on frame 1, with no intro.

The tempo is 128.57 BPM, which puts exactly 24 beats in the 11.2 s video. Every cut in v3 lands exactly on this beat grid.

The harmony follows the picture, in D minor:

| Section | Beats | Chord |
| --- | --- | --- |
| Hook | 0-4 | Dm |
| Grand Canyon | 4-9 | Bb |
| Rainier | 9-15 | F |
| Half Dome | 15-20 | C |
| "THE ENTIRE EARTH." | 20-22 | Gm |
| "1:1 SCALE." | 22-24 | A |

The A chord at the end resolves into the Dm on frame 1, so the loop sounds like the music continuing, not like an ending. In the last section the hats move to 16ths to lift into the loop.

## Cue sheet
Cut times were measured from the v3 video by frame differencing, not taken from the edit plan.

| Time (s) | Frame | Picture | Sound |
| --- | --- | --- | --- |
| 0.000 | 0 | Opening Minecraft Grand Canyon, "This isn't a Minecraft build." | Opening impact: pitch-dropping sub (62→36 Hz) plus a 124 Hz body for phone speakers, a 40 ms airy high transient and a quiet high shimmer. No long tail. Music ducks briefly. |
| 0.933 | 28 | Text switches to "It's the REAL Grand Canyon. 1:1 SCALE." | Soft high tick. |
| 1.867 | 56 | Cut to the Grand Canyon photo | Quiet airy swish (1.2-7 kHz). Ambience becomes the full canyon wind. |
| 2.660-2.800 | 80-84 | GC transformation begins | Layer A: suction swell, 140 ms into the center. |
| 2.633-2.967 | 79-89 | GC photo turns into blocks, then the game | Layer B: 10 stepped "resolution" blips, one per block-size step, falling in pitch, plus a textured band sweep falling 5 kHz→900 Hz. |
| 2.933 | 88 | Minecraft terrain resolves | Layer C: tactile pulse (90→58 Hz) plus a click. Layer D: ambience shifts to the duller "game" version. Music ducks. |
| 4.200 | 126 | Cut to the Rainier photo | Airy swish. Ambience becomes thin, cold alpine wind with a faint whistle band. |
| 5.133 | 154 | Rainier transformation | Same A-D layers, but rising in pitch and brighter (sweep 900 Hz→6.2 kHz, root A). |
| 7.000 | 210 | Cut to the Half Dome photo | Airy swish. Ambience becomes open mid-band mountain air. |
| 7.933 | 238 | Half Dome transformation | Same A-D layers, falling, root F, medium brightness. |
| 9.333 | 280 | Everest, "THE ENTIRE EARTH." | Low tick. Ambience goes back to canyon wind so it matches the opening across the loop. |
| 9.800 | 294 | Fuji | Lighter tick. |
| 10.267 | 308 | Grand Canyon, "1:1 SCALE." | Medium low pulse (80→50 Hz). Music ducks. |
| 11.050-11.200 | 331-336 | Last frames, running into frame 0 | Suction swell into the loop's opening impact, so the impact is "answered" on every replay. |

Each transformation uses the same four-layer recipe, so they share one identity. Each one differs in direction, pitch and brightness (GC falling/D, Rainier rising/bright/A, Half Dome falling/F), so no single whoosh repeats.

## Mix and master
Priority order: no narration exists, so music comes first, then the transformation effects, then ambience at about -28 dB under the music.

Processing:
- The music is side-chained to the kick.
- The music also ducks about 3 dB under the opening, the three transformations and "1:1 SCALE."
- Master high-pass at 40 Hz to keep sub energy that phones can't play out of the mix.
- Static gain to -14 LUFS.
- Lookahead true-peak limiter (4x oversampled) at -1.3 dBTP.

The limiter and every effect tail are rendered over three periods, and only the middle one is kept. This makes the master exactly periodic.

Measured on the final MP4 (after AAC):

| Measurement | Value |
| --- | --- |
| Integrated loudness | -14.3 LUFS |
| True peak | -1.2 dBTP |
| Sample peak (WAV) | -1.3 dBFS |
| Clipping | none |

Spectral balance by power: <60 Hz 7%, 60-150 Hz 39%, 150 Hz-1 kHz 42%, 1-5 kHz 8%, >5 kHz 4%.

The first pass had 69% of its energy below 150 Hz, which is muddy and would disappear on phones. I rebalanced it: less bass, a brighter pad and a louder arpeggio.

## Loop test
- The WAV was tiled five times and checked at each seam. The sample step across every seam is 0.078, against a 0.43 99th-percentile step in the surrounding audio, so there is no click or level jump.
- Because the whole soundtrack is periodic, the delay tails, wind beds and effect tails from the end continue into the start.
- The video was stream-looped five times into the 56 s loop-test file so it can be played on a phone.

## What changed from v3, and what was left out on purpose
Before: "Heroic Age" cut to length (CC BY, so it needed a credit), one stock-style whoosh per cut and a single low thump. The music ended mid-phrase at the loop.

After:
- An original score built for exactly this edit.
- An opening impact on frame 1.
- Three distinct four-layer transformation sounds that land on the frames where the photo breaks into blocks and the game resolves.
- Subtle per-location wind.
- A harmonic and ambience match across the loop point.

Left out on purpose:
- No Minecraft game sounds. They aren't ours to use, and the brief said to avoid them.
- No risers longer than 150 ms.
- No bass drop.
- No effect on the photo-to-photo text pops beyond one quiet tick, so the transformations stay the stars.

## Limits
These checks were done by measurement, not by ear: loudness, true peak, spectrum, seam continuity and cue alignment on a spectrogram. The headphone, phone-speaker and low-volume listening passes still need a human.
