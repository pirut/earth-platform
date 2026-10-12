package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.mixin.SceneBufferBuilderAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.lighting.LightPipelineAwareModelBlockRenderer;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;
import org.lwjgl.system.MemoryUtil;

/**
 * Installed-backend QA only. Streams a whole requested page through the real baseline native renderer,
 * independently compares every output byte to the pure worker, and never allocates/uploads a GPU object.
 * It intentionally requires active transformed Minecraft/Iris, so an ordinary unit test cannot fake this gate.
 */
public final class NativeVisualPageOracle implements AutoCloseable {
    public record Progress(int visitedBlocks, int nativeCallbacks, int totalBlocks, long comparedBytes,
                           int comparedPieces, long elapsedNanos, long maxCallbackNanos, boolean complete,
                           boolean renderingLevel) {}
    private static final BlockState[] PALETTE = {Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),Blocks.ANDESITE.defaultBlockState(),Blocks.TUFF.defaultBlockState(),Blocks.SNOW_BLOCK.defaultBlockState()};
    private final Thread owner=Thread.currentThread();
    private final NativeVisualCapsuleCapture.Lease lease;
    private final NativeVisualCpuBuilder.Input input;
    private final SceneNativeShaderContract.Capture shader;
    private final VertexFormat format;
    private final boolean disableDirectionalShading;
    private final NativeVisualCpuBuilder.Cancellation cancellation=new NativeVisualCpuBuilder.Cancellation();
    private final NativeVisualCpuBuilder worker;
    private final BlockRenderDispatcher renderer;
    private final View view=new View();
    private final boolean renderingLevel;
    private NativeVisualCpuPiece piece;
    private ByteBuffer workerBytes;
    private int visited,callbacks,pieces;
    private long compared,maxCallback;
    private boolean closed,failed,complete,stepping;
    public NativeVisualPageOracle(NativeVisualCapsuleCapture.Sealed sealed,NativeVisualCpuBuilder.Input input){
        checkOwner();this.lease=Objects.requireNonNull(sealed).lease();lease.requireCurrent();this.input=Objects.requireNonNull(input);
        if(!lease.capsuleIdentity().equals(sealed.capsule().identity()))throw new IllegalArgumentException("Oracle capsule/lease mismatch");
        shader=lease.shaderContract();format=lease.format();disableDirectionalShading=shader.values().disableDirectionalShading();
        worker=new NativeVisualCpuBuilder(input,sealed.capsule(),cancellation);renderer=Minecraft.getInstance().getBlockRenderer();
        renderingLevel=net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel;
    }
    public Progress step(int maxBlocks,long maxNanos){
        checkOwner();if(closed||failed||complete||stepping||maxBlocks<1||maxNanos<1)throw new IllegalStateException("Oracle is not available");
        stepping=true;long start=System.nanoTime();int limit=(int)Math.min(totalBlocks(),(long)visited+maxBlocks);Throwable failure=null;
        try{
            requireCurrent();clearCaches();
            while(visited<limit){
                if(visited%4096!=0&&System.nanoTime()-start>=maxNanos)break;
                int member=input.member(visited/4096),cell=visited%4096;
                int x=(member&3)*16+(cell&15),z=((member>>>2)&3)*16+((cell>>>4)&15),y=(member>>>4)*16+(cell>>>8);
                int palette=input.state(x,y,z);
                if(palette!=0){long before=System.nanoTime();renderAndCompare(PALETTE[palette],x,y,z);callbacks++;maxCallback=Math.max(maxCallback,System.nanoTime()-before);}
                visited++;
            }
            if(visited==totalBlocks()){
                // Consume the final empty completion certificate too. More worker bytes mean an extra face.
                while(piece==null||!piece.completesRequest()){
                    if(workerBytes!=null&&workerBytes.hasRemaining())throw new IllegalStateException("Worker contains extra native face bytes");
                    nextPiece();
                }
                if(workerBytes.hasRemaining())throw new IllegalStateException("Worker contains extra terminal face bytes");
                complete=true;
            }
        }catch(RuntimeException|Error error){failed=true;failure=error;}
        finally{
            try{clearCaches();requireCurrent();}catch(RuntimeException|Error error){failed=true;failure=SceneBuildStepper.append(failure,error);}
            stepping=false;
        }
        SceneBuildStepper.rethrow(failure);
        return new Progress(visited,callbacks,totalBlocks(),compared,pieces,Math.max(0,System.nanoTime()-start),maxCallback,complete,renderingLevel);
    }
    /**
     * Negative-oracle boundary used only by the host's unknown-halo fixture (index 12).
     * A normal step never converts unknown data into successful completion.
     */
    public void requireUnknownHaloRefusal(int maxBlocks,long maxNanos){
        try{step(maxBlocks,maxNanos);}
        catch(RuntimeException|Error failure){
            if(isExpectedUnknownHaloFailure(failure,view.unknown,input))return;
            throw failure;
        }
        throw new IllegalStateException("Unknown native oracle did not refuse");
    }
    static boolean isExpectedUnknownHaloFailure(Throwable failure,NativeVisualCpuBuilder.UnknownDependency expected,
                                                 NativeVisualCpuBuilder.Input input){
        if(expected==null||!expected.matches(input,-1,0,0)||expected.getCause()!=null)return false;
        // Pinned Minecraft can report at model and dispatcher boundaries. Never walk arbitrary causes.
        for(int wrappers=0;wrappers<=2;wrappers++){
            if(failure==null||failure.getSuppressed().length!=0)return false;
            if(failure==expected)return true;
            if(wrappers==2||failure.getClass()!=ReportedException.class)return false;
            ReportedException reported=(ReportedException)failure;
            CrashReport report=reported.getReport();
            if(report==null||report.getClass()!=CrashReport.class)return false;
            Throwable cause=reported.getCause();
            if(cause==failure||cause!=report.getException())return false;
            failure=cause;
        }
        return false;
    }
    private void renderAndCompare(BlockState state,int x,int y,int z){
        BlockPos pos=new BlockPos(input.originX()+x,input.originY()+y,input.originZ()+z);
        view.begin(pos);RandomSource random=RandomSource.create(state.getSeed(pos));
        int layers=0;for(var layer:renderer.getBlockModel(state).getRenderTypes(state,random,ModelData.EMPTY)){
            if(layer!=RenderType.solid())throw new IllegalStateException("Native baseline layer changed");layers++;
        }
        if(layers!=1)throw new IllegalStateException("Missing native baseline solid layer");
        try(var storage=new Storage()){
            var builder=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.BLOCK);
            if(!(builder instanceof SceneBufferBuilderAccessor actual))throw new IllegalStateException("Transformed native buffer accessor absent");
            shader.requireFormat(actual.earthplatform$format(),actual.earthplatform$vertexSize());
            MeshData mesh=null;PoseStack pose=new PoseStack();pose.translate(x&15,y&15,z&15);
            try{
                try{shader.beginBlock(builder,state,x&15,y&15,z&15);renderer.renderBatched(state,pos,view,pose,builder,true,random,ModelData.EMPTY,RenderType.solid());}
                finally{shader.endBlock(builder);}
                mesh=builder.build();view.requireValid();
                if(mesh==null)return;
                var draw=mesh.drawState();
                if(draw.format()!=format||draw.mode()!=VertexFormat.Mode.QUADS||draw.vertexCount()%4!=0||draw.vertexCount()>24||mesh.indexBuffer()!=null)
                    throw new IllegalStateException("Native baseline emitted unsupported geometry");
                ByteBuffer actualBytes=mesh.vertexBuffer().order(ByteOrder.nativeOrder());
                if(actualBytes.remaining()!=draw.vertexCount()*52)throw new IllegalStateException("Native baseline size mismatch");
                // Baseline uses cube-local beginBlock and positions. Only these three floats are translated.
                int base=actualBytes.position();
                for(int vertex=0;vertex<draw.vertexCount();vertex++){
                    int offset=base+vertex*52;
                    actualBytes.putFloat(offset,actualBytes.getFloat(offset)+(x&~15));
                    actualBytes.putFloat(offset+4,actualBytes.getFloat(offset+4)+(y&~15));
                    actualBytes.putFloat(offset+8,actualBytes.getFloat(offset+8)+(z&~15));
                }
                while(actualBytes.hasRemaining()){
                    if(workerBytes==null||!workerBytes.hasRemaining())nextPiece();
                    if(!workerBytes.hasRemaining())throw new IllegalStateException("Worker is missing native baseline face bytes");
                    byte expected=actualBytes.get(),got=workerBytes.get();
                    if(expected!=got)throw new IllegalStateException("Whole-page native byte mismatch at "+pos+", page byte="+compared+", expected="+(expected&255)+", actual="+(got&255));
                    compared++;
                }
            }finally{if(mesh!=null)mesh.close();}
        }
    }
    private void nextPiece(){
        if(piece!=null&&piece.completesRequest())throw new IllegalStateException("Native worker completed before baseline");
        piece=worker.nextPiece();pieces++;
        workerBytes=ByteBuffer.allocate(piece.byteLength());piece.copyTo(workerBytes);workerBytes.flip();
        if(!piece.capsuleIdentity().equals(lease.capsuleIdentity())||!piece.requestStamp().equals(input.requestStamp()))throw new IllegalStateException("Oracle worker stamp mismatch");
    }
    private int totalBlocks(){return input.memberCount()*4096;}
    private void requireCurrent(){if(closed)throw new IllegalStateException("Native oracle was cancelled");lease.requireCurrent();if(renderingLevel!=net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel)throw new IllegalStateException("Oracle render phase changed");}
    private void clearCaches(){
        ModelBlockRenderer.clearCache();if(renderer.getModelRenderer()instanceof LightPipelineAwareModelBlockRenderer nativeRenderer){nativeRenderer.getQuadLighter(false).reset();nativeRenderer.getQuadLighter(true).reset();}
    }
    public long retainedBytes(){checkOwner();return input.retainedBytes()+(piece==null?0:piece.retainedBytes())+(workerBytes==null?0:workerBytes.capacity())+4096;}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native oracle requires its original render owner");RenderSystem.assertOnRenderThread();}
    @Override public void close(){checkOwner();closed=true;cancellation.cancel();if(stepping)throw new IllegalStateException("Retry native oracle cleanup after its active callback returns");piece=null;workerBytes=null;clearCaches();}
    private static final class Storage extends ByteBufferBuilder{
        private int used;Storage(){super(6*208);}
        @Override public long reserve(int bytes){if(bytes!=52||used>6*208-bytes)throw new IllegalStateException("Native baseline exceeded six-quads-per-block bound");long p=super.reserve(bytes);used+=bytes;MemoryUtil.memSet(p,0,bytes);return p;}
    }
    private final class View implements BlockAndTintGetter{
        private BlockPos current;private int reads;private boolean poisoned;
        private NativeVisualCpuBuilder.UnknownDependency unknown;
        void begin(BlockPos pos){current=pos;reads=0;poisoned=false;unknown=null;}
        void requireValid(){if(poisoned)throw new IllegalStateException("Native baseline getter was poisoned");}
        private IllegalStateException unsupported(String detail){poisoned=true;return new IllegalStateException("Unsupported native baseline getter: "+detail);}
        private void check(BlockPos pos){
            if(++reads>8192||Math.abs((long)pos.getX()-current.getX())>1||Math.abs((long)pos.getY()-current.getY())>1||Math.abs((long)pos.getZ()-current.getZ())>1)
                throw unsupported("outside one-cell/8192-read budget");
        }
        @Override public BlockState getBlockState(BlockPos pos){
            check(pos);
            try{return PALETTE[input.state(pos.getX()-input.originX(),pos.getY()-input.originY(),pos.getZ()-input.originZ())];}
            catch(NativeVisualCpuBuilder.UnknownDependency failure){if(unknown==null)unknown=failure;throw failure;}
        }
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){getBlockState(pos);throw unsupported("entity");}
        @Override public ModelData getModelData(BlockPos pos){getBlockState(pos);return ModelData.EMPTY;}
        @Override public AuxiliaryLightManager getAuxLightManager(ChunkPos pos){throw unsupported("auxiliary light");}
        @Override public int getHeight(){return SceneNeighborhood.HEIGHT;}
        @Override public int getMinBuildHeight(){return SceneNeighborhood.MIN_Y;}
        @Override public int getMaxLightLevel(){return 15;}
        @Override public float getShade(Direction direction,boolean shade){return !shade||disableDirectionalShading?1:switch(direction){case DOWN->.5f;case UP->1;case NORTH,SOUTH->.8f;case WEST,EAST->.6f;};}
        @Override public float getShade(float x,float y,float z,boolean shade){throw unsupported("experimental non-axis shade");}
        @Override public LevelLightEngine getLightEngine(){throw unsupported("propagated light engine");}
        @Override public int getBrightness(LightLayer layer,BlockPos pos){getBlockState(pos);return layer==LightLayer.SKY?15:0;}
        @Override public int getRawBrightness(BlockPos pos,int ambient){getBlockState(pos);return 15-Math.min(15,Math.max(0,ambient));}
        @Override public int getBlockTint(BlockPos pos,ColorResolver resolver){getBlockState(pos);throw unsupported("tint");}
    }
}
