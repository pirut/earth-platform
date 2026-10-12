# Earth Cubic Platform: source handoff

Development and testing were stopped at the owner's request on October 12, 2026. This repository preserves the work so another agent can continue when the owner asks. It is **not a new playable release**.

- Start with [AGENT_HANDOFF.md](AGENT_HANDOFF.md), then [ROADMAP.md](ROADMAP.md).
- The root source is candidate **039 / focused foundation batch 69**, unfinished and not playable survival.
- [historical/exploration-037](historical/exploration-037/) contains the separately recovered delivered 037 exploration source. It is a bounded exploration prototype, not full survival or a continuous global Earth. Candidate 038 was abandoned.
- [unfinished](unfinished/) preserves independent worker drafts, probe tools and reports. These are not automatically applied to the root source. Some manifests are stale; read [the inventory](docs/handoff/UNFINISHED_WORK.md) first.
- [Build and test instructions](docs/handoff/BUILD_AND_TEST.md) describe existing commands for a future owner-authorized continuation. No new game builds, tests or play sessions were run for this handoff.
- [Evidence status](docs/handoff/EVIDENCE_STATUS.md) separates component passes, actual native runs and failures.

The design target is a true cubic Minecraft base plus a one-metre Earth plugin, continuous world travel, ordinary survival including Nether/End, user-selected native view distance before optional distant LOD, and shaders. Those targets are not current completion claims.

Minecraft, NeoForge, Distant Horizons, Iris, Sodium and shader binaries are not distributed here. Use the existing exact-version instructions and notices in [PLAYER-NOTICES.md](PLAYER-NOTICES.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the build guide. This handoff contains original source, authored assets/data, dependency locks/notices and selected existing textual evidence. Worlds, player saves, caches, executable artifacts and decompiled upstream Java are excluded.

Older root notes such as WIP-RECOVERY-STATUS.json, GUI-ACCEPTANCE.md and the legacy 037 quick start are historical evidence; they are superseded only where this handoff explicitly records a later result. No older gate is silently upgraded to a pass.

## Full recovery snapshot

The repository keeps current code and final evidence directly browsable. Other historical/off-tree paths mentioned above are in the [complete recovery archive](recovery/README.md). Run its source-only reconstruction helper before following those paths. All original variant files are retained; identical content is stored once.
