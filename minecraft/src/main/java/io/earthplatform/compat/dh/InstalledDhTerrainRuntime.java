package io.earthplatform.compat.dh;

import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import io.earthplatform.minecraft.lod.SurvivalDhTerrainData;
import io.earthplatform.geography.survival.SurvivalMaterials;

import java.io.IOException;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.lang.reflect.Constructor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

/** Borrows the installed DH runtime. Creates no loader, upstream pool, or mod entrypoint. */
public final class InstalledDhTerrainRuntime {
    public static final String ARTIFACT_SHA256="864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab";
    public static final int SURVIVAL_MAX_FACES=4096, SURVIVAL_MAX_OUTPUT_BYTES=4096*64;
    /** Worst-case bounded reference snapshot, including transient validation bookkeeping. */
    public static final long SURVIVAL_INPUT_COPY_BYTES=4096L*Long.BYTES+256;
    public static final String SURVIVAL_COMPILER_POLICY="survival-dh-official-packing-039-v1;"
            +"explicit1,2,3,5,13;down-euclidean-squares;dirt="
            +Integer.toHexString(SurvivalMaterials.argb(SurvivalMaterials.DIRT))
            +";replacement-dirt-shade:up,down=1,north,south=.85,west,east=.72;observed-config-only";
    /** This identity describes physical packing only; it carries no source or publication authority. */
    public static final String SURVIVAL_ATTESTED_POLICY=SURVIVAL_COMPILER_POLICY.replace("observed-config-only",
            "actual-borrowed-settings-v2");
    private final ClassLoader loader;
    private final Class<?> installedApi;
    private final Class<?> builderClass,directionClass;
    private final Method factory,checkoutMethod,opaque,addSide,addUp,addDown;
    private final Object pool;
    private final Path jar;
    private final InstalledDhResolver.InstalledMod installation;
    private static InstalledDhTerrainRuntime processRuntime;
    private static Throwable initializationFailure;
    // Upstream builders are thread-local and not reentrant. One monitor also safely
    // publishes its lazily initialized static packing fields across adapter callers.
    private volatile boolean building,poisoned;
    private long captureOrdinal;
    // Fixed owner exists before any borrow. Poisoned references/tombstones live until process exit.
    private final BuildOwner buildOwner=new BuildOwner();
    private final HelperCalls helperCalls;
    private SurvivalFieldBinding survivalFields;
    private Object survivalColorSource;
    private SurvivalConfigBinding survivalConfig;
    // Optional managed lane. Once selected, its exact official workspace and charges survive every
    // generation/session until JVM exit. Pool return is not reclamation and is never used here.
    public static final long SURVIVAL_WORKSPACE_BYTES=(2L<<20)+4096L*128+7L*8192*8+65536+16384;
    /** Logical Java worker/queue/reflection ownership; excludes JVM native thread stack/runtime RSS. */
    public static final long SURVIVAL_WORKSPACE_METADATA_BYTES=128L<<10;
    private volatile boolean managedAttempted;
    private NativeDistanceCpuBudget managedBudget;
    private NativeDistanceCpuBudget.Reservation managedWorking,managedMetadata;
    private ManagedSurvivalWorkspace managed;
    private boolean managedInvocation;

    /** Opt-in is irreversible; existing diagnostic/legacy adapter compile calls then refuse. */
    public synchronized ManagedSurvivalWorkspace managedSurvivalWorkspace(NativeDistanceCpuBudget budget){
        java.util.Objects.requireNonNull(budget);
        if(managedAttempted){
            if(managed==null||poisoned||managedBudget!=budget)throw new IllegalStateException("Exact process DH workspace/budget is unavailable");
            return managed;
        }
        if(building||poisoned)throw new IllegalStateException("Cannot opt in during a live or poisoned helper borrow");
        // Refusal before admission leaves the old diagnostic runtime untouched.
        var metadata=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,SURVIVAL_WORKSPACE_METADATA_BYTES);
        NativeDistanceCpuBudget.Reservation working;
        try{working=budget.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,SURVIVAL_WORKSPACE_BYTES);}
        catch(RuntimeException|Error failure){metadata.releaseAfterPhysicalDrain();throw failure;}
        managedAttempted=true;managedBudget=budget;managedMetadata=metadata;managedWorking=working;
        try{managed=new ManagedSurvivalWorkspace();return managed;}
        catch(IOException|ReflectiveOperationException failure){poisoned=true;throw new IllegalStateException("Official bounded DH workspace unavailable",failure);}
        catch(RuntimeException|Error failure){poisoned=true;throw failure;}
    }
    /** A privately minted exact-call outcome. Operation completion is not workspace reclamation. */
    public static final class ManagedCall {
        private final SurvivalCompilation compilation;
        private final Throwable failure;
        private final boolean completed;
        private ManagedCall(SurvivalCompilation compilation,Throwable failure,boolean completed){this.compilation=compilation;this.failure=failure;this.completed=completed;}
        public boolean operationCompleted(){return completed;}
        public SurvivalCompilation compilation(){
            if(failure instanceof Error error)throw error;
            if(failure instanceof RuntimeException error)throw error;
            if(failure!=null)throw new IllegalStateException("Managed DH operation failed",failure);
            return java.util.Objects.requireNonNull(compilation);
        }
    }
    /** No executor/thread/helper object is exposed. Exactly one fresh dedicated worker is created. */
    public final class ManagedSurvivalWorkspace {
        private final AtomicBoolean busy=new AtomicBoolean();
        private final ThreadPoolExecutor executor;
        private final Constructor<?> freshCheckout;
        private final Method checkoutStarted,maxBufferBytes;
        private final Field checkoutDiagnostics;
        private volatile Thread thread;
        private Object builder,checkout;
        private boolean checkoutInitialized;
        private ManagedSurvivalWorkspace()throws IOException,ReflectiveOperationException {
            Class<?> checkoutClass=checkoutMethod.getReturnType();
            Class<?> poolClass=survivalClass("com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool");
            survivalClass("com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.ByteBufferCheckoutWrapper");
            survivalClass("com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.BufferQuad");
            if(pool.getClass()!=poolClass)throw new IllegalStateException("Different official workspace pool identity");
            checkoutDiagnostics=poolClass.getField("logGarbageCollectedStacks");
            maxBufferBytes=builderClass.getMethod("getMaxBufferByteSize");
            freshCheckout=checkoutClass.getConstructor(pool.getClass());
            checkoutStarted=checkoutClass.getMethod("onCheckout");
            executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),work->{
                if(thread!=null)throw new IllegalStateException("Managed DH worker cannot be replaced");
                Thread created=new Thread(work,"survival-dh-official-workspace");created.setDaemon(true);thread=created;return created;
            },new ThreadPoolExecutor.AbortPolicy());
            if(!executor.prestartCoreThread())throw new IllegalStateException("Managed DH worker did not start");
        }
        /** Input/output transient reservations remain the caller's responsibility until this exact call returns. */
        public ManagedCall compile(List<SurvivalDhTerrainData.PageFace> faces,ObservedSurvivalSettings expected,Runnable cancellation){
            java.util.Objects.requireNonNull(faces);java.util.Objects.requireNonNull(cancellation);
            if(Thread.currentThread()==thread||!busy.compareAndSet(false,true))throw new IllegalStateException("Managed DH workspace is busy/nonreentrant");
            boolean interrupted=false;
            try{
                FutureTask<ManagedCall> task=new FutureTask<>(()->{
                    synchronized(InstalledDhTerrainRuntime.this){
                        SurvivalCompilation result=null;Throwable failure=null;managedInvocation=true;
                        try{result=compileSurvivalRectangles(faces,expected,cancellation);}
                        catch(RuntimeException|Error error){failure=error;}
                        finally{managedInvocation=false;}
                        return new ManagedCall(result,failure,!building&&!poisoned);
                    }
                });
                executor.execute(task);
                // Never abandon a running task/input alias when the submitting owner is interrupted.
                for(;;)try{return task.get();}
                catch(InterruptedException interruption){interrupted=true;}
                catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Error error)throw error;throw new IllegalStateException("Managed DH task failed",cause);}
            }finally{busy.set(false);if(interrupted)Thread.currentThread().interrupt();}
        }
        public boolean operationReady(){return !busy.get()&&!building&&!poisoned;}
        public long retainedWorkingBytes(){return managedWorking.bytes();}
    }

    /** One borrowed helper binding per installed API identity; never owns or closes DH process resources. */
    public static synchronized InstalledDhTerrainRuntime open(InstalledDhResolver.Runtime runtime) throws IOException,ReflectiveOperationException {
        java.util.Objects.requireNonNull(runtime);
        if(initializationFailure!=null)throw new IllegalStateException("Installed helper binding previously failed; restart required",initializationFailure);
        if(processRuntime==null)try{processRuntime=new InstalledDhTerrainRuntime(runtime);}
            catch(IOException|ReflectiveOperationException|RuntimeException|Error failure){initializationFailure=failure;throw failure;}
        else if(processRuntime.installedApi!=runtime.apiClass()||!processRuntime.jar.equals(runtime.installation().path()))
            throw new IllegalArgumentException("Installed runtime identity cannot change within a process");
        return processRuntime;
    }
    private InstalledDhTerrainRuntime(InstalledDhResolver.Runtime runtime) throws IOException,ReflectiveOperationException {
        this(runtime,HelperCalls.DIRECT);
    }
    /** Original test seam: production always uses DIRECT and the process singleton above. */
    InstalledDhTerrainRuntime(InstalledDhResolver.Runtime runtime,HelperCalls helperCalls) throws IOException,ReflectiveOperationException {
        this.helperCalls=java.util.Objects.requireNonNull(helperCalls);
        this.jar=runtime.installation().path();loader=runtime.loader();installedApi=runtime.apiClass();installation=runtime.installation();
        InstalledDhResolver.verifyArtifact(jar);InstalledDhResolver.verifyOrigin(runtime.installation(),installedApi);
        if(Class.forName("com.seibel.distanthorizons.api.DhApi",false,loader)!=installedApi)
            throw new IllegalStateException("Borrowed API is not the installed game class identity");
        builderClass=Class.forName("com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder",false,loader);
        directionClass=Class.forName("com.seibel.distanthorizons.core.enums.EDhDirection",false,loader);
        if(builderClass.getClassLoader()!=installedApi.getClassLoader()||builderClass.getModule()!=installedApi.getModule())throw new IllegalStateException("Foreign DH helper classloader");
        Class<?> wrapper=Class.forName("com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper",false,loader);
        Class<?> checkout=Class.forName("com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout",false,loader);
        for(Class<?> helper:new Class<?>[]{builderClass,directionClass,wrapper,checkout}) {
            if(helper.getClassLoader()!=installedApi.getClassLoader()||helper.getModule()!=installedApi.getModule())throw new IllegalStateException("Foreign installed DH helper identity");
            InstalledDhResolver.verifyInstalledClassOrigin(runtime.installation(),helper);
        }
        factory=builderClass.getMethod("getBuilder",boolean.class,wrapper);
        pool=builderClass.getField("ARRAY_LIST_POOL").get(null);
        checkoutMethod=pool.getClass().getMethod("checkoutByteBuffers",int.class);
        opaque=builderClass.getMethod("makeOpaqueVertexBuffers",checkout);
        addSide=builderClass.getMethod("addQuadAdj",directionClass,short.class,short.class,short.class,short.class,short.class,
                int.class,short.class,byte.class,byte.class,byte.class);
        addUp=builderClass.getMethod("addQuadUp",short.class,short.class,short.class,short.class,int.class,short.class,byte.class,byte.class,byte.class);
        addDown=builderClass.getMethod("addQuadDown",short.class,short.class,short.class,short.class,int.class,short.class,byte.class,byte.class,byte.class);
    }
    public record Face(String direction,int x,int pageLocalY,int z,int argb) {
        public Face {
            if(!List.of("UP","DOWN","NORTH","SOUTH","WEST","EAST").contains(direction))throw new IllegalArgumentException("Invalid DH face");
            if(x<0||x>16||z<0||z>16||pageLocalY<0||pageLocalY>2048)throw new IllegalArgumentException("Invalid page-local DH face");
            if((argb>>>24)!=255)throw new IllegalArgumentException("Opaque fixture only");
        }
    }
    /** Each requested face is authored from known snapshot voxels; all returned bytes come from official LodQuadBuilder. */
    @SuppressWarnings({"unchecked","rawtypes"})
    public synchronized byte[] build(List<Face> faces) {
        if(faces.isEmpty()||faces.size()>4096)throw new IllegalArgumentException("DH fixture face budget exceeded or empty");
        enterBuild();Throwable primary=null;
        try {
            Object builder=borrowBuilder(null),checkout=borrowCheckout();
            for(Face f:faces) {
                if(f.direction.equals("UP")||f.direction.equals("DOWN"))
                    (f.direction.equals("UP")?addUp:addDown).invoke(builder,(short)f.x,(short)f.pageLocalY,(short)f.z,(short)1,
                            f.argb,(short)0,(byte)0,(byte)15,(byte)0);
                else addSide.invoke(builder,Enum.valueOf((Class)directionClass,f.direction),(short)f.x,(short)f.pageLocalY,(short)f.z,
                        (short)1,(short)1,f.argb,(short)0,(byte)0,(byte)15,(byte)0);
            }
            if(managedInvocation&&((Integer)managed.maxBufferBytes.invoke(null))!=(2<<20))
                throw new IllegalStateException("Official DH workspace capacity changed before packing");
            var buffers=(ArrayList<ByteBuffer>)opaque.invoke(builder,checkout);
            int total=0;for(var b:buffers)total=Math.addExact(total,b.remaining());
            if(total<=0||total>4096*64||total%64!=0)throw new IllegalStateException("Actual DH vertex output exceeded budget");
            byte[] result=new byte[total];int offset=0;
            for(var b:buffers){int count=b.remaining();b.get(result,offset,count);offset+=count;}
            return result;
        } catch(ReflectiveOperationException error) {
            Throwable cause=error instanceof InvocationTargetException wrapped?wrapped.getCause():error;
            var failure=new IllegalStateException("Installed DH quad builder failed",cause);primary=failure;throw failure;
        } catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {finishBuild(primary);}
    }
    public String shader(boolean vertex) throws IOException {
        String path="assets/distanthorizons/shaders/terrain/gl/"+(vertex?"vert.vert":"frag.frag");
        String expected=vertex?"ebd4e079225b7827624cf5652de390c441314f72134084099940f4c44457663d":"0b08a09258784e58fcd17f5cd46264ed2e42a962368519e667270183ef1df37e";
        try(var zip=new ZipFile(jar.toFile())) {
            var entry=zip.getEntry(path);if(entry==null||entry.getSize()<1||entry.getSize()>65536)throw new IOException("Missing bounded official DH shader");
            byte[] bytes;try(var in=zip.getInputStream(entry)){bytes=in.readNBytes(65536);}
            if(!sha256(bytes).equals(expected))throw new IOException("Unchanged official DH shader hash mismatch");
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    /** Bounded page-local rectangles. Only copied bytes leave the compiling thread. */
    public byte[] buildRectangles(List<DhTerrainGeometry.PageFace> faces) {
        return buildRectangles(faces,()->{});
    }
    /**
     * Serializes the pinned upstream helper, never GL. Cancellation is checked after
     * monitor acquisition and between bounded phases; a waiting caller remains charged.
     * The supplied check must not recursively invoke a builder on this same runtime.
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public byte[] buildRectangles(List<DhTerrainGeometry.PageFace> faces,Runnable cancellationCheck) {
        return buildRectangles(faces,(byte)0,cancellationCheck);
    }
    /** Fixed compiler flavor: UNKNOWN for the stock diagnostic, STONE for the pinned opaque analytic shader backend. */
    @SuppressWarnings({"unchecked","rawtypes"})
    public synchronized byte[] buildRectangles(List<DhTerrainGeometry.PageFace> faces,byte material,Runnable cancellationCheck) {
        if(material!=0&&material!=2)throw new IllegalArgumentException("Unsupported bounded DH material policy");
        java.util.Objects.requireNonNull(cancellationCheck,"cancellationCheck");
        if(faces.isEmpty()||faces.size()>4096)throw new IllegalArgumentException("DH rectangular face budget exceeded or empty");
        enterBuild();Throwable primary=null;
        try {
            cancellationCheck.run();
            Object builder=borrowBuilder(null),checkout=borrowCheckout();
            for(var f:faces) {
                cancellationCheck.run();
                if((f.argb()>>>24)!=255)throw new IllegalArgumentException("Opaque geographic DH rectangles only");
                byte faceMaterial=f.material()==0?material:(byte)f.material();
                if(f.direction()==DhTerrainGeometry.Direction.DOWN) {
                    if(f.width()!=f.height())throw new IllegalArgumentException("Only square DOWN faces are supported by this official builder path");
                    addDown.invoke(builder,(short)f.x(),(short)f.y(),(short)f.z(),(short)f.width(),f.argb(),(short)0,faceMaterial,(byte)15,(byte)0);
                } else addSide.invoke(builder,Enum.valueOf((Class)directionClass,f.direction().name()),(short)f.x(),(short)f.y(),(short)f.z(),
                        (short)f.width(),(short)f.height(),f.argb(),(short)0,faceMaterial,(byte)15,(byte)0);
            }
            cancellationCheck.run();
            if(managedInvocation&&((Integer)managed.maxBufferBytes.invoke(null))!=(2<<20))
                throw new IllegalStateException("Official DH workspace capacity changed before packing");
            var buffers=(ArrayList<ByteBuffer>)opaque.invoke(builder,checkout);
            cancellationCheck.run();int total=0;for(var b:buffers)total=Math.addExact(total,b.remaining());
            if(total<=0||total>4096*64||total%64!=0)throw new IllegalStateException("Actual DH rectangle vertex output exceeded budget");
            byte[] result=new byte[total];int offset=0;
            for(var b:buffers){cancellationCheck.run();int n=b.remaining();b.get(result,offset,n);offset+=n;}
            cancellationCheck.run();return result;
        }catch(ReflectiveOperationException error){
            var failure=new IllegalStateException("Installed DH rectangle builder failed",error instanceof InvocationTargetException wrapped?wrapped.getCause():error);primary=failure;throw failure;
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {finishBuild(primary);}
    }
    /**
     * Compiles one signed-page survival tile into detached official vertex bytes. Callers must
     * charge their input plus SURVIVAL_INPUT_COPY_BYTES and SURVIVAL_MAX_OUTPUT_BYTES under
     * the existing DH working/result budgets before calling, and retain those charges until
     * physical completion/output release. This method creates no cache, GL resource, ownership,
     * publication, currentness, history, collision or depth capability. The source list must
     * remain stable during admission; only its bounded immutable face snapshot is used afterward.
     * Settings are read from the exact borrowed helper, never changed. Public observations
     * remain diagnostic-only. A successful return proves physical cleanup; after a failure,
     * callers must check physicalDrain() before releasing working charges. It is also false
     * during any live build. If poisoned(), keep the affected charges until process exit.
     */
    public byte[] buildSurvivalRectangles(List<SurvivalDhTerrainData.PageFace> faces) {
        return buildSurvivalRectangles(faces,()->{});
    }
    /** All categories are explicit; UNKNOWN, legacy fallback and AIR are never accepted. */
    @SuppressWarnings({"unchecked","rawtypes"})
    public synchronized byte[] buildSurvivalRectangles(List<SurvivalDhTerrainData.PageFace> faces,Runnable cancellationCheck) {
        return buildSurvivalRectangles(faces,null,cancellationCheck);
    }
    /** The optional observation detects stale modes but is expressly not a cache/publication key. */
    @SuppressWarnings({"unchecked","rawtypes"})
    public synchronized byte[] buildSurvivalRectangles(List<SurvivalDhTerrainData.PageFace> faces,
            ObservedSurvivalSettings expectedSettings,Runnable cancellationCheck) {
        return compileSurvivalRectangles(faces,expectedSettings,cancellationCheck).bytes();
    }
    /**
     * The privately minted receipt describes these bytes and this borrow only. It cannot
     * authorize another borrow, cache publication, source currentness, collision or depth.
     * bytes() returns the single already charged detached array; no hidden second copy.
     * Caller mutation invalidates content evidence: this receipt identifies produced array
     * ownership/settings at return, not immutable byte content or a publication token.
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public synchronized SurvivalCompilation compileSurvivalRectangles(List<SurvivalDhTerrainData.PageFace> faces,
            ObservedSurvivalSettings expectedSettings,Runnable cancellationCheck) {
        java.util.Objects.requireNonNull(faces,"faces");
        java.util.Objects.requireNonNull(cancellationCheck,"cancellationCheck");
        int count=faces.size();
        if(count<1||count>SURVIVAL_MAX_FACES)throw new IllegalArgumentException("Survival DH face budget exceeded or empty");
        enterBuild();Throwable primary=null;
        try {
            cancellationCheck.run();
            var snapshot=new SurvivalDhTerrainData.PageFace[count];
            int submittedQuads=0,page=0;
            for(int i=0;i<count;i++) {
                cancellationCheck.run();
                var face=java.util.Objects.requireNonNull(faces.get(i),"survival face");var g=face.geometry();
                // Revalidate coordinates, solid-side signed-page ownership, opacity and category
                // without widening or substituting the old Earth material policy.
                new SurvivalDhTerrainData.PageFace(new DhTerrainGeometry.PageFace(g.direction(),g.pageY2048(),
                        g.x(),g.y(),g.z(),g.width(),g.height(),g.argb(),g.material()),face.category());
                if(i==0)page=g.pageY2048();
                else if(page!=g.pageY2048())throw new IllegalArgumentException("One signed survival DH page per compile required");
                int quads=g.direction()==DhTerrainGeometry.Direction.DOWN?survivalDownQuads(g.width(),g.height()):1;
                submittedQuads=Math.addExact(submittedQuads,quads);
                if(submittedQuads>SURVIVAL_MAX_FACES)throw new IllegalArgumentException("Survival DH subdivided quad budget exceeded");
                snapshot[i]=face;
            }
            if(faces.size()!=count)throw new IllegalArgumentException("Survival DH input changed during admission");
            cancellationCheck.run();
            // Snapshot BOTH mutation generations before the value reads, then check again.
            SurvivalDhConfiguration.Witness configuration=captureSurvivalConfiguration();
            ObservedSurvivalSettings settings=observeSurvivalSettings();
            requireSurvivalConfiguration(configuration);
            if(expectedSettings!=null&&!expectedSettings.equals(settings))throw new IllegalStateException("Survival DH settings observation is stale");
            // Every resulting DOWN square is admitted before borrowing either upstream resource.
            prepareSurvivalFields(); // Refuse inaccessible fields before acquiring upstream state.
            Object builder=borrowBuilder(survivalColors());
            helperCalls.checkpoint(Phase.BUILDER_ACQUIRED,builder);
            AttestedSurvivalSettings captured=captureSurvivalSettings(builder,settings,configuration);
            requireSurvivalSettings(settings);
            cancellationCheck.run();
            Object checkout=borrowCheckout();
            cancellationCheck.run();
            for(var face:snapshot) {
                var g=face.geometry();byte material=(byte)face.category();
                if(g.direction()==DhTerrainGeometry.Direction.DOWN) {
                    // Pinned addQuadAdj explicitly refuses DOWN; addQuadDown accepts one size.
                    // Greedy square tiling preserves the complete rectangle without a unit-grid
                    // allocation, an invented vertex format, or changing the source face plane.
                    int x=g.x(),z=g.z(),width=g.width(),height=g.height();
                    while(width>0&&height>0) {
                        cancellationCheck.run();int side=Math.min(width,height);
                        addDown.invoke(builder,(short)x,(short)g.y(),(short)z,(short)side,g.argb(),(short)0,material,(byte)15,(byte)0);
                        if(width>=height){x+=side;width-=side;}else{z+=side;height-=side;}
                    }
                } else {
                    cancellationCheck.run();
                    addSide.invoke(builder,Enum.valueOf((Class)directionClass,g.direction().name()),
                            (short)g.x(),(short)g.y(),(short)g.z(),(short)g.width(),(short)g.height(),
                            g.argb(),(short)0,material,(byte)15,(byte)0);
                }
            }
            cancellationCheck.run();
            requireSurvivalSettings(settings);
            requireCapturedSettings(builder,captured);
            if(managedInvocation&&((Integer)managed.maxBufferBytes.invoke(null))!=(2<<20))
                throw new IllegalStateException("Official DH workspace capacity changed before packing");
            var buffers=(ArrayList<ByteBuffer>)opaque.invoke(builder,checkout);
            cancellationCheck.run();requireSurvivalSettings(settings);int total=0;
            for(var buffer:buffers)total=Math.addExact(total,buffer.remaining());
            if(total<=0||total>SURVIVAL_MAX_OUTPUT_BYTES||total>submittedQuads*64||total%64!=0)
                throw new IllegalStateException("Actual survival DH vertex output exceeded admitted budget");
            byte[] result=new byte[total];int offset=0;
            for(var buffer:buffers){cancellationCheck.run();helperCalls.checkpoint(Phase.OUTPUT_COPY,buffer);
                int n=buffer.remaining();buffer.get(result,offset,n);offset+=n;}
            cancellationCheck.run();requireSurvivalSettings(settings);requireCapturedSettings(builder,captured);
            requireSurvivalConfiguration(configuration);
            return new SurvivalCompilation(result,captured);
        }catch(ReflectiveOperationException error){
            var failure=new IllegalStateException("Installed survival DH rectangle builder failed",
                    error instanceof InvocationTargetException wrapped?wrapped.getCause():error);primary=failure;throw failure;
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {finishBuild(primary);}
    }
    /** Euclidean quotient count is exactly the number emitted by the square tiling above. */
    private static int survivalDownQuads(int width,int height) {
        int count=0;
        while(width>0&&height>0) {
            if(width>=height){count=Math.addExact(count,width/height);width%=height;}
            else{count=Math.addExact(count,height/width);height%=width;}
        }
        return count;
    }
    /** Public samples only: this is never proof of an atomically captured native configuration. */
    public record ObservedSurvivalSettings(String grassSideMode,String debugMode) {
        public ObservedSurvivalSettings {
            if(!List.of("AS_GRASS","AS_DIRT","FADE_TO_DIRT").contains(grassSideMode)||!"OFF".equals(debugMode))
                throw new IllegalArgumentException("Unsupported survival DH grass/debug mode");
        }
        public String diagnosticIdentity(){return SURVIVAL_COMPILER_POLICY+";grass="+grassSideMode+";debug="+debugMode;}
    }
    /** Actual private enum values from one admitted helper borrow; constructors are not public. */
    public static final class AttestedSurvivalSettings {
        private final String grassSideMode,debugMode;
        private final long captureOrdinal;
        private final InstalledDhTerrainRuntime owner;
        private final SurvivalDhConfiguration.Witness configuration;
        private final SurvivalConfigBinding configurationBinding;
        private AttestedSurvivalSettings(InstalledDhTerrainRuntime owner,String grass,String debug,long ordinal,
                SurvivalDhConfiguration.Witness configuration) {
            this.owner=owner;this.grassSideMode=grass;this.debugMode=debug;this.captureOrdinal=ordinal;
            this.configuration=configuration;this.configurationBinding=owner.survivalConfig;
        }
        /** Current configuration requires actual transformed objects, never historical setting strings. */
        public void requireCurrentConfiguration() {
            if(configuration==null)throw new IllegalStateException("Historical DH packing has no installed mutation witness");
            // Never acquire the runtime/helper monitor: its dedicated compiler may be working.
            // Only the two exact config objects' brief generation read monitors are used here.
            requireSurvivalConfiguration(configurationBinding,configuration);
            var actual=readSurvivalSettings(configurationBinding);
            if(!grassSideMode.equals(actual.grassSideMode())||!debugMode.equals(actual.debugMode()))
                throw new IllegalStateException("Installed DH packing settings changed");
            requireSurvivalConfiguration(configurationBinding,configuration);
        }
        /** Coherent historical generation assertion. A diagnostic null generation still grants no currentness. */
        public void requireSameConfiguration(AttestedSurvivalSettings other) {
            if(other==null||owner!=other.owner||!grassSideMode.equals(other.grassSideMode)||!debugMode.equals(other.debugMode)
                    ||(configuration==null?other.configuration!=null:!configuration.same(other.configuration)))
                throw new IllegalStateException("DH output tiles mixed exact configuration generations");
            if(configuration!=null){requireCurrentConfiguration();other.requireCurrentConfiguration();}
        }
        public String grassSideMode(){return grassSideMode;}
        public String debugMode(){return debugMode;}
        /** Adapter-local diagnostic sequence, not a global cache key or transferable capability. */
        public long captureOrdinal(){return captureOrdinal;}
        public String physicalSettingsIdentity(){return SURVIVAL_ATTESTED_POLICY+";grass="+grassSideMode+";debug="+debugMode;}
    }
    /**
     * The mutable array remains caller-owned and charged until released, including every alias.
     * Future provenance must adopt/lock its ownership or verify a digest before trusting content.
     */
    public static final class SurvivalCompilation {
        private final byte[] bytes;
        private final AttestedSurvivalSettings settings;
        private SurvivalCompilation(byte[] bytes,AttestedSurvivalSettings settings){this.bytes=bytes;this.settings=settings;}
        public byte[] bytes(){return bytes;}
        public AttestedSurvivalSettings settings(){return settings;}
    }
    private record SurvivalFieldBinding(Field grass,Field debug) {}
    private void prepareSurvivalFields()throws ReflectiveOperationException {
        if(survivalFields!=null)return;
        // Config class identities/origins have already been checked by the public observation.
        var config=survivalConfig;
        Field grass=exactSurvivalField("grassSideRenderingMode",config.grassEnum);
        Field debug=exactSurvivalField("debugRenderingMode",config.debugEnum);
        survivalFields=new SurvivalFieldBinding(grass,debug);
    }
    private Field exactSurvivalField(String name,Class<?> type)throws ReflectiveOperationException {
        Field field=builderClass.getDeclaredField(name);
        if(field.getDeclaringClass()!=builderClass||field.getType()!=type||!type.isEnum()
                ||field.getModifiers()!=Modifier.PRIVATE)
            throw new IllegalStateException("Unsupported installed DH captured setting field");
        allowPrivateRead(field);return field;
    }
    /** Accessibility-policy seam only. Never opens a module, changes a field or installs an override. */
    static void allowPrivateRead(Field field)throws IllegalAccessException {
        Module target=field.getDeclaringClass().getModule(),ours=InstalledDhTerrainRuntime.class.getModule();
        if(!ours.canRead(target)||!target.isOpen(field.getDeclaringClass().getPackageName(),ours)||!field.trySetAccessible())
            throw new IllegalAccessException("Installed DH captured settings are not open for read-only attestation");
    }
    private AttestedSurvivalSettings captureSurvivalSettings(Object builder,ObservedSurvivalSettings observed,
            SurvivalDhConfiguration.Witness configuration)
            throws ReflectiveOperationException {
        if(builder.getClass()!=builderClass)throw new IllegalStateException("Foreign borrowed DH builder identity");
        Object grass=survivalFields.grass.get(builder),debug=survivalFields.debug.get(builder);
        if(!survivalConfig.grassEnum.isInstance(grass)||!survivalConfig.debugEnum.isInstance(debug))
            throw new IllegalStateException("Foreign captured survival DH setting value");
        String grassName=((Enum<?>)grass).name(),debugName=((Enum<?>)debug).name();
        if(!observed.grassSideMode.equals(grassName)||!observed.debugMode.equals(debugName))
            throw new IllegalStateException("Actual borrowed survival DH settings differ from public observation");
        return new AttestedSurvivalSettings(this,grassName,debugName,captureOrdinal,configuration);
    }
    private void requireCapturedSettings(Object builder,AttestedSurvivalSettings expected)throws ReflectiveOperationException {
        Object grass=survivalFields.grass.get(builder),debug=survivalFields.debug.get(builder);
        if(builder!=buildOwner.builder||expected.captureOrdinal!=captureOrdinal
                ||!survivalConfig.grassEnum.isInstance(grass)||!survivalConfig.debugEnum.isInstance(debug)
                ||!expected.grassSideMode.equals(((Enum<?>)grass).name())||!expected.debugMode.equals(((Enum<?>)debug).name()))
            throw new IllegalStateException("Actual borrowed survival DH settings changed during compilation");
    }
    private record SurvivalConfigBinding(Object grass,Object debug,Method get,Class<?> grassEnum,Class<?> debugEnum,
            Class<?> entryClass,Field grassField,Field debugField) {}
    private SurvivalDhConfiguration.Witness captureSurvivalConfiguration() {
        if(survivalConfig==null)observeSurvivalSettings(); // Initialization only; no witness predates this read.
        var b=survivalConfig;
        var result=SurvivalDhConfiguration.capture(b.entryClass,b.grass,b.debug);
        requireSurvivalConfiguration(result);return result;
    }
    private void requireSurvivalConfiguration(SurvivalDhConfiguration.Witness witness) {
        requireSurvivalConfiguration(survivalConfig,witness);
    }
    private static void requireSurvivalConfiguration(SurvivalConfigBinding binding,SurvivalDhConfiguration.Witness witness) {
        if(witness==null)return; // Historical diagnostic packing remains supported.
        try {witness.requireCurrent(binding.grassField.get(null),binding.debugField.get(null));}
        catch(IllegalAccessException error){throw new IllegalStateException("Installed DH configuration identities unavailable",error);}
    }
    private static ObservedSurvivalSettings readSurvivalSettings(SurvivalConfigBinding binding) {
        try {
            Object grass=binding.get.invoke(binding.grass),debug=binding.get.invoke(binding.debug);
            if(!binding.grassEnum.isInstance(grass)||!binding.debugEnum.isInstance(debug))
                throw new IllegalStateException("Foreign survival DH setting value");
            return new ObservedSurvivalSettings(((Enum<?>)grass).name(),((Enum<?>)debug).name());
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Installed survival DH settings cannot be observed",
                error instanceof InvocationTargetException wrapped?wrapped.getCause():error);}
    }
    /** Reads only public installed config; never registers listeners or changes an upstream setting. */
    public synchronized ObservedSurvivalSettings observeSurvivalSettings() {
        try {
            if(survivalConfig==null) {
                Class<?> quality=survivalClass("com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Quality");
                Class<?> debugging=survivalClass("com.seibel.distanthorizons.core.config.Config$Client$Advanced$Debugging");
                Class<?> entry=survivalClass("com.seibel.distanthorizons.core.config.types.ConfigEntry");
                Class<?> grass=survivalClass("com.seibel.distanthorizons.api.enums.config.EDhApiGrassSideRendering");
                Class<?> debug=survivalClass("com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering");
                survivalConfig=new SurvivalConfigBinding(quality.getField("grassSideRendering").get(null),
                        debugging.getField("debugRenderingColors").get(null),entry.getMethod("get"),grass,debug,
                        entry,quality.getField("grassSideRendering"),debugging.getField("debugRenderingColors"));
            }
            var binding=survivalConfig;Object grass=binding.get.invoke(binding.grass),debug=binding.get.invoke(binding.debug);
            if(!binding.grassEnum.isInstance(grass)||!binding.debugEnum.isInstance(debug))
                throw new IllegalStateException("Foreign survival DH setting value");
            return new ObservedSurvivalSettings(((Enum<?>)grass).name(),((Enum<?>)debug).name());
        }catch(IOException|ReflectiveOperationException error){
            throw new IllegalStateException("Installed survival DH settings cannot be observed",
                    error instanceof InvocationTargetException wrapped?wrapped.getCause():error);
        }
    }
    private void requireSurvivalSettings(ObservedSurvivalSettings expected) {
        if(!expected.equals(observeSurvivalSettings()))throw new IllegalStateException("Survival DH settings changed during compilation");
    }
    private Class<?> survivalClass(String name)throws ClassNotFoundException,IOException {
        Class<?> value=Class.forName(name,false,loader);
        if(value.getClassLoader()!=installedApi.getClassLoader()||value.getModule()!=installedApi.getModule())
            throw new IllegalStateException("Foreign installed survival DH helper identity");
        InstalledDhResolver.verifyInstalledClassOrigin(installation,value);return value;
    }
    /**
     * Immutable color-only projection of the already pinned survival palette, not a world.
     * putQuad uses these callbacks only when replacing grass vertex color with dirt: the
     * normal face ARGB is already directionally shaded and is never shaded again here.
     * Official ColorUtil retains responsibility for the replacement color's arithmetic.
     */
    private Object survivalColors() {
        if(survivalColorSource==null) {
            Class<?> wrapper=factory.getParameterTypes()[1];
            survivalColorSource=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{wrapper},(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class)return switch(method.getName()) {
                    case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);
                    case "toString"->"Immutable survival DH color source";
                    default->throw new UnsupportedOperationException("Unsupported color-source Object method");
                };
                if(method.getName().equals("getDirtBlockColor")&&method.getReturnType()==int.class&&method.getParameterCount()==0)
                    return SurvivalMaterials.argb(SurvivalMaterials.DIRT);
                if(method.getName().equals("getShade")&&method.getReturnType()==float.class&&method.getParameterCount()==1
                        &&method.getParameterTypes()[0]==directionClass&&directionClass.isInstance(args[0]))
                    return switch(((Enum<?>)args[0]).name()) {
                        case "UP","DOWN"->1.0f;case "NORTH","SOUTH"->0.85f;case "WEST","EAST"->0.72f;
                        default->throw new UnsupportedOperationException("Unsupported installed shade direction");
                    };
                throw new UnsupportedOperationException("Survival DH color source refuses world access: "+method.getName());
            });
        }
        return survivalColorSource;
    }
    /** True only when no compiler operation or uncertain acquisition/return remains. */
    public boolean physicalDrain(){return !managedAttempted&&!building&&!poisoned;}
    /** No reset API: uncertain upstream ownership requires a complete process restart. */
    public boolean poisoned(){return poisoned;}
    private static final class BuildOwner {
        Object checkout,builder;
        Thread thread;
        boolean acquisitionUncertain;
        Throwable acquisitionFailure,checkoutFailure,builderFailure;
    }
    enum Phase { BUILDER_ACQUIRED, OUTPUT_COPY }
    enum Resource { CHECKOUT, BUILDER }
    /**
     * Narrow fault seam. Production target failures are InvocationTargetException; checked
     * access/lookup failures mean no target invocation. Test doubles must preserve that boundary.
     * Attestation always reads the real installed fields and is never supplied by this seam.
     */
    interface HelperCalls {
        HelperCalls DIRECT=new HelperCalls() {};
        default Object borrow(Method method,Object target,Object... arguments)throws ReflectiveOperationException {
            return method.invoke(target,arguments);
        }
        default Object createCheckout(Constructor<?> constructor,Object pool)throws ReflectiveOperationException {return constructor.newInstance(pool);}
        default void startCheckout(Method method,Object checkout)throws ReflectiveOperationException {method.invoke(checkout);}
        default void close(Resource kind,Object value)throws Exception {((AutoCloseable)value).close();}
        default void checkpoint(Phase phase,Object value)throws ReflectiveOperationException {}
    }
    private void enterBuild(){
        if(managedAttempted&&(!managedInvocation||managed==null||Thread.currentThread()!=managed.thread))
            throw new IllegalStateException("Process helper is exclusively owned by its managed survival workspace");
        if(poisoned)throw new IllegalStateException("Installed DH helper ownership is uncertain; restart required");
        if(building)throw new IllegalStateException("Official DH thread-local builder is not reentrant");
        long next=Math.addExact(captureOrdinal,1); // Refuse sequence exhaustion before any upstream acquisition.
        buildOwner.thread=Thread.currentThread();captureOrdinal=next;building=true;
    }
    private Object borrowBuilder(Object colors)throws ReflectiveOperationException {
        try {
            // Assign immediately, before any callback, allocation or further helper operation.
            buildOwner.builder=helperCalls.borrow(factory,null,false,colors);
            if(buildOwner.builder==null)throw new IllegalStateException("Installed DH factory returned no builder");
            if(managedInvocation){
                if(managed.builder==null)managed.builder=buildOwner.builder;
                else if(managed.builder!=buildOwner.builder)throw new IllegalStateException("Managed official builder identity changed");
            }
            return buildOwner.builder;
        }catch(InvocationTargetException|RuntimeException|Error failure){uncertainAcquisition(failure);throw failure;}
    }
    private Object borrowCheckout()throws ReflectiveOperationException {
        try {
            if(managedInvocation){
                if(managed.checkout==null){
                    // The genuine public constructor creates a fresh empty checkout, never an
                    // inherited arbitrary-capacity pooled graph. The fixed charge precedes it.
                    managed.checkout=helperCalls.createCheckout(managed.freshCheckout,pool);
                    if(managed.checkout==null)throw new IllegalStateException("No fresh official checkout");
                    buildOwner.checkout=managed.checkout;
                    if(managed.checkoutDiagnostics.getBoolean(pool))throw new IllegalStateException("Unbounded checkout diagnostic stacks are unsupported");
                    helperCalls.startCheckout(managed.checkoutStarted,managed.checkout); // Preserve official diagnostic hook.
                    managed.checkoutInitialized=true;
                }else{
                    if(!managed.checkoutInitialized)throw new IllegalStateException("Managed checkout initialization did not complete");
                    buildOwner.checkout=managed.checkout;
                }
            }else buildOwner.checkout=helperCalls.borrow(checkoutMethod,pool,1);
            if(buildOwner.checkout==null)throw new IllegalStateException("Installed DH pool returned no checkout");
            return buildOwner.checkout;
        }catch(InvocationTargetException|RuntimeException|Error failure){uncertainAcquisition(failure);throw failure;}
        catch(ReflectiveOperationException failure){
            // A checked hook/field-access failure can occur AFTER the fresh object was retained.
            // Never let a later non-null branch skip its incomplete initialization.
            if(managedInvocation&&managed.checkout!=null)uncertainAcquisition(failure);
            throw failure;
        }
    }
    private void uncertainAcquisition(Throwable failure){
        // A throwing factory may have changed thread-local/pool state without returning a handle.
        // Keep an honest tombstone and caller charge; never invent or retry the missing handle.
        poisoned=true;buildOwner.acquisitionUncertain=true;buildOwner.acquisitionFailure=failure;
    }
    /** Return each resource once on its acquiring thread; preserve primary cancellation/failure. */
    private void finishBuild(Throwable primary){
        var owner=buildOwner;
        if(owner.thread!=Thread.currentThread()) {
            poisoned=true;building=false;
            throw new IllegalStateException("DH helper cleanup attempted on a foreign thread");
        }
        // The pinned builder owns only BufferQuad lists; checkout owns only packed byte buffers.
        // Neither close touches the other's storage, so independent cleanup can still proceed.
        if(managedInvocation){
            // Deliberately held exact checkout remains in managed.checkout with lifetime charge.
            // No pool return, SoftReference ABA, or acquisition of another owner's capacities.
            owner.checkout=null;
        }
        if(owner.checkout!=null)try {
            helperCalls.close(Resource.CHECKOUT,owner.checkout);owner.checkout=null;
        }catch(Exception|Error failure){poisoned=true;owner.checkoutFailure=failure;}
        if(owner.builder!=null)try {
            helperCalls.close(Resource.BUILDER,owner.builder);owner.builder=null;
        }catch(Exception|Error failure){poisoned=true;owner.builderFailure=failure;}
        building=false;
        if(!poisoned)owner.thread=null;
        Throwable cleanup=owner.checkoutFailure!=null?owner.checkoutFailure:owner.builderFailure;
        if(owner.checkoutFailure!=null&&owner.builderFailure!=null)suppress(owner.checkoutFailure,owner.builderFailure);
        if(cleanup!=null) {
            if(primary!=null)suppress(primary,cleanup);
            else if(cleanup instanceof Error error)throw error;
            else throw new IllegalStateException("DH pool checkout cleanup failed",cleanup);
        }
    }
    private static void suppress(Throwable primary,Throwable secondary){
        if(primary==secondary)return;
        // addSuppressed may itself allocate. Original errors and retained ownership take priority.
        try{primary.addSuppressed(secondary);}catch(RuntimeException|Error suppressionFailure){/* retained owner still has both errors */}
    }
    private static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
