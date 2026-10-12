# Earth Platform 037: real Everest region

Experimental candidate. Packaged-client acceptance is still pending. Use a fresh disposable singleplayer world in a separate installation.

1. Install Java 21, Minecraft Java 1.21.1 and NeoForge 21.1.256. Sign in through your normal Minecraft Launcher.
2. Install the exact DH, Iris and Sodium versions linked in README.md. Keep their original filenames and bytes. Sodium already includes the four required API modules; do not install separate copies.
3. Put earthplatform-earth-distance-037.jar in the installation's mods folder. Put config/earthplatform-playable.properties in its config folder, then restart Minecraft.
4. Install the unchanged Photon v1.3b ZIP separately in shaderpacks. Copy the included photon_v1.3b.zip.txt settings beside it. Follow every pinned video/shader setting in README.md: your chosen render distance (2–32; start at 12 for this gate), simulation distance 5, DH distance 256, default block resources, no extra mods or resource packs.
5. Create a new disposable world. Run /earth enter and wait for READY. The starting ground is Y7314, derived from measured Copernicus elevation plus explicitly generated detail. /earth fly enables exploration; /earth walk restores supported walking; /earth leave returns to your original world, pose and mode.
6. Left-click to dig and right-click to place diorite, waiting for READY after each accepted change. Only the original cube's interior 14 × 14 × 14 blocks can be edited. The HUD gives its global coordinate bounds. Placement does not restore natural snow. Edits beyond that boundary or beyond the complexity budget are refused before saving.
7. Exploration is finite: 512 × 512 projected metres, with a 2 km distant view. Stay within the displayed boundary. The source has real ridges and snow/rock appearance. The direct summit view is blocked from the natural safe spawn; this package does not manufacture a summit or vegetation.

Render-distance changes rebuild at the held current pose. Leave the region before changing shaders or resources. If a reload fences the view, leave, finish the reload and reenter after readiness. Keep this installation and its saves backed up.

Known qualification: the pinned DH version can report a regeneration SELECT read-versus-close warning on shutdown. Do not ignore other SQL, saving or renderer failures. Consult README.md and PLAYER-NOTICES.md for exact limitations and source attribution. This is an intermediate Earth integration, not unrestricted Earth survival.
