# Consolidated source checkpoint 003

Verified on October 9, 2026. This checkpoint combines independently frozen, completed increments in an isolated source tree. It does not include unfinished near/far coexistence or the later natural-mountain-surface work. Active development files, caches and runtime worlds were not changed.

## Fresh staged verification

- Core: 25 test groups and 351,725 assertions.
- Geography: 847 baseline checks, 6 Copernicus regional checks, 2,512 source-composition checks and 251,345 terrain-variety checks.
- Importers: 14 NOAA, 10 Copernicus and 5 WorldCover Python tests.
- Land cover: all 2,097,152 native source-cell centers, 12 controls, exact hashes, boundaries and explicit unknown coverage.
- Streaming admission: 16 test groups.
- Minecraft: 97 tests across 14 suites, with zero failures, errors or skipped tests. Offline `test build` succeeded. This includes the frozen native renderer's 87 tests, 6 Copernicus window tests and 4 preview-teleport regressions.
- DH CPU proof: 3,312,663 assertions and 100,000 randomized intervals passed against the pinned official dependency. The 13-case, 184-quad, 16,698-byte fixture rebuilt byte-identically and passed independent byte inspection.

The exact combined source was compiled and tested. It was not launched as a new GUI runtime during packaging. Runtime evidence below belongs to the identified frozen increments and must not be presented as a new full-system gameplay pass. Java and Python suites ran with existing tools and dependencies outside the source archive. The verification cache is private to checkpoint packaging and is not the active workers' Gradle cache.

## Frozen inputs and integration

`evidence/frozen-source-snapshots.json` pins the native006, native-render001, Copernicus003, terrain-variety001 and landcover001 inputs. Original manifests and the three Minecraft source-only archives are retained unchanged. The geography baseline and composition manifests and their accepted proof data remain byte-identical.

The combined Minecraft baseline is native006 plus frozen native-render001. Copernicus003 contributes its accepted proof-pack loader, window generator, HUD, payload version, safe preview teleport and tests. Two narrow merges preserve the native payload listener and streaming source set. The exact patch is `evidence/copernicus003-over-native-render001.patch`. Native serializers, sparse ownership, mixins and renderer files are retained rather than overwritten by the older Copernicus baseline.

The later verified terrain-variety signed-zero correction is retained in `VariedOfflineTerrain.java`. This is the only geography main-source difference from the frozen renderer's dependency snapshot. The full combined suites were rerun. Landcover001 adds its own isolated source and data paths and changes no elevation or Minecraft provider API.

The completed DH diagnostic contributes only its two frozen, opt-in original classes. It reads unchanged official shader bytes from a separately supplied, hash-verified DH jar at runtime. No DH jar, copied shader, unfinished coexistence class or modified upstream implementation is included.

The original geography baseline manifest also names excluded third-party projection audits and a research note. Those entries remain in the original manifest as historical provenance; the excluded files are absent. The verifier checks every retained member and explicitly accounts for the omission. Historical archive checksum files identify frozen inputs; they do not imply that an excluded original archive is distributed.

## Recorded native runtime evidence

- Native006: actual isolated ServerLevel and ClientLevel checks passed at Y = -11,000 and +8,849 in two epochs, without tall legacy arrays or vanilla cached columns.
- Copernicus003: the initial grounded Overworld transition and steep ridge loading passed. This is a bounded 256-block preview using the accepted one-arcsecond DSM, not full-height gameplay or complete mountain coverage.
- Native-render001: real Minecraft baked models, MeshData and VertexBuffers rendered the gold and diamond markers at their native heights. First draw found 34,231 gold pixels and 33,895 diamond pixels. Retirement/re-add passed; the VBO resource-generation rebuild reached generation 2 with 5 accepted uploads and 3 rejected stale uploads. Closing left zero GPU meshes and zero resident cubes. A second epoch drew and closed successfully.
- DH first GPU gate: the recorded 13-case offscreen execution used actual DH-produced vertices and unchanged terrain shaders. It passed 184 quads, 52 stale-job rejections and 6,815,744 pixel comparisons, then released its GL resources and restored state.

Both graphics diagnostics ran on Mesa llvmpipe software OpenGL. These are actual graphics-path checks, not hardware GPU performance measurements. Native renderer G rebuilds VBOs; it is not a resource-pack reload. No actual pack-reload pass is claimed for this snapshot. The original frozen instructions contain an F3+T shortcut error; corrected instructions and the explicit erratum accompany them.

## Data, licensing and exclusion policy

The archive includes original implementation/test code, build files and the official Gradle wrapper bootstrap; accepted NOAA and Copernicus regional proof packs; completed ESA WorldCover 2021 v200 samples; exact provenance, notices and applicable license documents; and verification evidence. WorldCover is CC BY 4.0 and covers two imported regions only. Preserve its attribution, source DOI, license and modification notices. Preserve the Copernicus attribution, modified-data and liability notices and full license. The NeoForged template MIT notice is retained. Original project metadata remains All Rights Reserved; this private checkpoint does not grant a new project license.

The DH provenance records the official artifact URL, hash, wrapper and core commits, plus upstream GPL/LGPL texts. Original fixture geometry was converted to actual DH vertex format for the diagnostic. The official whole jar is not redistributed. No drop-in DH compatibility is implied.

The explicit file allowlist excludes Minecraft and NeoForge game binaries/assets/decompiled code, generated mod jars or classes, official DH binaries, JDKs, Gradle caches, credentials, runtime worlds, showcase copies, research-only Mariana data, third-party projection audits and unfinished branches. Native screenshots and diagnostic reports are evidence; no saved world is included.

## Remaining scope

Still open: a playable full-height world, full lighting/fluid/entity/tick/collision/save integration, long-distance near/far world rendering and cache ownership, hardware performance, actual resource-pack reload and full DH mod integration. Current source composition has measured hard discontinuities up to 208.614 metres. Procedural microdetail is labeled and is not enabled by the native generator. WorldCover classes are source-backed input, not surveyed biomes, geology, water heights or Minecraft vegetation; glacier-shadow water classifications may be wrong.

## Reproduce

After extracting the archive, run `python3 scripts/verify-checkpoint.py`. It verifies the complete inventory, original frozen archives/manifests, approved integration differences, data, test XML and native evidence hashes.

Set `JAVA_HOME` to Java 21 and run `./scripts/test-independent.sh`. Python 3, NumPy and Pillow are required. These checks run offline. With the separately resolved official development dependencies, run `minecraft/scripts/gradle.sh --offline test build`; omit `--offline` for an approved first setup.

The optional DH proof runner expects the pinned jar in `LOD_PROBE_CACHE` and previously resolved development dependencies under `minecraft/.gradle-home`. It verifies the official artifact before executing it. No dependency cache or jar is included. The staged CPU results and original GPU reproduction notes are under `evidence/lod-compatibility/`.

`python3 scripts/package-source.py` selects only the reviewed paths in `evidence/package-allowlist.json`. `CHECKPOINT-FILES.json` hashes every payload file; `SHA256SUMS` additionally hashes that manifest and does not hash itself. ZIP CRC, extracted-file verification and deterministic repackaging are checked separately in the external package-validation record.
