Earth Platform build 53: server commands, builds seen from far away, and a smaller jar.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 52:
- Server commands. Operators can pick the server spawn on the globe (`/earth spawn pick`), give players or teams their own spawn, and send players anywhere (`/earth send`), all from the console too. Players get `/earth sethome`, `/earth home`, `/earth spawn`, warps, `/earth random` trips to dry land, and teleport requests (`/earth tpa`) with Accept and Deny buttons. New players can choose where they start on the globe when the server allows it.
- Region borders (a box, a distance around spawn, or a whole country), "Now entering France" country names, and team starts spread across the world.
- Games: GeoGuessr rounds (`/earth guess start`) and races to famous places (`/earth race start`, or one every day).
- Admin tools: `/earth preload` to download an area ahead of time, `/earth admin` status, optional travel cost and cooldown, and a live web map of who is where. Every server feature can be switched on or off with `/earth settings`, no restart needed. The web map is off until switched on. See "Server commands" in docs/PLAYING.md.
- Player builds now show in far terrain out to the full far view once they are big enough: a 64-wide tower reaches about 40 km, a 128-wide tower about 80 km. Before, builds vanished past about 6 km.
- Two new far sharpness choices for very powerful computers, Extreme and Ultra, and the far view slider now reaches 397 km. The defaults are unchanged.
- Nearby ground loads in the direction you are looking first, so what you see fills in sooner after arriving or turning.
- On servers, "Downloading Earth data..." shows only to players whose own terrain is still loading, and long falls no longer get pulled back up.
- The old engine Earth worlds never used is gone. The jar shrinks from 5.2 MB to about 3.2 MB and play is unchanged. Development worlds made before 2026-10-13 no longer load.

Not yet tested on a Mac or in a real VR headset.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
