# Licensing and attribution audit

Checked on 2026-10-13 (updated after JR's imagery decision) against the cubic engine branch (PR #2) and the open feature branches (PRs #3 to #8). This is a practical checklist, not legal advice.

## Summary

| Item | Status | What's needed before a public release |
| --- | --- | --- |
| EOX Sentinel-2 cloudless (ground colours, PR #4) | Mitigated | Imagery is off by default (JR, 2026-10-13); players opt in. Emailing EOX is an optional follow-up |
| Photon on Macs | Resolved | No modified pack is distributed (JR: stock Photon only). The mod adapts Photon on the player's machine, which the licence allows; since PR #3 5d8f139 the jar carries no Photon text |
| Public name ("Minecraft Earth") | Resolved | JR chose Earth Platform (2026-10-13); PR #8's offline hint now says Earth Platform (179686a) |
| Mod licence | Decided | JR chose LGPL-3.0-only (2026-10-13); LICENSE, COPYING and the jar notice are in place |
| AWS Terrain Tiles | OK | Credited in game and in CREDITS.md |
| ESA WorldCover | OK | Credited, changes noted (CC BY 4.0) |
| CHELSA climate | OK (licence) / courtesy | CC0; ask CHELSA about public traffic or mirror the four bands |
| RESOLVE Ecoregions 2017 (PR #14) | OK | CC BY 4.0; credited with the citation, and the derived raster noted |
| Copernicus GLO-30 for filling elevation holes (coming) | OK | Same licence as the bundled crop; credit and liability line drafted below |
| River channels (coming) | **Use JRC Global Surface Water** | JRC GSW is free without restriction of use, with a citation. HydroRIVERS' licence forbids open redistribution of its data, so it can't ship in an LGPL mod (details below) |
| Hand-traced falls water (PR #16 `KnownWater`) | **Check source** | Fine if traced from EOX s2cloudless 2016 (CC BY 4.0, credit) or OSM (ODbL, credit); not if from Google/Bing/Esri imagery or EOX 2018+ (CC BY-NC-SA). Asked the Grand Canyon thread |
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

### EOX Sentinel-2 cloudless 2016 (ground colours) — mitigated
- Code (PR #4): `ImagerySource`, tiles from `https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/`, cached under `earthplatform/cache/s2cloudless-2016-v1`.
- What we know: EOX released the 2016 mosaic under CC BY 4.0 ("free to use for commercial and non-commercial purposes, as long as you provide proper attribution"); later years are CC BY-NC-SA 4.0. EOX's current licence pages describe one EOxCloudless licence for all years: non-commercial use unrestricted with attribution, commercial use only with a paid "EOX Commercial Attribution-RestrictedUse 1.2" licence, and no sub-licensing or redistribution. They no longer state the 2016 terms separately, and they publish no usage terms for the hosted tile service.
- Decision (JR, 2026-10-13): imagery ships **off by default**. New worlds are made without it, so a normal install never contacts EOX. Players opt in with the **Satellite imagery** toggle on the Customize screen or `satelliteImagery = true` in `earthplatform-common.toml` (PR #8); `-Dearthplatform.earth.imagery` still overrides both; worlds made that way pin `earth.imagery` and keep it. The install guide and FAQ describe the opt-in.
- Attribution: PR #4 now uses EOX's current form, "EOxCloudless - https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016)", and so do the mods-list credits, CREDITS.md and the store draft.
- Optional follow-ups:
  1. Email EOX (cloudless.eox.at, contact or sales) describing the mod, the 2016 layer, the request volume and the caching, and ask for written OK. With it, imagery could be on by default.
  2. Make our own ground-colour layer from Copernicus Sentinel-2 data (free and open) instead of EOX's mosaic. Larger job.
- If Modrinth or CurseForge creator rewards are switched on, opted-in players use EOX data in a mod that earns money; get EOX's OK before that.

## Bundled in the jar

- `earthplatform/copernicus_proof_data`, `alpine_data`: Copernicus WorldDEM-30 crops of the Everest area. The [GLO-30 licence](../../geography/data/copernicus-everest-proof/provenance/License-COPDEM-30.pdf) grants free reproduction, distribution and modification worldwide with the notice "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved". The licence PDF is in the jar. Credited.
- `earthplatform/proof_data`: NOAA ETOPO 2022 extracts, CC0. Credited.
- `earthplatform/worldcover_proof_data`: ESA WorldCover extracts, CC BY 4.0. Credited.
- `assets/earthplatform/textures/globe/blue_marble.jpg` (PR #8): NASA Blue Marble Next Generation, downscaled. US government work, public domain; NASA asks for credit, which CREDITS.md, the notices and the mods list give. OK.
- `earthplatform/ecoregions.png` (660 KB) and `ecoregions.tsv` (PR #14): a 0.05 degree raster derived from RESOLVE Ecoregions 2017 (Dinerstein et al. 2017, *BioScience* 67(6):534-545), with the ecoregion names. CC BY 4.0 allows redistribution and changes with attribution and a note of changes; CREDITS.md, the notices and the mods list give both. OK.
- The proof data above are test fixtures for the older engine; Earth worlds don't read them. Dropping them from the release jar saves about 2.5 MB and three notices. Engineering item, not a licence problem.
- No third-party libraries: Iris, Sodium and Distant Horizons are `compileOnly`; nothing is shaded or jar-in-jar.
- The logo (`earthplatform_logo.png`) is original pixel art.

## Coming data sources (checked 2026-10-13, credits drafted)

Paste each credit into CREDITS.md, THIRD_PARTY_NOTICES.md and the `credits` in `neoforge.mods.toml` when its PR lands. All three are data, separate from the LGPL-3.0 code licence: the code licence doesn't change their terms, and their terms don't restrict the code.

### Copernicus DEM GLO-30 ("Filling elevation holes")
- Terms: the [Copernicus WorldDEM-30 licence](../../geography/data/copernicus-everest-proof/provenance/License-COPDEM-30.pdf) (Art. 4) allows reproduction, distribution, communication to the public, and adaptation and combination with other data, free of charge and for any purpose. Same licence as the Everest crop already in the jar. OK, whether tiles are streamed at runtime or a derived fill is bundled.
- Obligations (Art. 6): give the "produced using" notice for adapted data; add the liability sentence in a notice covering the distribution; don't suggest Copernicus endorses the mod; and bind anyone we let redistribute to the same duties, which the notice in the jar and CREDITS.md does. The bundled crop's credit now carries the liability sentence too (it was missing).
- GLO-30 Public leaves out a few tiles (Armenia and Azerbaijan); those areas keep the other elevation sources.
- Credit line:
  > Elevation fill: produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30.

### JRC Global Surface Water ("Real river channels"), recommended
- Terms ([data access page](https://global-surface-water.appspot.com/download)): produced under the Copernicus Programme and "provided free of charge, without restriction of use". Products that use it must acknowledge it and cite the dataset and the Nature paper; maps showing it should say "Source: EC JRC/Google". Redistributing a derived river mask inside the jar and under an open licence is fine. OK.
- Credit line:
  > Surface water: JRC Global Surface Water, Source: EC JRC/Google. Pekel, J.-F., Cottam, A., Gorelick, N., Belward, A.S. (2016). High-resolution mapping of global surface water and its long-term changes. Nature 540, 418-422, doi:10.1038/nature20584. Produced under the Copernicus Programme.

### Hand-traced water at famous falls (PR #16, `KnownWater`)
- What: about 70 latitude/longitude points with widths, for Victoria Falls' gorges, the Niagara gorge and the river above the Horseshoe Falls, the Welland River and Skógafoss, "traced from satellite imagery". Water levels come from our elevation tiles.
- Risk depends on the imagery:
  - EOX `s2cloudless_3857` (the 2016 mosaic, what our imagery client uses): released CC BY 4.0. OK with an EOX credit that is not marked optional.
  - EOX s2cloudless 2018 and later: CC BY-NC-SA 4.0. Not OK for a mod on monetised platforms or under the LGPL's terms.
  - Google, Bing, Esri, Mapbox and similar basemaps: their terms forbid tracing features. Not OK; re-trace.
- A few dozen points describing where a river runs are close to bare facts, so the legal risk is small, but a clean, credited source removes it. Clean sources, in order:
  1. OpenStreetMap waterway lines and water outlines: ODbL. A few dozen points is an insubstantial extract; credit "© OpenStreetMap contributors, ODbL" in CREDITS.md and the mods list. A large extract would make the derived data ODbL too, which is fine for data but must be stated.
  2. JRC Global Surface Water: free without restriction, but 30 m pixels may not show 60 m gorges.
- Ask: each Course and Pool carries a one-line source comment (an OSM way id or "EOX s2cloudless 2016"). Asked the Grand Canyon thread 2026-10-13.

### HydroRIVERS (HydroSHEDS), not recommended
- Terms: "freely available for scientific, educational and commercial use", but under the HydroSHEDS v1 License Agreement ([technical documentation, Appendix A](https://data.hydrosheds.org/file/technical-documentation/HydroSHEDS_TechDoc_v1_4.pdf)). That agreement lets us distribute the data only built into a derivative product, to end users bound by a written end-user licence "at least as protective" as WWF's, never as a stand-alone dataset. It also forbids reverse engineering, obliges us to protect the data against unauthorised copying, and asks us to indemnify WWF.
- That doesn't fit Earth Platform: the mod and its source are open under the LGPL, anyone may copy and redistribute the jar, and Modrinth and CurseForge offer no end-user licence step. A river mask derived from HydroRIVERS would still contain the licensed data. Downloading it at runtime doesn't help either: the global file is 618 MB and each player would have to accept WWF's licence.
- Recommendation: build river channels from JRC Global Surface Water. If HydroRIVERS is ever needed, use it only offline as a check during development, with nothing derived from it shipped, or ask WWF (hydrosheds.org) for written permission first.
- Required wording if it were used (Exhibit B): "This product Earth Platform incorporates data from the HydroSHEDS version 1 database which is © World Wildlife Fund, Inc. (2006-2022) and has been used herein under license. WWF has not evaluated the data as altered and incorporated within Earth Platform, and therefore gives no warranty regarding its accuracy, completeness, currency or suitability for any particular purpose. Portions of the HydroSHEDS v1 database incorporate data which are the intellectual property rights of © USGS (2006-2008), NASA (2000-2005), ESRI (1992-1998), CIAT (2004-2006), UNEP-WCMC (1993), WWF (2004), Commonwealth of Australia (2007), and Her Royal Majesty and the British Crown and are used under license. The HydroSHEDS v1 database and more information are available at https://www.hydrosheds.org." Citation: Lehner, B., Grill, G. (2013), Hydrological Processes 27(15): 2171-2186.

## Shader pack

- Photon v1.3b, © 2021-2025 Benjamin Stott (SixthSurge), [Photon Shaders License Agreement](https://github.com/sixthsurge/photon/blob/main/LICENSE). The mod reads the player's own copy at runtime (its Voxy code) and ships none of it. OK.
- Decision (JR, 2026-10-13): Earth Platform must work with stock, unmodified Photon, and no modified pack is distributed. The Mac test copy (`photon_v1.3b_mac.zip`) was a derivative made for testing only; it stays out of every release, store page and download. The shader thread is making the mod itself handle the Mac's limits.
- How Macs run stock Photon (PR #3, `PhotonCompat`): on a driver without compute shaders or with 16 texture units, the mod copies the player's own Photon into `<game dir>/earthplatform/photon-compat/`, applies a few edits (regex replacements, one pass moved, one new fragment pass), and Iris loads the copy. The installed zip is untouched and nothing is uploaded or shared.
- Licence check (Photon Shaders License Agreement):
  - "Modify Photon Shaders for personal use": the adapted copy is made on the player's machine from their own download, for their own play, and never redistributed. OK.
  - "You may not redistribute Photon Shaders or derivative works on platforms that provide direct or indirect monetary compensation to creators (e.g. Modrinth, CurseForge) without my explicit written permission": the mod jar must therefore contain no Photon text. The regex edits quote only single lines to find them, which is fine. **Resolved at PR #3 5d8f139:** at 183fe4b, `PhotonCompat.SKY_SH_FRAGMENT` embedded Photon's uniform declarations and include list from `program/d4a_generate_sky_sh.csh` and ported its algorithm. Now the pass's uniform and `#include` lines are read from the player's own `d4a_generate_sky_sh.csh` at adapt time, and `main()` is new code (a 16x16 equal-solid-angle grid). A token-run comparison against Photon's file finds only trivial shared runs (`#include "/include/global.glsl"`, `vec3(0.0, 1.0, 0.0)`). Re-check this if `PhotonCompat` gains any new embedded shader text.
  - Courtesy: tell SixthSurge before release, and offer the Mac support upstream.
- The old Mac test copy (`photon_v1.3b_mac.zip`) stays out of every release.

## Companion mods

- Sodium 0.8.12-beta.1 (PolyForm Shield 1.0.0), Iris 1.8.14-beta.1 (LGPL-3.0), Distant Horizons 3.3.3 (LGPL-3.0): players install them; we only compile against them and mix into Iris at runtime. OK.
- Player impact, not licensing: `neoforge.mods.toml` declares Iris and Sodium as optional but with exact beta versions, so the game refuses to start with any other version installed. Most players with shaders will have newer builds. Widening the ranges needs the shader thread to confirm which Iris internals the mixins rely on.
- Distant Horizons is declared optional with exact version 3.3.3 for the old engine's compatibility backend; the install guide tells players to leave it out. Consider declaring it `discouraged` once the old backend is retired (a test pins the current block).

## Name and trademarks

- "Minecraft Earth" was Mojang's own augmented-reality game (2019-2021), and Mojang's usage guidelines don't allow "Minecraft" in a mod's name. The public name is **Earth Platform** (JR, 2026-10-13), used on Modrinth, CurseForge and GitHub.
- PR #8's offline hint (`earthplatform.arrival.unreachable.hint`) said "Minecraft Earth"; fixed in 179686a.
- Check the chosen name against existing mods: Terra (PolyhedralDev) and Terra++ (Build the Earth) already exist.
- Every page carries "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft."
- Screenshots of the game are fine to publish. Don't use the Minecraft logo or the end-poem screenshot on store pages.

## Mod licence

JR chose **LGPL-3.0-only** (2026-10-13). `mod_license=LGPL-3.0-only`; `LICENSE` is the LGPL-3.0 text and `COPYING` the GPL-3.0 text, and the jar carries both in `META-INF/earthplatform-LICENSE.txt` (LGPL section 4). It is compatible with everything the mod uses: no third-party code is bundled, Iris is LGPL-3.0 itself, and the data licences (CC BY 4.0, CC0, the Copernicus licence) are separate from the code licence and keep their own credits. Earlier engine notices that said All Rights Reserved (PLAYER-NOTICES.md) were updated.

## Originality check

JR asked (2026-10-13) that no code be taken from other projects, and that Voxy and Distant Horizons get full credit for what they inspired.

**Method.** Every Java and GLSL file on the cubic engine branch and on every feature branch (PRs #3 to #8, the caves branch and the integration branch) was compared, after stripping comments, imports and whitespace, against the source of:
Voxy, Distant Horizons (core and mod), Iris, Sodium, Photon's shaders, Cubic Chunks 1 and 2, Terrarium, Terra++, Terra 1:1, and Minecraft 1.21.1 itself (NeoForge sources).
Two passes looked for shared runs: 30 identical tokens in a row (copied code), and 60 tokens with every name and number replaced (copied code with renamed variables). A narrower pass at 14 tokens covered the cubic engine, far terrain and Earth packages.

**Findings.**
- No copied implementation from any of these projects.
- Matches that are not copying: Java and mixin-plugin boilerplate that every NeoForge mod has; Minecraft API calls and lists of vanilla block names; enum and switch layouts forced by Iris's own types.
- Voxy's shader-pack interface (the `VoxyFragmentParameters` struct and the `voxy_emitFragment` call in `FarShaders`) is declared the way Voxy declares it, because shader packs are written against those exact names. This is an interface for compatibility, like a header file, and Voxy is credited for it.
- PR #3 at 07da7f0 also hands far depth to packs through Voxy's hook (the `VOXY` define, samplers `vxDepthTexOpaque`/`vxDepthTexTrans`, uniforms `vxProj`, `vxProjInv`, `vxProjPrev`, `vxRenderDistance`). The names come from what Photon's shaders read. Re-scanned `FarLod`, `FarLodIris`, `FarShaders` and the three Iris mixins against Voxy, Iris and Sodium: the only shared runs are imports and the Iris method signature that any mixin into `IrisSamplers.addRenderTargetSamplers` must repeat. No copied code.
- One small helper to change for peace of mind: in the shader thread's `FarIrisUniforms` (PR #3), the switch that turns Iris uniform types into GLSL type names reads the same as the matching Voxy helper. It is a ten-line mapping fixed by Iris's enum, but it's being rewritten in our own form so no Voxy text remains. Asked through the coordinator.
- The older 039 engine under `recovery/`, `unfinished/` and `historical/` was not scanned; it is not part of the mod. Its optional Distant Horizons backend only calls DH's public API (compile-only, not bundled).
- Comments in the far terrain code already say where each idea came from ("the way Voxy keeps it", "as Distant Horizons keeps exact heights").

**Credits.** The full thanks to Voxy and Distant Horizons is in [CREDITS.md](../../CREDITS.md#distant-terrain-thank-you-voxy-and-distant-horizons), the in-game Mods screen, the README and the store page draft. All of them say we would have used Voxy and Distant Horizons directly, but neither works with a cubic-chunks world.
