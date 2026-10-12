package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import java.util.Objects;

/** Pure, no-I/O admission bound to the exact read20/owned18 source and complete finite census. */
public final class DhEarthReplacementAdmission implements EarthReplacementAdmission {
    public static final String SOURCE_IDENTITY="5c180d8b046dee2ef5e41536efd4d8b65f72d197bb36295b5e04eb05a0b05444";
    public static final String LAYOUT_IDENTITY="earth-alpine-2048-v1-stratified:square-radius=2048:center-align=128:snap=128:rings=[Ring[radiusBlocks=128, cellSize=16, tileSize=256], Ring[radiusBlocks=256, cellSize=16, tileSize=256], Ring[radiusBlocks=512, cellSize=32, tileSize=1024], Ring[radiusBlocks=1024, cellSize=64, tileSize=1024], Ring[radiusBlocks=2048, cellSize=128, tileSize=4096]]:fine=1:collar=1:fine-tile=32:native-y=[-11008,8864):full-xyz-clip=true:complete-footprint-required=true:strata=top1-sub3-deep:dh-material=stone2-snow8:coalesce=equal-color-and-category-v1:replacement=single-owned18-read20:six-material-unit:revision0-required:bucket=owned-min:pieces=4096:max=8192:face-color=up-down1-ew0.72-ns0.85";
    public static final String OUTSIDE_PIN_IDENTITY="f65df4366b37c855d8bba71503eacbe07703ea261247ce87efe84c7aa2b696f2";
    public static final CubePos PIN=new CubePos(604879,457,-194643);
    public static final int MAX_BASE_CELLS=6706,MAX_BASE_SOURCE_FACES=24066,MAX_BASE_FACES=23718,
            MAX_BASE_OWNERS=54,MAX_BASE_PAGE_FACES=3616;
    public static final int MAX_NATIVE_FACES=5041,MAX_REPLACEMENT_FACES=8192,MAX_REPLACEMENT_OWNERS=2;
    public static final long NATIVE_BYTES_PER_FACE=208,MAX_NATIVE_BYTES=1_048_576;
    public record Census(int replacementFaces,int nativePinFaces,long nativePinBytes,int replacementOwners,
                         int pooledFaces,int pooledOwners,long resultBytes,long oldNewGpuBytes,int oldNewOwners) {
        public boolean admitted(){return replacementFaces<=MAX_REPLACEMENT_FACES&&nativePinFaces<=MAX_NATIVE_FACES
                &&nativePinBytes<=MAX_NATIVE_BYTES&&replacementOwners<=MAX_REPLACEMENT_OWNERS
                &&pooledFaces<=DhAdaptiveTerrainData.MAX_FACES&&pooledOwners<=DhAdaptiveTerrainData.MAX_TILES
                &&resultBytes<=DhReplacementComposition.MAX_RESULT_BYTES&&oldNewGpuBytes<=8L*1024*1024&&oldNewOwners<=128;}
    }
    public DhEarthReplacementAdmission(){requireLayout();}
    public static void requireLayout(){
        if(!LAYOUT_IDENTITY.equals(DhLodLayout.EARTH_ALPINE_2048.identity()))
            throw new DhReplacementComposition.Declined("Earth layout differs from its complete census");
    }
    public static void requireCapture(EarthReplacementCapture capture){
        Objects.requireNonNull(capture,"Earth capture");
        if(!PIN.equals(capture.position())||capture.cellCount()!=8000||capture.declaredBytes()!=8000)
            throw new DhReplacementComposition.Declined("Earth replacement requires the exact original pin and read20 capture");
    }
    public Census inspect(EarthReplacementCapture capture,Runnable check){
        Objects.requireNonNull(check).run();requireLayout();requireCapture(capture);
        if(!OUTSIDE_PIN_IDENTITY.equals(outsidePinIdentity(capture)))
            throw new DhReplacementComposition.Declined("Earth capture collar/halo differs from its frozen source");
        int replacement=EarthReplacementAdmission.faces(capture);check.run();
        int nativeFaces=nativePinFaces(capture,check);
        int pieces=(replacement+4095)/4096;
        int faces=Math.addExact(MAX_BASE_FACES,replacement),owners=Math.addExact(MAX_BASE_OWNERS,pieces);
        return new Census(replacement,nativeFaces,nativeFaces*NATIVE_BYTES_PER_FACE,pieces,faces,owners,
                4096L+capture.declaredBytes()+128L*faces,88L*(DhAdaptiveTerrainData.MAX_FACES+(long)faces),64+owners);
    }
    public Receipt assess(EarthReplacementCapture capture){
        Objects.requireNonNull(capture);String reason="";boolean admitted=false;
        try{var census=inspect(capture,()->{});admitted=census.admitted();if(!admitted)reason="Earth capture exceeds native/replacement/pooled face, owner or byte reservation";}
        catch(DhReplacementComposition.Declined decline){reason=decline.getMessage();}
        return new Receipt(admitted,reason,capture.contentIdentity(),SOURCE_IDENTITY,LAYOUT_IDENTITY);
    }
    /** Canonical source-owned3904 material bytes only; edited16³ contents are intentionally excluded. */
    public static String outsidePinIdentity(EarthReplacementCapture capture){
        requireCapture(capture);
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            digest.update(("earth-alpine-frozen-read20-outside-pin-v1\0"+SOURCE_IDENTITY+"\0"+MATERIAL_POLICY+"\0"+DETAIL_POLICY+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(java.nio.ByteBuffer.allocate(12).putInt(PIN.x()).putInt(PIN.y()).putInt(PIN.z()).array());
            for(long y=capture.minY();y<capture.maxYExclusive();y++)for(long z=capture.minZ();z<capture.maxZExclusive();z++)for(long x=capture.minX();x<capture.maxXExclusive();x++)
                if(!PIN.equals(CubePos.fromBlock(x,y,z)))digest.update((byte)capture.materialAt(x,y,z));
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static int nativePinFaces(EarthReplacementCapture capture,Runnable check){
        requireCapture(capture);int count=0;var p=capture.position();
        for(long y=p.minBlockY();y<p.minBlockY()+16;y++){
            check.run();
            for(long z=p.minBlockZ();z<p.minBlockZ()+16;z++)for(long x=p.minBlockX();x<p.minBlockX()+16;x++){
                if(capture.materialAt(x,y,z)==EarthReplacementCapture.AIR)continue;
                if(capture.materialAt(x-1,y,z)==0)count++;if(capture.materialAt(x+1,y,z)==0)count++;
                if(capture.materialAt(x,y-1,z)==0)count++;if(capture.materialAt(x,y+1,z)==0)count++;
                if(capture.materialAt(x,y,z-1)==0)count++;if(capture.materialAt(x,y,z+1)==0)count++;
            }
        }return count;
    }
}
