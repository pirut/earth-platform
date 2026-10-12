package io.earthplatform.minecraft.coregate;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.lod.DhReplacementInput;
/** Whole-catalogue arena proof; it includes every dirty and clean editable cube at every view center. */
final class RollingArenaQueryGuard {
    static final String DETAIL_POLICY=io.earthplatform.minecraft.lod.DhArenaInput.DETAIL_POLICY;
    private RollingArenaQueryGuard(){}
    static RollingCubeActor.ReplacementPolicy replacementPolicy(RollingSessionDescriptor d){return new RollingCubeActor.ReplacementPolicy(d.sourceFingerprint(),io.earthplatform.minecraft.lod.DhArenaInput.MATERIAL_POLICY,DETAIL_POLICY,1,RollingEditPolicy.FULL_CUBE_V1);}
    static String policy(RollingSessionDescriptor d){return ArenaQueryAttestation.policyIdentity(d.world(),d.paletteFingerprint(),replacementPolicy(d),d.arenaDefinition(),d.arenaSource().identity(),d.lodLayout().identity());}
    static void require(RollingSessionDescriptor d,ArenaCertificate c){
        if(!d.arenaMode()||c==null||!d.world().equals(c.world())||!d.arenaDefinition().identity().equals(c.definition().identity())
                ||!d.sourceFingerprint().equals(c.providerIdentity())||!d.paletteFingerprint().equals(c.paletteIdentity())
                ||!io.earthplatform.minecraft.lod.DhArenaInput.MATERIAL_POLICY.equals(c.materialPolicy())||!DETAIL_POLICY.equals(c.detailPolicy()))throw new IllegalArgumentException("Exact arena source/material/session capability required");
        var q=c.queryAttestation();if(q==null||!q.completeCatalogue()||q.resultCount()!=8||!policy(d).equals(q.policyIdentity())||!d.lodLayout().identity().equals(q.layoutIdentity()))throw new IllegalArgumentException("COMPLETE whole arena catalogue required");q.verify(c.dependencies(),c.captureIdentity());
    }
}
