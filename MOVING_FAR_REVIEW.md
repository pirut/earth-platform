# Moving far-window integration review

## Baseline and scope

Earth002 is the immutable accepted baseline. Its actual runtime verification remains in `../evidence/native-earth-player-002/runtime-verification.json`. This increment changes the far center policy and proves source support for the full rendered cell footprint. Native provider/store identities, the ±128m Earth body envelope, the single permanent edit pin, the 512m radius and all owner/queue/byte limits remain unchanged.

## Authority and ownership

`RollingFarPublication` derives the 64m-snapped center exclusively from the exact authoritative native candidate center. A versioned outer digest binds that center, radius and snap to the descriptor fingerprint and full native manifest digest. The server rederives it at preparation, allowance and final collision publication. The unchanged wire shape does not imply interoperability with clients that submit the older unbound digest; those proofs are rejected. The client validates the same center before preparing and before installing the pair; it calls the explicit center-aware DH methods.

The far adapter retains immutable centers in worker input, prepared geometry, coverage tickets and presentation tokens. Private work cannot alter the published center. Only the existing server-ALLOW path can atomically commit the matching center and complete XYZ mask. The old pair remains current through movement preparation. Edit presentation retains an explicitly historical view with gameplay fenced. Same-geometry reuse requires both identical mask positions and the same center; revision/lease authority still receives a fresh ticket. One view/cache serves all centers.

The client verifies far coverage after commit, then retires historical presentation and activates fresh native bindings before exposing the pair. A partial failure fences the session and preserves retained cleanup owners. Collision publication remains a separate server-authorized final step. Camera movement neither selects a center nor bypasses this handshake.

## Source support

The earlier center-sampled geometry could extend 2m beyond the safe west source boundary even though every center query succeeded. This candidate adds an explicit optional complete-footprint capability. Earth requires it before creating the far view. Its immutable raster proof checks all finite bilinear interpolation nodes for the complete cell rectangle expanded by the source policy's ±8m slope/shelter halo. Internal no-data cannot be hidden by finite corners. Unsupported multi-tile composition fails closed.

The adaptive partition is bounded before source reads. A failed footprint proof omits the entire leaf before height/color queries. It emits no synthetic wall at the unknown edge. Diagnostics separate attempted leaves, attempted center samples, footprint rejects, fully supported leaves and unknown leaves. Sources without this capability retain an explicitly labeled legacy center approximation; this Earth host does not accept that mode.

The conservative whole-leaf omission deliberately discards some otherwise known metres at the edge. For the five westernmost reachable centers, requested minX9674288 is replaced by emitted minX9674304, with64 omitted leaves. No rendered footprint or packed vertex extends west of the verified support. This is a coverage correction, not an expansion.

## Evidence and acceptance

- Common/server and independent authority contracts: `review-evidence/moving-far-common/` and `review-evidence/moving-authority/`.
- Direct prepared-view lifecycle, same-geometry and source-capability tests: `review-evidence/moving-far/lifecycle/`.
- Real accepted source, 25-center masks, retained edit pin, seams and actual official DH packing: `review-evidence/moving-far/source-far/`.
- Independent full-footprint adversarial review: `review-evidence/moving-authority/footprint-review-summary.json`.
- Final aggregate and exact source/archive/jar hashes: `test-summary.json`; complete input hashes are in `logs/aggregate-inputs.sha256`.

Source-edge/headless tests do not establish actual rendered runtime acceptance. Root must exercise this exact frozen client and classify any cancellation by its emitted first-fence stage. Timing remains owner elapsed in Mesa llvmpipe software OpenGL; callback and shader stalls remain possible. Source-generation failure still closes the actor/session safely rather than implementing a new unknown-stage rollback protocol.
