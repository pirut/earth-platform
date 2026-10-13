# Release-day checklist

## Two choices for JR

### Public name (pick one)

Checked 2026-10-13 against Modrinth (search and slug lookup) and CurseForge (web search; CurseForge blocks direct lookups from here). No formal trademark search was done.

| Option | Clashes | Trade-off |
| --- | --- | --- |
| **True Scale Earth** (recommended) | None. Slug `true-scale-earth` is free on Modrinth; nearest titles are "True Scale Mobs" and "Terra 1 to 1" | Says what it is in three words, and players searching "earth" or "true scale" find it |
| Earth Platform (current) | None. Slug `earth-platform` is free; nothing on CurseForge | No rename work, but it sounds like developer tooling rather than a game mod |
| Cubic Earth | Slug `cubic-earth` is free on Modrinth; CurseForge has an "Earth Cube" modpack | Nods to the cubic-chunks engine, but it's close to that modpack and "cubic" means little to most players |

Renaming changes only the display name, docs and store page. The mod id `earthplatform` and its config files, folders and commands stay the same, so worlds aren't affected. "Minecraft" can't appear in any name (Mojang's "Minecraft Earth").

### Mod licence (pick one)

None of the data licences constrains this choice. The credits in the mod and CREDITS.md stay whatever the code licence is, ESA WorldCover (CC BY 4.0) and the bundled Copernicus extract need them, and CHELSA and NOAA are CC0. EOX imagery is opt-in, and its commercial-use question affects creator rewards, not the code licence.

| Option | Trade-off |
| --- | --- |
| **MPL-2.0** (recommended) | Anyone may fork, but changed files must stay open under MPL with our notices, so no one can quietly take the code closed; add-ons and modpacks may use any licence |
| All Rights Reserved (current) | Most control, but no one may legally fork, port or contribute, and some modpack platforms hesitate to include it |
| MIT | Maximum adoption, but anyone may reuse the code in closed or paid projects with only a copyright line |
| LGPL-3.0 | Similar protection to MPL at library level, common for Minecraft mods (Iris uses it), but its relinking rules are harder to read for modpack and add-on authors |

Then set `mod_license` in `minecraft/gradle.properties` and add the `LICENSE` file (step 2 below).

## Steps

Nothing here has been published. In order:

1. **Settle the blockers** in [LICENSING-AUDIT.md](LICENSING-AUDIT.md): the public name.
2. **Pick the mod licence** and set `mod_license` in `minecraft/gradle.properties`. Add a matching `LICENSE` file at the repository root.
3. **Merge the feature PRs** (#3 to #8) and build the combined jar from the merged branch.
4. **Set the version.** `mod_version` is `0.1.0-engine-prototype`; for the release use `0.1.0-alpha.1`. The old engine pins that string in three places that must change with it: `compat/dh/InstalledPlayableInventory.java`, `survival/runtime/InstalledCallbackStartupSourcePins.java` and `survival/host/SurvivalInstalledModIdentity.java`, plus their tests `InstalledPlayableInventoryIndependentTest` and `SurvivalInstalledModIdentityTest`.
5. **Check the jar:** Mods → Earth Platform shows the logo, description and credits; the arrival message shows the data credits; no file outside `io/earthplatform`, our data and resources is inside.
6. **Smoke test** a fresh install from the [install guide](../INSTALL.md) on Windows and on the Mac (stock Photon at defaults on the Mac): new Earth world, spawn picker, travel, survival basics, Nether portal, shaders on and off, a dedicated server with two players.
7. **Retake screenshots** at 1920×1080 on a real GPU for the gallery listed in [STORE-PAGE.md](STORE-PAGE.md).
8. **Make the repository public** (or decide not to) so the Source and Issues links work, and add `displayURL` and `issueTrackerURL` to `neoforge.mods.toml`.
9. **Publish** to Modrinth and CurseForge as Alpha with creator rewards off, using [STORE-PAGE.md](STORE-PAGE.md). Attach the jar to a GitHub release with [CHANGELOG.md](../../CHANGELOG.md) as the notes.
