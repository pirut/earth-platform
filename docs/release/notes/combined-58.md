Earth Platform build 58: far-terrain CPU improvements and local performance diagnostics.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack. Give the game about 6 GB of memory with Photon.

New since build 57:
- Eligible coarse far columns use exact vertical runs while meshing, reducing repeated CPU work. Unsupported inputs use the original mesher automatically. Near simulation, render distance, textures, geometry and fades keep the same behavior.
- Repeated terrain selection, allocation, coverage and river lookups do less CPU work. Component benchmarks improved; a general FPS or loading-time gain is not established.
- Local performance logs start automatically in the game folder at `earthplatform/perf/latest*.jsonl`. They record the exact build, GPU/driver capabilities, graphics distances, frame-time tails and terrain workload. Logs stay local, are capped at three files of 4 MiB, and keep build/GPU information through rotation. They exclude player/world names, coordinates, server addresses, credentials, file paths and exception messages. Send these files manually when reporting performance problems.
- The draw-command cache and shadow-hint experiment remain disabled after Mac timing regressions. Direct server surface-packet generation and transport also remain inactive; no network or memory saving is claimed.

Validation: the actual Photon ZIP and 155 local tests passed with zero failures and four optional benchmark skips. Bounded native Mac audits matched declared mesh geometry, attributes and ordering. Independent Astra/high review findings were fixed and cleared. NVIDIA/AMD native hardware and a real VR headset remain untested.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
