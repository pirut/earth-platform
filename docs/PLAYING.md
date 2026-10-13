# Playing on the Earth

## Make a world

1. Install the mod (NeoForge 21.1 for Minecraft 1.21.1).
2. Singleplayer → Create New World. The **World Type** is already **Earth** (World tab).
3. Press **Customize** to choose where you start: a famous place from the list, **Surprise me**, any latitude and longitude, or **Choose on a globe...**: spin and zoom a globe of the real Earth (drag, scroll, arrow keys) and click where you want to begin. Close up it loads the real terrain and land cover; offline it shows the built-in NASA Blue Marble map. Without a choice you start in Yosemite Valley.
4. Create the world. The first visit to a place downloads its terrain, land cover and climate; a "Preparing your Earth" screen shows while that happens. It is kept on disk, so later visits are instant and work offline.

## Travel

| Command | What it does |
| --- | --- |
| `/earth visit <place>` | Go to a famous place, e.g. `/earth visit everest` (tab completes) |
| `/earth goto <latitude> <longitude>` | Go anywhere, e.g. `/earth goto 46.5935 7.9090` |
| `/earth places` | List famous places; click one to go there |
| `/earth where` | Your latitude, longitude and real height |
| `/earth home` | Back to the world's starting point |

In singleplayer you can always travel. On servers only operators can, unless `anyoneCanTravel` is on.

## Settings

Mods → Earth Platform → Config, or the files in `config/`:

- `earthplatform-client.toml`: far terrain detail and textured levels (restart to apply), the download note, and whether Create World starts on Earth.
- `earthplatform-common.toml`: the default starting place for new worlds, satellite imagery for new worlds (EOX Sentinel-2, off by default; also a toggle on the Customize screen), offline mode, the cache size limit (GB, oldest files removed at start) and who may travel.

Command-line `-Dearthplatform.far.detail`, `-Dearthplatform.far.textured`, `-Dearthplatform.earth.offline` and `-Dearthplatform.earth.imagery` still win over the files.

## Servers

Set `level-type=earthplatform\:earth` in `server.properties` before the world is first created, and `defaultHome` in `config/earthplatform-common.toml` (a place id from `/earth places`, or `latitude,longitude`). Players join straight onto the Earth.

## Offline

Places you have visited work without a connection. When the data servers can't be reached, a note at the top of the screen says so; new ground fills in once the connection is back. A new world whose starting place was never downloaded waits on the "Preparing your Earth" screen and keeps retrying.

## How Earth worlds work

An Earth world's overworld is an empty stand-in that nobody stays in. New players, respawns without a bed and anyone who would land in it are sent to the Earth (the `earthplatform:cubic` dimension). Older saves made with the Default world type keep `/cubes enter`.
