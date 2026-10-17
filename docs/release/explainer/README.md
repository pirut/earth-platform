# Earth Platform: how it works

## Current delivery status

Source, scripts and documentation are committed. Both videos were rendered and validated locally, and the owner received review copies. Upload of the canonical MP4 files to `docs/release/videos/explainer/` is not verified and remains incomplete. The browser upload ended without a confirmed approval or commit. Do not interpret the output paths below as evidence that the videos are present in this branch. Rebuild instructions and local QA evidence are provided so production can continue.

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

## Distribution encoding

The prepared canonical MP4 files are compact delivery encodes at the requested resolutions: portrait 1080×1920 and landscape 1920×1080, both 30 fps. They use two-pass H.264 with AAC 128k to stay below the authenticated browser's per-file upload ceiling. They are not the higher-bitrate editing masters, and their repository upload has not been verified. Fine foliage and fast terrain motion can show additional compression; text and diagram readability were separately inspected.

The production masters were retained separately for the owner at approximately 29.6 MB and 90.9 MB. No private file links or credentials are embedded in this repository. `tools/encode_delivery.py` reproduces the compact encodes from an input directory of masters without modifying those inputs. Run it only after `assemble.py` and `chapters.py` have produced the masters. Keep the masters outside the destination directory when rebuilding.
