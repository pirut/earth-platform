# Earth Platform 030: notices

## Original project

Earth Platform is an experimental prototype. Its original code is licensed under the GNU Lesser General Public License version 3 (LGPL-3.0-only); see `LICENSE` and `COPYING` at the repository root. It is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.

The selected Everest profile uses retained Copernicus elevations and ESA WorldCover classifications. Its bounded local relief and slope/shelter material variation are procedural. Measured heights are retained separately, and no surveyed summit height is substituted. Legacy diagnostic and procedural arena profiles remain separate.

## Separately installed projects

Obtain each required project yourself through the exact official release links in the README. Its accompanying license applies independently. Earth Platform does not bundle or relicense these projects' binaries, implementation source, shaders, or nested libraries.

- **Minecraft: Java Edition** is supplied separately by Mojang/Microsoft through the [official launcher](https://www.minecraft.net/en-us/download).
- **NeoForge** is supplied separately by the NeoForged project. [Official downloads](https://neoforged.net/); [project source](https://github.com/neoforged/NeoForge).
- **Distant Horizons 3.3.3**, copyright James Seibel and contributors, is listed as LGPL-3.0-only by its [official release page](https://modrinth.com/mod/distanthorizons/version/3.3.3-1.21.1). Corresponding source pins: [wrapper](https://gitlab.com/distant-horizons-team/distant-horizons/-/tree/a54dd3eb1df0078e63a71266d042c10d9cda4493) and [core](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/tree/b02c66d778beea11a931291815d652cd8abaa7ad).
- **Iris 1.8.14 Beta 1** is listed as LGPL-3.0-only by its [official release page](https://modrinth.com/mod/iris/version/1.8.14-beta.1%2B1.21.1-neoforge).
- **Sodium 0.8.12 Beta 1** is listed under PolyForm Shield 1.0.0 by its [official release page](https://modrinth.com/mod/sodium/version/mc1.21.1-0.8.12-beta.1-neoforge). Do not assume every Sodium release has the same terms.
- **Photon v1.3b**, copyright © 2021–2025 Benjamin Stott (SixthSurge), has its own Photon Shaders License Agreement, included in the official ZIP. See its [official release](https://modrinth.com/shader/photon-shader/version/v1.3b) and [license](https://github.com/sixthsurge/photon/blob/main/LICENSE). Its redistribution permissions include conditions and exceptions; this package distributes no Photon content.

## Geographic data

Copernicus and WorldCover resources support the selected finite Everest region. Other bounded demonstration resources are retained independently. These notices apply to data separately from the original engine code; retain the accompanying attribution and provenance.

### Copernicus GLO-30

Produced using Copernicus WorldDEM-30 © DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH 2014–2018, provided under COPERNICUS by the European Union and ESA; all rights reserved.

The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30. This project is not endorsed by those organisations.

The bounded native-resolution elevation pack contains 528 × 1024 valid retained posts, expanded from already acquired source bytes without changing elevations or including source padding. Exact source ranges, checksums and the bit-identical original crop check are retained in the bundled import report. [Copernicus DEM GLO-30 license](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf).

### ESA WorldCover 2021 v200

© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium.

Zanaga, D. et al. (2022), *ESA WorldCover 10 m 2021 v200*. [Dataset and citation](https://doi.org/10.5281/zenodo.7254221). Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Changes: extraction of bounded native blocks and repacking into ELC1 without changing class codes.

### NOAA ETOPO 2022

NOAA National Centers for Environmental Information (2022), *ETOPO 2022 15 Arc-Second Global Relief Model*. [Dataset](https://www.ncei.noaa.gov/products/etopo-global-relief-model); [DOI 10.25921/fd45-gt74](https://doi.org/10.25921/fd45-gt74). Source accessed 2026-10-09.

Bounded ice-surface elevation/bathymetry samples are extracted and repacked into EGP1. The retained source metadata identifies [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). These data are not for navigation. Regional extraction and procedural appearance do not establish worldwide coverage or measured one-meter detail.
