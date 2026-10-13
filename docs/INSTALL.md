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

Apple's OpenGL lets a shader use at most 16 textures and has no compute shaders, so Photon's defaults exceed what a Mac can run. Before choosing Photon:

1. Options → Video Settings → Shader Packs → select Photon → **Shader Pack Settings**.
2. Open **Lighting**. Turn **Cloud Shadows** off, then open **Shadows** and turn **Shadow Color** off.
3. Apply, then choose a profile no higher than Medium if the frame rate is low.

Photon's sky light uses a compute pass a Mac can't run, so the sky light on shaded cliffs is flatter than on Windows or Linux. We have an adjusted Photon for Macs that fixes this, but Photon's licence does not let us share a changed copy on Modrinth or CurseForge without its author's permission; we will link it here if that is granted.

## Satellite ground colours (optional)

Earth Platform can use Sentinel-2 cloudless satellite imagery by EOX to match bare ground and plant cover more closely to each place. It is off by default. To turn it on for a new world, press **Customize** in Create World and set **Satellite imagery** to ON. To make it the default for every new world (and on servers), set `satelliteImagery = true` in `config/earthplatform-common.toml`, or use Mods → Earth Platform → Config. Worlds made with imagery keep using it, and worlds made without it never use it.

The JVM argument `-Dearthplatform.earth.imagery=true` (or `=false`) also works and overrides the setting. Imagery adds downloads from `tiles.maps.eox.at`.

## Distant Horizons

Leave Distant Horizons out. Earth Platform draws its own far terrain, and Distant Horizons draws over it and changes the fog.

## Dedicated servers

1. Run the NeoForge 21.1.256 installer with **Install server**.
2. Put the Earth Platform jar in the server's `mods` folder.
3. Before the world is first created, set `level-type=earthplatform\:earth` in `server.properties`.
4. Optionally set `defaultHome` in `config/earthplatform-common.toml` to a place id from `/earth places` or to `latitude,longitude`.

The server downloads Earth data, so it needs internet access. Players need the same Earth Platform version; they do not need Iris, Sodium or a shader pack.

## Updating

Replace the jar with the new one. Existing Earth worlds keep the data sources and settings they were made with, so the ground you have already explored does not change under your builds.
