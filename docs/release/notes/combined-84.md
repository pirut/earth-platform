Earth Platform build 84 stops the server from hanging on shutdown when a world file is unreadable.

Requires Minecraft 1.21.1 with NeoForge 21.1.256 or newer, or Fabric Loader 0.16.14 or newer with Fabric API. Optional mods may require newer loader versions.

### Fixes

- The server no longer hangs forever on shutdown when a world file is unreadable.
- It gives up on a stuck region after 30 seconds without progress and logs which one.
- Healthy saves are unchanged.
- Your edits survive an interrupted shutdown.
