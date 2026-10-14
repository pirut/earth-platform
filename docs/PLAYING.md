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

### The travel map

In creative mode, press **N** (or run `/earth map`) to open a globe centred on you. Click where you want to go and press **Travel here**: the camera climbs out to the globe, swings across the planet and comes back down at the new place while its terrain loads. The key can be changed under Controls → Earth Platform.

### The jet stream

For survival travel, climb high and glide. With an elytra, the wind above about Y 9,200 carries you along your heading at over 1,000 blocks a second, a little faster eastward and a little slower westward, so a continent takes under an hour. Fireworks pointed steeply up climb fast (about 30 take you from the ground into the wind); in the wind they add nothing, and your elytra doesn't wear. Tip your nose down to leave the wind; you slow over a few kilometres before landing. The terrain below stays drawn as far terrain the whole way.

### Commands

In singleplayer you can always travel. On servers only operators can, unless `anyoneCanTravel` is on. Operators and the server console can send someone else: `/earth goto <latitude> <longitude> <player>`.

## Settings

Mods → Earth Platform → Config, or the files in `config/`.

| Setting | File | Default | What it does |
| --- | --- | --- | --- |
| `detail` | client | 11 (4 to 24) | How far fine far terrain reaches. Lower helps weaker GPUs; higher needs more memory. Restart to apply |
| `texturedLevels` | client | 2 (0 to 6) | How many far levels use block textures; further ones use flat colours. Restart to apply |
| `showDownloadStatus` | client | on | The small note while Earth data downloads or can't be reached |
| `preselectEarth` | client | on | Create World starts with the Earth world type |
| `cloudHeight` | client | 2500 | Cloud height in metres; 0 gives vanilla clouds |
| `defaultHome` | common | Yosemite Valley | Where new worlds start when nobody picks: a place id from `/earth places`, or `latitude,longitude` |
| `satelliteImagery` | common | off | Ground colours from EOX satellite imagery for new worlds (also a toggle on the Customize screen) |
| `offline` | common | off | Never download; only places already cached generate. Restart to apply |
| `cacheLimitGb` | common | 8 | Size limit for downloaded data in GB (0 for no limit); the least recently used files go first, when the game starts and as the cache grows |
| `anyoneCanTravel` | common | off | Let every player use `/earth goto` on servers |

Command-line `-Dearthplatform.far.detail`, `-Dearthplatform.far.textured`, `-Dearthplatform.earth.offline` and `-Dearthplatform.earth.imagery` still win over the files.

## The Nether, the End and other dimensions

Nether portals work as usual: a portal built on the Earth leads to the Nether, and coming back puts you at the portal you built. Strongholds generate underground (eyes of ender find them), and leaving the End brings you home to the Earth. Dying in the Nether or the End respawns you at your bed or the Earth's start. The Twilight Forest portal works too, and its return trip brings you back to where you left.

## Other mods

See [Which other mods work?](FAQ.md#which-other-mods-work) and [Known limitations](FAQ.md#known-limitations) in the FAQ.

## Servers

Set `level-type=earthplatform\:earth` in `server.properties` before the world is first created, and `defaultHome` in `config/earthplatform-common.toml` (a place id from `/earth places`, or `latitude,longitude`). Players join straight onto the Earth.

## Offline

Places you have visited work without a connection. When the data servers can't be reached, a note at the top of the screen says so; new ground fills in once the connection is back. A new world whose starting place was never downloaded waits on the "Preparing your Earth" screen and keeps retrying.

## How Earth worlds work

An Earth world's overworld is an empty stand-in that nobody stays in. New players, respawns without a bed and anyone who would land in it are sent to the Earth (the `earthplatform:cubic` dimension). Older saves made with the Default world type keep `/cubes enter`.

The Earth shares the overworld's clock and weather: sleeping through the night on the Earth skips it and clears the rain, as in vanilla. New players, and players respawning without a bed, land spread over the `spawnRadius` game rule (10 blocks when it is 0, as Earth worlds set it) around the start, so players joining a server don't stack on one block. With **Bonus Chest** on in Create World, the chest and its torches appear beside where you first arrive on the Earth. Clouds float a few kilometres up (`cloudHeight` in the client settings, 0 for vanilla clouds), and the sky stays bright in real places below sea level such as Death Valley and the Dead Sea.
