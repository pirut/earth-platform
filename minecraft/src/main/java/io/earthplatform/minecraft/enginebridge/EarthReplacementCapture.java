package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Exact Earth six-material owned18/read20 capture. No decoded int array or mutable source escapes. */
public final class EarthReplacementCapture {
    public static final int AIR=0, STONE=1, DIORITE=2, ANDESITE=3, TUFF=4, SNOW_BLOCK=5;
    public static final int MAX_CELLS=20*20*20, CAPTURE_BYTES=MAX_CELLS;
    private final CubePos position;
    private final byte[] values;
    private final EarthReplacementCapture source;
    private final int overlayIndex, overlayMaterial;
    private final String contentIdentity;

    /** Caller bytes remain caller-owned; prefer Builder for a single ownership-transferred allocation. */
    public EarthReplacementCapture(CubePos position,byte[] values){this(position,copy(values),null,-1,-1);}
    private EarthReplacementCapture(CubePos position,byte[] values,EarthReplacementCapture source,int index,int material){
        this.position=Objects.requireNonNull(position);RollingCubePlan.validateWindowCenter(position);
        this.values=values;this.source=source;overlayIndex=index;overlayMaterial=material;
        if(values!=null){if(values.length!=MAX_CELLS)throw new IllegalArgumentException("Exact Earth read20 capture required");for(byte value:values)validate(value);}
        contentIdentity=digest();
    }
    private static byte[] copy(byte[] values){Objects.requireNonNull(values);if(values.length!=MAX_CELLS)throw new IllegalArgumentException("Exact Earth read20 capture required");return values.clone();}
    private static void validate(int material){if(material<AIR||material>SNOW_BLOCK)throw new IllegalArgumentException("Unknown Earth replacement material");}
    public CubePos position(){return position;}
    public CubePos editedCube(){return position;}
    public long minX(){return position.minBlockX()-2;} public long minY(){return position.minBlockY()-2;} public long minZ(){return position.minBlockZ()-2;}
    public long maxXExclusive(){return position.minBlockX()+18;} public long maxYExclusive(){return position.minBlockY()+18;} public long maxZExclusive(){return position.minBlockZ()+18;}
    public int cellCount(){return MAX_CELLS;} public long declaredBytes(){return CAPTURE_BYTES;}
    public String contentIdentity(){return contentIdentity;}
    public boolean containsReadCell(long x,long y,long z){return x>=minX()&&x<maxXExclusive()&&y>=minY()&&y<maxYExclusive()&&z>=minZ()&&z<maxZExclusive();}
    private int index(long x,long y,long z){if(!containsReadCell(x,y,z))throw new IndexOutOfBoundsException("Outside exact Earth read20 capture");return Math.toIntExact(((y-minY())*20+z-minZ())*20+x-minX());}
    private int value(int index){return values!=null?values[index]:index==overlayIndex?overlayMaterial:source.values[index];}
    public int materialAt(long x,long y,long z){return value(index(x,y,z));}
    public int argbAt(long x,long y,long z){return switch(materialAt(x,y,z)){
        case AIR->0;case STONE->0xff8a8a8a;case DIORITE->0xffdedede;case ANDESITE->0xff999a99;case TUFF->0xff777a69;case SNOW_BLOCK->0xfff1f5f6;default->throw new AssertionError();};}
    /** Zero-copy one-cell prospective overlay. Serial callers compact only after durability. */
    public EarthReplacementCapture withMaterial(long x,long y,long z,int material){
        validate(material);
        if(!position.equals(CubePos.fromBlock(x,y,z)))throw new IllegalArgumentException("Earth edit must belong to its original cube");
        int index=index(x,y,z);if(value(index)==material)return this;
        if(source!=null)throw new IllegalStateException("Compact Earth capture before another prospective edit");
        return new EarthReplacementCapture(position,null,this,index,material);
    }
    /** Allocates exactly one new8k array; the private constructor takes sole ownership without cloning. */
    public EarthReplacementCapture compact(){
        if(source==null)return this;
        byte[] compact=source.values.clone();compact[overlayIndex]=(byte)overlayMaterial;
        return new EarthReplacementCapture(position,compact,null,-1,-1);
    }
    private String digest(){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update("earth-alpine-six-material-read20-capture-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(12).putInt(position.x()).putInt(position.y()).putInt(position.z()).array());
            for(int i=0;i<MAX_CELLS;i++)digest.update((byte)value(i));
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    /** Owner-thread sliced capture/wire decoder; finish revokes builder ownership before transfer. */
    public static final class Builder implements AutoCloseable {
        private final CubePos position;private byte[] values;private int count;
        public Builder(CubePos position){this.position=Objects.requireNonNull(position);RollingCubePlan.validateWindowCenter(position);values=new byte[MAX_CELLS];}
        public void setNext(int material){if(values==null)throw new IllegalStateException("Earth capture builder is closed");validate(material);if(count==MAX_CELLS)throw new IllegalStateException("Earth capture builder is full");values[count++]=(byte)material;}
        public int count(){return count;}public long declaredBytes(){return values==null?0:CAPTURE_BYTES;}
        public EarthReplacementCapture finish(){if(values==null||count!=MAX_CELLS)throw new IllegalStateException("Complete live Earth capture builder required");byte[] owned=values;values=null;return new EarthReplacementCapture(position,owned,null,-1,-1);}
        @Override public void close(){values=null;}
    }
}
