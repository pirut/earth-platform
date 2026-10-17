Earth Platform build 52: server commands for running an Earth server.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 51:
- Operators can pick the server spawn on the globe (`/earth spawn pick`), give players or teams their own spawn, and send players anywhere (`/earth send`), all from the console too.
- Players get `/earth sethome`, `/earth home`, `/earth spawn`, warps, `/earth random` trips to dry land, and teleport requests (`/earth tpa`) with Accept and Deny buttons.
- New players can choose where they start on the globe when the server allows it (`playersChooseStart`).
- A region border (a box, a distance around spawn, or a whole country), "Now entering France" country names, and team starts spread across the world.
- Games: GeoGuessr rounds (`/earth guess start`) and races to famous places (`/earth race start`, or one every day).
- Admin tools: `/earth preload` to download an area ahead of time, `/earth admin` status, optional travel cost in experience levels and a cooldown, and a live web map of who is where (`webMapPort`).
- Every feature is optional: `/earth settings` lists them with clickable ON/OFF buttons and switches them at once, no restart (or edit earthplatform-common.toml). The web map is off until switched on.
- See "Server commands" in docs/PLAYING.md.

Not yet tested on a Mac or in a real VR headset.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
