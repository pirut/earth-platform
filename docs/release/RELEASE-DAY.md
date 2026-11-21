# Release-day checklist

## Decided (JR, 2026-10-13)

- **Name: Earth Platform.** Kept from three options checked against Modrinth and CurseForge (Earth Platform, True Scale Earth, Cubic Earth); `earth-platform` was free on Modrinth and nothing on CurseForge used the name. No formal trademark search was done. "Minecraft" stays out of the name (Mojang's "Minecraft Earth").
- **License: LGPL-3.0-only** for the mod's code and logo. `LICENSE` holds the LGPL-3.0 text and `COPYING` the GPL-3.0 text it builds on; the jar carries both in `META-INF/earthplatform-LICENSE.txt`, and `mod_license=LGPL-3.0-only`. Earth data keeps its own terms (CREDITS.md). LGPL means anyone may fork or port it as long as changes to our code stay open under the LGPL; modpacks and add-ons may include or build on it freely.

## Release checklist

Nothing has been published. The clicks for each of JR's steps are in [GO-PUBLIC.md](GO-PUBLIC.md); the page fields are in [STORE-PAGE.md](STORE-PAGE.md).

- [x] License and credits settled; no copied code ([LICENSING-AUDIT.md](LICENSING-AUDIT.md)).
- [x] Repository ready to go public: community files, issue forms, safe workflows, history checked ([GO-PUBLIC.md](GO-PUBLIC.md#1-readiness-check-done)).
- [x] A pull request labeled `build` that merges into `main` publishes a GitHub release of the newest build, marked **Latest**. Any other merge does not.
- [ ] **JR:** make the repository public (GO-PUBLIC step 2).
- [ ] **JR:** test the newest release in a fresh world, with and without Photon, on the Mac and on Windows if possible (step 3).
- [x] Public version `0.1.0-alpha.N`, N = build number (JR, 2026-10-14); set to `0.1.0-alpha.38` for build 38 (GO-PUBLIC step 4). Each build raises it with its notes file.
- [ ] Check the jar: Mods → Earth Platform shows the logo, description and credits; the arrival message shows the data credits.
- [ ] Optional: retake the gallery at 1920 × 1080 on a real GPU.
- [ ] **JR:** publish on Modrinth (step 5) and CurseForge (step 6) as Alpha, creator rewards off.
- [ ] Add the Modrinth and CurseForge links to the README and the repository's About box (step 7).

## Shipping a build

Merge fix pull requests into `main` freely. A merge on its own does not publish a release.

To ship, open a pull request from a branch in this repository that:

1. Bumps `mod_version` in `minecraft/gradle.properties` to the next `0.1.0-alpha.N`. Fabric takes its version from that same line. If `fabric/gradle.properties` ever has its own `mod_version`, bump that to the same value in this pull request.
2. Adds `docs/release/notes/combined-N.md` for that same N. The release workflow stops if the version and the notes file name a different build.
3. Has the `build` label. The label has to be on the pull request when it merges.

Merging that pull request builds the NeoForge and Fabric jars, publishes the GitHub release from the merge commit, and uploads to CurseForge and Modrinth when the repository secrets are set. Pushing a `combined-*` tag, or running the Release workflow by hand with a tag name, still publishes too.
