# Release-day checklist

## Decided (JR, 2026-10-13)

- **Name: Earth Platform.** Kept from three options checked against Modrinth and CurseForge (Earth Platform, True Scale Earth, Cubic Earth); `earth-platform` was free on Modrinth and nothing on CurseForge used the name. No formal trademark search was done. "Minecraft" stays out of the name (Mojang's "Minecraft Earth").
- **Licence: LGPL-3.0-only** for the mod's code and logo. `LICENSE` holds the LGPL-3.0 text and `COPYING` the GPL-3.0 text it builds on; the jar carries both in `META-INF/earthplatform-LICENSE.txt`, and `mod_license=LGPL-3.0-only`. Earth data keeps its own terms (CREDITS.md). LGPL means anyone may fork or port it as long as changes to our code stay open under the LGPL; modpacks and add-ons may include or build on it freely.

## Steps

Nothing here has been published. In order:

1. ~~Settle the blockers in [LICENSING-AUDIT.md](LICENSING-AUDIT.md)~~ Done: no licence blockers remain, and the name is Earth Platform.
2. ~~Pick the mod licence~~ Done: LGPL-3.0-only (see above). Before publishing, add a short header to source files if wanted (optional under the LGPL).
3. **Freeze after build 30** (JR, 2026-10-13): no new features after combined build 30, only fixes. JR plays 30 in a fresh world and approves merging the cubic Earth build (PR #2) into main; the release jar is built from main.
4. **Set the version.** `mod_version` is `0.1.0-engine-prototype`; for the release use `0.1.0-alpha.1`. The old engine pins that string in three places that must change with it: `compat/dh/InstalledPlayableInventory.java`, `survival/runtime/InstalledCallbackStartupSourcePins.java` and `survival/host/SurvivalInstalledModIdentity.java`, plus their tests `InstalledPlayableInventoryIndependentTest` and `SurvivalInstalledModIdentityTest`.
5. **Check the jar:** Mods → Earth Platform shows the logo, description and credits; the arrival message shows the data credits; no file outside `io/earthplatform`, our data and resources is inside.
6. **Smoke test** a fresh install from the [install guide](../INSTALL.md) on Windows and on the Mac (stock Photon at defaults on the Mac): new Earth world, spawn picker, travel, survival basics, Nether portal, shaders on and off, a dedicated server with two players.
7. **Retake screenshots** at 1920×1080 on a real GPU for the gallery listed in [STORE-PAGE.md](STORE-PAGE.md).
8. **Make the repository public** (or decide not to) so the Source and Issues links work, and add `displayURL` and `issueTrackerURL` to `neoforge.mods.toml`.
9. **Publish** to Modrinth and CurseForge as Alpha with creator rewards off, using [STORE-PAGE.md](STORE-PAGE.md). Attach the jar to a GitHub release with [CHANGELOG.md](../../CHANGELOG.md) as the notes.
