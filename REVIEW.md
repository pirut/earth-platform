# Rolling gate review

Independent actor, replica/protocol, server-handshake and coordinate-bound reviews were completed before the isolated full build. The review caught and corrected a copied logout guard, the actor's externally visible Committed ordering, and a missing publication stamp on edit acknowledgements.

The first focused native pass completed58 tests with zero failures/errors/skips. The final suite also includes the real actor→wire→replica diagonal/reverse/durable-edit integration, full-body candidate-boundary tests, permanent SAVE_FAILED fencing, and the narrowly reused terrain006 CPU/GPU cleanup/appearance tests. The final native aggregate passed 550 tests with no failures, errors or skips; see docs/evidence/rolling-player-001.json.

The source deliberately keeps physical ownership separate from the213-coordinate proof. Failed mesh/build/DH cleanup stays reachable through a retained session. Re-enter waits until cleanup/drain succeeds. The edit pin supports one mesh with27 dependencies; immutable boundary-shell tests prevent unsupported neighbor geometry edits.

The real client rendering path and server tick controller subsequently passed the scoped native-client protocol recorded in docs/evidence/rolling-player-001.json. Pure tests do not claim a full fake-driver test of every render callback or vanilla event ordering. VertexBuffer constructor-internal failure remains an upstream limitation. Performance is unaccepted until separately measured and improved.
