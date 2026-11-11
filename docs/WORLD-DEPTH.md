# Underground depth

New Earth worlds default to a bedrock floor 12,000 blocks below sea level, at
Y -11,937 (sea level is Y 63). Bedrock is one to five layers thick, with a solid
bottom layer. This leaves about 1,065 blocks beneath Challenger Deep's approximate
Y -10,872 seabed. The limit is global, rather than 12,000 blocks below each hill.

Set `earthDepth = true` in `config/earthplatform-common.toml` before creating a
world to use the original extended floor at Y -16,384. The equivalent JVM flag is
`-Dearthplatform.earth.depth=true`. "Earth depth" names the extended option; it
does not model the real Earth's crust, mantle or core.

Each new world pins `earth.depth=standard` or `earth.depth=earth_depth` in its
`generator.properties`. Worlds without this pin keep the original extended floor,
regardless of the global setting. Changing the config affects new worlds only;
existing saves and player builds are not rewritten.

Below the standard floor, newly generated cubes are empty and bypass terrain,
cave, ore and structure generation. The engine's addressable Y range stays intact
for compatibility. Bedrock is sealed after structure placement too. Normal cave
noise, ores and surface terrain above the floor retain their previous rules.
This reduces the mineable/generated depth; it is not an expectation of a large
surface FPS improvement, because buried terrain already generates on demand.

## Existing caves

The underground is procedural, not mapped from real-world caves. It combines
tunnels, larger caverns and narrow winding noodle passages. Lush, dripstone and
deep-dark cave biomes occupy broad 3D regions, with cave plants and decorations.
Vanilla structures such as mineshafts and ancient cities use the cubic structure
adapter; dungeons and amethyst geodes have their own cube placement.

Rock, ore and lava use a smoothed local floor based on nearby low ground, rather
than absolute world Y. Deepslate blends in roughly 56–64 blocks below this floor;
caves below about 118 blocks fill with lava. Ore odds below the normal vanilla
range are clamped to the bottom-range odds, so mining much deeper does not unlock
new realistic geological layers. Mountains retain rock above their nearby valleys.

Caves normally keep six blocks of roof under dry land and fifteen under shores
or water. Some steep rocky slopes permit cave entrances. These are the mod's own
noise rules inspired by vanilla, not the exact vanilla Overworld density/aquifer
generator. The new depth setting does not redesign cave shapes or lava behavior.
