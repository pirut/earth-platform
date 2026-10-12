package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.ReplacementCertificate;
import java.util.Objects;

/**
 * Bounded client mirror of server-issued replacement authority. Holds at most two distinct committed/proposed or
 * explicitly historical/proposed certificate values, never source snapshots or a Level.
 * A proposal is usable only for private preparation. The paired owner must validate its capture
 * dependencies and server ALLOW before commit. Intentional source lease retirement is independent.
 */
final class RollingReplacementAuthority implements AutoCloseable {
    private final WorldIdentity world;
    private final CubePos original;
    private final long epoch, resources;
    private final String provider,palette,material,detail;
    private String store;
    private ReplacementCertificate committed, proposed, historical;
    private String committedId,proposedId,historicalId;
    private long highestGeneration;
    private boolean closed;

    RollingReplacementAuthority(WorldIdentity world, long epoch, long resources, CubePos original, String provider,String palette,String material,String detail) {
        this.world=Objects.requireNonNull(world); this.original=Objects.requireNonNull(original);
        if(epoch<=0||resources<=0)throw new IllegalArgumentException("Positive authority scope required");
        this.epoch=epoch;this.resources=resources;
        this.provider=Objects.requireNonNull(provider);this.palette=Objects.requireNonNull(palette);this.material=Objects.requireNonNull(material);this.detail=Objects.requireNonNull(detail);
    }

    synchronized void propose(ReplacementCertificate certificate) {
        check();Objects.requireNonNull(certificate);
        if(!world.equals(certificate.world())||epoch!=certificate.epoch()
                ||resources!=certificate.resourceGeneration()||!original.equals(certificate.originalCube())
                ||!provider.equals(certificate.providerIdentity())||!palette.equals(certificate.paletteIdentity())
                ||!material.equals(certificate.materialPolicy())||!detail.equals(certificate.detailPolicy())
                ||store!=null&&!store.equals(certificate.storeIdentity()))
            throw new IllegalArgumentException("Replacement certificate scope differs from the session");
        if(store==null)store=certificate.storeIdentity();
        String id=certificate.identity();
        if(committed!=null&&committedId.equals(id)) { proposed=committed;proposedId=committedId;return; }
        if(proposed!=null&&proposedId.equals(id))return;
        if(certificate.generation()<=highestGeneration)
            throw new IllegalArgumentException("Replayed or conflicting replacement generation");
        if(proposed!=null&&proposed!=committed)throw new IllegalStateException("One replacement proposal at a time");
        highestGeneration=certificate.generation();proposed=certificate;proposedId=id;
    }

    /** Exact unchanged Earth refusal may be privately prepared again; collision still waits for paired commit. */
    synchronized void reproposeRefusedEarth(ReplacementCertificate certificate) {
        check();
        if(!io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.MATERIAL_POLICY.equals(material)
                ||!io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.DETAIL_POLICY.equals(detail)
                ||historical==null||committed!=null||proposed!=null||certificate==null
                ||!historicalId.equals(certificate.identity()))
            throw new IllegalArgumentException("Only the exact historical Earth refusal can be reproposed");
        proposed=historical;proposedId=historicalId;
    }

    /** Called after historical presentation capture and before durable replacement admission. */
    synchronized void beginEdit() {
        check();
        if(proposed!=null&&proposed!=committed)throw new IllegalStateException("Edit overlaps replacement preparation");
        historical=committed;historicalId=committedId;committed=null;proposed=null;committedId=null;proposedId=null;
    }

    synchronized void cancelProposal() { check();proposed=null;proposedId=null; }

    /** Caller must first commit the exact far ticket and retire historical presentation tokens. */
    synchronized void commit(String identity) {
        check();
        if(identity==null) { committed=null;proposed=null;historical=null;committedId=proposedId=historicalId=null;return; }
        if(proposed==null||!proposedId.equals(identity))
            throw new IllegalStateException("Exact prepared replacement authority required");
        committed=proposed;committedId=proposedId;proposed=null;historical=null;proposedId=historicalId=null;
    }

    synchronized boolean isCurrent(String identity) {
        return !closed&&identity!=null&&(identity.equals(committedId)||identity.equals(proposedId));
    }
    synchronized boolean canPresent(String identity) {
        return !closed&&identity!=null&&(identity.equals(committedId)||identity.equals(proposedId)||identity.equals(historicalId));
    }
    synchronized ReplacementCertificate committed(){return committed;}
    synchronized int retainedCertificates(){return (committed==null?0:1)+(proposed==null||proposed==committed?0:1)+(historical==null||historical==committed||historical==proposed?0:1);}
    private void check(){if(closed)throw new IllegalStateException("Replacement authority closed");}
    @Override public synchronized void close(){closed=true;committed=null;proposed=null;historical=null;committedId=proposedId=historicalId=null;}
}
