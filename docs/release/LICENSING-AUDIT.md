# Licensing and attribution audit

Checked on 2026-10-13 against the cubic engine branch (PR #2) and the open feature branches (PRs #3 to #8). This is a practical checklist, not legal advice.

## Summary

| Item | Status | What's needed before a public release |
| --- | --- | --- |
| EOX Sentinel-2 cloudless (ground colours, PR #4) | **Blocker** | Written OK from EOX for use through their tile service in a free public mod, or ship with imagery off |
| Mac Photon pack | **Blocker for Modrinth/CurseForge** | Don't upload it there. Ask SixthSurge, upstream the fix, or host it on GitHub only |
| Public name ("Minecraft Earth") | **Blocker** | Release as Earth Platform (or another name without "Minecraft"); fix one game string in PR #8 |
| Mod licence (All Rights Reserved) | Decision | JR to choose: keep ARR or open the source |
| AWS Terrain Tiles | OK | Credited in game and in CREDITS.md |
| ESA WorldCover | OK | Credited, changes noted (CC BY 4.0) |
| CHELSA climate | OK (licence) / courtesy | CC0; ask CHELSA about public traffic or mirror the four bands |
| Bundled test data (Copernicus GLO-30, NOAA ETOPO, WorldCover) | OK | Credited; could be dropped from the release jar |
| Iris, Sodium, Photon, Distant Horizons | OK | Not bundled; compile-only. Exact version pins hurt players (see below) |
| Third-party code in the jar | OK | None. Only `io/earthplatform` classes and our own data |

## Data downloaded at runtime

### AWS Terrain Tiles (elevation)
- Code: `geography/.../globe/TerrariumTileSource`, `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/`.
- Terms: free public dataset on the Registry of Open Data on AWS. Mixed sources, each with an attribution requirement listed in Tilezen's [attribution.md](https://github.com/tilezen/joerd/blob/master/docs/attribution.md). No restriction on commercial use or on games.
- Done: full attribution in the mods list (`credits` in `neoforge.mods.toml`), CREDITS.md, THIRD_PARTY_NOTICES.md and `TerrariumTileSource.ATTRIBUTION`. The arrival message carries the short form.

### ESA WorldCover 2021 v200 (land cover)
- Code: `WorldCoverSource`, `https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/`.
- Terms: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Commercial use allowed. Requires the attribution string and a note that the data were changed.
- Done: attribution in game, CREDITS.md and notices, with the changes described (classes mapped to biomes and blocks, built-up land replaced).

### CHELSA V2.1 climatologies 1981-2010 (climate)
- Code: `ClimateSource`, HTTP range reads from `https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/...`.
- Terms: [CC0 1.0](https://www.chelsa-climate.org/datasets/chelsa_climatologies) per the CHELSA site and EnviDat. No attribution required; citation requested, and we give it.
- Courtesy, not licence: every player reads ranges from an academic object store. Before a big launch, either tell the CHELSA team (chelsa-climate.org) or mirror the five files we read (kg2, bio1, bio10, bio12 and the DEM) to our own storage, which CC0 allows.

### EOX Sentinel-2 cloudless 2016 (ground colours) — blocker
- Code (PR #4): `ImagerySource`, tiles from `https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/`, cached under `earthplatform/cache/s2cloudless-2016-v1`.
- What we know: EOX released the 2016 mosaic under CC BY 4.0 ("free to use for commercial and non-commercial purposes, as long as you provide proper attribution"); later years are CC BY-NC-SA 4.0. EOX's current licence pages describe one EOxCloudless licence for all years: non-commercial use unrestricted with attribution, commercial use only with a paid "EOX Commercial Attribution-RestrictedUse 1.2" licence, and no sub-licensing or redistribution. They no longer state the 2016 terms separately, and they publish no usage terms for the hosted tile service.
- Why it matters: a public mod sends every player's tile requests to EOX's servers and caches tiles on their disks. If Modrinth or CurseForge creator rewards are switched on, EOX could see the use as commercial.
- What to do (pick one):
  1. Email EOX (cloudless.eox.at, contact or sales) describing the mod, the 2016 layer, the request volume and the caching, and ask for written OK. Recommended.
  2. Until then, release with imagery off for new worlds (worlds pin `earth.imagery`, so this is a default, not a code removal). Ground then looks as it did before PR #4.
  3. Make our own ground-colour layer from Copernicus Sentinel-2 data (free and open) instead of EOX's mosaic. Larger job.
- Also: EOX now asks for the attribution "EOxCloudless - https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016)"; `ImagerySource.ATTRIBUTION` still says s2maps.eu, which now redirects. Owned by PR #4.
- Keep creator rewards off on Modrinth and CurseForge until this is settled.

## Bundled in the jar

- `earthplatform/copernicus_proof_data`, `alpine_data`: Copernicus WorldDEM-30 crops of the Everest area. The [GLO-30 licence](../../geography/data/copernicus-everest-proof/provenance/License-COPDEM-30.pdf) grants free reproduction, distribution and modification worldwide with the notice "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved". The licence PDF is in the jar. Credited.
- `earthplatform/proof_data`: NOAA ETOPO 2022 extracts, CC0. Credited.
- `earthplatform/worldcover_proof_data`: ESA WorldCover extracts, CC BY 4.0. Credited.
- These are test fixtures for the older engine; Earth worlds don't read them. Dropping them from the release jar saves about 2.5 MB and three notices. Engineering item, not a licence problem.
- No third-party libraries: Iris, Sodium and Distant Horizons are `compileOnly`; nothing is shaded or jar-in-jar.
- The logo (`earthplatform_logo.png`) is original pixel art.

## Shader pack

- Photon v1.3b, © 2021-2025 Benjamin Stott (SixthSurge), [Photon Shaders License Agreement](https://github.com/sixthsurge/photon/blob/main/LICENSE). The mod reads the player's own copy at runtime (its Voxy code) and ships none of it. OK.
- The adjusted Mac pack (`photon_v1.3b_mac.zip`) is a derivative work. The licence allows redistributing derivatives with the licence included (our zip includes it and a MAC_CHANGES.txt), **except** on platforms that pay creators, naming Modrinth and CurseForge, and never for money, without SixthSurge's written permission.
  - Don't upload it to Modrinth or CurseForge or bundle it in the mod.
  - Options: ask SixthSurge for permission; offer the Mac changes upstream as a pull request to Photon (best for players); or attach it to a GitHub release (not a paying platform), with the licence inside.
  - The install guide currently tells Mac players to turn Cloud Shadows and Shadow Color off in stock Photon, which needs no redistribution.

## Companion mods

- Sodium 0.8.12-beta.1 (PolyForm Shield 1.0.0), Iris 1.8.14-beta.1 (LGPL-3.0), Distant Horizons 3.3.3 (LGPL-3.0): players install them; we only compile against them and mix into Iris at runtime. OK.
- Player impact, not licensing: `neoforge.mods.toml` declares Iris and Sodium as optional but with exact beta versions, so the game refuses to start with any other version installed. Most players with shaders will have newer builds. Widening the ranges needs the shader thread to confirm which Iris internals the mixins rely on.
- Distant Horizons is declared optional with exact version 3.3.3 for the old engine's compatibility backend; the install guide tells players to leave it out. Consider declaring it `discouraged` once the old backend is retired (a test pins the current block).

## Name and trademarks

- "Minecraft Earth" was Mojang's own augmented-reality game (2019-2021), and Mojang's usage guidelines don't allow "Minecraft" in a mod's name. The mod's metadata name is **Earth Platform**, which is fine. Use that or another name without "Minecraft" on Modrinth, CurseForge and GitHub.
- PR #8's string `earthplatform.arrival.unreachable.hint` says "Minecraft Earth needs it the first time you visit a place"; change it to "Earth Platform needs it" or "The Earth needs it". Owned by PR #8.
- Check the chosen name against existing mods: Terra (PolyhedralDev) and Terra++ (Build the Earth) already exist.
- Every page carries "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft."
- Screenshots of the game are fine to publish. Don't use the Minecraft logo or the end-poem screenshot on store pages.

## Mod licence

`mod_license=All Rights Reserved`. Modrinth and CurseForge accept it, and the data credits don't depend on it. For a mod meant for everyone, an open licence (MIT, or LGPL-3.0 like Iris) invites contributions and modpack use; ARR keeps full control. Modpacks need permission under ARR unless the page grants it. JR's call.
