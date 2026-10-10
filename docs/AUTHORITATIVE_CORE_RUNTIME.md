# Authoritative core runtime gate001

A bounded Minecraft1.21.1 / NeoForge21.1.256 integrated-server experiment. The server-owned NativeCubeActor drives the existing NativeCubicHost, CubeProvider, BoundedCubeLoader, CubicWorld and FileEditStore. The client receives actual framed native snapshots through NeoForge packets, verifies schema/CRC/world/epoch/palette/lease/revision, and renders admitted cubes with Minecraft baked models and VBOs. The generator is analytic flat128, independent of Earth data.

The fixed5×5×5 working set has125 cube owners and27 inner native mesh targets, including known-empty coverage and the required one-cube neighbor halo. The operator edit is restricted to known block(4,129,4) in cube(0,8,0). Only one edit can be pending. A result is acknowledged only after the durable save has completed and its replacement snapshot has been delivered first. An ordered barrier fences the entire near/far draw pair during the change. The new native batch and exact3D DH mask must be ready together before drawing resumes.

The unedited flat far capability covers512m. Edited volumes remain pinned under actual native meshes and dependencies. Arbitrary edit-aware distant summaries and edited-volume retirement are unsupported. Leaving closes the complete pair. The actual vanilla spectator camera sits in an empty stock-height carrier; player collision and vanilla block interaction are not integrated here. Lighting is explicitly fixed diagnostic lighting. No hardware FPS or huge-distance claim.

## Actual native client validation

Use the portable launch instructions in the repository README. Create a fresh disposable Creative world with commands enabled and accept the already-authorized experimental-world warning.

1. /earth_core enter. Wait for READY,125 owners/27 meshes, target revision0. A flat stone surface should cover the view with native textured near and actual DH far. Camera points at block(4,129,4).
2. /earth_core edit. Observe pending fenced stage then APPLIED revision1 and a white diorite block at the target. JSON must show exact revision1, actual target state, near/far coverage generation agreement.
3. /earth_core leave. Wait for CORE_GATE_ACTOR_DRAIN successful=true and CORE_GATE_CLOSED nativeOwners0 meshes0 / far GL deleted.
4. /earth_core enter. Revision1 and the diorite block must return without client generation. /earth_core status reports server revision1.
5. Leave; save and quit the world, reopen the SAME world, enter. Exact revision1 must survive world/server restart.
6. /earth_core erase. APPLIED revision2 must restore explicit air. Leave and enter, verify revision2 and absence of the block.
7. Real F3+T must fence and close client resources. Use leave then enter to obtain a fresh authoritative epoch. Finish leave, save, close client.

Evidence is in minecraft/run/earthplatform-evidence/authoritative-core-gate and minecraft/run/logs/latest.log. Auto screenshots are capped6 per epoch. Closed reports retain far cleanup diagnostics. Saves live only inside the selected disposable world's earthplatform-core-gate directory. The actor continues draining accepted I/O/providers even after server event polling stops. Failed-close owners remain reserved by the core host.

303 headless tests passed on this frozen source. Actual native GUI validation then passed the bounded server authority, edit, erase, persistence/restart, resource reload and five-epoch disposal sequence. See evidence/core-runtime-001.json. The 296 ms maximum CPU update is a control-path hitch; performance is not accepted.
