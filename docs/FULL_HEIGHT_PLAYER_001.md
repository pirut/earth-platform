# Bounded full-height native player gate 001

This isolated integrated single-player operator diagnostic uses the same generic full-3D CubeProvider, durable store, bounded actor, actual network snapshots, Minecraft meshes and vanilla collision solver as collision001. Actual native-client endpoint walking, jumping, mouse edits, independent store recovery, reload safety, walking Save & Quit recovery and death/respawn recovery passed. See evidence/full-height-player-001.json for the measured scope and remaining limits. It does not claim finished survival, arbitrary mod support or expanded distant-view range.

## Native coordinates and bounded authority

- `/earth_core enter abyss`: ground block Y−11000; standing feet Y−10999; cube Y−688.
- `/earth_core enter summit`: ground block Y+8849; standing feet Y+8850; cube Y553.
- `/earth_core enter`: base ground128. Each profile has its own provider identity, descriptor fingerprint and versioned store path.
- Every profile owns exactly125 actual server-generated and network-admitted cubes, with27 native meshes. Endpoints use real provider-backed render halos within[−11040,8896), not invented air. Gameplay/raycast/collision remain[−11008,8864). Only18 fully in-domain current native meshes enter the exact3D far mask. The central edited cube and all native dependencies remain pinned.
- Distant Horizons remains a fixed512m-radius field from its unchanged official shaders and VBO format. The flat summary is exact outside the native fixture; the real3D wall/roof/edits remain inside actual native coverage. Top planes use signed page−6/local1289 for the abyss and page4/local658 for the summit. This is not a moving or huge-distance gate.
- The carrier retains vanilla height384 and24 sections. Native block positions, movement and edits have real signed Y values; no display-height offset or dense full-height allocation is used.
- A narrow exact-player/Level safety lease replaces only the stock below-world check while the player is inside the diagnostic native envelope. It persists through loading/edit/reload/failed return, including spectator mode, independently of zeroed collision owners. It is released after verified return or disconnect. Ordinary players/levels and all other damage rules are unchanged. Outside the lease envelope the ordinary void check applies and the server requests safe recovery; outside-envelope gameplay is not supported.
- Death ends the diagnostic gate. Recovery data survives the native clone policy, and restoration waits for the live replacement to own both the connection and player-list entry. The current persisted record must still match. Hardcore entry is rejected; hardcore orphan recovery preserves spectator policy. Cancelled travel/mode restoration keeps the record and blocks a new gate from overwriting it.

Lighting is explicitly fixed diagnostic illumination. Vanilla material/friction/fluid/block-entity/survival/tick compatibility remains incomplete; ordinary Level.getBlockState still returns void air at extreme Y, and physics traces disclose that separately from native support. The control native/far rebuild can hitch. Mesa llvmpipe is software OpenGL; no hardware or full frame-pacing claim is made.

## Portable launch

Use the repository README launch instructions with separately resolved official dependencies. The development-layout-only launcher is intentionally omitted. Create a fresh disposable Creative world with commands enabled; accept the approved experimental-world warning. Capture original position and mode before entering. Do not run the Earth terrain viewer simultaneously.

## GUI protocol

1. Enter abyss. Wait for READY and `CORE_GATE_COLLISION_READY`; verify profile abyss,125 owners,27 meshes,18 DH masks. `/earth_core walk` switches to Adventure only after both client meshes and server collision are ready. Feet settle at−10999. Hold still at least10seconds, then Space to jump and land. `/earth_core status` records native support, health and unchanged carrier height. The wrapped HUD should fit GUI scale3.
2. Ordinary right-click from the default walk view places diorite at(4,−10999,4), incrementing durable revision once. Hold/release semantics are unchanged. Walk into it: stop near Z3.7. To remove it, look down at the block and left-click. Record status after the durable revision and collision frame become ready.
3. Optional abyss roof/wall tests: `/tp @s 4.5 -10999 5.5 0 0`, then Space (roof underside−10996, feet maximum about−10997.8). `/tp @s 6.5 -10999 5.5 -90 0`, then W (stop X8.7). Diagnostic teleports position the player; subsequent ordinary movement proves contact.
4. Trigger actual F3+T while walking. The frame closes and motion stays fenced; health must not drain from the stock void rule while the safety lease remains. Leave and re-enter abyss; durable revisions remain. Verify a post-fence receipt has collisionOwners0 and collisionReadyfalse.
5. Leave, wait for actor drain, then enter summit and walk. Feet settle at8850. Repeat ordinary mouse edit and jump. Its initial revision is independent of the abyss store. Optional roof/wall coordinates use feet8850 and roof8853.
6. Save & Quit directly while WALKING at one endpoint. Reopen the SAME world; original normal-world position/mode must return. Re-enter each endpoint to verify its separate durable revision. A later leave should dispose all native/DH owners and release its safety lease.
7. Death recovery diagnostic: in a disposable endpoint walk session use `/kill @s`, then Respawn. Native inventory/death policy is not overridden; the gate ends and the next server tick restores the recorded normal-world location/mode. No stale session, carrier void damage or replacement-player teleport alias may remain. Re-enter after actor drain if desired, then leave and close cleanly.

Reports and auto screenshots are under `minecraft/run/earthplatform-evidence/authoritative-core-gate`. Server physics traces retain at most512 ticks and8 requested status reports plus pre-close per epoch. Client CLOSED precedes queued collision cleanup, so `epoch-*-collision-fenced-*-closed.json` is the final collision receipt. Server logs separately record actor drain and collision-owner release. Save data is selected-world/earthplatform-full-height-player-gate-v1/{base,abyss,summit}.

The source archive excludes runtime worlds, caches, credentials and upstream binaries. External reproduction requires pinned Java21/Minecraft1.21.1/NeoForge21.1.256 and the official DistantHorizons3.3.3 jar supplied through EARTH_DH_PROBE_JAR. Upstream notices remain included.
