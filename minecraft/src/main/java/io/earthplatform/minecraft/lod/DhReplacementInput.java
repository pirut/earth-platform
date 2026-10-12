package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One owner-side replacement attestation. A server certificate, not a native-ready
 * lease, authorizes lifetime after deliberate source retirement. Capture and this
 * wrapper are not themselves evidence that a server issued or committed a certificate;
 * the host's exact authenticated publication/ALLOW protocol supplies that authority.
 */
public final class DhReplacementInput {
    public static final String ANALYTIC_MATERIAL_POLICY="analytic-air-stone-diorite-unit-opaque-v1";

    /** identity and dependencyIdentity are the host's canonical SHA-256 certificate/vector digests. */
    public record Certificate(WorldIdentity world,long epoch,long resourceGeneration,long summaryGeneration,
                              CubePos editedCube,long durableRevision,String identity,
                              String dependencyIdentity,String materialPolicy) {
        public Certificate {
            Objects.requireNonNull(world);Objects.requireNonNull(editedCube);
            if(epoch<0||resourceGeneration<=0||summaryGeneration<=0||durableRevision<0)
                throw new IllegalArgumentException("Replacement authority generation/revision bounds");
            requireHash(identity,"certificate");requireHash(dependencyIdentity,"dependency vector");
            if(materialPolicy==null||materialPolicy.isBlank()||materialPolicy.length()>128)
                throw new IllegalArgumentException("Bounded immutable material policy required");
        }
    }
    /** Owner-thread checks only. Neither callback nor a host/session object enters a worker payload. */
    public interface AuthorityGuard {
        boolean isCurrent(Certificate certificate);
        /** Explicit immutable historical display only; never collision/current-ready authority. */
        boolean permitsRetainedPresentation(Certificate certificate);
    }
    /** Deeply immutable CPU-only content. Intentionally has no AuthorityGuard or enclosing instance. */
    public static final class Data {
        private final Certificate certificate;
        private final DhVoxelReplacementGeometry.Capture capture;
        private final String identity;
        private Data(Certificate certificate,DhVoxelReplacementGeometry.Capture capture){
            this.certificate=certificate;this.capture=capture;
            identity=hash("dh-single-replacement-content-v1\n"+certificate+"\n"+capture.contentDigest()+"\n");
        }
        public Certificate certificate(){return certificate;}
        public DhVoxelReplacementGeometry.Capture capture(){return capture;}
        public String identity(){return identity;}
        public long declaredBytes(){return capture.declaredBytes();}
    }
    private final Data data;
    private final AuthorityGuard guard;
    private boolean currentRevoked,historicalRevoked;
    public DhReplacementInput(Certificate certificate,DhVoxelReplacementGeometry.Capture capture,AuthorityGuard guard){
        Objects.requireNonNull(certificate);Objects.requireNonNull(capture);this.guard=Objects.requireNonNull(guard);
        if(!certificate.editedCube().equals(capture.editedCube()))throw new IllegalArgumentException("Certificate/capture cube mismatch");
        data=content(certificate,capture);
    }
    /** Pure immutable content factory; this value does not grant publication authority. */
    public static Data content(Certificate certificate,DhVoxelReplacementGeometry.Capture capture){
        Objects.requireNonNull(certificate);Objects.requireNonNull(capture);
        if(!certificate.editedCube().equals(capture.editedCube()))throw new IllegalArgumentException("Certificate/capture cube mismatch");
        boolean earth = EarthReplacementAdmission.MATERIAL_POLICY.equals(certificate.materialPolicy());
        if (earth != (capture.earthCapture()!=null))
            throw new IllegalArgumentException("Earth policy requires its exact six-material immutable capture");
        return new Data(certificate,capture);
    }
    public Data data(){return data;}
    public Certificate certificate(){return data.certificate();}
    public String identity(){return data.identity();}
    boolean isCurrent(){if(currentRevoked)return false;if(!guard.isCurrent(data.certificate()))currentRevoked=true;return !currentRevoked;}
    boolean permitsRetainedPresentation(){if(historicalRevoked)return false;if(!guard.permitsRetainedPresentation(data.certificate()))historicalRevoked=true;return !historicalRevoked;}
    private static void requireHash(String value,String label){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Canonical "+label+" SHA-256 required");}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
