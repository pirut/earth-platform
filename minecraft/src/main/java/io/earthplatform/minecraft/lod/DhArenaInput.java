package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.enginebridge.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Exact complete arena authority. Worker content retains no guard, session, provider or level. */
public final class DhArenaInput {
    public static final String MATERIAL_POLICY="arena-air-stone-diorite-unit-opaque-v1";
    public static final String DETAIL_POLICY="arena-owned34-read36-two-bit-metre-v1";
    public interface AuthorityGuard {
        boolean isCurrent(ArenaCertificate certificate);
        boolean permitsRetainedPresentation(ArenaCertificate certificate);
    }
    public static final class Data {
        private final ArenaTerrainSource source;
        private final ArenaCertificate certificate;
        private final ArenaCapture capture;
        private final String identity,certificateIdentity;
        private Data(ArenaTerrainSource source,ArenaCertificate certificate,ArenaCapture capture){
            this.source=source;this.certificate=certificate;this.capture=capture;certificateIdentity=certificate.identity();
            identity=hash("dh-exact-arena-union-v1\n"+source.identity()+"\n"+certificateIdentity+"\n"+capture.contentIdentity()+"\n"+DhLodLayout.ARENA_4096.identity());
        }
        public ArenaTerrainSource source(){return source;}
        public ArenaCertificate certificate(){return certificate;}
        public ArenaCapture capture(){return capture;}
        public String identity(){return identity;}
        public String certificateIdentity(){return certificateIdentity;}
        public long declaredBytes(){return capture.declaredBytes();}
    }
    private final Data data;
    private final AuthorityGuard guard;
    private boolean currentRevoked,historicalRevoked;
    public DhArenaInput(ArenaTerrainSource source,ArenaCertificate certificate,ArenaCapture capture,AuthorityGuard guard){
        Objects.requireNonNull(source);Objects.requireNonNull(certificate);Objects.requireNonNull(capture);this.guard=Objects.requireNonNull(guard);
        certificate.verifyCapture(capture);
        if(!certificate.world().datasetVersion().equals(source.identity())
                ||!NativeArenaCubeProvider.GENERATOR_ID.equals(certificate.world().generatorId())
                ||!NativeArenaCubeProvider.GENERATOR_VERSION.equals(certificate.world().generatorVersion())
                ||!MATERIAL_POLICY.equals(certificate.materialPolicy())||!DETAIL_POLICY.equals(certificate.detailPolicy()))
            throw new IllegalArgumentException("Exact bounded arena source/material/detail identity required");
        var query=certificate.queryAttestation();
        var policy=new RollingCubeActor.ReplacementPolicy(certificate.providerIdentity(),certificate.materialPolicy(),certificate.detailPolicy(),certificate.resourceGeneration(),RollingEditPolicy.FULL_CUBE_V1);
        String expected=ArenaQueryAttestation.policyIdentity(certificate.world(),certificate.paletteIdentity(),policy,certificate.definition(),source.identity(),DhLodLayout.ARENA_4096.identity());
        if(!query.completeCatalogue()||!query.layoutIdentity().equals(DhLodLayout.ARENA_4096.identity())||!query.policyIdentity().equals(expected))
            throw new IllegalArgumentException("Complete exact arena query/source/layout authority required");
        DhArenaGeometry.validate(source,capture,()->{});
        data=new Data(source,certificate,capture);
    }
    public Data data(){return data;}
    public ArenaCertificate certificate(){return data.certificate();}
    public String identity(){return data.identity();}
    public String certificateIdentity(){return data.certificateIdentity();}
    boolean isCurrent(){if(currentRevoked)return false;if(!guard.isCurrent(certificate()))currentRevoked=true;return !currentRevoked;}
    boolean permitsRetainedPresentation(){if(historicalRevoked)return false;if(!guard.permitsRetainedPresentation(certificate()))historicalRevoked=true;return !historicalRevoked;}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
