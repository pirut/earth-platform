# Datapacks and quest mods

Earth Platform adds one thing for datapacks and quest mods to build on: an advancement criterion that says a player is at a real place on the Earth. Quest books that can wait on an advancement, such as Heracles, can use it too.

## `earthplatform:reached_place`

A player meets it when they are standing at a real latitude and longitude in an Earth world (`earthplatform:cubic`). The place is named in degrees, so the same advancement is right in every Earth world. A landmark is at different blocks in a world that started in Yosemite and in one that started on the equator, because a world is scaled to be true to size at the latitude it started at; a criterion in block coordinates would only fit one of them.

| Condition | Required | What it is |
| --- | --- | --- |
| `latitude` | yes | Degrees, -90 to 90. |
| `longitude` | yes | Degrees, -180 to 180. The world goes round: 180 and -180 are the same line. |
| `radius_m` | no | How close counts, in real metres along the ground, more than 0 and at most 20,100,000. Default 100. |
| `elevation_m` | no | `min` and/or `max`, in real metres above sea level. Any height by default. |
| `player` | no | The usual [player predicate](https://minecraft.wiki/w/Advancement_definition#Player_predicate). |

Distances are the great circle's (haversine), on a sphere of mean radius 6,371,008.8 m, between the place and where the player stands, so they are the same in every world however stretched its map is at that latitude (`/earth where` says how much). A block of height is a metre everywhere, so a player at block Y has an elevation of `Y - 62` metres: sea level is Y 62.

### Example

An advancement for Half Dome, at the top of the dome or anywhere within 150 m of it on the ground:

```json
{
  "parent": "earthplatform:earth/root",
  "criteria": {
    "half_dome": {
      "trigger": "earthplatform:reached_place",
      "conditions": {
        "latitude": 37.7459,
        "longitude": -119.5332,
        "radius_m": 150,
        "elevation_m": { "min": 2500 }
      }
    }
  },
  "display": {
    "icon": { "id": "minecraft:spyglass" },
    "title": "Half Dome",
    "description": "Stand on top of Half Dome"
  },
  "requirements": [["half_dome"]]
}
```

Save it as `data/<namespace>/advancement/<name>.json` in a datapack. Only the latitude and longitude are needed:

```json
"conditions": { "latitude": 48.8584, "longitude": 2.2945 }
```

A bad value (a latitude of 91, a negative radius) makes the game refuse to load that advancement, and the log says why.

### When it is checked

The server looks at every player on the Earth once a second (every 20 ticks), and a player with no `reached_place` criterion left to meet costs nothing. So a player has to be in the radius at one of those looks: a fast flier can cross a small radius between two of them. Use a larger `radius_m` for places people pass at speed.

It is never met:

- in any other kind of world, or in the Nether or End;
- by a spectator;
- while playing Where on Earth, where a toast would give the answer away.

Teleporting counts: a player who arrives at the place by `/earth goto` or a trip is at the place.

### Mod loaders

The criterion is registered on both NeoForge and Fabric under the same name, and it needs no client install: advancements are decided on the server.
