# Changing regional cloud cover

Includes the blocky-near preview and earlier performance/cloud fixes.

The mod's cloud renderer now has a world-anchored regional weather envelope.
Cloud cover transitions through clear, scattered and cloudy phases rather than
keeping the same weather density indefinitely. The envelope applies to all
three existing cloud bands, while their separate cloud shapes and winds remain.
At default height the highest band ends around Y 7743, below Everest-scale
summits, so high peaks can project above cloud banks when the region is cloudy.
Other mountains can pass through or rise above lower decks according to height.

The repeating weather lattice has cells about 20 km across at map scale one.
Each temporal lattice step takes 12,000 game ticks (about ten real minutes at
20 TPS); transitions are smooth. This is not a fixed clear/cloudy timer:
duration and coverage depend on the region and neighboring lattice values.
The weather field repeats after 768,000 ticks. Game time and world position
make it deterministic across clients and reloads; it does not follow the camera.
The game's rain increases cloud cover, and day/night cloud lighting still applies.

This is visual cloud weather, not a replacement for rain/gameplay mechanics.
Cloud heights, ray sample budgets, opacity cap, precise terrain clipping and
grazing-angle treatment remain in place. No chunks or terrain are generated for
clouds. The existing 64-cubed noise texture is reused; one extra noise lookup
controls occupied cloud samples, and clear areas skip body/shading work early.
Shader packs retain their own cloud systems and do not use this envelope.

Tests cover clear/cloudy phases at one fixed location, per-tick continuity,
regional differences, periodic boundaries and large coordinates. The real-driver
suite adds natural clear/cloudy views from Y 8912 using the same production
weather helper and noise texture; fixed-weather depth/lighting regressions remain
separate. These diagnostic PNGs use a blue backdrop and look down from a synthetic
mountain-height camera, not a captured Everest world:

- `minecraft/build/reports/cloud-volume/mountain-clear.png`
- `minecraft/build/reports/cloud-volume/mountain-cloudy.png`

The exact in-game location and whole-game performance still need comparison.

November 9 verification: 258 tests passed, five optional benchmarks skipped,
no failures. Three new regional-weather checks and seven real-driver tests
passed; the GPU suite includes seventeen cloud views, including naturally clear
and cloudy fixtures at the same location and mountain-height camera.

Artifact: `minecraft/build/flight-preview/earthplatform-mountain-weather-preview.jar`.
SHA-256: `96c28c8a7fae97689455fa8728e2358f0d0d826f14da5d53c332ad85a8dfdbe6`.
