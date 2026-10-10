# Authoritative mouse editing gate 002

A bounded Minecraft 1.21.1 / NeoForge 21.1.256 integrated-server experiment. It adds ordinary mouse input to the verified command-driven core runtime 001. The server owns generation, known-cube ray queries, edits and FileEditStore durability. The client receives framed native snapshots and only publishes revision-matched native meshes and exact 3D Distant Horizons coverage together.

The working set remains 125 cubes, 27 inner native meshes and a fixed 512 m flat far fixture. Mouse edits are restricted to the known 16³ cube at cube coordinates (0,8,0), block X/Z 0–15 and Y 128–143. Right mouse places diorite against the nearest known native face. Left mouse removes the nearest known native block. The client sends only an epoch and action, never a target coordinate. Reach is 8 m. The server captures its own player pose, revalidates it and all query dependencies before committing, rejects placement into the player body, and acknowledges only after durable storage. One CPU ray job runs with at most one queued process-wide job. Each mouse button requires release before another action.

The player is still a vanilla spectator in a stock-height empty carrier. This proves mouse-to-authoritative-edit integration, not standing, falling, collision, survival or general mod compatibility. Fixed diagnostic illumination is explicit. Arbitrary edited-volume retirement and edit-aware distant summaries remain unsupported; the entire editable volume stays pinned beneath actual native coverage. A pending edit fences the whole near/far pair until the new batch is ready. That synchronous control rebuild may hitch; no performance acceptance is claimed.

## Native client validation protocol

Use the portable launch instructions in the repository README. Create a fresh disposable Creative world with commands enabled and accept the approved experimental-world warning.

1. Enter `/earth_core enter`. Wait for READY, 125 cubes and 27 meshes. The initial first-person position is (4.5,131,-1.5), yaw 0°, pitch 31°. Its actual 1.62 m eye-height ray hits stone (4,128,4) in about 7.03 m. A dedicated native regression checks this exact pose.
2. Right-click once without moving the camera. Pending/ACK stages fence rendering; READY revision 1 shows diorite at (4,129,4). Hold right mouse through rebuild for at least one second: revision must remain 1. Release before the next action.
3. Left-click once at the visible block. READY revision 2 removes that diorite. Hold through rebuild: revision must stay 2. Release, then right-click once to restore it at revision 3.
4. Aim at sky and click: reject with no new durable revision. Aim at a block outside the editable central cube, within reach, and click: reject without changing it. Third-person editing is rejected. Return via leave/enter if needed to reset the camera.
5. Leave, wait for successful actor drain and CLOSED native/far zero. Re-enter: the last durable revision and block must persist. Leave, Save & Quit, reopen the SAME world and enter: the persisted edit must survive server restart.
6. Trigger real F3+T. The client view must close safely. Leave then enter to obtain a fresh epoch and rebuild. Finish with leave, Save & Quit and close.

The existing `/earth_core edit` and `/earth_core erase` commands still target (4,129,4) for diagnostics; use actual mouse events for this gate's interaction evidence. The mouse hook also suppresses stock interaction in the active carrier. Requests rejected for unknown data never convert it into air. Resource reload is not an undo of an edit the server has already accepted.

Evidence: `minecraft/run/earthplatform-evidence/authoritative-core-gate` and `minecraft/run/logs/latest.log`. Automatic screenshots are capped at six per epoch; use F2 for additional stages. Saves stay under the selected disposable world's `earthplatform-core-gate` directory. Accepted actor I/O drains independently after GUI/server polling stops.

## Reproduction

The source archive contains source, build scripts, tests, provider fixtures and provenance; it excludes worlds, credentials, build caches and upstream binaries. Java 21 and official Minecraft/NeoForge artifacts are required. Set EARTH_DH_PROBE_JAR to the pinned official Distant Horizons 3.3.3 Minecraft 1.21.1 JAR to run official-builder tests without skips. The local launch script reuses this workspace's resolved official toolchain and assets. On another machine, configure that toolchain and run `minecraft/scripts/gradle.sh test build` normally before launching.

The frozen native suite passed 356 tests. Actual native-client mouse validation passed three durable edits, held-button no-repeat, negative ray/input checks, same-world restart and resource reload; all four closed epochs drained and retired their owners. See evidence/core-runtime-002.json. A measured 225.9 ms maximum CPU update remains an unresolved control-rebuild hitch.
