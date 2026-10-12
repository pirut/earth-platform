package io.earthplatform.minecraft.lod;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

/** Isolated unmodified official DH CPU classes. Never scans mod metadata or invokes a loader entrypoint. */
public final class DhOfficialRuntime {
    public static final String ARTIFACT_SHA256="864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab";
    private final URLClassLoader loader;
    private final Class<?> builderClass,directionClass;
    private final Method factory,checkoutMethod,opaque,addSide,addUp,addDown;
    private final Object pool;
    private final Path jar;
    private static DhOfficialRuntime processRuntime;
    private static Throwable initializationFailure;
    // Upstream builders are thread-local and not reentrant. One monitor also safely
    // publishes its lazily initialized static packing fields across adapter callers.
    private boolean building;

    /** Upstream's pool recycler ignores interruption. Keep exactly one bounded runtime per game process. */
    public static synchronized DhOfficialRuntime open() throws IOException,ReflectiveOperationException {
        requireDiagnosticTest();
        return open(configuredJar());
    }
    public static synchronized DhOfficialRuntime open(Path jar) throws IOException,ReflectiveOperationException {
        requireDiagnosticTest();
        if(initializationFailure!=null)throw new IllegalStateException("Isolated DH initialization previously failed; restart required before retry",initializationFailure);
        if(processRuntime==null)try{processRuntime=new DhOfficialRuntime(jar);}
            catch(IOException|ReflectiveOperationException|RuntimeException|Error failure){initializationFailure=failure;throw failure;}
        else if(!processRuntime.jar.equals(jar.toAbsolutePath().normalize()))throw new IllegalArgumentException("Isolated runtime artifact path cannot change within a game process");
        return processRuntime;
    }
    private static void requireDiagnosticTest(){
        if(!Boolean.getBoolean("earthplatform.test.isolatedDhRuntime")||Boolean.getBoolean("earthplatform.experimental.installedDhBackend")||io.earthplatform.compat.PlayableProfile.selection().selected())
            throw new IllegalStateException("Isolated DH runtime is a headless-test-only helper; install DH separately for the live adapter");
    }
    private DhOfficialRuntime(Path jar) throws IOException,ReflectiveOperationException {
        this.jar=jar.toAbsolutePath().normalize();
        if(Files.size(this.jar)!=28_289_212||!sha256(this.jar).equals(ARTIFACT_SHA256))
            throw new IllegalArgumentException("Expected the pinned, unchanged official DH 3.3.3 artifact");
        loader=new URLClassLoader(new java.net.URL[]{this.jar.toUri().toURL()},DhOfficialRuntime.class.getClassLoader());
        try {
            builderClass=loader.loadClass("com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder");
            directionClass=loader.loadClass("com.seibel.distanthorizons.core.enums.EDhDirection");
            Class<?> wrapper=loader.loadClass("com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper");
            Class<?> checkout=loader.loadClass("com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout");
            factory=builderClass.getMethod("getBuilder",boolean.class,wrapper);
            pool=builderClass.getField("ARRAY_LIST_POOL").get(null);
            checkoutMethod=pool.getClass().getMethod("checkoutByteBuffers",int.class);
            opaque=builderClass.getMethod("makeOpaqueVertexBuffers",checkout);
            addSide=builderClass.getMethod("addQuadAdj",directionClass,short.class,short.class,short.class,short.class,short.class,
                    int.class,short.class,byte.class,byte.class,byte.class);
            addUp=builderClass.getMethod("addQuadUp",short.class,short.class,short.class,short.class,int.class,short.class,byte.class,byte.class,byte.class);
            addDown=builderClass.getMethod("addQuadDown",short.class,short.class,short.class,short.class,int.class,short.class,byte.class,byte.class,byte.class);
            Class<?> info=loader.loadClass("com.seibel.distanthorizons.coreapi.ModInfo");
            if(!"3.3.3".equals(info.getField("VERSION").get(null)))throw new IllegalStateException("Wrong isolated DH runtime");
            if(builderClass.getClassLoader()!=loader)throw new IllegalStateException("DH classes were already present in the mod classloader");
        } catch(ReflectiveOperationException|RuntimeException|Error failure) {
            try{loader.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
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
        enterBuild();Object builder=null,checkout=null;Throwable primary=null;
        try {
            builder=factory.invoke(null,false,null);checkout=checkoutMethod.invoke(pool,1);
            for(Face f:faces) {
                if(f.direction.equals("UP")||f.direction.equals("DOWN"))
                    (f.direction.equals("UP")?addUp:addDown).invoke(builder,(short)f.x,(short)f.pageLocalY,(short)f.z,(short)1,
                            f.argb,(short)0,(byte)0,(byte)15,(byte)0);
                else addSide.invoke(builder,Enum.valueOf((Class)directionClass,f.direction),(short)f.x,(short)f.pageLocalY,(short)f.z,
                        (short)1,(short)1,f.argb,(short)0,(byte)0,(byte)15,(byte)0);
            }
            var buffers=(ArrayList<ByteBuffer>)opaque.invoke(builder,checkout);
            int total=0;for(var b:buffers)total=Math.addExact(total,b.remaining());
            if(total<=0||total>4096*64||total%64!=0)throw new IllegalStateException("Actual DH vertex output exceeded budget");
            byte[] result=new byte[total];int offset=0;
            for(var b:buffers){int count=b.remaining();b.get(result,offset,count);offset+=count;}
            return result;
        } catch(ReflectiveOperationException error) {
            Throwable cause=error instanceof InvocationTargetException wrapped?wrapped.getCause():error;
            var failure=new IllegalStateException("Isolated actual DH quad builder failed",cause);primary=failure;throw failure;
        } catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {finishBuild(checkout,builder,primary);}
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
    public synchronized byte[] buildRectangles(List<DhTerrainGeometry.PageFace> faces,Runnable cancellationCheck) {
        java.util.Objects.requireNonNull(cancellationCheck,"cancellationCheck");
        if(faces.isEmpty()||faces.size()>4096)throw new IllegalArgumentException("DH rectangular face budget exceeded or empty");
        enterBuild();Object builder=null,checkout=null;Throwable primary=null;
        try {
            cancellationCheck.run();
            builder=factory.invoke(null,false,null);checkout=checkoutMethod.invoke(pool,1);
            for(var f:faces) {
                cancellationCheck.run();
                if((f.argb()>>>24)!=255)throw new IllegalArgumentException("Opaque geographic DH rectangles only");
                if(f.direction()==DhTerrainGeometry.Direction.DOWN) {
                    if(f.width()!=f.height())throw new IllegalArgumentException("Only square DOWN faces are supported by this official builder path");
                    addDown.invoke(builder,(short)f.x(),(short)f.y(),(short)f.z(),(short)f.width(),f.argb(),(short)0,(byte)f.material(),(byte)15,(byte)0);
                } else addSide.invoke(builder,Enum.valueOf((Class)directionClass,f.direction().name()),(short)f.x(),(short)f.y(),(short)f.z(),
                        (short)f.width(),(short)f.height(),f.argb(),(short)0,(byte)f.material(),(byte)15,(byte)0);
            }
            cancellationCheck.run();
            var buffers=(ArrayList<ByteBuffer>)opaque.invoke(builder,checkout);
            cancellationCheck.run();int total=0;for(var b:buffers)total=Math.addExact(total,b.remaining());
            if(total<=0||total>4096*64||total%64!=0)throw new IllegalStateException("Actual DH rectangle vertex output exceeded budget");
            byte[] result=new byte[total];int offset=0;
            for(var b:buffers){cancellationCheck.run();int n=b.remaining();b.get(result,offset,n);offset+=n;}
            cancellationCheck.run();return result;
        }catch(ReflectiveOperationException error){
            var failure=new IllegalStateException("Actual DH rectangle builder failed",error instanceof InvocationTargetException wrapped?wrapped.getCause():error);primary=failure;throw failure;
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {finishBuild(checkout,builder,primary);}
    }
    private void enterBuild(){if(building)throw new IllegalStateException("Official DH thread-local builder is not reentrant");building=true;}
    /** Return each checkout on its acquiring thread, and never hide a primary cancellation/failure. */
    private void finishBuild(Object checkout,Object builder,Throwable primary){
        Throwable cleanup=null;
        try{
            for(Object resource:new Object[]{checkout,builder})if(resource!=null)try{((AutoCloseable)resource).close();}
                catch(Exception|Error failure){if(cleanup==null)cleanup=failure;else if(cleanup!=failure)cleanup.addSuppressed(failure);}
        }finally{building=false;}
        if(cleanup!=null){if(primary!=null){if(primary!=cleanup)primary.addSuppressed(cleanup);}
            else if(cleanup instanceof Error error)throw error;
            else throw new IllegalStateException("DH pool checkout cleanup failed",cleanup);}
    }
    private static Path configuredJar() {
        String value=System.getProperty("earthplatform.lodProbeJar",System.getenv("EARTH_DH_PROBE_JAR"));
        if(value==null||value.isBlank())throw new IllegalStateException("Explicit EARTH_DH_PROBE_JAR or earthplatform.lodProbeJar is required");
        return Path.of(value);
    }
    private static String sha256(Path path)throws IOException {
        try(InputStream in=Files.newInputStream(path)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];int n;
            while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
