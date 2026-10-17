# Far terrain CPU checks

The renderer keeps the same geometry, draw order, shaders, textures, sharpness and far reach. World generation is unchanged.

`vertexBytes()` returns a running total. Replacing a column transfers its byte ownership; uploads adjust the total by the difference, and eviction and close subtract it once.

A settled view reuses the column walk, coverage sets, frustum results, distance order and face groups. The key includes exact camera position and rotation, the actual culling matrix and its offset, column membership, mesh uploads, fade stamps, the finest level, sharpness and the shader fade mode. Exact position matters even inside one block: it can change water order or which side of a face the camera sees. Unknown frustum subclasses use the uncached path. Timed fades, including a parent waiting for its own fade before splitting, continue visiting each frame. Spatial fade bands still draw through their original shaders every frame.

When selection changes, fastutil's stable indirect radix sort orders primitive `double` distances and `int` indices. It preserves full precision and the traversal order of ties. No distance bits are discarded.

Water keeps its column buffers and passes. Cached lists exclude dry columns, fading-in water and walls covered by neighboring water. The lists preserve the original surface, wall and depth-pass order. Repeated equal offset/model-view values avoid uniform uploads, and the depth programs look up their offset locations once per link. This does not batch water geometry or reduce the number of nonempty water draws.

## A/B flags

The defaults enable the changes. Restart the client with these flags for the old paths:

```text
-Dearthplatform.cubic.perf=true
-Dearthplatform.far.trackBytes=false
-Dearthplatform.far.cachePick=false
-Dearthplatform.far.fastWater=false
```

`cachePick=false` also restores the comparator sort and per-frame face-group checks. `fastWater=false` restores the full water scans, neighbor checks, uniform uploads and per-column uniform-location lookups. Leave the existing `splitRanges` and `indexed` flags unchanged between runs.

Current `main` at `73e27da` had no `far water` timing. This change adds it around the water draw, including the depth restoration pass. The water-floor pass remains within `far draw`, as before. Use the flags-disabled build for a water comparison with identical instrumentation.

## Reproduction

Use the exact Iris, Sodium and stock Photon inputs from [BUILDING.md](../../BUILDING.md). Build with `cd minecraft && ./gradlew jar test`. The test suite checks full-precision sort ties at 46,000 entries, array reuse, and changes to every cached view input.

Use the same saved Earth world and window size for all runs. Set Sharpest and retain the same far distance. Disable pause on lost focus. In spectator mode:

```text
/gamemode spectator
/gamerule doDaylightCycle false
/gamerule doWeatherCycle false
/weather clear
/time set 6000
/tp @s -10530225.5 1572 -3593832.5 90 8
```

Wait until the view is fully settled and uploads have stopped. Capture the view without the HUD, then measure 30 seconds with F3 visible and `earthplatform.cubic.perf=true`. Hide F3 and the HUD and capture it again. Repeat with shaders disabled. Compare a default build with the same build using all three flags above. Also check sub-block movement, turning, loading, fades and shader reloads.

Cross-run screenshots of stock Photon contain temporal antialiasing and animated sky/water differences. Screenshot differences alone cannot establish bit-for-bit equality. Compare repeat captures from each path as well, and verify selection, face groups, water order and coverage against a fresh computation at the same view.

## Measured results

Mac M4 Pro, Java 21, NeoForge 21.1.262, Iris 1.8.14-beta.1 and Sodium 0.8.12-beta.1, Minecraft 1.21.1. Sharpest, detail 11, 94 km far reach, 1708 × 960 framebuffer, 6 GiB heap. The saved view held 58,909 columns and drew about 46,000 columns / 28,000 face ranges under Photon. No shader ZIP, shader option, texture, world-generation setting or terrain geometry was edited.

Each sample is a 30-second stationary window with F3 visible. Times average the five complete five-second `cubic perf:` intervals inside it. Upload and mesh time were zero in the retained windows. These are sequential local samples, not repeated-trial confidence intervals or a promise for a moving camera. Exact movement or an animated culling frustum invalidates the cache; arrival fades also keep rebuilding.

| Configuration | Far draw, ms before → after | Far water, ms before → after | Frame, ms before → after | FPS before → after |
| --- | ---: | ---: | ---: | ---: |
| Stock Photon v1.3b, production flags off → default | 18.440 → 9.158 | 8.232 → 6.420 | 60.284 → 45.578 | 16.6 → 22.0 |
| No shaders, same loaded meshes | 14.364 → 6.146 | 4.046 → 3.210 | 35.868 → 27.284 | 27.9 → 36.6 |
| Stock BSL v10.1.8, existing far-color fallback | 18.528 → 9.216 | 7.920 → 6.102 | 41.574 → 32.818 | 24.0 → 30.4 |
| Stock Complementary Reimagined r5.4, Build 55 | 13.968 → 5.970 | 5.354 → 4.304 | 38.470 → 30.556 | 25.9 → 32.7 |

Photon's far draw fell 50.3% and water fell 22.0%. The shader-free run reduced them by 57.2% and 20.7%. Separately, exact original main (`73e27da`) measured 18.504 ms far draw, 54.074 ms frame and 18.5 FPS with Photon; the optimized production run reached 9.158 ms, 45.578 ms and 22.0 FPS. Main lacked the water timer, so its water time cannot be reported. The user-provided 21.5/9.2 ms profile is context, not a matched local baseline.

The Photon optimized run included two frames over 100 ms and the Complementary optimized run included one; these results do not establish a tail-latency improvement. The initial cross-run no-shader samples had either uploads or a concurrent build and are excluded from performance claims.

The production measurements used the Build 54 base. During testing, main advanced to Build 55 (`7d46c86`), with helper extraction and shared thread factories. The three commits rebased cleanly; the complete build/test task was rerun after the rebase. See the additional shader results below for the Build 55 runtime check.

## Pixel and runtime checks

The raw same-world [no-shader before](vanilla-before.png) and [after](vanilla-after.png) captures are identical: **0 of 1,639,680 pixels changed**. Both unchanged-path repeat captures also match exactly. Separate launches had a small difference at overlapping geometry, so the same-world test was added to keep the actual mesh allocations fixed.

The raw [Photon before](photon-before.png) and [after](photon-after.png) captures retain the same terrain, water and shading. Their mean absolute RGB-channel difference is 1.870/255; unchanged-path repeats are 1.710 and 1.726/255. Photon has temporal antialiasing and animated clouds/water even with world time frozen, so these captures do **not** prove pixel identity under a shader pack.

A local test driver checked running bytes against the sum of all 58,909 columns, cached selected/visible key order and fade values against a fresh pick, stable distance order, face groups, and water lists/neighbor coverage. All five initial checks passed: Photon, shaders off, a 0.25-block move, a 180-degree turn, and return to the fixed view. Further checks passed after shader reloads.

For the same-process A/B captures, a test-only copy of the built JAR cleared `final` from the three boolean flag fields so the local driver could toggle them and invalidate the cache. No method bytecode or other archive entry was changed. This diagnostic copy is not the shipped artifact; the production flags remain final and require restarting the client. The production Photon comparison used normal, unmodified builds and JVM arguments.

## Other shader packs

Complementary Reimagined r5.4 ran with an active Iris pipeline on the rebased Build 55 artifact. Both paths kept the same terrain and water in the raw [before](complementary-before.png) and [after](complementary-after.png) captures. The mean channel difference is 0.171/255; unchanged-path repeats are 0.096 and 0.175/255. The final runtime audit passed with 58,909 columns, 17,005 visible columns and 1,375 water candidates. Far draw fell 57.3%, water 19.6%, and average FPS rose from 25.9 to 32.7.

BSL v10.1.8 loaded its Iris pipeline and completed both paths. The mod's existing attempt to compile BSL's Voxy fragment code failed (`vxModelView` and other uniforms undeclared), so far terrain used the existing plain-color fallback in both paths. This is a fallback-compatibility check, not proof of full BSL far shading. The [before](bsl-before.png) and [after](bsl-after.png) mean channel difference is 0.347/255; unchanged-path repeats are 0.391 and 0.054/255. Cached selection and water coverage checks passed.

Complementary Reimagined r5.9.3 could not create an Iris pipeline: `Render target with index 19 is not supported, only 16 render targets are supported.` It failed with the optimization flags both off and on. Its resulting shader-free screenshots and timings are excluded. This is a compatibility limit with the required Iris version. No shader source was changed to work around it.

The downloaded BSL and Complementary packs were checked against Modrinth's published SHA-512 hashes; Photon matched the required build input. Their SHA-256 fingerprints are:

| Pack | SHA-256 |
| --- | --- |
| Photon v1.3b | `120897768eaa8cb1f14a3e98f88dff009ae3204ee3b96904a4fe8586edfc1ead` |
| BSL v10.1.8 | `36b0a50ff7918bf10e9422c401d93779f9d0088a26acea975b435243f96930b5` |
| Complementary Reimagined r5.9.3 | `fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38` |
| Complementary Reimagined r5.4 | `13af6c439ef8651916b18d2d658f134b07ce3c2e835d23d37d5d24c5d43284de` |

## Build verification

After rebasing onto Build 55, `cd minecraft && ./gradlew jar test` passed: 73 tests, zero failures/errors, three existing opt-in/file-dependent skips. The production JAR SHA-256 is `0f9f1a12caa8bb32466d039aec1117a30de9f550d01d1559d24533edff46f304`.

[Retained timing samples](measurements.json) include the five individual intervals for each comparison. Frame times include the rest of the game; far draw and far water are CPU wall-clock sections, not GPU timer-query results.
