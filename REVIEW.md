# Collision gate review and verification scope

The final aggregate runs 397 native tests with zero failures, errors or skips. The official Distant Horizons builder tests use the pinned runtime JAR. Nine additional integration-review tests cover native hook descriptors/installation, the reasons a movement-only fence is insufficient, bounded geometry/coverage, epoch replay, and persistence event ordering. Actual transformed Level tests query collision at Y−11000 and +8849 while the Level remains stock height384 with24 sections.

Review closed these integration faults before the GUI gate:

1. Vanilla server movement can assign packet coordinates after Entity.move, even when that call was cancelled. A server-thread packet-entry fence now prevents that path for closed or overbudget experimental walking frames.
2. Walk activation checks the current carrier, authoritative known-clear body and paired readiness. It verifies Adventure was actually accepted, then clears delayed spectator physics/flight flags before subsequent packets.
3. Normal logout/stopping restores the captured origin before vanilla persists the player. A validated persistent recovery record covers orphan carrier login; missing/invalid data recovers safely to Overworld spectator. If another mod cancels safe transition, teardown retains an explicit zero-owner boundary.
4. Background asset invalidation immediately fences rendering, but collision close and support reset run together on the client owner thread. A queued old callback cannot reset a newer session: Level, connection, session and exact binding must still match.

Pinned Minecraft/NeoForge source artifact reviewed: neoforge-21.1.256-sources.jar, SHA-256 fa5b7bffbe27c7b467b485ab502b4c822fa3ca9aa28c169ca7aa7c8344580b51.

Headless tests do not prove actual player motion, latency or renderer appearance. Required GUI checks are landing/support, open jump, roof and wall contacts, unknown boundary, durable edit/collision convergence, reload freeze, fly/leave, and Save & Quit/reopen directly from walking. Live collision is at Y128; full native-height player mechanics remain a later scoped integration.
