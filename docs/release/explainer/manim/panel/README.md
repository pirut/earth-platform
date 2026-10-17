# Native right-hand diagram panels

Optional reflowed Manim layout for 1920×1080 landscape edits with portrait gameplay on the left. Original `earth_scenes.py` and its alpha renders are unchanged.

## Recommended composition

- Canvas: 1920×1080, navy `#0C1830`.
- Portrait gameplay: retain aspect ratio, height 1080, x=150, y=0 (608 px wide for 9:16).
- Panel: native 960×800 at x=930, y=90. Do not scale down.
- Captions: x=910, y=925, width 980, height up to 115. Two lines, 33–36 px Inter; no overlay on gameplay.
- The panel's title is ~44 px tall, primary labels ~34–40 px, descriptions ~28 px, and explanatory footers ~25 px. These remain useful at final size; simply halving the original landscape overlay makes dense labels too small.

Visual proof using actual source gameplay: `../qa/panel-placement-example.jpg`. Six-scene contact sheet: `../qa/panel-contact.jpg`.

## Outputs

`media/videos/panel_scenes/800p30/{DataToBlocks,TrueHeight,FarTerrainLOD,SharedServer,FairComparison,HonestLimits}.mp4`

All six renders are 960×800 at 30 fps, opaque navy-backed H.264. The final two seconds retain the final composition. Extend the final frame with `tpad=stop_mode=clone`; do not loop the animation. Use `render-verification.json` for measured durations.

Render from the parent `manim/` directory:

```bash
/tmp/earth-manim-venv/bin/python -m manim --renderer=cairo --media_dir panel/media panel/panel_scenes.py DataToBlocks TrueHeight FarTerrainLOD SharedServer FairComparison HonestLimits
```

The source imports reusable vector helpers and brand values from `../earth_scenes.py`, then sets a separate compact canvas. No new performance claims or rankings are introduced. Manim's star import must precede the explicit brand import, so built-in Manim colors cannot override the Earth palette.
