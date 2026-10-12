package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.minecraft.visual.NativePageKey;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/** Render-owned physical allocation. Register this object before upload; retain it after any failure. */
public final class NativeVisualGpuPiece implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final NativeVisualCapsuleCapture.Lease lease;
    private final NativeVisualBudget budget;
    private final NativeVisualCapsule.Identity capsuleIdentity;
    private final NativePageKey key;
    private final String contentIdentity,bytesDigest;
    private final int ordinal,quads,indexCount;
    private final boolean completesRequest;
    private final List<NativeVisualCpuPiece.MemberRange> ranges;
    private NativeVisualCpuPiece cpu;
    private NativeVisualGpuStorage storage;
    private ByteBuffer staging;
    private IntBuffer indices;
    private boolean started,uploaded,failed,closed,closeComplete;
    private long fullDrawCalls,rangeDrawCalls;
    private final Completion completion=new Completion();

    public NativeVisualGpuPiece(NativeVisualCpuPiece cpu,NativeVisualCapsuleCapture.Lease lease,NativeVisualBudget budget) {
        this.cpu=Objects.requireNonNull(cpu);this.lease=Objects.requireNonNull(lease);this.budget=Objects.requireNonNull(budget);
        lease.requireCurrent();
        if(!cpu.capsuleIdentity().equals(lease.capsuleIdentity()))throw new IllegalArgumentException("Native capsule identity mismatch");
        capsuleIdentity=cpu.capsuleIdentity();
        key=NativePageKey.containing(cpu.originX(),cpu.originY(),cpu.originZ());
        contentIdentity=cpu.requestStamp();bytesDigest=cpu.bytesDigest();ordinal=cpu.ordinal();quads=cpu.quads();indexCount=cpu.indexCount();
        completesRequest=cpu.completesRequest();ranges=cpu.memberRanges();
    }
    public void upload() {
        checkOwner();if(started||closed)throw new IllegalStateException("Native upload is single-use");started=true;
        Throwable failure=null;
        try {
            requireLease();check("upload.entry");
            if(quads!=0) {
                storage=new NativeVisualGpuStorage(budget,new NativeVisualGpuStorage.Operations(){
                    @Override void invalidate(){BufferUploader.invalidate();}
                    @Override void bindVertexArray(int id){GlStateManager._glBindVertexArray(id);}
                    @Override void bindBuffer(int target,int id){GlStateManager._glBindBuffer(target,id);}
                });
                storage.reserve(cpu.byteLength(),Math.multiplyExact(indexCount,4));
                staging=MemoryUtil.memAlloc(cpu.byteLength());cpu.copyTo(staging);staging.flip();
                indices=MemoryUtil.memAllocInt(indexCount);
                for(int q=0;q<quads;q++){int v=q*4;indices.put(v).put(v+1).put(v+2).put(v+2).put(v+3).put(v);}
                indices.flip();
                storage.upload(staging,indices,()->lease.format().setupBufferState());
            }
            requireLease();
        } catch(RuntimeException|Error e){failure=e;failed=true;}
        try{freeStaging();}catch(RuntimeException|Error e){failure=SceneBuildStepper.append(failure,e);failed=true;}
        SceneBuildStepper.rethrow(failure);
        requireLease();cpu=null;uploaded=true;
    }
    private void freeStaging(){if(staging!=null){MemoryUtil.memFree(staging);staging=null;}if(indices!=null){MemoryUtil.memFree(indices);indices=null;}}
    public Completion completion(){requireDrawable();return completion;}
    /** Only this physical owner can mint a live completed receipt. No setter or caller-provided completed flag exists. */
    public final class Completion {
        private Completion(){}
        public NativePageKey key(){requireDrawable();return key;}
        public String contentIdentity(){requireDrawable();return contentIdentity;}
        public String bytesDigest(){requireDrawable();return bytesDigest;}
        public int ordinal(){requireDrawable();return ordinal;}
        public int quads(){requireDrawable();return quads;}
        public boolean completesRequest(){requireDrawable();return completesRequest;}
        public List<NativeVisualCpuPiece.MemberRange> memberRanges(){requireDrawable();return ranges;}
        public NativeVisualCapsule.Identity capsuleIdentity(){requireDrawable();return capsuleIdentity;}
        public NativeVisualCapsuleCapture.Lease resourceLease(){requireDrawable();return lease;}
        public void requirePhysicalCurrent(){requireDrawable();}
        public void requireCurrent(){requireDrawable();requireLease();}
        public NativeVisualGpuPiece physicalOwner(){requireDrawable();return NativeVisualGpuPiece.this;}
    }
    void bind(){requireDrawable();if(quads!=0)storage.bind();}
    void draw(){draw(-1L);}
    void draw(long memberMask){drawMasked(memberMask);}
    record DrawStats(long fullDrawCalls,long rangeDrawCalls){long drawCalls(){return fullDrawCalls+rangeDrawCalls;}}
    DrawStats drawEvidence(){checkOwner();return new DrawStats(fullDrawCalls,rangeDrawCalls);}
    int selectedQuads(long memberMask){requireDrawable();int selected=0;for(var range:ranges)if((memberMask&(1L<<range.member()))!=0)selected=Math.addExact(selected,range.quads());return selected;}
    DrawStats drawMasked(long memberMask){
        requireDrawable();int selected=selectedQuads(memberMask);if(selected==0)return new DrawStats(0,0);
        if(selected==quads){submit(true,indexCount,0);return new DrawStats(1,0);}
        int first=-1,count=0,calls=0;
        for(var range:ranges){
            if(range.quads()==0||(memberMask&(1L<<range.member()))==0)continue;
            if(first>=0&&range.firstQuad()==first+count)count=Math.addExact(count,range.quads());
            else {if(first>=0){submit(false,Math.multiplyExact(count,6),Math.multiplyExact(first,6));calls++;}first=range.firstQuad();count=range.quads();}
        }
        if(first>=0){submit(false,Math.multiplyExact(count,6),Math.multiplyExact(first,6));calls++;}
        return new DrawStats(0,calls);
    }
    private void submit(boolean full,int count,int firstIndex){
        long before=storage.drawCalls();
        try{if(full)storage.draw(count);else storage.drawRange(count,firstIndex);}
        finally{long issued=storage.drawCalls()-before;if(full)fullDrawCalls+=issued;else rangeDrawCalls+=issued;}
    }
    NativeVisualCapsuleCapture.Lease lease(){requireDrawable();return lease;}
    public NativePageKey key(){return key;}
    public String contentIdentity(){return contentIdentity;}
    public int quads(){return quads;}
    public long chargedBytes(){checkOwner();return storage==null?0:storage.chargedBytes();}
    public long retainedCpuBytes(){checkOwner();return (cpu==null?0:cpu.retainedBytes())+(staging==null?0:staging.capacity())+(indices==null?0:indices.capacity()*4L);}
    public boolean closeComplete(){checkOwner();return closeComplete;}
    public boolean uploaded(){checkOwner();return uploaded&&!failed&&!closed;}
    private void requireDrawable(){checkOwner();if(!uploaded||failed||closed)throw new IllegalStateException("Native GPU piece is not live and complete");if(storage!=null)storage.requireDrawable();}
    private void requireLease(){lease.requireCurrent();}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native GPU owner thread required");}
    private void check(String phase){StrictGlErrors.check("native-distance."+phase);}
    @Override public void close(){
        checkOwner();if(closeComplete)return;closed=true;Throwable failure=null;
        if(storage!=null)try{storage.close();}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);}
        try{freeStaging();}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);}
        if(failure==null&&(storage==null||storage.closeComplete())&&staging==null&&indices==null){cpu=null;closeComplete=true;}
        SceneBuildStepper.rethrow(failure);
    }
}
