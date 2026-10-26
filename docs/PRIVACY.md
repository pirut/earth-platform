# Privacy

Earth Platform downloads map data (elevation, climate, land cover, rivers and, if you turn it on, satellite imagery) from the hosts listed in [CREDITS.md](../CREDITS.md). Those requests ask for map tiles by area, as any map viewer does; they carry no account or player data.

Nothing else leaves your computer. The mod sends no reports, analytics or crash data anywhere.

## Performance log and export

The game keeps a small local performance log in `earthplatform/perf/` (see [PERFORMANCE-LOG.md](PERFORMANCE-LOG.md)). `/earthperf export` packs it into one zip under `earthplatform/perf/exports/`. It only saves the file; you decide whether to attach it to a bug report.

| In the log | Never in the log |
| --- | --- |
| Mod version and commit | Player name, UUID or skin |
| Java version, OS name and version, CPU thread count, maximum memory | Coordinates, place names, world names or seeds |
| GPU vendor, model and driver version, OpenGL version | Server addresses, chat or commands |
| Window size, render and simulation distance, frame limit, vsync | File paths or folder names |
| Whether a shader pack is on (not which one), far detail and sharpness | Other mods or resource packs |
| Frame times, memory use, far terrain and cube counts, timings of each stage in milliseconds | Error messages (only the kind of error, such as `java.lang.OutOfMemoryError`) |

Start the game with `-Dearthplatform.perfLog=false` to turn the log off.
