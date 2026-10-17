Earth Platform build 51: dedicated servers keep up when players travel.

**Play in a fresh world.** Give the game about 6 GB of memory with Photon.

Needs Minecraft 1.21.1 and NeoForge 21.1.256 or newer. For shaders, add Iris 1.8 and Sodium 0.8 and the stock Photon v1.3b pack.

New since build 50:
- On a dedicated server with few cores, two players traveling to new places at once could slow the server to a crawl until Minecraft's watchdog stopped it ("A single server tick took 60 seconds"). Now, while the server is running late, background map work waits briefly so the game catches up. In testing on 2 cores the server fell at most 2.7 s behind instead of 18.7 s, with arrival times about the same. Far terrain can finish a few seconds later under load. Singleplayer is unchanged.

Not yet tested on a Mac or in a real VR headset.

Known: dark far slabs can show in the Amazon for the first ~20 seconds; Everglades far water is still patchy.
