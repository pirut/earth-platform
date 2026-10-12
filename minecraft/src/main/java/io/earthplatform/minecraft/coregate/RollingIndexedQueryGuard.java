package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.ReplacementQueryAttestation;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import java.util.Objects;

/** Common-side COMPLETE-only admission; query extent is distinct from native residency. */
final class RollingIndexedQueryGuard {
    private RollingIndexedQueryGuard() {}
    static String policy(RollingSessionDescriptor descriptor) {
        if (!descriptor.indexedWorkspaces()) throw new IllegalArgumentException("No indexed session policy");
        return ReplacementQueryAttestation.policyIdentity(descriptor.world(),descriptor.paletteFingerprint(),
                new RollingCubeActor.ReplacementPolicy(descriptor.sourceFingerprint(),DhReplacementInput.ANALYTIC_MATERIAL_POLICY,
                        RollingGateFixture.REPLACEMENT_DETAIL_POLICY,1,descriptor.editPolicy()),
                descriptor.flatPolicy(),descriptor.workspaces());
    }
    static void require(RollingSessionDescriptor descriptor, ReplacementSetCertificate certificate, CubePos candidate) {
        Objects.requireNonNull(descriptor);Objects.requireNonNull(certificate);
        var proof=certificate.queryAttestation();
        if (!descriptor.indexedWorkspaces()) {
            if (proof!=null) throw new IllegalArgumentException("Indexed proof in a non-indexed session");
            return;
        }
        if (proof==null || !proof.completeCatalogue() || !proof.complete()
                || !proof.storeIdentity().equals(certificate.storeIdentity()) || !proof.indexIdentity().equals(certificate.indexIdentity())
                || !proof.policyIdentity().equals(policy(descriptor)) || !proof.layoutIdentity().equals(descriptor.lodLayout().identity()))
            throw new IllegalArgumentException("Exact COMPLETE catalogue proof required");
        proof.verifyRegions(certificate.regions());
        var center=RollingFarPublication.center(descriptor,candidate);long radius=descriptor.lodLayout().radiusBlocks();
        if(!proof.covers(Math.subtractExact(center.x(),radius),CoreGateProfile.GAMEPLAY_MIN_Y,Math.subtractExact(center.z(),radius),
                Math.addExact(center.x(),radius),CoreGateProfile.GAMEPLAY_MAX_Y_EXCLUSIVE,Math.addExact(center.z(),radius)))
            throw new IllegalArgumentException("Indexed query does not cover the exact candidate far footprint");
    }
}
