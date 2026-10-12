package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageParityFixture;
import io.earthplatform.minecraft.survival.render.SurvivalPageGeometry;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/**
 * INTERNAL, explicitly invoked loaded-client diagnostic. No launch/event hook or production GPU
 * publication is installed. The reference is unchanged SceneMesh native compilation/upload followed
 * by real VBO readback. The host's existing QUADS index storage must already cover the reference;
 * this probe never grows that persistent Minecraft owner. Synthetic layouts use real loaded models; source-page mode measures actual
 * generated/edited page values. This probe never substitutes canned quads or capsule bytes.
 *
 * Retain this cleanup owner before run(). On any failure, call close() and retain it if close fails.
 * Neither a successful probe nor its CPU-only page data certifies a complete overlay, a selected
 * distance, page GPU publication, a camera frame, DH complement, or shader-pack compatibility.
 */
public final class SurvivalNativePageParityProbe implements AutoCloseable {
    private static final long METADATA_BYTES = 256L << 10;
    // 3 fixed native writers + 1 readback, two simultaneously retained footprint allowances
    // while Recorder -> sealed SceneReadFootprint is copied, and 64 KiB for owner/shader/scratch
    // metadata. SceneMesh currently closes writers before sealing; retaining both allowances
    // anyway is conservative. This reserves 12,648,448 bytes INSIDE the existing 16 MiB pool.
    private static final long REFERENCE_SCRATCH_BYTES = Math.addExact(
            Math.multiplyExact(4L, SceneMesh.MAX_VERTEX_BYTES),
            Math.addExact(Math.multiplyExact(2L, Math.multiplyExact((long) SceneReadFootprint.MAX_READS,
                    SceneReadFootprint.ACCOUNTED_BYTES_PER_READ)), 64L << 10));
    private final Thread owner = Thread.currentThread();
    private final SurvivalNativePageParityFixture fixture;
    private final SceneNativeShaderContract.Capture capture;
    private final NativeDistanceCpuBudget cpu;
    private final NativeVisualBudget gpu;
    private final Map<Key,Accumulator> reference = new LinkedHashMap<>(), grouped = new LinkedHashMap<>();
    private final List<NativeVisualBudget.Reservation> referenceGpu = new ArrayList<>(3);
    private NativeDistanceCpuBudget.Reservation metadata, scratch;
    private ByteBuffer readback;
    private SceneMesh.Build referenceBuild;
    private SceneMesh referenceMesh;
    private SceneGlRestoreOwner restore;
    private ReferenceCleanup referenceCleanup;
    private SurvivalNativePageCompiler.Build pageBuild;
    private SurvivalNativePageCompiler.CompiledPage page;
    private boolean started, closed, closeComplete;
    private long peakWorkerBytes, peakCpuBytes, peakGpuBytes;
    private long referenceReservationCalls, referenceBulkQuadReservationCalls, referenceReservedVertices, referenceReservedBytes;
    private int peakGpuOwners;

    public record Element(String name,int offset,int bytes) {
        public Element {
            Objects.requireNonNull(name);
            if (name.isEmpty() || offset<0 || bytes<1 || offset>Integer.MAX_VALUE-bytes)
                throw new IllegalArgumentException("Invalid actual vertex element span");
        }
    }
    /** Padding is derived exclusively from the actual immutable VertexFormat element spans. */
    public record Schema(int stride,List<Element> elements,List<Integer> paddingOffsets) {
        public Schema {
            if (stride<12 || stride>SurvivalPageGeometry.MAX_PIECE_BYTES/4) throw new IllegalArgumentException("Invalid native stride");
            elements=List.copyOf(elements);paddingOffsets=List.copyOf(paddingOffsets);
            boolean[] occupied=new boolean[stride];
            if(elements.size()>stride)throw new IllegalArgumentException("Too many native attributes");
            for(var element:elements) {
                if(element.offset>stride-element.bytes)throw new IllegalArgumentException("Native attribute escapes stride");
                for(int i=element.offset;i<element.offset+element.bytes;i++) {
                    if(occupied[i])throw new IllegalArgumentException("Overlapping native attributes");
                    occupied[i]=true;
                }
            }
            var expected=new ArrayList<Integer>();for(int i=0;i<stride;i++)if(!occupied[i])expected.add(i);
            if(!expected.equals(paddingOffsets))throw new IllegalArgumentException("Padding must be exactly the complement of native attributes");
            if(elements.isEmpty() || !elements.getFirst().equals(new Element("Position",0,12)))
                throw new IllegalArgumentException("Native reference requires the actual three-float Position first");
        }
        public static Schema capture(VertexFormat format) {
            Objects.requireNonNull(format);
            var names=format.getElementAttributeNames();var nativeElements=format.getElements();
            if(nativeElements.isEmpty()||!nativeElements.getFirst().type().name().equals("FLOAT"))
                throw new IllegalStateException("Native Position is not the exact float attribute");
            if(names.size()!=nativeElements.size())throw new IllegalStateException("Native format names/elements differ");
            var elements=new ArrayList<Element>();var occupied=new boolean[format.getVertexSize()];
            for(int i=0;i<names.size();i++) {
                var element=nativeElements.get(i);int offset=format.getOffset(element),bytes=element.byteSize();
                elements.add(new Element(names.get(i),offset,bytes));
                if(offset<0 || offset>occupied.length-bytes)throw new IllegalStateException("Native format element escapes stride");
                for(int j=offset;j<offset+bytes;j++)occupied[j]=true;
            }
            var padding=new ArrayList<Integer>();for(int i=0;i<occupied.length;i++)if(!occupied[i])padding.add(i);
            return new Schema(format.getVertexSize(),elements,padding);
        }
    }
    private record Key(int member,SurvivalNativePageCompiler.Layer layer) {}
    public record Comparison(int member,SurvivalNativePageCompiler.Layer layer,long vertices,
            String referenceRawSha256,String pageRawSha256,String referenceAttributeSha256,String pageAttributeSha256) {}
    /** Actual successful native allocator observations; arithmetic-only tests do not mint installed evidence. */
    public record ReservationEvidence(long referenceCalls,long referenceBulkQuadCalls,long pageCalls,long pageBulkQuadCalls,
            long referenceVertices,long referenceBytes,long pageVertices,long pageBytes) {
        public void requireParity(int stride, boolean shaderLayout) {
            if (stride != (shaderLayout ? 52 : 32) || referenceVertices != pageVertices || referenceBytes != pageBytes
                    || pageVertices < 0 || pageVertices % 4 != 0 || Math.multiplyExact(pageVertices, stride) != pageBytes)
                throw new IllegalStateException("Native allocation bytes/vertices differ from exact complete-quad parity");
            requireCounts(referenceCalls, referenceBulkQuadCalls, referenceVertices);
            requireCounts(pageCalls, pageBulkQuadCalls, pageVertices);
            if (!shaderLayout && pageVertices > 0 && (referenceBulkQuadCalls == 0 || pageBulkQuadCalls == 0))
                throw new IllegalStateException("Nonempty DEFAULT32 parity must exercise the installed native bulk-quad reservation path in both compilers");
        }
        private static void requireCounts(long calls,long bulk,long vertices) {
            if (calls < 0 || bulk < 0 || bulk > calls || calls > vertices || calls < vertices / 4
                    || bulk > vertices / 4 || vertices - calls != 3 * bulk || (vertices == 0 && calls != 0))
                throw new IllegalStateException("Invalid observed native reservation counts");
        }
    }
    public record Report(String fixtureKind,boolean shaderLayout,Schema schema,int members,int dependencies,long wireBytes,
            long exactCompilerInputCharge,long nativeVertexBytes,long nativeIndexBytes,int pagePieces,long retainedPageCpuBytes,
            List<SurvivalNativePageCompiler.LayerStats> layers,List<Comparison> comparisons,
            long peakWorkerBytes,long peakCpuBytes,long peakGpuBytes,int peakGpuOwners,
            boolean memberScopesVerified,boolean cpuReturnedToBaseline,boolean gpuReturnedToBaseline,
            ReservationEvidence reservations) {
        public Report { layers=List.copyOf(layers);comparisons=List.copyOf(comparisons); }
    }
    public SurvivalNativePageParityProbe(SurvivalNativePageParityFixture fixture,SceneNativeShaderContract.Capture capture,
            NativeDistanceCpuBudget cpu,NativeVisualBudget gpu) {
        RenderSystem.assertOnRenderThread();
        this.fixture=Objects.requireNonNull(fixture);this.capture=Objects.requireNonNull(capture);
        this.cpu=Objects.requireNonNull(cpu);this.gpu=Objects.requireNonNull(gpu);
    }
    /** One execution only; shaders/default, AO mode, resource pack and pipeline must already be configured by the host. */
    public Report run(int maxBlocks,long maxNanos) {
        checkOwner();if(started||closed)throw new IllegalStateException("Native parity probe is single-use");started=true;
        if(maxBlocks<1||maxNanos<1)throw new IllegalArgumentException("Positive parity slice budgets required");
        var beforeCpu=cpu.evidence();var beforeGpu=gpu.evidence();
        StrictGlErrors.check("survival-parity.probe.entry");
        capture.requireCurrent();
        if(capture.values().stride()!=(capture.values().shaders()?52:32))throw new IllegalStateException("Parity requires default32 or pinned Iris52 layout");
        var schema=Schema.capture((VertexFormat)capture.values().format());
        metadata=cpu.reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD,METADATA_BYTES);samplePeaks();
        var planned=fixture.page();var cubes=fixture.cubes();
        boolean scopesVerified = fixture.kind()!=SurvivalNativePageParityFixture.Kind.SOURCE_PAGE;
        if(scopesVerified)verifyMemberScopes();
        var members=planned.members().stream().sorted(Comparator.comparingInt(p->SurvivalPageGeometry.memberIndex(planned.address(),p))).toList();
        for(var member:members)for(var layer:SurvivalNativePageCompiler.Layer.values()) {
            var key=new Key(SurvivalPageGeometry.memberIndex(planned.address(),member),layer);
            reference.put(key,new Accumulator(schema));grouped.put(key,new Accumulator(schema));
        }
        scratch=cpu.reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD,REFERENCE_SCRATCH_BYTES);samplePeaks();
        readback=MemoryUtil.memAlloc(SurvivalPageGeometry.MAX_PIECE_BYTES);
        var neighborhood=fixture.neighborhood(capture.values().disableDirectionalShading());
        for(CubePos member:members) {
            // Existing SceneMesh does not use the distance ledger itself. Precharge all three possible
            // VBO owners, their maximum vertex storage and explicit sequential-index storage here.
            for(int i=0;i<3;i++)referenceGpu.add(gpu.reserve(SceneMesh.MAX_VERTEX_BYTES,SceneMesh.MAX_QUADS*6*Integer.BYTES));
            samplePeaks();
            // Capture before upload: Iris tracks native VertexBuffer.bind independently of actual GL.
            var referenceBindings=new ReferenceBindings(capture.values().shaders());
            referenceCleanup=new ReferenceCleanup(() -> {
                if(referenceBuild!=null)referenceBuild.close();
                if(referenceMesh!=null)referenceMesh.close();
            },referenceBindings::restore,
                    ()->StrictGlErrors.check("survival-parity.reference.delete.entry"),
                    ()->StrictGlErrors.check("survival-parity.reference.delete.exit"),
                    ()->StrictGlErrors.check("survival-parity.reference.restore.entry"),
                    ()->StrictGlErrors.check("survival-parity.reference.restore.exit"));
            referenceBuild=SceneMesh.begin(fixture.guard(),cubes.get(member),neighborhood,capture);
            while(!referenceBuild.isComplete()) {
                referenceBuild.step(maxBlocks,maxNanos);
                StrictGlErrors.check("survival-parity.reference.cpu.slice");
            }
            // The unchanged reference uses Minecraft's process-owned sequential index buffer. Never
            // create/grow that unrelated persistent owner and then pretend its storage was deleted.
            int emitted=((SceneNativeShaderContract.Budget)field(referenceBuild,"budget")).vertices;
            int requiredIndices=Math.multiplyExact(emitted/4,6);
            if(!RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).hasStorage(requiredIndices))
                throw new IllegalStateException("Native parity requires preexisting host QUADS index storage for "
                        +requiredIndices+" indices; establish/account the host baseline before this diagnostic");
            referenceMesh=referenceBuild.finish();
            StrictGlErrors.check("survival-parity.reference.upload.exit");
            referenceCleanup.restoreHost();
            var allocation=(SceneNativeShaderContract.Budget)field(referenceBuild,"budget");
            referenceReservationCalls=Math.addExact(referenceReservationCalls,allocation.reservationCalls);
            referenceBulkQuadReservationCalls=Math.addExact(referenceBulkQuadReservationCalls,allocation.bulkQuadReservationCalls);
            referenceReservedVertices=Math.addExact(referenceReservedVertices,allocation.vertices);
            referenceReservedBytes=Math.addExact(referenceReservedBytes,allocation.bytes);
            referenceBuild.close();referenceBuild=null;
            readReference(member,schema);
            closeReference(); // Charge survives until the actual native mesh and restoration are closed.
        }
        freeReadback();scratch.releaseAfterPhysicalDrain();scratch=null;
        // No reference SceneMesh scratch/VBO/readback overlaps the grouped compiler's retained CPU peak.
        StrictGlErrors.check("survival-parity.page.cpu.entry");
        pageBuild=SurvivalNativePageCompiler.begin(planned,cubes,fixture.guard(),
                SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,capture.values().disableDirectionalShading(),capture,cpu);
        long inputCharge=pageBuild.retainedCpuBytes();samplePeaks();
        long expectedInput=32L*1024+capture.metadataBytes();
        for(var cube:cubes.values())expectedInput=Math.addExact(expectedInput,1024+CubePos.VOLUME*8L+cube.snapshot().sectionBytes());
        if(inputCharge!=expectedInput)throw new IllegalStateException("Compiler input reservation differs from measured dependency payloads");
        while(!pageBuild.complete()){pageBuild.step(maxBlocks,maxNanos);StrictGlErrors.check("survival-parity.page.cpu.slice");samplePeaks();}
        page=pageBuild.finish();pageBuild.close();pageBuild=null;page.requireCurrent();samplePeaks();
        StrictGlErrors.check("survival-parity.page.cpu.exit");
        if(page.completedMemberMask()!=planned.memberMask())throw new IllegalStateException("Native parity omitted selected known-empty members");
        int pieceCount=page.pieces().size();long bytes=0,indexBytes=0;
        for(var piece:page.pieces()) {
            if(!Schema.capture(piece.format()).equals(schema))throw new IllegalStateException("Grouped piece schema differs from its native reference");
            bytes=Math.addExact(bytes,piece.vertexBytes());indexBytes=Math.addExact(indexBytes,piece.indexBytes());
            ByteBuffer source=piece.vertexBuffer();
            for(var range:piece.memberRanges()) {
                var part=source.duplicate();part.position(Math.multiplyExact(range.firstVertex(),piece.stride()));
                part.limit(Math.multiplyExact(range.firstVertex()+range.vertices(),piece.stride()));
                grouped.get(new Key(range.member(),piece.layer())).add(part.slice(),range.member(),false);
            }
        }
        var comparisons=new ArrayList<Comparison>();
        for(var entry:reference.entrySet()) {
            Key key=entry.getKey();var expected=entry.getValue();var actual=grouped.get(key);
            String expectedHash=expected.attributes(),actualHash=actual.attributes();
            if(expected.vertices!=actual.vertices||!expectedHash.equals(actualHash))
                throw new IllegalStateException("Actual native page byte parity failed for member="+key.member+" layer="+key.layer
                        +" referenceVertices="+expected.vertices+" pageVertices="+actual.vertices
                        +" reference="+expectedHash+" page="+actualHash);
            comparisons.add(new Comparison(key.member,key.layer,actual.vertices,expected.raw(),actual.raw(),expectedHash,actualHash));
        }
        var layers=page.layerStats();
        if(fixture.kind()==SurvivalNativePageParityFixture.Kind.SPARSE_STATES||fixture.kind()==SurvivalNativePageParityFixture.Kind.FULL_PAGE_STATES)
            for(var layer:layers)if(layer.vertices()==0)throw new IllegalStateException("State fixture did not emit native "+layer.layer()+"; verify actual Fancy leaves/cutout resources");
        if(fixture.kind()==SurvivalNativePageParityFixture.Kind.CUTOUT_ROLLOVER
                &&(bytes<=SurvivalPageGeometry.MAX_PIECE_BYTES||pieceCount<2))
            throw new IllegalStateException("Real loaded cutout models did not exercise whole-quad page rollover");
        long retained=page.retainedCpuBytes(),wire=fixture.wireBytes();String kind=fixture.kind().name();
        boolean shaderLayout=capture.values().shaders();int dependencies=cubes.size();
        var reservations=new ReservationEvidence(referenceReservationCalls,referenceBulkQuadReservationCalls,
                page.reservationCalls(),page.bulkQuadReservationCalls(),referenceReservedVertices,referenceReservedBytes,
                bytes/schema.stride(),bytes);
        reservations.requireParity(schema.stride(),shaderLayout);
        page.requireCurrent();close();
        var afterCpu=cpu.evidence();var afterGpu=gpu.evidence();
        boolean drainedCpu=afterCpu.bytes()==beforeCpu.bytes()&&afterCpu.reservations()==beforeCpu.reservations()
                &&afterCpu.pools().equals(beforeCpu.pools());
        boolean drainedGpu=afterGpu.bytes()==beforeGpu.bytes()&&afterGpu.pieces()==beforeGpu.pieces();
        if(!drainedCpu||!drainedGpu)throw new IllegalStateException("Actual parity owners did not physically drain to baseline");
        StrictGlErrors.check("survival-parity.probe.exit");
        return new Report(kind,shaderLayout,schema,members.size(),dependencies,wire,inputCharge,bytes,indexBytes,pieceCount,
                retained,layers,comparisons,peakWorkerBytes,peakCpuBytes,peakGpuBytes,peakGpuOwners,scopesVerified,drainedCpu,drainedGpu,reservations);
    }
    /** Real loaded-model regression: no retained facade may read terrain between either solid or air slices. */
    private void verifyMemberScopes() {
        var planned=fixture.page();var cubes=fixture.cubes();
        CubePos first=planned.members().stream().min(Comparator.comparingInt(p->SurvivalPageGeometry.memberIndex(planned.address(),p))).orElseThrow();
        for(int blocks:new int[]{1,2}) {
            pageBuild=SurvivalNativePageCompiler.begin(planned,cubes,fixture.guard(),
                    SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,capture.values().disableDirectionalShading(),capture,cpu);
            pageBuild.step(blocks,Long.MAX_VALUE);StrictGlErrors.check("survival-parity.scope.cpu.slice");samplePeaks();
            var bridge=(SurvivalNativeCompilerBridge)field(pageBuild,"bridge");
            boolean refused=false;
            try {
                bridge.view().getBlockState(new net.minecraft.core.BlockPos(Math.toIntExact(first.minBlockX()),
                        Math.toIntExact(first.minBlockY()),Math.toIntExact(first.minBlockZ())));
            } catch(IllegalStateException expected) {
                if(!expected.getMessage().contains("outside an active member callback"))throw expected;
                refused=true;
            }
            if(!refused)throw new IllegalStateException("Retained native facade accepted a read between page slices");
            boolean poisoned=false;
            try{pageBuild.step(1,Long.MAX_VALUE);}catch(IllegalStateException expected){poisoned=true;}
            if(!poisoned)throw new IllegalStateException("Between-slice native read did not poison subsequent compilation");
            pageBuild.close();pageBuild=null;
            StrictGlErrors.check("survival-parity.scope.cpu.close");
        }
    }
    private void readReference(CubePos member,Schema schema) {
        capture.requireCurrent();
        if(!referenceMesh.isCurrent())throw new IllegalStateException("Native reference mesh became stale before readback");
        Object value=field(referenceMesh,"passes");
        if(!(value instanceof List<?> passes))throw new IllegalStateException("Pinned SceneMesh pass field changed");
        for(Object pass:passes) {
            var layer=layer((RenderType)field(pass,"layer"));var buffer=(VertexBuffer)field(pass,"buffer");
            int vertices=(Integer)field(pass,"vertices");int length=Math.multiplyExact(vertices,schema.stride);
            if(!Schema.capture(buffer.getFormat()).equals(schema)||length<1||length>readback.capacity())
                throw new IllegalStateException("Native reference VBO violates its actual schema/charged payload");
            StrictGlErrors.check("survival-parity.readback.entry");var bindings=new ReferenceBindings(capture.values().shaders());
            restore=new SceneGlRestoreOwner(bindings::restore,()->StrictGlErrors.check("survival-parity.restore.before"),
                    ()->StrictGlErrors.check("survival-parity.restore.after"));
            Throwable failure=null;
            try {
                buffer.bind();
                // Position is actual attribute zero, verified by the captured native format.
                int vbo=glGetVertexAttribi(0,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
                if(vbo<=0)throw new IllegalStateException("Native reference has no Position VBO");
                GlStateManager._glBindBuffer(GL_ARRAY_BUFFER,vbo);
                if(glGetBufferParameteri(GL_ARRAY_BUFFER,GL_BUFFER_SIZE)!=length)
                    throw new IllegalStateException("Actual native VBO storage differs from emitted reference bytes");
                readback.clear();readback.limit(length);glGetBufferSubData(GL_ARRAY_BUFFER,0,readback);
                StrictGlErrors.check("survival-parity.readback.bytes");
                int memberIndex=SurvivalPageGeometry.memberIndex(fixture.page().address(),member);
                reference.get(new Key(memberIndex,layer)).add(readback.duplicate(),memberIndex,true);
            } catch(RuntimeException|Error error){failure=error;}
            finally{try{restoreBindings();}catch(RuntimeException|Error error){failure=append(failure,error);}}
            rethrow(failure);
        }
        if(!referenceMesh.isCurrent())throw new IllegalStateException("Native reference changed during VBO readback");
    }
    /** Reflection is restricted to original diagnostic SceneMesh private owners; it never mutates them. */
    private static Object field(Object owner,String name) {
        try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Pinned native parity field unavailable: "+name,error);}
    }
    private static SurvivalNativePageCompiler.Layer layer(RenderType type) {
        if(type==RenderType.solid())return SurvivalNativePageCompiler.Layer.SOLID;
        if(type==RenderType.cutoutMipped())return SurvivalNativePageCompiler.Layer.CUTOUT_MIPPED;
        if(type==RenderType.cutout())return SurvivalNativePageCompiler.Layer.CUTOUT;
        throw new IllegalStateException("Native reference emitted an unsupported terrain layer");
    }
    private static final class Accumulator {
        private final Schema schema;
        private final MessageDigest raw=digest(),attributes=digest();
        private final byte[] vertex;
        private final ByteBuffer word;
        private long vertices;
        private String rawResult,attributeResult;
        Accumulator(Schema schema){this.schema=schema;vertex=new byte[schema.stride];word=ByteBuffer.wrap(vertex).order(ByteOrder.nativeOrder());}
        void add(ByteBuffer source,int member,boolean relocate) {
            if(source.remaining()%schema.stride!=0||source.remaining()>SurvivalPageGeometry.MAX_PIECE_BYTES)
                throw new IllegalArgumentException("Invalid bounded native parity byte range");
            raw.update(source.duplicate());var input=source.duplicate();
            while(input.hasRemaining()) {
                input.get(vertex);canonicalizeVertex(word,schema,member,relocate);attributes.update(vertex);vertices++;
            }
        }
        String raw(){if(rawResult==null)rawResult=HexFormat.of().formatHex(raw.digest());return rawResult;}
        String attributes(){if(attributeResult==null)attributeResult=HexFormat.of().formatHex(attributes.digest());return attributeResult;}
    }
    /** Independent reference normalization. Does not call the production page-position relocation helper. */
    public static void canonicalizeVertex(ByteBuffer ownedVertex,Schema schema,int member,boolean relocate) {
        Objects.requireNonNull(ownedVertex);Objects.requireNonNull(schema);
        if(member<0||member>=64||ownedVertex.remaining()!=schema.stride||ownedVertex.isReadOnly())
            throw new IllegalArgumentException("Exact owned native vertex required for parity normalization");
        var bytes=ownedVertex.slice().order(ByteOrder.nativeOrder());
        if(relocate) {
            int[] shift={(member%4)*16,(member/16)*16,((member/4)%4)*16};
            for(int axis=0;axis<3;axis++) {
                float value=bytes.getFloat(axis*4);
                if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite native reference position");
                bytes.putFloat(axis*4,value+shift[axis]);
            }
        }
        for(int padding:schema.paddingOffsets)bytes.put(padding,(byte)0);
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private void samplePeaks(){var c=cpu.evidence();var g=gpu.evidence();peakWorkerBytes=Math.max(peakWorkerBytes,c.pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD));peakCpuBytes=Math.max(peakCpuBytes,c.bytes());peakGpuBytes=Math.max(peakGpuBytes,g.bytes());peakGpuOwners=Math.max(peakGpuOwners,g.pieces());}
    private void restoreBindings(){if(restore!=null){restore.restore();if(!restore.pending())restore=null;}}
    private void freeReadback(){if(readback!=null){MemoryUtil.memFree(readback);readback=null;}}
    private void closeReference() {
        restoreBindings();
        if(referenceCleanup!=null) {
            referenceCleanup.close();
            referenceCleanup=null;referenceBuild=null;referenceMesh=null;
        }
        for(var reservation:referenceGpu)reservation.releaseAfterPhysicalClose();referenceGpu.clear();
    }
    public boolean closeComplete(){checkOwner();return closeComplete;}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native parity requires its render owner");RenderSystem.assertOnRenderThread();}
    @Override public void close() {
        checkOwner();if(closeComplete)return;closed=true;Throwable failure=null;
        try{closeReference();}catch(RuntimeException|Error error){failure=error;}
        if(pageBuild!=null)try{pageBuild.close();pageBuild=null;}catch(RuntimeException|Error error){failure=append(failure,error);}
        if(page!=null)try{page.close();page=null;}catch(RuntimeException|Error error){failure=append(failure,error);}
        try{freeReadback();}catch(RuntimeException|Error error){failure=append(failure,error);}
        try{StrictGlErrors.check("survival-parity.probe.close.exit");}catch(RuntimeException|Error error){failure=append(failure,error);}
        if(failure==null) {
            if(scratch!=null){scratch.releaseAfterPhysicalDrain();scratch=null;}
            reference.clear();grouped.clear();
            if(metadata!=null){metadata.releaseAfterPhysicalDrain();metadata=null;}closeComplete=true;
        }
        rethrow(failure);
    }
    /** One retained scope owns upload/readback/delete restoration, including partial cleanup failures. */
    static final class ReferenceCleanup implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final Runnable physicalClose,restoreHost,deleteBefore,deleteAfter,restoreBefore,restoreAfter;
        private SceneGlRestoreOwner pendingRestore;
        private boolean physicalComplete,complete,active;
        ReferenceCleanup(Runnable physicalClose,Runnable restoreHost,Runnable deleteBefore,Runnable deleteAfter,
                Runnable restoreBefore,Runnable restoreAfter) {
            this.physicalClose=Objects.requireNonNull(physicalClose);this.restoreHost=Objects.requireNonNull(restoreHost);
            this.deleteBefore=Objects.requireNonNull(deleteBefore);this.deleteAfter=Objects.requireNonNull(deleteAfter);
            this.restoreBefore=Objects.requireNonNull(restoreBefore);this.restoreAfter=Objects.requireNonNull(restoreAfter);
        }
        void restoreHost() {
            checkOwner();
            if(pendingRestore==null)pendingRestore=new SceneGlRestoreOwner(restoreHost,restoreBefore,restoreAfter);
            pendingRestore.restore();
            if(!pendingRestore.pending())pendingRestore=null;
        }
        boolean complete(){checkOwner();return complete;}
        private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Reference cleanup requires its render owner");}
        @Override public void close() {
            checkOwner();if(complete)return;
            if(active)throw new IllegalStateException("Reference cleanup is already running");
            active=true;
            try {
                Throwable failure=null;
                try{deleteBefore.run();}catch(RuntimeException|Error error){failure=error;}
                if(!physicalComplete)try{physicalClose.run();physicalComplete=true;}
                    catch(RuntimeException|Error error){failure=append(failure,error);}
                try{deleteAfter.run();}catch(RuntimeException|Error error){failure=append(failure,error);}
                try{restoreHost();}catch(RuntimeException|Error error){failure=append(failure,error);}
                rethrow(failure);complete=true;
            } finally {active=false;}
        }
    }

    /** Preserve both actual bindings and the installed Iris native-buffer cache, even with shaders off. */
    static final class ReferenceBindings extends SceneMesh.BufferBindings {
        final int elementBuffer=glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
        private final IrisBufferCache iris;
        ReferenceBindings(boolean requireIris) {
            iris=IrisBufferCache.captureIfPresent(requireIris);
            StrictGlErrors.check("survival-parity.reference.bindings.capture");
        }
        @Override void restore() {
            Throwable failure=null;
            // Native bind/unbind updates Iris.current. Never overwrite Iris.saved, which may belong to an outer pass.
            try{if(iris!=null)iris.restore();}catch(RuntimeException|Error error){failure=error;}
            try {
                super.restore();
                if(iris!=null)iris.verify();
                if(glGetInteger(GL_VERTEX_ARRAY_BINDING)!=vao||glGetInteger(GL_ARRAY_BUFFER_BINDING)!=arrayBuffer
                        ||glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING)!=elementBuffer)
                    throw new IllegalStateException("Reference host VAO/VBO/EBO bindings did not restore exactly");
            }catch(RuntimeException|Error error){failure=append(failure,error);}
            rethrow(failure);
        }
    }

    /** Pinned diagnostic-only read access; restore uses native APIs, never reflective mutation. */
    static final class IrisBufferCache {
        private static final String HELPER="net.irisshaders.iris.helpers.VertexBufferHelper";
        private final Field currentField,savedField;
        private final VertexBuffer current,saved;
        private IrisBufferCache() {
            currentField=cacheField("current");savedField=cacheField("saved");
            current=read(currentField);saved=read(savedField);
            requireLive(current);requireLive(saved);
        }
        static IrisBufferCache captureIfPresent(boolean required) {
            boolean installed=false;
            for(var type:VertexBuffer.class.getInterfaces())if(type.getName().equals(HELPER))installed=true;
            if(!installed) {
                var mods=net.neoforged.fml.ModList.get();
                if(required||(mods!=null&&mods.isLoaded("iris")))throw new IllegalStateException("Pinned Iris native VertexBuffer cache mixin is absent");
                return null;
            }
            return new IrisBufferCache();
        }
        private static Field cacheField(String name) {
            try {
                Field field=VertexBuffer.class.getDeclaredField(name);
                if(field.getType()!=VertexBuffer.class||!Modifier.isStatic(field.getModifiers())||!Modifier.isPrivate(field.getModifiers()))
                    throw new IllegalStateException("Pinned Iris VertexBuffer cache field changed: "+name);
                field.setAccessible(true);return field;
            }catch(ReflectiveOperationException error){throw new IllegalStateException("Pinned Iris VertexBuffer cache unavailable: "+name,error);}
        }
        private static VertexBuffer read(Field field) {
            try{return (VertexBuffer)field.get(null);}
            catch(IllegalAccessException error){throw new IllegalStateException("Pinned Iris VertexBuffer cache unreadable",error);}
        }
        private static void requireLive(VertexBuffer buffer) {
            if(buffer!=null&&buffer.isInvalid())throw new IllegalStateException("Captured Iris VertexBuffer owner was deleted");
        }
        void restore() {
            requireLive(current);requireLive(saved);
            if(read(savedField)!=saved)throw new IllegalStateException("Outer Iris saved VertexBuffer owner changed during reference work");
            if(current==null)VertexBuffer.unbind();else current.bind();
            verify();
        }
        void verify() {
            if(read(currentField)!=current||read(savedField)!=saved)
                throw new IllegalStateException("Pinned Iris VertexBuffer cache did not restore exactly");
        }
    }
    private static Throwable append(Throwable prior,Throwable next){if(prior==null)return next;if(prior!=next)prior.addSuppressed(next);return prior;}
    private static void rethrow(Throwable failure){if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;}
}
