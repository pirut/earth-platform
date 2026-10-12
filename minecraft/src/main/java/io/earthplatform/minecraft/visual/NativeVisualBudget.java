package io.earthplatform.minecraft.visual;

/**
 * Named031 native buffer envelope. Reservations include actual rounded GL storage before allocation;
 * callers retain them through staging, cached guards, replacement and failed cleanup. This does not
 * measure opaque driver internals or unrelated Minecraft/Photon memory.
 */
public final class NativeVisualBudget {
    public static final String POLICY="native-setting-buffer-envelope-1gib-1536-v1";
    public static final long MAX_BYTES=1L<<30;
    public static final int MAX_PIECES=1536;
    public static final int ALLOCATION_QUANTUM=64*1024;
    public static final int OWNER_ALLOWANCE=4096;
    private static NativeVisualBudget global;
    /** Render-owner shared ledger, including abandoned generations whose cleanup is still retained. */
    public static synchronized NativeVisualBudget global(){if(global==null)global=new NativeVisualBudget();global.checkOwner();return global;}
    private final Thread owner=Thread.currentThread();
    private long bytes,peakBytes;
    private int pieces,peakPieces;
    public record Evidence(long bytes,long peakBytes,int pieces,int peakPieces) { }
    public static int allocationBytes(int payload) {
        if(payload<=0||payload>(1<<20))throw new IllegalArgumentException("Bounded positive native payload required");
        return Math.toIntExact(((long)payload+ALLOCATION_QUANTUM-1)/ALLOCATION_QUANTUM*ALLOCATION_QUANTUM);
    }
    public Reservation reserve(int vertexPayload,int indexPayload) {
        checkOwner();
        int v=allocationBytes(vertexPayload),i=allocationBytes(indexPayload);
        long charge=(long)v+i+OWNER_ALLOWANCE;
        if(pieces==MAX_PIECES||charge>MAX_BYTES-bytes)throw new IllegalStateException("Selected native distance exceeds "+POLICY);
        bytes+=charge;pieces++;peakBytes=Math.max(peakBytes,bytes);peakPieces=Math.max(peakPieces,pieces);
        return new Reservation(v,i,charge);
    }
    public Evidence evidence(){checkOwner();return new Evidence(bytes,peakBytes,pieces,peakPieces);}
    public final class Reservation {
        private final int vertexBytes,indexBytes;
        private final long chargedBytes;
        private boolean released;
        private Reservation(int vertexBytes,int indexBytes,long chargedBytes){this.vertexBytes=vertexBytes;this.indexBytes=indexBytes;this.chargedBytes=chargedBytes;}
        public int vertexAllocationBytes(){return vertexBytes;}
        public int indexAllocationBytes(){return indexBytes;}
        public long chargedBytes(){return chargedBytes;}
        public boolean released(){checkOwner();return released;}
        /** Only after the physical GPU owner has confirmed every deletion/restoration succeeded. */
        public void releaseAfterPhysicalClose(){
            checkOwner();if(released)return;
            if(pieces<1||bytes<chargedBytes)throw new IllegalStateException("Native reservation ledger underflow");
            pieces--;bytes-=chargedBytes;released=true;
        }
    }
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native allocation budget requires its render owner");}
}
