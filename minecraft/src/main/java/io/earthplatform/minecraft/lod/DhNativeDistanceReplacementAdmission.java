package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import java.util.Objects;

/**031 actor-side source/capture admission. Dynamic far base candidates must independently obey the reserved base ceiling. */
public final class DhNativeDistanceReplacementAdmission implements EarthReplacementAdmission {
    public static final int REPLACEMENT_FACE_RESERVE=8192,REPLACEMENT_OWNER_RESERVE=2;
    public static final int MAX_BASE_FACES=DhNativeDistanceBudget.MAX_FACES-REPLACEMENT_FACE_RESERVE;
    public static final int MAX_BASE_OWNERS=DhNativeDistanceBudget.MAX_OWNERS-REPLACEMENT_OWNER_RESERVE;
    public static final String POLICY=DhNativeDistanceBudget.POLICY+":base-faces122880:base-owners254:replacement8192-two-pieces";
    public record Census(int nativeFaces,int replacementFaces,int replacementOwners){
        public boolean admitted(){return nativeFaces<=5041&&replacementFaces<=8192&&replacementOwners<=2;}
    }
    public Census inspect(EarthReplacementCapture capture,Runnable check){
        Objects.requireNonNull(check).run();DhEarthReplacementAdmission.requireLayout();DhEarthReplacementAdmission.requireCapture(capture);
        if(!DhEarthReplacementAdmission.OUTSIDE_PIN_IDENTITY.equals(DhEarthReplacementAdmission.outsidePinIdentity(capture)))
            throw new DhReplacementComposition.Declined("Earth capture collar/halo differs from immutable source");
        int replacement=EarthReplacementAdmission.faces(capture);check.run();
        int nativeFaces=DhEarthReplacementAdmission.nativePinFaces(capture,check);
        return new Census(nativeFaces,replacement,(replacement+4095)/4096);
    }
    @Override public Receipt assess(EarthReplacementCapture capture){
        Objects.requireNonNull(capture);boolean admitted=false;String reason="";
        try{admitted=inspect(capture,()->{}).admitted();if(!admitted)reason="Earth capture exceeds native5041/replacement8192/two-piece reserve";}
        catch(DhReplacementComposition.Declined declined){reason=declined.getMessage();}
        return new Receipt(admitted,reason,capture.contentIdentity(),DhEarthReplacementAdmission.SOURCE_IDENTITY,DhEarthReplacementAdmission.LAYOUT_IDENTITY);
    }
}
