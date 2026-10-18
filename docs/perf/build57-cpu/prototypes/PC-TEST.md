# Combined experimental PC test build

Minecraft 1.21.1 / NeoForge 21.1. This download contains **one combined JAR**. No JVM switches or A/B setup are needed. Keep your normal graphics, distance and shader settings.

1. Close Minecraft. Move the previous Earth Platform JAR out of this instance's `mods` folder as a backup. Put the experimental JAR in `mods`; keep only one Earth Platform JAR installed.
2. Play normally for a few minutes: load terrain, move and turn, and use your usual shader setup. A disposable test world is a convenient first check.
3. Send back the files named `latest*.jsonl` from **the instance's game folder → `earthplatform/perf/`**. The current file is `latest.jsonl`. These logs identify the exact build, GPU/driver capabilities, settings, frame times/tails and terrain workload. They are local only; no upload happens automatically.

The combined build enables the existing validated CPU improvements and the bounded automatic run mesher where its input passes exactness checks. Unsupported biome/layout/coverage cases use the original dense mesher automatically. Near simulation, quality and distance are unchanged. The regressing command cache and shadow hint experiment remain disabled. Direct server run generation/transport is still a developer prototype and is not claimed as an active network optimization.

Logs are capped at three files of 4 MiB each and use an asynchronous bounded writer. Frame quantiles are 1 ms upper bounds; GPU timings are sampled occasionally to limit overhead. No chat, player/world names, coordinates, server addresses, credentials, file paths, exception messages or stacks are included. If a GPU timer is unavailable or busy, CPU/frame and workload logging continues.

To roll back: close Minecraft, remove the experimental JAR, and put the backed-up JAR back into `mods`. No world conversion or settings reset is required. NVIDIA/AMD hardware validation is your forthcoming test; only Apple GPU hardware has been measured so far. This remains an experimental build from draft PR #93, not a merged release.
