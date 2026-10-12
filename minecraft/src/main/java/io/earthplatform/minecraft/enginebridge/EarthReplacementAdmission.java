package io.earthplatform.minecraft.enginebridge;

import java.util.Objects;

/** Pure source/layout-bound admission; the renderer recenses its pooled geometry again before upload. */
@FunctionalInterface public interface EarthReplacementAdmission {
    String MATERIAL_POLICY="earth-alpine-air-stone-diorite-andesite-tuff-snow-unit-v1";
    String DETAIL_POLICY="earth-alpine-single-owned18-read20-faces8192-native5041-v1";
    int MAX_FACES=8192;
    Receipt assess(EarthReplacementCapture capture);
    record Receipt(boolean admitted,String reason,String captureIdentity,String sourceIdentity,String layoutIdentity){
        public Receipt{Objects.requireNonNull(reason);Objects.requireNonNull(captureIdentity);Objects.requireNonNull(sourceIdentity);Objects.requireNonNull(layoutIdentity);}
        public void verify(EarthReplacementCapture capture,String source,String layout){
            if(!captureIdentity.equals(capture.contentIdentity())||!sourceIdentity.equals(source)||!layoutIdentity.equals(layout))
                throw new IllegalArgumentException("Admission receipt does not bind this exact Earth capture/source/layout");
        }
    }
    /** Exact exposed unit faces of owned18 using its complete read20 halo. Allocation-free. */
    static int faces(EarthReplacementCapture capture){
        Objects.requireNonNull(capture);int faces=0;
        for(long y=capture.minY()+1;y<capture.maxYExclusive()-1;y++)for(long z=capture.minZ()+1;z<capture.maxZExclusive()-1;z++)for(long x=capture.minX()+1;x<capture.maxXExclusive()-1;x++){
            if(capture.materialAt(x,y,z)==EarthReplacementCapture.AIR)continue;
            if(capture.materialAt(x-1,y,z)==EarthReplacementCapture.AIR)faces++;
            if(capture.materialAt(x+1,y,z)==EarthReplacementCapture.AIR)faces++;
            if(capture.materialAt(x,y-1,z)==EarthReplacementCapture.AIR)faces++;
            if(capture.materialAt(x,y+1,z)==EarthReplacementCapture.AIR)faces++;
            if(capture.materialAt(x,y,z-1)==EarthReplacementCapture.AIR)faces++;
            if(capture.materialAt(x,y,z+1)==EarthReplacementCapture.AIR)faces++;
        }return faces;
    }
    static void verifyPolicy(RollingCubeActor.ReplacementPolicy policy){
        if(!MATERIAL_POLICY.equals(policy.materialPolicy())||!DETAIL_POLICY.equals(policy.detailPolicy())||policy.editPolicy()!=RollingEditPolicy.INTERIOR_V1)
            throw new IllegalArgumentException("Exact Earth material/detail/interior policy required");
    }
    static void verifyCapture(ReplacementCertificate certificate,EarthReplacementCapture capture){
        if(certificate==null||!certificate.originalCube().equals(capture.position())||!MATERIAL_POLICY.equals(certificate.materialPolicy())||!DETAIL_POLICY.equals(certificate.detailPolicy()))
            throw new IllegalArgumentException("Earth capture requires matching original-cube authority and policy");
    }
}
