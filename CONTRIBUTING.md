# Contributing to Earth Platform

Thanks for helping. Bug reports with a place (latitude and longitude) and a screenshot are the most useful thing you can send.

## Reporting a problem

Open an [issue](https://github.com/pirut/earth-cubic-platform/issues/new/choose) and fill in the form. Before you do:

- Try a **fresh world**. A world keeps the data choices it was made with, so fixes to terrain only show in new worlds.
- Check the [FAQ](docs/FAQ.md) for memory, shader and first-visit questions.
- Include `logs/latest.log` or the crash report.

Security problems go to the private form on the [Security tab](https://github.com/pirut/earth-cubic-platform/security/advisories/new), not a public issue. See [SECURITY.md](SECURITY.md).

## Changing the code

- Build and test as described in [docs/BUILDING.md](docs/BUILDING.md): JDK 21, Gradle 9.2.1, and three checked files (Iris, Sodium, stock Photon) that the build uses but never bundles.
- Keep pull requests to one change, and say what a player sees before and after.
- Terrain fixes should be rules that work everywhere (from elevation, land cover, climate, slope and so on), not lists of hand-placed spots.
- The mod must keep working with **stock, unmodified** Photon. Fixes go in the mod, not in a shader pack.
- CI builds and tests every pull request.

## License and originality

Earth Platform is licensed **LGPL-3.0-only** (see [LICENSE](LICENSE) and [COPYING](COPYING)). By opening a pull request you agree that your contribution is licensed the same way.

- Only submit code you wrote yourself. Don't copy code from other mods, even with changes, unless its license is compatible and you name the source in the pull request. In particular, nothing may come from Voxy, whose code is all rights reserved.
- New data sources need a license that allows redistribution in an open-source mod, and a credit in [CREDITS.md](CREDITS.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the mods list (`credits` in `minecraft/src/main/templates/META-INF/neoforge.mods.toml`). No OpenStreetMap or HydroSHEDS data.
- No real-world cities or roads: built-up land becomes the wild land around it.
