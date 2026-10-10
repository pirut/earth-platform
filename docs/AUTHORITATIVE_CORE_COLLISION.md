# Native collision-only walking gate 001

This isolated Minecraft 1.21.1 / NeoForge 21.1.256 experiment extends verified server-authoritative mouse editing with native standing, falling, jumping, walls and a roof. Actual native-client validation passed walking support, jumping, low-roof and wall contact, unknown boundaries, mouse-edit collision changes, resource-reload motion fencing, direct walking Save & Quit recovery and revision 2 reopen. See evidence/core-collision-001.json for measured results and report-timing limits. It remains an experimental integrated single-player operator gate, not a finished survival world or general mod-compatibility claim.

## What runs

- Server-owned full-3D CubeProvider, bounded loading, durable FileEditStore and actual framed network snapshots from core runtime002.
- Fixed 125 cube owners, 27 native meshes, and 512 m actual Distant Horizons far geometry. A finite 65-block wall/roof fixture is wholly inside the central editable cube, so the far base is exactly flat outside its pinned native coverage. No heightfield is substituted for the roof.
- The existing vanilla BlockCollisions, movement and supporting-block solver consume a scoped immutable sparse BlockGetter on the exact active server/client Level. Ordinary dimensions retain their original path and stock section allocation. Full native Y extremes are tested through actual transformed Level collision queries; live walking in this gate is at Y128, not proof of full-height player mechanics.
- Missing, stale, unsupported or out-of-domain collision data gives a labeled unavailable boundary. This policy is never generated terrain, a mesh or a stored block. At most 125 decoded collision owners are held per side in addition to the existing actor/replica data. Reload and edits close the collision frame and movement; native/DH revision readiness reopens it. Collision publication is serialized with native movement on the owning game thread.
- Both Entity.move and the authoritative server movement-packet path are fenced. The latter is essential because vanilla can assign packet coordinates after the movement call. Walking requests require finite player-sized bodies and displacement at most 4 blocks per axis. The server packet hook runs after the vanilla server-thread handoff.
- Ordinary mouse inputs still carry only epoch/action. Server ray and body/dependency validation determine the target, and durability precedes acknowledgement. Edits are limited to cube (0,8,0), and this volume remains pinned under actual native coverage.
- Original player position, dimension and mode are recorded before carrier travel. Logout/stopping restores them before vanilla persistence. Login recovers an orphan carrier safely; invalid/missing recovery data falls back to Overworld spectator. Safe mode/travel cancellation retains a zero-owner collision boundary rather than releasing a walking player into empty space.

The carrier stays stock-height and lighting is fixed diagnostic lighting. Fluids, survival/material effects, block entities, general entity ticking, arbitrary edited far summaries and moving interest are incomplete. The synchronous native/far edit rebuild is still a control implementation that may hitch. Mesa llvmpipe is software OpenGL; there is no hardware performance claim.

## Exact native-client validation protocol

Use the portable launch instructions in the repository README. Create a fresh disposable Creative world with commands enabled and accept the approved experimental-world warning.

1. `/earth_core enter`, then wait for READY and server `CORE_GATE_COLLISION_READY`. The initial view is spectator. `/earth_core walk` changes to Adventure only after both sides are ready; feet fall from Y131 to Y129 and remain grounded. `/earth_core status` records a bounded server physics trace. Space should jump and land on the same surface.
2. Roof: `/tp @s 4.5 129 5.5 0 0`. The roof underside is Y132, with genuine air below. Jumping must stop at feet Y130.2 rather than pass through it. Call `/earth_core status` immediately afterward to save the recent trajectory.
3. Wall: `/tp @s 6.5 129 5.5 -90 0`. Hold W toward positive X; the wall begins at X9, so the 0.6 m player stops at approximately X8.7. Call status.
4. Unknown boundary: `/tp @s 47 129 12 -90 0`, then W. The resident box ends at X48, so movement stops near X47.7 and the HUD labels unknown collision. It must not invent air or extend residency. Call status.
5. `/earth_core walk` resets to the known clear landing corridor. Right-click a ground face in the central cube to place diorite; hold through rebuilding, then release. The durable revision increments once. The near/far pair and movement stay fenced until the new native mesh and collision frame are ready. Left-click the block to remove it; confirm its collision follows the acknowledged revision. `/earth_core status` records the current server state.
6. Trigger real F3+T while walking. Rendering closes and movement stays fenced with a visible instruction. `/earth_core leave`, then enter and walk to rebuild a fresh epoch. Test `/earth_core fly` restores spectator flight, then walk again.
7. Save & Quit directly while WALKING, without a prior leave. Reopen the SAME world. It must restore the pre-carrier location/mode, rather than falling into an empty carrier. Enter and walk again; durable edits remain. Finish leave, Save & Quit and close.

The `/tp` commands are explicit diagnostic placement of the operator at known test locations, not movement acceptance evidence. Wall/roof contacts and the held W/Space actions exercise normal vanilla movement. Initial `/walk` only uses a body location that the authoritative snapshots prove clear.

## Evidence and reproduction

Reports and screenshots: `minecraft/run/earthplatform-evidence/authoritative-core-gate`. Server traces keep only the last 512 walking ticks, with at most eight requested status reports plus a pre-close report per epoch. They include feet coordinates, onGround, native supporting-block identity, collision readiness, revision and coverage generation. A PRE_CLOSE trace is an active-state sample; final owner disposal is recorded separately by client CLOSED reports and `CORE_GATE_COLLISION_CLOSED` server logs. The collision store is separate from earlier fixtures: selected-world/earthplatform-core-collision-gate.

The source archive excludes worlds, credentials, caches and upstream binaries. Java21 and the pinned official Minecraft/NeoForge artifacts are required. Set EARTH_DH_PROBE_JAR to the official Distant Horizons3.3.3 Minecraft1.21.1 JAR for actual upstream-builder tests without skips. The local launcher reuses resolved toolchain/assets; other machines should provision these and run minecraft/scripts/gradle.sh test build normally.
