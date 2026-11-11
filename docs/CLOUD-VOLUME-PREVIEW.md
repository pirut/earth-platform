# Cloud volume preview

This replaces the cloud sheets in the weather/cruise preview. Other rendering,
world-depth, and flight changes remain included. Existing saves need no changes.

## Whiteout correction

At the default cloud height, the middle band extends from Y 3388 to 4238. The
previous implementation also compressed the entire terrain fog range while the
camera was inside a dense part of that band, sometimes to about 250 blocks.
That explains the reported loss of visibility around Y 3400–4200.

Clouds no longer change the world's fog start/end. Their extinction is calculated
only along rays through occupied cloud volume, stopping at scene depth. A
combined opacity limit of 0.72 leaves at least 28% of the underlying view in the
final cloud blend, even in a dense bank. This is an intentional visibility aid.

## Cloud rendering

- Three world-fixed bands retain independent weather coverage, altitude,
  thickness, and wind. Mountains can rise through them.
- A screen-space ray march samples 3D noise for bodies and smaller eroded edges,
  replacing the five/five/two translucent horizontal sheets.
- Coverage-dependent billow height, vertical density falloff, a sample toward the sun, and forward light scattering
  give thicker bodies shaded undersides and brighter thin edges. The game's
  cloud colour supplies the time-of-day lighting.
- Scene depth stops clouds behind terrain and nearby opaque objects. The pass
  restores depth/cull/blend enable state and its 3D texture/sampler binding.
- Work is bounded to three bands with 16, 24, or 32 samples per intersected band.
  Samples concentrate near the cloud-bank entry, and the distant limit fades
  instead of cutting a hard line. Empty weather areas and opaque rays skip
  further work. A repeating 64-cubed
  noise lattice uses about 256 KiB. This is a GPU quality/performance tradeoff;
  it is not a simulated weather system or a complete planetary atmosphere.

Shader packs retain their own cloud rendering. The mod no longer applies its
old cloud immersion fog with or without a pack. Clouds=Off disables this pass;
turning off `layeredClouds` restores vanilla clouds.

## Settings

Client settings in `config/earthplatform-client.toml`:

```toml
layeredClouds = true
cloudQuality = "HIGH"
cloudOpacity = 0.72
cloudHeight = 2500
```

`cloudQuality` accepts `BALANCED`, `HIGH`, or `CINEMATIC`. It changes ray samples,
not the weather layout. `cloudOpacity` accepts 0.20–0.98. These quality/opacity
settings apply live; increasing quality costs GPU time.

JVM overrides are `earthplatform.clouds.quality=balanced|high|cinematic` and
`earthplatform.clouds.opacity=0.72`. Existing layered-cloud and height controls
remain available.

## Verification

The opt-in real-driver suite compiles the actual shader resources, loads them
through Minecraft's ShaderInstance, and renders the cloud pass at Y 1000, 3400,
3800, 4200, and 8000 at 1280×720. Extra cases exercise curvature, Balanced and
Cinematic quality, nighttime lighting, and a camera inside an unbroken dense
bank. It checks bounded opacity and clipping against opaque depth and writes
diagnostic renders to `minecraft/build/reports/cloud-volume/`.
These use a plain blue backdrop, not a running game world. In-game flight,
weather, resource reloads, and performance at the player's resolution still
need testing. In particular, a shader pack's own cloud appearance is unchanged.

The November 6 preview passed `verifyShimArtifact`: 239 tests passed, five
optional benchmarks skipped, no failures. Five real-driver tests include the
ten cloud renders above. Diagnostic draws on the RTX 5070 Laptop GPU took
roughly 0.7–1.5 ms at 1280×720, including a GPU finish; these short isolated
renders are not an in-game FPS benchmark.

Test artifact: `minecraft/build/flight-preview/earthplatform-cloud-volume-preview.jar`.
SHA-256: `0c96304b242dea5e2698a9156008456f0fc0f981c6dd3b953bee6e37c46fc7a6`.
