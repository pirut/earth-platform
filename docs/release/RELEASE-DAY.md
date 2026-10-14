# Release-day checklist

## Decided (JR, 2026-10-13)

- **Name: Earth Platform.** Kept from three options checked against Modrinth and CurseForge (Earth Platform, True Scale Earth, Cubic Earth); `earth-platform` was free on Modrinth and nothing on CurseForge used the name. No formal trademark search was done. "Minecraft" stays out of the name (Mojang's "Minecraft Earth").
- **Licence: LGPL-3.0-only** for the mod's code and logo. `LICENSE` holds the LGPL-3.0 text and `COPYING` the GPL-3.0 text it builds on; the jar carries both in `META-INF/earthplatform-LICENSE.txt`, and `mod_license=LGPL-3.0-only`. Earth data keeps its own terms (CREDITS.md). LGPL means anyone may fork or port it as long as changes to our code stay open under the LGPL; modpacks and add-ons may include or build on it freely.

## Release checklist

Nothing has been published. The clicks for each of JR's steps are in [GO-PUBLIC.md](GO-PUBLIC.md); the page fields are in [STORE-PAGE.md](STORE-PAGE.md).

- [x] Licence and credits settled; no copied code ([LICENSING-AUDIT.md](LICENSING-AUDIT.md)).
- [x] Repository ready to go public: community files, issue forms, safe workflows, history checked ([GO-PUBLIC.md](GO-PUBLIC.md#1-readiness-check-done)).
- [x] Every merge into `main` publishes a GitHub release of the newest build, marked **Latest**.
- [ ] **JR:** make the repository public (GO-PUBLIC step 2).
- [ ] **JR:** test the newest release in a fresh world, with and without Photon, on the Mac and on Windows if possible (step 3).
- [x] Public version `0.1.0-alpha.N`, N = build number (JR, 2026-10-14); set to `0.1.0-alpha.38` for build 38 (GO-PUBLIC step 4). Each build raises it with its notes file.
- [ ] Check the jar: Mods → Earth Platform shows the logo, description and credits; the arrival message shows the data credits.
- [ ] Optional: retake the gallery at 1920 × 1080 on a real GPU.
- [ ] **JR:** publish on Modrinth (step 5) and CurseForge (step 6) as Alpha, creator rewards off.
- [ ] Add the Modrinth and CurseForge links to the README and the repository's About box (step 7).
