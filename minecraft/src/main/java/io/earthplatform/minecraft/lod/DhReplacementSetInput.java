package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** One complete canonical two-workspace attestation, independent of deliberately retired leases. */
public final class DhReplacementSetInput {
    public interface AuthorityGuard {
        boolean isCurrent(ReplacementSetCertificate certificate);
        boolean permitsRetainedPresentation(ReplacementSetCertificate certificate);
    }
    /** Worker-safe content: complete immutable set metadata plus both exact compact payloads, including generated revision-zero fixtures. */
    public static final class Data {
        private final DhAnalyticFlatPolicy policy;
        private final ReplacementSetCertificate certificate;
        private final List<DhReplacementInput.Data> members;
        private final String identity,certificateIdentity,workspaceIdentity,ownershipPolicy,queryIdentity;
        private final long declaredBytes;
        private Data(DhAnalyticFlatPolicy policy,ReplacementSetCertificate certificate,
                     List<DhReplacementInput.Data> members,String certificateIdentity){
            this.policy=policy;this.certificate=certificate;this.members=List.copyOf(members);this.certificateIdentity=certificateIdentity;
            queryIdentity=certificate.queryAttestation()==null?"":certificate.queryAttestation().identity();
            var workspaces=certificate.workspaces();workspaceIdentity=workspaces.identity();
            ownershipPolicy=workspaces.isAdjacent()?"lowest-canonical-solid-owner-union-v1":"disjoint-owned-regions-v1";
            declaredBytes=this.members.stream().mapToLong(DhReplacementInput.Data::declaredBytes).sum();
            StringBuilder canonical=new StringBuilder("dh-complete-replacement-set-content-v2\n")
                    .append(policy.identity()).append('\n').append(workspaceIdentity).append('\n')
                    .append(ownershipPolicy).append('\n').append(certificateIdentity).append('\n');
            for(var member:this.members)canonical.append(member.identity()).append('\n');
            identity=hash(canonical.toString());
        }
        public DhAnalyticFlatPolicy policy(){return policy;}
        public ReplacementSetCertificate certificate(){return certificate;}
        public List<DhReplacementInput.Data> members(){return members;}
        public String identity(){return identity;}
        public String certificateIdentity(){return certificateIdentity;}
        public String workspaceIdentity(){return workspaceIdentity;}
        public String ownershipPolicy(){return ownershipPolicy;}
        public String queryIdentity(){return queryIdentity;}
        public io.earthplatform.minecraft.enginebridge.ReplacementQueryAttestation queryAttestation(){return certificate.queryAttestation();}
        public long declaredBytes(){return declaredBytes;}
        public List<CubePos> workspaces(){return certificate.regions().stream().map(ReplacementSetCertificate.Region::position).toList();}
    }
    private final Data data;
    private final AuthorityGuard guard;
    private boolean currentRevoked,historicalRevoked;
    public DhReplacementSetInput(DhAnalyticFlatPolicy policy,ReplacementSetCertificate certificate,
                                 List<NativeReplacementCapture> completeTwoCaptures,AuthorityGuard guard){
        Objects.requireNonNull(policy);Objects.requireNonNull(certificate);this.guard=Objects.requireNonNull(guard);
        if(!policy.providerIdentity().equals(certificate.world()))throw new IllegalArgumentException("Exact flat provider world required");
        if(!DhReplacementInput.ANALYTIC_MATERIAL_POLICY.equals(certificate.materialPolicy()))
            throw new IllegalArgumentException("Unsupported replacement-set material policy");
        Objects.requireNonNull(completeTwoCaptures);
        if(completeTwoCaptures.size()!=2)throw new IllegalArgumentException("Complete two-region capture set required");
        var captures=List.copyOf(completeTwoCaptures);
        String certificateIdentity=certificate.identity(),dependencyIdentity=certificate.dependencyIdentity();
        var members=new ArrayList<DhReplacementInput.Data>(2);
        for(int i=0;i<2;i++){
            var region=certificate.regions().get(i);var capture=captures.get(i);region.verifyCapture(capture);
            // The bounded analytic fixture has a generated roof/wall even at revision zero.
            // Both complete regions therefore replace the base, independently of edit count.
            var value=new DhReplacementInput.Certificate(certificate.world(),certificate.epoch(),certificate.resourceGeneration(),certificate.generation(),
                    region.position(),region.revision(),certificateIdentity,dependencyIdentity,certificate.materialPolicy());
            members.add(DhReplacementInput.content(value,DhVoxelReplacementGeometry.captureCompact(capture)));
        }
        // Bounded immutable validation precedes authority acquisition or worker staging.
        // In particular, separately valid captures must not disagree in their shared halo.
        DhReplacementSetComposition.validateCaptures(policy,
                members.stream().map(DhReplacementInput.Data::capture).toList(),()->{});
        data=new Data(policy,certificate,members,certificateIdentity);
    }
    public Data data(){return data;}
    public ReplacementSetCertificate certificate(){return data.certificate();}
    public String identity(){return data.identity();}
    public String certificateIdentity(){return data.certificateIdentity();}
    boolean isCurrent(){if(currentRevoked)return false;if(!guard.isCurrent(certificate()))currentRevoked=true;return !currentRevoked;}
    boolean permitsRetainedPresentation(){if(historicalRevoked)return false;if(!guard.permitsRetainedPresentation(certificate()))historicalRevoked=true;return !historicalRevoked;}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
