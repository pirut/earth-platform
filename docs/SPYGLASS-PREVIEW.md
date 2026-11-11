# Spyglass performance and cloud depth preview

This supersedes the horizon-seam preview. It retains earlier water seam fixes.

- Coarse ocean cells use a 1,024-block grid rather than 512. A full coarsest
  column needs 16 water quads instead of 64; curvature chord error remains below
  five centimetres at scale one. This reduces tessellation introduced by the
  seam fix without returning to disconnected column transforms.

- Spyglass no longer switches cruise smoothing off and back on. Previously each
  transition scheduled levels 3/4 for rebuilding, even though the view needed
  their existing surfaces. Altitude hysteresis remains; nearby real blocks remain.
- Extra zoom terrain is limited to 2,048 columns (previously 8,000), counting at
  most 8x detail magnification. The view cone still follows the actual lens up to
  32x. Quality beyond the cap can be coarser; the whole world is not sharpened.
- Changed zoom directions can replan at most every four ticks, with immediate
  planning on opening the spyglass. Ordinary ten-tick planning continues.
- Clouds use the retained 32-bit far terrain depth when available. Nearby game
  geometry still uses game depth; distant water's deliberately biased depth is
  excluded from that cloud comparison. It could otherwise cut false dark lines
  through clouds over the sea. Without the precise pass the previous depth path
  remains available. Depth textures are reused, not newly allocated per frame.
- Upper cloud weather has a less elongated footprint (8:6 instead of 16:2), a
  thicker vertical envelope, and lower density. This breaks up the straight
  ribbons without adding march samples. Unresolved upper-deck boundaries fade
  at grazing angles instead of becoming long bright bars. Shader packs retain
  their own clouds.

GPU regression cases cover changes in biased distant scene depth, correct near
and precise-far occlusion, a 7-degree spyglass view, and existing cloud opacity
bounds and water geometry joins. CPU tests cover stable cruise geometry and
zoom-detail bounds. The isolated draws do not establish full-world frame rate;
high far settings can still load/render substantially more terrain than Fast.

No Minecraft client is launched for these tests. In-game performance and the
exact pictured scene still need comparison. This is an iteration on identified
causes, not a claim that every frame-time spike or visual seam is eliminated.

November 9 build verification: 243 tests passed, five optional benchmarks skipped,
no failures. Seven real-driver tests include fifteen cloud renders, near/far
occlusion, biased-water depth invariance, and the ocean join comparison. The
upper straight ribbon fades out in the Y 8000 diagnostic render. The 7-degree
cloud-only draw took about 0.8 ms at 1280×720 on the RTX 5070 Laptop GPU; that
does not measure world generation, terrain drawing, or total game frame time.

Artifact: `minecraft/build/flight-preview/earthplatform-spyglass-performance-preview.jar`.
SHA-256: `7befe4aa1740517b0d570c8de866780c39b6aeb0bd4ce61c9379447b467733b7`.
