# Complete source recovery

The latest integrated code and important final reports are directly browsable in the repository. Historical037 and all recovered independent drafts, their earlier variants and bulk textual evidence are preserved here in one deduplicated source-only archive. It has no vendor executables, worlds, player saves or caches. See CONTENTS.json and SHA256SUMS.

To reconstruct the complete original layout into a new empty directory, run:

    python3 recovery/restore_snapshot.py recovered-snapshot

This only restores original files after checking archive/content hashes. It does not run the game, builds or tests. Read recovered-snapshot/AGENT_HANDOFF.md and its docs/handoff/UNFINISHED_WORK.md before applying drafts. The original SOURCE_INVENTORY lists copied source inputs; the archive's manifest.json binds every final snapshot file, including new handoff docs. Historical source attestations can refer to deliberately excluded binaries/worlds and are not standalone replay guarantees.

The archive is stored as numbered binary parts in GitHub to keep individual files small. Keep all parts beside CONTENTS.json. The same reconstruction script also accepts the single complete .tar.gz when available. No Git LFS service is required. CONTENTS.json records the exact ordered parts and combined SHA256.

Documentation clarification after archive sealing: installed006 authenticated the sticky frontier18 observation-refusal code, not its exact triggering nested branch. Later source diagnosis implicated nested same-thread admission. The current root EVIDENCE_STATUS.md uses this narrower wording; archive contents retain the original frozen wording and hashes.
