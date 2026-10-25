# Privacy

Earth Platform downloads map data (elevation, climate, land cover, rivers and, if you turn it on, satellite imagery) from the hosts listed in [CREDITS.md](../CREDITS.md). Those requests ask for map tiles by area, as any map viewer does; they carry no account or player data.

Everything else stays on your computer unless you turn on **Send performance reports**.

## Performance reports

Off by default. The setting is `sendPerformanceReports` in `config/earthplatform-client.toml`, also on the mod's config screen. Starting the game with `-Dearthplatform.perfReports=false` forces it off.

While it is on, every five minutes the game sends the lines it already writes to `earthplatform/perf/latest.jsonl` (see [PERFORMANCE-LOG.md](PERFORMANCE-LOG.md)). You can open that file to see exactly what goes out.

| Sent | Never sent |
| --- | --- |
| Mod version and commit | Player name, UUID or skin |
| Java version, OS name and version, CPU thread count, maximum memory | Coordinates, place names, world names or seeds |
| GPU vendor, model and driver version, OpenGL version | Server addresses, chat or commands |
| Window size, render and simulation distance, frame limit, vsync | File paths or folder names |
| Whether a shader pack is on (not which one), far detail and sharpness | Other mods or resource packs |
| Frame times, memory use, far terrain and cube counts, timings of each stage in milliseconds | Error messages (only the kind of error, such as `java.lang.OutOfMemoryError`) |
| A random id made fresh each time the game starts | Anything at all while the setting is off |

The receiver does not keep your IP address. F3 shows the id while reports are on; to have the reports from a session deleted, open an issue with that id.

Where reports go and how long they are kept will be listed here before any build sends them. The current build has no receiver set, so nothing is sent even with the setting on.
