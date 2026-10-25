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
| `/earth where` | Your latitude, longitude, real height and country |
| `/earth random` | A random trip to somewhere on land; `/earth random <km>` stays within that distance |
| `/earth sethome` | Save where you stand as your home |
| `/earth home` | Back to your home, or to your spawn if you haven't set one |
| `/earth spawn` | Back to your spawn: your own or your team's if an operator gave you one, else the server spawn |
| `/earth warps`, `/earth warp <name>` | List the server's warps (click one to go), or go to one |
| `/earth tpa <player>`, `/earth tpahere <player>` | Ask to go to another player, or ask them to come to you; they click **Accept** or **Deny** (or `/earth tpaccept`, `/earth tpdeny`). Requests last two minutes |
| `/earth race` | During a race, how far the finish is and which way |
| `/earth guess` | During a Where on Earth round, opens the globe to mark where you think you are |

Crossing into another country shows its name on screen, as "Now entering France".

### The travel map

In creative mode, press **N** (or run `/earth map`) to open a globe centered on you. Click where you want to go and press **Travel here**: the camera climbs out to the globe, swings across the planet and comes back down at the new place while its terrain loads. The key can be changed under Controls → Earth Platform.

### The map and compasses

Players who can't use the travel map, such as survival players on a server, get a map instead: **N** (or `/earth map`) opens the same globe, centered on you, but it never moves anyone. It shows where you are, the other players and where your compass points. Click a spot, a famous place or a player and press **Point compass here** to point a compass you carry there.

On the Earth a plain compass points to your spawn (your own, your team's or the server's) and a recovery compass to where you died. A compass in your hand says on the action bar what it points to and how far away that is. A pointed compass works like a lodestone compass with no lodestone, and one pointed at a player follows them while they're on the Earth. During a Where on Earth round every compass spins and shows no distance.

| Command | What it does |
| --- | --- |
| `/earth compass` | Where the compass in your hand points, how far and which way |
| `/earth compass to <latitude> <longitude>` | Points a compass at a spot: the one in your hand, or else one you carry (one off a stack) |
| `/earth compass place <place>` | Points it at a famous place (tab completes) |
| `/earth compass here` | Points it at where you stand, to find your way back |
| `/earth compass player <player>` | Follows a player; they're told |
| `/earth compass home` | Points it at your home |
| `/earth compass spawn` | Makes the compass in your hand a plain one again |

### The jet stream

For survival travel, climb high and glide. With an elytra, the wind above about Y 9,200 carries you along your heading at over 1,000 blocks a second, a little faster eastward and a little slower westward, so a continent takes under an hour. Fireworks pointed steeply up climb fast (about 30 take you from the ground into the wind); in the wind they add nothing, and your elytra doesn't wear. Tip your nose down to leave the wind; you slow over a few kilometers before landing. The terrain below stays drawn as far terrain the whole way.

### Commands

In singleplayer you can always travel. On servers only operators can, unless `anyoneCanTravel` is on. Operators and the server console can send someone else: `/earth goto <latitude> <longitude> <player>`.

## Settings

Mods → Earth Platform → Config, or the files in `config/`.

| Setting | File | Default | What it does |
| --- | --- | --- | --- |
| `detail` | client | 11 (4 to 48) | How far fine far terrain reaches. Lower helps weaker GPUs; higher needs more memory, and past 24 is the extreme range for very powerful computers. Restart to apply |
| `sharpness` | client | Balanced | How close coarser far levels take over: Fast, Balanced, Sharp, Sharpest, Extreme or Ultra. Changes at once (also on the pause menu) |
| `texturedFarLevels` | client | 8 (0 to 8) | How many far levels use block textures; further ones use flat colors. Restart to apply |
| `showDownloadStatus` | client | on | The small note while Earth data downloads or can't be reached |
| `preselectEarth` | client | on | Create World starts with the Earth world type |
| `cloudHeight` | client | 2500 | Cloud height in meters; 0 gives vanilla clouds |
| `defaultHome` | common | Yosemite Valley | Where new worlds start when nobody picks: a place id from `/earth places`, or `latitude,longitude` |
| `satelliteImagery` | common | off | Ground colors from EOX satellite imagery for new worlds (also a toggle on the Customize screen) |
| `offline` | common | off | Never download; only places already cached generate. Restart to apply |
| `cacheLimitGb` | common | 8 | Size limit for downloaded data in GB (0 for no limit); the least recently used files go first, when the game starts and as the cache grows |
| `anyoneCanTravel` | common | off | Let every player use `/earth goto` on servers |

Command-line `-Dearthplatform.far.detail`, `-Dearthplatform.far.textured`, `-Dearthplatform.earth.offline` and `-Dearthplatform.earth.imagery` still win over the files. The other developer flags are listed in [FLAGS.md](FLAGS.md).

## The Nether, the End and other dimensions

Nether portals work as usual: a portal built on the Earth leads to the Nether, and coming back puts you at the portal you built. Strongholds generate underground (eyes of ender find them), and leaving the End brings you home to the Earth. Dying in the Nether or the End respawns you at your bed or the Earth's start. The Twilight Forest portal works too, and its return trip brings you back to where you left.

## Other mods

See [Which other mods work?](FAQ.md#which-other-mods-work) and [Known limitations](FAQ.md#known-limitations) in the FAQ.

## Servers

Set `level-type=earthplatform\:earth` in `server.properties` before the world is first created, and `defaultHome` in `config/earthplatform-common.toml` (a place id from `/earth places`, or `latitude,longitude`). Players join straight onto the Earth.

### Server commands

These are for operators, and all of them work from the server console too. Wherever a command takes a spot, you can write it three ways: `to <latitude> <longitude>`, `place <famous place>` (tab completes), or `here` for where you stand.

| Command | What it does |
| --- | --- |
| `/earth spawn pick` | Opens the globe; click a spot and press **Set spawn here** to make it the server spawn |
| `/earth spawn set to <lat> <lon>`, `set place <place>`, `set here` | Moves the server spawn: where new players arrive and where players without a bed respawn |
| `/earth spawn reset` | The world's starting point is the server spawn again |
| `/earth spawn info` | Shows where the server spawn is |
| `/earth spawn player <players> to <lat> <lon>` (or `place`, `here`) | Gives players their own spawn, used when they have no bed |
| `/earth spawn player <players> reset` | Back to the server spawn; also lets them choose their start again |
| `/earth send <players> to <lat> <lon>` (or `place`, `here`) | Sends players somewhere, e.g. `/earth send @a place everest` |
| `/earth send <players> random [km]` | Sends each player on their own random trip |
| `/earth send <players> spawn` / `home` | Sends players to the server spawn or to their homes |
| `/earth setwarp <name> here` (or `to`, `place`), `/earth delwarp <name>` | Adds a warp or removes one |
| `/earth team <team> spawn to <lat> <lon>` (or `place`, `here`, `reset`) | Gives a vanilla `/team` its own start |
| `/earth teams spread [km]` | Gives every team a random start on land, at least that far apart (2000 km unless given) |
| `/earth border` | Shows the server's border |
| `/earth border box <south> <west> <north> <east>` | Keeps players inside a latitude and longitude box |
| `/earth border around <km>` | A box that many kilometers each way around the server spawn |
| `/earth border country <name>` | Keeps players inside one country, e.g. `/earth border country Switzerland` |
| `/earth border reset` | No border |
| `/earth race start [place]` | A race to a famous place (a random one unless named). The first player to get within 250 meters wins the prize; a travel command takes a player out. `/earth race stop` calls it off |
| `/earth guess start [players]` | A Where on Earth round, a guessing game: everyone is dropped at the same random spot and marks on the globe where they think they are. Points by distance, up to 5000. `/earth guess end` reveals early; `/earth guess scores` totals the rounds |
| `/earth preload <km>` | Downloads the Earth data within that many kilometers of the server spawn ahead of time, nearest first. `/earth preload stop` ends it |
| `/earth settings` | Lists every feature below with clickable **[ON]** / **[OFF]** buttons. `/earth settings <feature> on` or `off` switches one, `/earth settings travelCost 3` sets a number. Saved to the config file and in effect at once, no restart |
| `/earth admin` | Server status: TPS, memory, downloads, cache size, border, warps, web map and who is online (click a name to go there) |

**The border.** Trips past the border are refused, whoever sends them, and players who walk out are put back. Operators in creative can cross it. Random trips, team starts and Where on Earth spots stay inside it.

**The players' globe.** When an operator picks a spawn on the globe, other players show as green dots (`showPlayersOnMap`).

**The web map.** With `webMap` on, the server serves a live world map at `http://<your server>:8123/` with everyone online, the spawn and the warps, updating every few seconds. Open the port in your firewall to share it.

**Turning features on and off.** Every feature is optional. Operators switch them in game with `/earth settings` (or edit `config/earthplatform-common.toml`; a running server picks up the edit). A feature switched off disappears from players' commands at once; a race or Where on Earth round that is on ends. Operators keep their own tools (spawn, send, warps, border, preload) whatever the switches say.

| Setting | Default | What it does |
| --- | --- | --- |
| `homes` (`anyoneCanUseHomes` in the file) | on | Players can use `/earth sethome`, `/earth home` and `/earth spawn` |
| `warps` | on | Players can use `/earth warp` |
| `randomTrips` | on | Players can use `/earth random` (players who may travel always can) |
| `teleportRequests` | on | Players can use `/earth tpa` and `/earth tpahere` |
| `anyoneCanTravel` | off | Players can use `/earth visit`, `/earth goto` and the travel map |
| `chooseStart` (`playersChooseStart`) | off | New players get a **Choose on the map** button and pick, once, where they start (`/earth start`); they respawn there without a bed |
| `countryNames` | on | "Now entering ..." when a player crosses a border |
| `playersOnMap` (`showPlayersOnMap`) | on | Other players as dots on the globe and the web map; compasses can follow them |
| `survivalMap` | on | Players without the travel map open the map with **N** and point compasses with it (`/earth compass`) |
| `geoguessr` | on | Where on Earth guessing rounds |
| `races` | on | Races |
| `dailyRace` | off | A race to a random famous place each day (UTC) |
| `webMap` | off | The live web map |
| `travelCost` (`travelLevelsPer1000Km`) | 0 | In survival, trips players start themselves cost this many experience levels per 1000 km (at least one). Operators and creative players go free |
| `travelCooldown` (`travelCooldownSeconds`) | 0 | In survival, seconds a player waits between their own trips |
| `webMapPort` | 8123 | The web map's port |
| `raceReward` (file only) | `minecraft:diamond 5` | The race prize as `item count`; empty for none |

Console commands run in the overworld, but these `/earth` commands always act on the Earth, so you don't need `execute in`.

## Offline

Places you have visited work without a connection. When the data servers can't be reached, a note at the top of the screen says so; new ground fills in once the connection is back. A new world whose starting place was never downloaded waits on the "Preparing your Earth" screen and keeps retrying.

## How Earth worlds work

An Earth world's overworld is an empty stand-in that nobody stays in. New players, respawns without a bed and anyone who would land in it are sent to the Earth (the `earthplatform:cubic` dimension). Older saves made with the Default world type keep `/cubes enter`.

The Earth shares the overworld's clock and weather: sleeping through the night on the Earth skips it and clears the rain, as in vanilla. New players, and players respawning without a bed, land spread over the `spawnRadius` game rule (10 blocks when it is 0, as Earth worlds set it) around the start, so players joining a server don't stack on one block. With **Bonus Chest** on in Create World, the chest and its torches appear beside where you first arrive on the Earth. Clouds float a few kilometers up (`cloudHeight` in the client settings, 0 for vanilla clouds), and the sky stays bright in real places below sea level such as Death Valley and the Dead Sea.
