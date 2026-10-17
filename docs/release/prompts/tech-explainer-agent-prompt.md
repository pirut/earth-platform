# Prompt: build a technical explainer video for Earth Platform (Manim)

You are a technical video producer and engineer. Build an explainer that answers the question viewers keep asking in the comments: **how does Earth Platform actually work, and how is it different from Terra++ / Tellus, Distant Horizons and Voxy?** Use **Manim** (Community Edition, `pip install manim`) for the diagrams and animations, mixed with gameplay footage that already exists in the repo.

Make two cuts from the same Manim scenes:
1. **YouTube Short:** vertical 1080x1920, 30 fps, 45-60 s, hook in the first second, loops cleanly.
2. **Full video:** horizontal 1920x1080, 30 or 60 fps, about 4-7 min, chaptered.

## Repo
- GitHub: https://github.com/pirut/earth-cubic-platform
- Branch to work on and push to: `claude/project-thread-dubj6e`. Push only to this branch. Never merge to main and never open a PR into main.
- Clone: `git clone -b claude/project-thread-dubj6e https://github.com/pirut/earth-cubic-platform`
- Read the source before writing a single claim. Every technical statement in the video must come from the code, from `docs/` (`README.md`, `docs/FAQ.md`, `docs/PLAYING.md`, `CREDITS.md`, `THIRD_PARTY_NOTICES.md`) or from a cited public source for the other mods.

### Where things live
- Cubic engine (true-height cube storage, Level block reads and writes hooked into it): `minecraft/src/main/java/io/earthplatform/minecraft/cubic/`
- World generator: `EarthGenerator` and the `gen/` package.
- Elevation pipeline: `TerrariumTileSource.read()` cleans the tiles (seams, mend, repair, despike). `ElevationLayer`s then stack on top in a per-world pinned order:
  - Copernicus GLO-30 fill
  - USGS 3DEP for the US
  - REMA for Antarctica
- Far terrain renderer: the `cubic.far` package and `client/Far*` classes (`FarGpu`, `FarShaders`, `FarRenderer`).
- Existing edit helpers to reuse for text, branding and audio: `docs/release/trailer/tools/shorts.py`, `loop.py` and `sound3.py`. sound3.py shows the house style for original, code-synthesized music with loop-safe mixing.
- Gameplay footage:
  - `docs/release/trailer/hooks/clips/` (b47-*, b48-*)
  - finished Shorts in `docs/release/videos/hooks/`
  - public-domain NPS photos in `docs/release/trailer/hooks/photos/`
- In-game dev camera, if you need new shots: `minecraft/.../cubic/play/client/Cinema.java`, run with `-Dearthplatform.cinema=<script.cine>`. `docs/release/trailer/hooks/green.cine` is a working example. A cloud render runs on Java 21 under Xvfb, without shaders, at about 0.35-0.7 s per frame.

## What to explain (verify each point in the code first)
1. **Real data, 1:1 scale.** One block is one meter, and true height goes up to Y 32,768, so Everest is at its real height.
   - Show the data stack: global elevation tiles, cleaning passes, then higher-quality layers on top (Copernicus GLO-30, USGS 3DEP lidar in the US, REMA in Antarctica). After that come biomes from CHELSA climate plus ecoregions, rivers from JRC Global Surface Water plus elevation streams, and vanilla trees.
   - Manim idea: a heightmap grid extruding into block columns, then layers sliding in and correcting it.
2. **Why vanilla height isn't enough.** Vanilla worlds stop at a fixed build height, and real mountains are far taller. Earth Platform stores the world in 16x16x16 cubes at true Y, so it loads only the cubes near the player, at any altitude.
   - Manim idea: a vanilla chunk column capped at the build limit, next to a stack of cubes loading around a player on a mountain.
3. **Seeing 94-201 km away.** The mod's own far renderer works like this:
   - It builds a pyramid of 3D voxel sections, where each level's voxel is 2^level blocks.
   - The sections are generated from the same generator, and the player's edits are folded in.
   - They are meshed into compact faces and drawn by the mod's own shader.
   - Detail depends on how big a section is on screen, and exact heights are kept.
   - Manim idea: a level-of-detail pyramid, voxels doubling in size with distance, and a camera frustum.
4. **Servers.** The server downloads the data and streams near and far terrain to players. Clients fetch only map tiles. A shared cache sits behind it.
5. **How it differs** (fair and respectful; verify every claim against each project's own docs or source and cite them in the description):
   - **Terra++ / Tellus:** Earth generators that work inside Minecraft's world height, or on older versions with Cubic Chunks. Explain the scale and height trade-offs they make, as documented.
   - **Distant Horizons:** a general LOD renderer with height-column data. Our notes say its data format caps height at a 4,096-block band and its look is flatter. Confirm from its public source before stating it.
   - **Voxy:** a textured voxel LOD renderer. It is the direct inspiration for our far terrain and should be credited as such. Earth Platform contains no copied code.
   - The message: those are great tools for what they do. Earth Platform combines a true-height cube engine, a real-Earth generator and its own Voxy-style far renderer in one mod.
6. **Honest limits:** elevation data quality varies (some spires are rounded and some fjords were filled in the source data), there are no real cities or roads, and the mod is still in alpha.

## Blend Minecraft gameplay with the diagrams (required)
This is a showcase of the mod, not a slideshow. Every point should start or end in real Minecraft gameplay, with the Manim graphics laid over or morphing out of that footage. Roughly half of the screen time should be gameplay.

Ideas:
- **Freeze-frame:** freeze on an in-game shot, then let Manim draw over it. For example, height lines on Everest, the cube grid around the player, or the far-view LOD rings spreading out to the horizon.
- **Grid zoom:** zoom from a gameplay shot into a Manim grid of the same scene, then back out into the game.
- **Morph:** turn a heightmap into blocks, reusing the photo-to-blocks mosaic morph in `docs/release/trailer/tools/rvm3.py`.
- **Side by side:** split screen with the real NPS photo on one side and the in-game view on the other.
- **Altitude meter:** fly up with the jet stream or elytra while a Manim altimeter counts past vanilla's build limit to Everest's height.
- **Far view:** walk out to a cliff edge with the far view on, then show the 94 km horizon labeled.
- **Server scene:** two players together in the world for the server part.

Plan a shot list that names a clip for each beat. Reuse the existing clips where they fit, and film new ones with Cinema.java (third-person, glide, flyovers) where nothing fits. Match Manim colors and fonts to the Shorts branding in shorts.py so the graphics and the game read as one piece.

The Short should cover only points 1-3 plus a one-line comparison. Its hook should be something like "How do you fit the ENTIRE Earth in Minecraft?" or "Minecraft's height limit is 320 blocks*. Everest is 8,849." (*check the real number), with a "Where should I go next?" loop ending.

## Hard rules
- Say "coming soon" only. Never mention Modrinth, CurseForge or any platform name. Nothing should be claimable or downloadable yet.
- The mod's name is **Earth Platform**. Never say "Minecraft Earth".
- No Mojang logos and no Minecraft game sounds or music. All music and SFX must be original (synthesized in code) or verifiably cleared.
- Use no copyrighted imagery: no Google Earth, no Street View, and no screenshots of other mods unless their license allows it. Diagrams of how other mods work should be your own Manim drawings.
- Show this disclaimer readably: "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft."
- No real cities or towns shown or named.
- Use US spelling. Give ft/mi alongside meters.
- Audio: master to -14 LUFS integrated, about -1 dBTP, with no clipping. If you add narration, use synthetic text-to-speech only if its license allows commercial YouTube use, or leave it as on-screen text plus music.
- In the Short, keep text inside the safe zone, away from the right-side Shorts UI and the bottom caption area.
- Commit messages must not include any AI model names. End every commit message with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_015fiswpx9oroBZZSPXKpcLv
  ```

## Deliverables
1. The Manim source in `docs/release/explainer/manim/` (one scene per section, with a README showing how to render it). Edit scripts go in `docs/release/explainer/tools/`.
2. The Short at `docs/release/videos/explainer/how-it-works-short.mp4`.
3. The full video at `docs/release/videos/explainer/how-it-works.mp4`.
4. `docs/release/explainer/SCRIPT.md` containing:
   - the on-screen text and narration script with timestamps
   - a sources list, giving the file:line for each claim about Earth Platform and links for the other mods
   - 3 title options for each cut
   - descriptions with chapters and hashtags, including the disclaimer and "coming soon"
   - an honest list of weaknesses
5. Commit and push everything to `claude/project-thread-dubj6e`. Note that GitHub warns on files over 50 MB, so keep the renders reasonably sized.
