package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.ArenaCapture;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;

/** Reuses one immutable content owner before a decoded READY can enter replica state. */
final class RollingArenaCaptureOwner {
    private RollingArenaCaptureOwner() {}
    static RollingCubeActor.StageReady canonicalize(RollingCubeActor.StageReady ready,ArenaCapture retained) {
        if(ready.arenaCertificate()==null||ready.arenaCapture()==null)throw new IllegalArgumentException("Complete arena required");
        ready.arenaCertificate().verifyCapture(ready.arenaCapture());
        if(retained==null||!retained.contentIdentity().equals(ready.arenaCapture().contentIdentity()))return ready;
        ready.arenaCertificate().verifyCapture(retained);
        return new RollingCubeActor.StageReady(ready.world(),ready.epoch(),ready.generation(),ready.candidateCenter(),ready.versions(),ready.replacementCertificate(),ready.pinned(),ready.activeFocus(),ready.replacementSet(),ready.replacementCaptures(),ready.arenaCertificate(),retained);
    }
}
