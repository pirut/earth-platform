# Release-day checklist

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
