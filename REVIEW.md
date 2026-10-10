# Independent source and native-test review

Based on immutable collision001 plus reviewed full-height-contracts001. Native endpoint integration was reviewed read-only against pinned Minecraft/NeoForge sources before GUI handoff.

Important corrections made before freezing:
- PlayerRespawnEvent fires before connection.player is replaced. Recovery is deferred to the next owner-thread tick and requires live exact replacement/player-list/connection identity plus an unchanged validated persisted record.
- Cancelled travel or mode restoration retains recovery; a fresh gate cannot overwrite unresolved recovery. No dead-player mode mutation occurs before native inventory/XP policy. Hardcore entry is excluded and orphan recovery preserves its spectator policy.
- A bounded one-slot recovery queue replaces repeated same-UUID clone objects, rejects a different live owner without throwing, and discards cleared/changed records.
- Full-height spectator failed-return states retain a zero-cube exact-player void lease until verified return or disconnect. Cleanup also handles actor-drained/session-null recovery, invalid-record fallback and logout.
- Rendering has explicit small native halo padding; gameplay/raycast/DH limits stay unchanged. All27 native dependencies remain pinned, while only18 in-domain actual meshes mask far geometry.
- HUD wraps to viewport width. Client post-fence zero-owner receipts resolve the timing ambiguity in collision001 CLOSED reports.

Headless tests exercise actual transformed Level collision and Player.checkBelowWorld paths, positive/negative signed provider/network/replica admission, durable edit/reopen at each endpoint, exact signed far masks/pages, recovery slot/record policy and pinned respawn bytecode ordering. These do not replace actual GUI walking/reload/death/restart acceptance.

Remaining limits: synchronous control mesh/far rebuild, fixed125/27 ownership and512m field, narrow inert full-block palette, central-cube edits only, no general survival/material/fluid/block-entity/tick or arbitrary edited far-summary support. Actual bounded endpoint GUI acceptance subsequently passed; see docs/evidence/full-height-player-001.json. This does not expand the compatibility or performance scope above.
