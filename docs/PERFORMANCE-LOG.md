# Testing a build and the performance log

This is for testing a build from CI or a release on Minecraft 1.21.1 with NeoForge 21.1. Keep your usual graphics, distance and shader settings; nothing needs to be switched on.

1. Close Minecraft. Move the old Earth Platform jar out of the instance's `mods` folder and keep it as a backup. Put the new jar in `mods`. Keep only one Earth Platform jar there.
2. Play for a few minutes: load terrain, move and turn, with your usual shader setup. A throwaway world is fine.
3. Send the `latest*.jsonl` files from the instance's game folder, under `earthplatform/perf/`. The newest is `latest.jsonl`.

To go back, close Minecraft, remove the new jar and put the old one back. Worlds and settings don't need any change.

## What the log holds

The game writes a line every few seconds: the exact build, the GPU and driver, graphics and distance settings, frame times, and how much far terrain it was loading and drawing. It holds no chat, player or world names, coordinates, server addresses, file paths or error text, and it never leaves your computer by itself. It keeps at most three files of 4 MiB each.

- Start the game with `-Dearthplatform.perfLog=false` to turn the log off.
- Start it with `-Dearthplatform.far.gpuTimers=true` to add GPU timings of the far terrain passes. They are off by default because the timing queries wrap other mods' and shader packs' draws.

## Sending reports (opt-in)

The **Send performance reports** setting (`sendPerformanceReports` in `config/earthplatform-client.toml`) is off by default. While it is on, the game sends the lines above, batched every five minutes, with a random id made fresh each launch. F3 shows "Performance reports: on" and that id while it is on. Turning it off stops sending at once and throws away anything not yet sent. [PRIVACY.md](PRIVACY.md) lists exactly what is and isn't sent.

This build has no receiver set, so nothing is sent even with the setting on. To try it on one computer, run `python3 minecraft/scripts/perf-receiver.py` and start the game with `-Dearthplatform.perfReports.url=http://127.0.0.1:8787/report`.

See [FLAGS.md](FLAGS.md) for the other developer flags.
