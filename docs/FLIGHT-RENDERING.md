# Flight rendering: first pass

The player-centered cube band has a vertical radius of six 16-block cubes. After
climbing about 100 blocks over flat ground, the surface leaves that band and the
far voxel renderer takes over. This can be visible even though the ground is still
close. The far renderer previously also forced 8-block voxels at 200 blocks/second
and 64-block voxels at 800 blocks/second, independently of altitude.

## Implemented

- Preserve the original player cube band for collisions, builds, and descent.
- Starting 48 blocks above the local ground or water, also request surface cubes
  within 384 blocks of the camera and the existing horizontal view distance.
  Bounds follow each column's terrain and water, including negative elevations
  and mountains; they do not use a fixed world Y.
- Keep one cube below the visible surface for neighbouring mesh/light data and
  six above for vegetation, including tall trees. Limit a column to 16 cubes;
  extreme cliffs use far terrain. Oceans keep the water skin without generating
  every cube down to the seabed.
- Prioritize the nearest 64 player cubes, then the surface, then the remaining
  original band. Requests are deduplicated and use the existing worker, per-player
  generation, and network budgets. Buried-rock suppression remains active.
- While airborne, release the ordinary near-player buried-rock digging buffer
  and skip its requests. Surface neighbours, saved edits and known cave openings
  remain protected. On descent the ordinary digging buffer is requested again.
- Keep the same surface set through worker admission, server retention and client
  unload decisions. The existing retiring-mesh/far-mask handoff covers departure.
- Refresh lighting of retained surface meshes when neighbouring sky tops arrive.
- Let altitude choose the finest far level, with the existing descent hysteresis.
  Speed still affects prefetch and fades but no longer forces coarse nearby voxels.

These changes share game code across NeoForge and Fabric. No save format, terrain
generation rules, network payload, or shader-pack source is changed. An updated
server is needed for surface streaming; on multiplayer an updated client alone
cannot request extra near cubes from an older server.

## Validation in game

Compare with `-Dearthplatform.cubic.flightSurface=false` on the server (or the
integrated singleplayer server). This toggles the extra surface band only.

1. Over flat land, climb slowly from ground level to 50, 100, 200, 350, 400 and
   1,000 blocks above ground. Look straight down and toward the horizon. Check
   surface detail, lighting, cracks, and the near/far handoff on both ascent and
   descent. Repeat near the 384-block cutoff and while crossing column boundaries.
2. Repeat over a forest with tall trees, a coast/deep ocean, below-sea-level land,
   a steep cliff, and an 8,000-block mountain. Height must be relative to the
   visible surface. Extreme cliffs intentionally fall back to far terrain.
3. Fly horizontally into newly visited ground, turn, descend quickly, and approach
   a player-built platform. Check collisions and that nearby cubes load first.
   Repeat with another player underground to check independent retention.
4. Repeat without shaders and with stock Photon. Compare near mesh counts,
   pending work, server tick time, frame time, and GPU memory after settling.
   Check that requests and retained cubes settle after flight stops.

Keeping surface cubes can increase resident memory. Existing buried-rock skipping
already saves work, so this is a quality change with bounded additional work,
not a demonstrated performance gain. High-speed low flight can outpace data
generation; coarse parents remain the loading fallback. Automated tests cannot
establish visual quality, frame rate or freedom from visible popping.

## Atmosphere stage

The [distant rendering preview](DISTANT-RENDERING.md) now adds initial high-altitude
haze and texture softening, plus distant ground smoothing. The full proxy described
below remains future work.

At several thousand blocks above terrain, use a separate distant surface proxy,
cloud layers and atmospheric shading. Terrain must remain visible through gaps
in clouds. A readiness/coverage gate must keep existing terrain until the proxy
covers it, then release chunk meshes; descent must prefetch surfaces before
revealing them. Thresholds should use altitude above visible terrain, projected
detail, and hysteresis, including nearby high peaks and zoomed views.

This pass does not implement that proxy or a chunk-free atmosphere mode. Existing
Earth clouds and shader-pack clouds are unchanged. Ordinary nearby cubes still
support builds and gameplay at high altitude, and the existing jet-stream travel
path still retains sky builds for collision.
