package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.*;

/** Canonicalizes unchanged content before replica ownership; no mutable bytes escape. */
final class RollingEarthCaptureOwner {
    private RollingEarthCaptureOwner() {}
    static RollingCubeActor.StageReady canonicalize(RollingCubeActor.StageReady ready,EarthReplacementCapture retained) {
        var capture=ready.earthReplacementCapture();RollingEarthReplacementWire.require(ready.replacementCertificate(),capture);
        if(retained==null||!retained.contentIdentity().equals(capture.contentIdentity()))return ready;
        RollingEarthReplacementWire.require(ready.replacementCertificate(),retained);
        return new RollingCubeActor.StageReady(ready.world(),ready.epoch(),ready.generation(),ready.candidateCenter(),ready.versions(),
                ready.replacementCertificate(),ready.pinned(),ready.activeFocus(),ready.replacementSet(),ready.replacementCaptures(),
                ready.arenaCertificate(),ready.arenaCapture(),retained);
    }
}
