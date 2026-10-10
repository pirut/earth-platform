# Synthetic baseline, 2026-10-09

Command: `./core/test.sh --benchmark`

Environment reported by the JVM: Linux amd64, Temurin Java 21.0.12.1, 9 JVM-visible processors. Shared cloud execution and temporary filesystem. One uncalibrated run at 20:19:49 UTC; not JMH, no repeated-run confidence interval, CPU pinning or physical-storage characterization.

| Operation | Work | Elapsed | Approximate throughput |
| --- | ---: | ---: | ---: |
| Signed block→cube/local round trips | 2,000,000 | 0.010822 s | 184,804,505/s |
| Two-player ticket updates | 4,000 | 0.200283 s | 19,972/s |
| Synthetic async uniform-cube requests | 20,000 | 0.220978 s | 90,507/s |
| Durable overlay saves, verified previous backup | 200 | 0.137583 s | 1,454/s |

Two players occupied independently streamed 5×3×5 cube boxes at cube Y=553 and −688. Peak union residency was exactly the configured 150-cube budget during 2,000 moves each. The async loader finished with 20,000 completed, zero failed, zero running and zero queued requests. Synthetic providers performed no disk/network/real-Earth work.

The final save contained 200 explicit edits and occupied 1,289 bytes. Each homogeneous cube stores a 4-byte block payload versus 16,384 bytes for the dense integer-array payload, excluding all JVM object/array headers, maps and references. These are representation sizes, not a measured 4,096× reduction in whole-process heap.

The test phase in this run passed 19 groups / 351,696 assertions in 0.741 seconds. Later regression additions may change test totals; the runner is authoritative.

Not measured: Minecraft server ticks, FPS, rendering, client networking, entities, liquids, lighting/skylight, map/bathymetry data fetches, full material palettes, actual source-resolution accuracy, long-session heap/cache growth, multiple physical players, macOS/Windows durability, or recovery from actual power loss. Do not use these microbenchmarks to promise game capacity or compatibility.

## Later verification run, 20:30:36 UTC

Same command and benchmark sizes after additional lifecycle regression coverage:

- 25 test groups / 351,725 assertions passed in 0.844 seconds
- Coordinate round trips: 0.017737 s, approximately 112,756,091/s
- Ticket updates: 0.215829 s, approximately 18,533/s; peak residency still 150/150
- Synthetic async requests: 0.574559 s, approximately 34,809/s; all 20,000 completed, no residual work
- Durable overlay saves: 0.189999 s, approximately 1,053/s

The substantial throughput variation on the shared machine is itself important. Neither development run is a calibrated capacity result. Raw latest output is generated at `core/build/verification.txt` and can be reproduced with the command above.
