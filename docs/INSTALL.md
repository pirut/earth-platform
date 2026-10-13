# Installing Earth Platform

## What you need

- Minecraft: Java Edition **1.21.1**
- **NeoForge 21.1.256** (later 21.1.x builds should work but are untested)
- At least **4 GB** of memory for the game, 6 GB or more if you raise the far terrain detail
- An internet connection the first time you visit each place
- A few GB of free disk space for downloaded Earth data (the limit is a setting)

## Install

1. Download the [NeoForge 21.1.256 installer](https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.256/neoforge-21.1.256-installer.jar), run it and choose **Install client**.
2. Open the Minecraft Launcher. Under **Installations**, edit the new NeoForge profile, open **More options** and change `-Xmx2G` in **JVM arguments** to `-Xmx4G` (or more).
3. Start the profile once so it creates its folders, then quit.
4. Put the Earth Platform jar into the `mods` folder of your game directory:
   - Windows: `%APPDATA%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
5. Start the game. Singleplayer → Create New World: the world type is already **Earth**. Press **Customize** to pick a starting place.

Other launchers (Prism, Modrinth App, CurseForge App, MultiMC): make a NeoForge 21.1.256 instance for 1.21.1, add the jar, and set the memory to 4 GB or more.

## Shaders (optional)

Earth Platform is tested with this exact set. Iris and Sodium must be **these versions**: the game refuses to start if a different version of either is installed alongside Earth Platform.

| Project | Version | Goes in |
| --- | --- | --- |
| [Sodium](https://modrinth.com/mod/sodium/version/mc1.21.1-0.8.12-beta.1-neoforge) | 0.8.12-beta.1 for NeoForge 1.21.1 | `mods` |
| [Iris](https://modrinth.com/mod/iris/version/1.8.14-beta.1%2B1.21.1-neoforge) | 1.8.14-beta.1 for NeoForge 1.21.1 | `mods` |
| [Photon](https://modrinth.com/shader/photon-shader/version/v1.3b) | v1.3b | `shaderpacks` (leave it zipped) |

Choose Photon under Options → Video Settings → Shader Packs. Press **K** to turn shaders off and on.

Packs that ship Voxy support (Photon does) light the far terrain the same way as nearby blocks. Other packs light near blocks normally, but far terrain may look flat or too bright.

### On a Mac

Use the same official Photon zip at its default settings; there is nothing to change. Apple's OpenGL has no compute shaders and fewer texture slots than Photon's defaults need, so on a Mac Earth Platform makes an adapted copy of the pack for your own game in `earthplatform/photon-compat/` inside the game folder, and Iris loads that. Your Photon zip is never changed, and other systems run Photon exactly as it is.

On a Mac, three Photon features are unavailable: **Colored Lights** (hidden from the menu), **Colored Shadows** and **Cloud Shadows** (shown, but stay off). To load Photon without the adapted copy, add `-Dearthplatform.photon.compat=off` to the JVM arguments.

## Satellite ground colours (optional)

Earth Platform can use Sentinel-2 cloudless satellite imagery by EOX to match bare ground and plant cover more closely to each place. It is off by default. To turn it on for a new world, press **Customize** in Create World and set **Satellite imagery** to ON. To make it the default for every new world (and on servers), set `satelliteImagery = true` in `config/earthplatform-common.toml`, or use Mods → Earth Platform → Config. Worlds made with imagery keep using it, and worlds made without it never use it.

The JVM argument `-Dearthplatform.earth.imagery=true` (or `=false`) also works and overrides the setting. Imagery adds downloads from `tiles.maps.eox.at`.

## Distant Horizons

You don't need Distant Horizons: Earth Platform draws its own far terrain. If it's installed anyway, it pauses itself while you're in an Earth world, with a one-time note in chat, and works as usual in other worlds.

For other mods, see [Which other mods work?](FAQ.md#which-other-mods-work) in the FAQ.

## Dedicated servers

1. Run the NeoForge 21.1.256 installer with **Install server**.
2. Put the Earth Platform jar in the server's `mods` folder.
3. Before the world is first created, set `level-type=earthplatform\:earth` in `server.properties`.
4. Optionally set `defaultHome` in `config/earthplatform-common.toml` to a place id from `/earth places` or to `latitude,longitude`.

The server downloads Earth data, so it needs internet access. Players need the same Earth Platform version; they do not need Iris, Sodium or a shader pack.

## Updating

Replace the jar with the new one. Existing Earth worlds keep the data sources and settings they were made with, so the ground you have already explored does not change under your builds.
