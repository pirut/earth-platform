# Earth Platform: how it works

Two source-audited, gameplay-led edits: a 54-second portrait Short and a five-minute landscape explainer. See SCRIPT.md for every on-screen statement, source references, exact shot windows, descriptions and chapters. These edits use on-screen text and original synthesized music, without narration or source gameplay audio.

## Rebuild

1. Install Python 3.11+, FFmpeg, Manim Community Edition, Pillow, NumPy and SciPy, plus Cairo/Pango development dependencies and the Inter font.
2. Set EARTH_FONT_DIR to the directory containing Inter-Regular.otf and Inter-Bold.otf.
3. Render portrait overlays using manim/README.md, then the native landscape panels using manim/panel/README.md. The assembler uses native panels for portrait-footage sections in the full cut.
4. Generate the two music masters with tools/audio.py (see its --help).
5. Run `python docs/release/explainer/tools/assemble.py --cut short` and the same command with `--cut full`. Supply --audio-dir if the masters are elsewhere. Use --preview for half-size review files only.
6. Run `python docs/release/explainer/tools/chapters.py` to add full-video chapter metadata without re-encoding. Run tools/validate.py on the finished files. Review all frames around source-window boundaries and the Short loop in addition to automated checks.

Source footage remains in its existing repository locations. Generated Manim media, audio WAVs, temporary cuts and Python caches are excluded from the source deliverable. Production videos live in docs/release/videos/explainer/.

The timeline records source_in/source_duration so the pre-existing trailer's release announcement is never included. All source audio is discarded before the original soundtrack is added. A source window may play slowly; this changes time, not terrain detail or distance. Diagrams are conceptual illustrations, not measured terrain or performance demonstrations.
