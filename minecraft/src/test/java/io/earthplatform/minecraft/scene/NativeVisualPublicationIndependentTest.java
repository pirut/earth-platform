package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.visual.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

/** Validator/worker unit tests. Reflection fixtures are deliberately NOT authenticated GPU or capsule evidence. */
public final class NativeVisualPublicationIndependentTest {
    static final String HASH="0123456789abcdef".repeat(4);
    static final Unsafe UNSAFE=unsafe();
    static Unsafe unsafe(){try{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(Unsafe)f.get(null);}catch(Exception e){throw new AssertionError(e);}}
    static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static NativeVisualStamp stamp(){return new NativeVisualStamp(new WorldIdentity(1,"test","test","test","test"),new UUID(1,2),1,1,1,0,2,2,0,HASH,HASH,HASH,HASH,HASH);}
    static NativeVisualGpuPiece.Completion part(int ordinal,int quads,boolean last,String identity,NativeVisualCpuPiece.MemberRange... ranges)throws Exception{
        var owner=(NativeVisualGpuPiece)UNSAFE.allocateInstance(NativeVisualGpuPiece.class);set(owner,"owner",Thread.currentThread());set(owner,"uploaded",true);set(owner,"ordinal",ordinal);set(owner,"quads",quads);set(owner,"completesRequest",last);set(owner,"contentIdentity",identity);set(owner,"ranges",List.of(ranges));
        Constructor<?> c=NativeVisualGpuPiece.Completion.class.getDeclaredConstructors()[0];c.setAccessible(true);return(NativeVisualGpuPiece.Completion)c.newInstance(owner);
    }
    static NativeVisualCpuPiece.MemberRange range(int member,int first,int end,int quads){return new NativeVisualCpuPiece.MemberRange(member,first,end,0,quads);}
    static EarthDistanceSurfacePlan.Page page(long quads,long mask){return new EarthDistanceSurfacePlan.Page(new NativePageKey(0,0,0),quads,(int)((quads+5040)/5041),HASH,new EarthDistanceSurfacePlan.FaceBounds(0,0,0,16,16,16),mask);}
    static void validate(EarthDistanceSurfacePlan.Page page,NativeVisualGpuPiece.Completion... parts)throws Throwable{
        Method m=NativeVisualPublication.class.getDeclaredMethod("validatePage",EarthDistanceSurfacePlan.Page.class,List.class);m.setAccessible(true);try{m.invoke(null,page,new ArrayList<>(List.of(parts)));}catch(InvocationTargetException e){throw e.getCause();}
    }
    @Test void scanValidationRejectsMissingDuplicatedGappedOverlappingOrStaleParts()throws Exception{
        var p=page(6,1);var okay=part(0,6,true,HASH,range(0,0,24576,6));assertDoesNotThrow(()->validate(p,okay));
        assertThrows(IllegalArgumentException.class,()->validate(p));assertThrows(IllegalArgumentException.class,()->validate(p,okay,okay));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(1,6,true,HASH,range(0,0,24576,6))));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(0,6,false,HASH,range(0,0,24576,6))));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(0,6,true,"f".repeat(64),range(0,0,24576,6))));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(0,6,true,HASH,range(0,1,24576,6))));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(0,6,true,HASH,range(0,0,24575,6))));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(0,3,false,HASH,range(0,0,100,3)),part(1,3,true,HASH,range(0,101,24576,3))));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(0,3,false,HASH,range(0,0,100,3)),part(1,3,true,HASH,range(0,99,24576,3))));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(0,6,true,HASH,range(1,0,24576,6))));
        assertThrows(IllegalArgumentException.class,()->validate(p,part(0,5,true,HASH,range(0,0,24576,5))));
    }
    @Test void exactSourcePieceCensusIsRequiredEvenWhenScansAndQuadsSumCorrectly()throws Exception{
        var p=page(6,1);var a=part(0,3,false,HASH,range(0,0,12000,3));var b=part(1,3,true,HASH,range(0,12000,24576,3));
        assertThrows(IllegalArgumentException.class,()->validate(p,a,b));
    }
    @Test void splitMemberCompletionAndShuffledPhysicalReceiptOrderAreCanonical()throws Exception{
        var p=page(6000,3);var a=part(0,5041,false,HASH,range(0,0,24576,3000),new NativeVisualCpuPiece.MemberRange(1,0,12000,3000,2041));var b=part(1,959,true,HASH,range(1,12000,24576,959));
        assertDoesNotThrow(()->validate(p,b,a));set(a.physicalOwner(),"closed",true);assertThrows(IllegalStateException.class,()->validate(p,a,b));
    }
    static NativeVisualPublication uncheckedPublication()throws Exception{
        var p=(NativeVisualPublication)UNSAFE.allocateInstance(NativeVisualPublication.class);set(p,"owner",Thread.currentThread());set(p,"stamp",stamp());set(p,"ownerGuard",(BooleanSupplier)()->true);set(p,"receipts",List.of());set(p,"guaranteedRadius",32.0);return p;
    }
    static NativeVisualPublication.CheckedDepth depth(NativeVisualPublication p,Matrix4f matrix)throws Throwable{
        var c=NativeVisualPublication.CheckedDepth.class.getDeclaredConstructors()[0];c.setAccessible(true);try{return(NativeVisualPublication.CheckedDepth)c.newInstance(p,matrix);}catch(InvocationTargetException e){throw e.getCause();}
    }
    @Test void depthUsesActualSelectedFarPlaneAndDefensivelyCopiesProjection()throws Throwable{
        var p=uncheckedPublication();var matrix=new Matrix4f().perspective((float)Math.toRadians(70),1.6f,.05f,128);var d=depth(p,matrix);assertEquals(128,d.nativeFar(),.1);assertEquals(32,d.guaranteedNativeRadius());matrix.m32(42);assertNotEquals(42,d.nativeProjection().m32());var copy=d.nativeProjection();copy.m32(42);assertNotEquals(42,d.nativeProjection().m32());
        assertThrows(IllegalArgumentException.class,()->depth(p,new Matrix4f().perspective(1,1,.05f,64)));assertThrows(IllegalArgumentException.class,()->depth(p,new Matrix4f()));assertThrows(IllegalArgumentException.class,()->depth(p,new Matrix4f().m00(Float.NaN)));
    }
    @Test void selectedRadiusCannotBePublishedWithAPathologicallyLargeNearPlane()throws Exception{
        var p=uncheckedPublication();assertThrows(IllegalArgumentException.class,()->depth(p,new Matrix4f().perspective(1,1,100,128)));assertThrows(IllegalArgumentException.class,()->depth(p,new Matrix4f().perspective(1,1,1,128)));
    }
    @Test void retainedPublicationRejectsNewMainProjectionWithSmallerDepth()throws Throwable{
        var p=uncheckedPublication();var original=new Matrix4f().perspective((float)Math.toRadians(70),1.6f,.05f,128);set(p,"depth",depth(p,original));assertDoesNotThrow(()->p.requireNativeProjection(new Matrix4f(original)));assertDoesNotThrow(()->p.requireNativeProjection(new Matrix4f().perspective((float)Math.toRadians(95),1.9f,.05f,128)));assertThrows(IllegalArgumentException.class,()->p.requireNativeProjection(new Matrix4f().perspective(1,1,.05f,64)));assertThrows(IllegalArgumentException.class,()->p.requireNativeProjection(new Matrix4f(original).m02(1)));assertThrows(IllegalArgumentException.class,()->p.requireNativeProjection(new Matrix4f().perspective(1,1,.06f,128)));
    }
    @Test void retainedFarPlaneCannotShrinkByMoreThanItsOneMetreMemberSafetyMargin()throws Throwable{
        var p=uncheckedPublication();set(p,"stamp",new NativeVisualStamp(new WorldIdentity(1,"test","test","test","test"),new UUID(1,2),1,1,1,0,24,24,0,HASH,HASH,HASH,HASH,HASH));
        var actual=new Matrix4f().perspective(1,1,.05f,1536);var prior=new Matrix4f(actual).m22(Math.nextUp(actual.m22()));var old=depth(p,prior);var now=depth(p,actual);assertTrue(old.nativeFar()-now.nativeFar()>1);set(p,"depth",old);assertThrows(IllegalStateException.class,()->p.requireNativeProjection(actual));
    }
    @Test void depthCrossTermsCannotAuthorizeAnIncorrectSphericalNativeEnvelope()throws Exception{
        var p=uncheckedPublication();var canonical=new Matrix4f().perspective((float)Math.toRadians(70),1.6f,.05f,128);
        for(int field=0;field<4;field++){var changed=new Matrix4f(canonical);switch(field){case 0->changed.m02(1);case 1->changed.m12(1);case 2->changed.m03(1);case 3->changed.m13(1);}assertThrows(IllegalArgumentException.class,()->depth(p,changed),"Unexpected depth/w cross-term "+field);}
        // Concrete negative control: a point inside selected radius is behind the sheared far plane.
        var clip=new Matrix4f(canonical).m02(1).transform(new Vector4f(10,0,-20,1));assertTrue(clip.z>clip.w);assertTrue(Math.sqrt(10*10+20*20)<32);
    }
    @Test void selectedTwoFlatPagesNeedABoundedCameraPermitInsteadOfEntireRoundingSurplus()throws Exception{
        var limits=NativeDistanceLimits.sourceLimits();var env=new EarthAlpineDefinition.Envelope(0,1,0,1,-16,16,-4096,4096,-4096,4096);var source=EarthDistanceSurfacePlan.Source.prepareSampled(env,-256,256,HASH,HASH,HASH,2,0,limits,(x,z)->new EarthDistanceSurfacePlan.Sample(-1,5,3,7,0,-1,0));var plan=EarthDistanceSurfacePlan.build(source,null,new CubePos(0,0,0),2,0,limits);
        Method far=NativeVisualPublication.class.getDeclaredMethod("farthest",EarthDistanceSurfacePlan.FaceBounds.class,NativeVisualPublication.CameraDomain.class);far.setAccessible(true);Method near=NativeVisualPublication.class.getDeclaredMethod("nearest",EarthDistanceSurfacePlan.FaceBounds.class,NativeVisualPublication.CameraDomain.class);near.setAccessible(true);
        var surplus=new NativeVisualPublication.CameraDomain(-32,-16,-32,32,48,32);var local=new NativeVisualPublication.CameraDomain(-2,-8.38,-2,18,11.62,18);var bounds=plan.bounds();assertTrue(local.minX()-32>=bounds.minX()&&local.maxX()+32<=bounds.maxXExclusive()&&local.minZ()-32>=bounds.minZ()&&local.maxZ()+32<=bounds.maxZExclusive());
        for(var page:plan.pages()){assertEquals(144.0,(double)far.invoke(null,page.faceBounds(),surplus),1e-9);assertEquals(0.0,(double)near.invoke(null,page.faceBounds(),surplus),1e-9);assertTrue((double)far.invoke(null,page.faceBounds(),local)<127);}
        assertTrue(local.contains(8,1.62,8));System.out.println("PURE_SELECTED2_DEPTH surplusWorst=144 nativeFar=128 localPermitAllPagesEligible=true");
    }
    @Test void cameraPermitPreservesActorCubeAndOverlapsAdjacentFiveMetreMargins(){
        var current=NativeVisualPublication.cameraPermit(new CubePos(0,0,0));assertEquals(28,current.maxX()-current.minX());assertEquals(-6,current.minX());assertEquals(22,current.maxX());assertTrue(current.contains(0,0,0));assertTrue(current.contains(16,16,16));
        for(var next:List.of(new CubePos(1,0,0),new CubePos(0,1,0),new CubePos(0,0,1))){var c=NativeVisualPublication.cameraPermit(next);assertTrue(Math.min(current.maxX()-5,c.maxX()-5)-Math.max(current.minX()+5,c.minX()+5)>=2);assertTrue(Math.min(current.maxY()-5,c.maxY()-5)-Math.max(current.minY()+5,c.minY()+5)>=2);assertTrue(Math.min(current.maxZ()-5,c.maxZ()-5)-Math.max(current.minZ()+5,c.minZ()+5)>=2);}
    }
    @Test void selectedTwoMemberAuthorityFitsEveryPagePhaseWhileWholePageNegativeControlFails()throws Exception{
        var limits=NativeDistanceLimits.sourceLimits();var env=new EarthAlpineDefinition.Envelope(0,4,0,4,-16,16,-4096,4096,-4096,4096);var source=EarthDistanceSurfacePlan.Source.prepareSampled(env,-256,256,HASH,HASH,HASH,2,0,limits,(x,z)->new EarthDistanceSurfacePlan.Sample(-1,5,3,7,0,-1,0));Method far=NativeVisualPublication.class.getDeclaredMethod("farthest",EarthDistanceSurfacePlan.FaceBounds.class,NativeVisualPublication.CameraDomain.class);far.setAccessible(true);Method near=NativeVisualPublication.class.getDeclaredMethod("nearest",EarthDistanceSurfacePlan.FaceBounds.class,NativeVisualPublication.CameraDomain.class);near.setAccessible(true);int wholePageFailures=0;
        for(int z=0;z<4;z++)for(int x=0;x<4;x++){
            var center=new CubePos(x,0,z);var plan=EarthDistanceSurfacePlan.build(source,null,center,2,0,limits);var c=NativeVisualPublication.cameraPermit(center);var requested=new EarthDistanceSurfacePlan.Bounds(center.minBlockX()-32,center.minBlockZ()-32,center.minBlockX()+48,center.minBlockZ()+48);int selected=0;boolean oldFailed=false;
            for(var page:plan.pages()){
                double f=(double)far.invoke(null,page.faceBounds(),c),n=(double)near.invoke(null,page.faceBounds(),c);if(f>=127&&n<=26)oldFailed=true;
                long actual=NativeVisualPublication.depthEligibleMemberMask(page,requested,c,128,26),expected=0;
                for(int member=0;member<64;member++)if((page.memberMask()&(1L<<member))!=0){long mx=page.key().minX()+(member&3)*16L,mz=page.key().minZ()+((member>>>2)&3)*16L;if(requested.contains(mx,mz))expected|=1L<<member;}
                assertEquals(expected,actual,"Exact selected chunk footprint at "+center+" / "+page.key());selected+=Long.bitCount(actual);
            }
            assertEquals(25,selected,"Exactly (2r+1)^2 surface members, without 64m rounding/prefetch leakage");if(oldFailed)wholePageFailures++;
        }
        assertEquals(12,wholePageFailures,"Preserved negative control for the former whole-page depth authority");System.out.println("PURE_SELECTED2_MEMBER_PHASES centers=16 exactSurfaceMembersPerCenter=25 wholePageNegativeFailures=12 memberAuthorityFailures=0");
    }
    @Test void exactFullPieceAbsorbsAnEntireAirMemberTailWithoutMintingAnExtraPiece(){
        byte[] halo=new byte[NativeVisualCpuBuilder.HALO_BYTES];int solids=0;
        outer:for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(((x+y+z)&1)==0){halo[NativeVisualCpuBuilder.Input.haloIndex(x,y,z)]=1;if(++solids==841)break outer;}
        // 841 isolated cubes provide 5046 faces; five one-cell halo neighbors hide exactly five.
        for(int z=0;z<10;z+=2)halo[NativeVisualCpuBuilder.Input.haloIndex(-1,0,z)]=1;
        var capsule=new NativeVisualCapsule(1,HASH,HASH,HASH,new byte[NativeVisualCapsule.TABLE_BYTES]);
        var builder=new NativeVisualCpuBuilder(new NativeVisualCpuBuilder.Input(0,0,0,halo,new int[]{0,63},HASH),capsule,new NativeVisualCpuBuilder.Cancellation());
        var piece=builder.nextPiece();assertEquals(5041,piece.quads());assertTrue(piece.completesRequest());assertEquals(2,piece.memberRanges().size());assertEquals(24576,piece.memberRanges().getFirst().endFace());assertEquals(63,piece.memberRanges().getLast().member());assertEquals(0,piece.memberRanges().getLast().quads());assertEquals(24576,piece.memberRanges().getLast().endFace());assertThrows(IllegalStateException.class,builder::nextPiece);
    }
    @Test void cameraDomainRejectsNonfiniteAndUnboundedLocations(){
        var c=new NativeVisualPublication.CameraDomain(-1,-2,-3,1,2,3);assertTrue(c.contains(-1,2,3));assertFalse(c.contains(1.00001,0,0));assertFalse(c.contains(Double.NaN,0,0));assertThrows(IllegalArgumentException.class,()->new NativeVisualPublication.CameraDomain(0,0,0,129,0,0));assertThrows(IllegalArgumentException.class,()->new NativeVisualPublication.CameraDomain(0,0,0,Double.POSITIVE_INFINITY,0,0));
    }
    @Test void trimmingKeepsCurrentAndPendingContentButClosesUnreferencedCacheOwners()throws Exception{
        var limits=NativeDistanceLimits.sourceLimits();var env=new EarthAlpineDefinition.Envelope(0,9,0,1,-16,16,-4096,4096,-4096,4096);
        var source=EarthDistanceSurfacePlan.Source.prepareSampled(env,-256,256,HASH,HASH,HASH,2,0,limits,(x,z)->new EarthDistanceSurfacePlan.Sample(-1,5,3,7,0,-1,0));
        var current=EarthDistanceSurfacePlan.build(source,null,new CubePos(0,0,0),2,0,limits);var pending=EarthDistanceSurfacePlan.build(source,null,new CubePos(4,0,0),2,0,limits);var stale=EarthDistanceSurfacePlan.build(source,null,new CubePos(8,0,0),2,0,limits);
        Class<?> pageClass=Class.forName(NativeVisualStream.class.getName()+"$PageOwner");Constructor<?> ctor=pageClass.getDeclaredConstructors()[0];ctor.setAccessible(true);Map<String,Object> cache=new LinkedHashMap<>();Set<NativeVisualGpuPiece> physical=Collections.newSetFromMap(new IdentityHashMap<>());Set<String> retained=new HashSet<>(),keep=new HashSet<>();for(var p:current.pages()){retained.add(p.identity());keep.add(p.identity());}for(var p:pending.pages())keep.add(p.identity());
        for(var plan:List.of(current,pending,stale))for(var page:plan.pages())if(!cache.containsKey(page.identity())){Object owner=ctor.newInstance(page);set(owner,"complete",true);var piece=part(0,1,true,page.identity(),range(0,0,24576,1)).physicalOwner();@SuppressWarnings("unchecked") var parts=(List<NativeVisualGpuPiece>)get(owner,"pieces");parts.add(piece);cache.put(page.identity(),owner);physical.add(piece);}
        int before=physical.size();var stream=(NativeVisualStream)UNSAFE.allocateInstance(NativeVisualStream.class);set(stream,"owner",Thread.currentThread());set(stream,"cache",cache);set(stream,"physical",physical);set(stream,"retainedPresentation",Set.copyOf(retained));Method trim=NativeVisualStream.class.getDeclaredMethod("trimTo",EarthDistanceSurfacePlan.class);trim.setAccessible(true);trim.invoke(stream,pending);assertEquals(keep,cache.keySet());assertEquals(keep.size(),physical.size());assertTrue(physical.size()<before);for(var piece:physical)assertFalse(piece.closeComplete());
    }
    @Test void immutableCachedPageIdentitiesSkipActualCpuEmissionForRepeatedPlan()throws Exception{
        var limits=NativeDistanceLimits.sourceLimits();var env=new EarthAlpineDefinition.Envelope(0,1,0,1,-16,16,-4096,4096,-4096,4096);
        var source=EarthDistanceSurfacePlan.Source.prepareSampled(env,-256,256,HASH,HASH,HASH,2,1,limits,(x,z)->new EarthDistanceSurfacePlan.Sample(-1,5,3,7,0,-1,0));EarthReplacementCapture capture;try(var b=new EarthReplacementCapture.Builder(new CubePos(0,0,0))){for(int y=-2;y<18;y++)for(int z=-2;z<18;z++)for(int x=-2;x<18;x++)b.setNext(source.materialAt(x,y,z));capture=b.finish();}
        var cachePlan=EarthDistanceSurfacePlan.build(source,capture,new CubePos(0,0,0),2,1,limits);Set<String> reused=new HashSet<>();for(var page:cachePlan.pages())reused.add(page.identity());
        Class<?> jobClass=Class.forName(NativeVisualStream.class.getName()+"$Job");var jc=jobClass.getDeclaredConstructors()[0];jc.setAccessible(true);Object job=jc.newInstance(stamp(),new CubePos(0,0,0),capture);Class<?> workerClass=Class.forName(NativeVisualStream.class.getName()+"$Worker");var wc=workerClass.getDeclaredConstructors()[0];wc.setAccessible(true);var capsule=new NativeVisualCapsule(1,HASH,HASH,HASH,new byte[NativeVisualCapsule.TABLE_BYTES]);Runnable worker=(Runnable)wc.newInstance(job,Set.copyOf(reused),null,capsule,new AtomicReference<>(source));Thread thread=new Thread(worker,"independent-native-cache-reuse");thread.start();thread.join(10000);assertFalse(thread.isAlive());assertTrue((boolean)get(job,"exited"));assertNull(get(job,"failure"));@SuppressWarnings("unchecked") var queue=(ArrayBlockingQueue<Object>)get(job,"output");assertEquals(1,queue.size());assertEquals("Plans",queue.peek().getClass().getSimpleName());assertEquals(0L,get(job,"workerBytes"));
    }
    @Test void streamCloseRetainsSourceAndCapsuleAliasesUntilPhysicalWriterDrain()throws Exception{
        var limits=NativeDistanceLimits.sourceLimits();var env=new EarthAlpineDefinition.Envelope(0,1,0,1,-16,16,-4096,4096,-4096,4096);var source=EarthDistanceSurfacePlan.Source.prepareSampled(env,-256,256,HASH,HASH,HASH,2,0,limits,(x,z)->new EarthDistanceSurfacePlan.Sample(-1,5,3,7,0,-1,0));var capsule=new NativeVisualCapsule(1,HASH,HASH,HASH,new byte[NativeVisualCapsule.TABLE_BYTES]);var sealed=new NativeVisualCapsuleCapture.Sealed(capsule,null,null,null);
        Class<?> jobClass=Class.forName(NativeVisualStream.class.getName()+"$Job");var jc=jobClass.getDeclaredConstructors()[0];jc.setAccessible(true);Object job=jc.newInstance(stamp(),new CubePos(0,0,0),null);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();executor.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}finally{try{set(job,"exited",true);}catch(Exception e){throw new AssertionError(e);}}});assertTrue(started.await(5,TimeUnit.SECONDS));
        var sourceRef=new AtomicReference<>(source);var budget=new NativeDistanceCpuBudget(4096);var reservation=budget.reserve(NativeDistanceCpuBudget.Pool.SOURCE,4096);var stream=(NativeVisualStream)UNSAFE.allocateInstance(NativeVisualStream.class);set(stream,"owner",Thread.currentThread());set(stream,"source",sourceRef);set(stream,"sealed",sealed);set(stream,"definition",UNSAFE.allocateInstance(EarthAlpineDefinition.class));set(stream,"executor",executor);set(stream,"job",job);set(stream,"cache",new LinkedHashMap<>());set(stream,"physical",Collections.newSetFromMap(new IdentityHashMap<>()));set(stream,"cpuReservations",new ArrayList<>(List.of(reservation)));set(stream,"retainedPresentation",Set.of());
        try{stream.close();assertFalse(stream.closeComplete());assertSame(source,sourceRef.get());assertSame(sealed,get(stream,"sealed"));assertNotNull(get(stream,"definition"));assertFalse(reservation.released());assertEquals(4096,budget.evidence().bytes());}finally{release.countDown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        stream.close();assertTrue(stream.closeComplete());assertNull(sourceRef.get());assertNull(get(stream,"sealed"));assertNull(get(stream,"definition"));assertTrue(reservation.released());assertEquals(0,budget.evidence().bytes());
    }
    @Test void actualCpuWriterCancellationKeepsMailboxUntilWorkerPhysicallyRelinquishes()throws Exception{
        var limits=NativeDistanceLimits.sourceLimits();var env=new EarthAlpineDefinition.Envelope(0,1,0,1,-16,16,-4096,4096,-4096,4096);
        var source=EarthDistanceSurfacePlan.Source.prepareSampled(env,-256,256,HASH,HASH,HASH,2,1,limits,(x,z)->new EarthDistanceSurfacePlan.Sample(-1,5,3,7,0,-1,0));
        EarthReplacementCapture capture;try(var b=new EarthReplacementCapture.Builder(new CubePos(0,0,0))){for(int y=-2;y<18;y++)for(int z=-2;z<18;z++)for(int x=-2;x<18;x++)b.setNext(source.materialAt(x,y,z));capture=b.finish();}
        var capsule=new NativeVisualCapsule(1,HASH,HASH,HASH,new byte[NativeVisualCapsule.TABLE_BYTES]);
        Class<?> jobClass=Class.forName(NativeVisualStream.class.getName()+"$Job");var jc=jobClass.getDeclaredConstructors()[0];jc.setAccessible(true);Object job=jc.newInstance(stamp(),new CubePos(0,0,0),capture);
        Class<?> workerClass=Class.forName(NativeVisualStream.class.getName()+"$Worker");var wc=workerClass.getDeclaredConstructors()[0];wc.setAccessible(true);Runnable worker=(Runnable)wc.newInstance(job,Set.of(),null,capsule,new AtomicReference<>(source));
        @SuppressWarnings("unchecked") var queue=(ArrayBlockingQueue<Object>)get(job,"output");Thread thread=new Thread(worker,"independent-native-physical-writer");thread.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(queue.size()!=2&&thread.isAlive()&&System.nanoTime()<deadline)Thread.sleep(1);assertEquals(2,queue.size());assertFalse((boolean)get(job,"exited"));
        var stream=(NativeVisualStream)UNSAFE.allocateInstance(NativeVisualStream.class);set(stream,"owner",Thread.currentThread());set(stream,"job",job);set(stream,"cache",new LinkedHashMap<>());set(stream,"physical",Collections.newSetFromMap(new IdentityHashMap<>()));
        var cancel=(NativeVisualCpuBuilder.Cancellation)get(job,"cancel");assertThrows(IllegalStateException.class,stream::drainCancelled);assertEquals(2,queue.size());stream.cancelRequest();thread.join(10000);assertFalse(thread.isAlive());assertTrue(cancel.isCancelled());assertTrue((boolean)get(job,"exited"));assertNull(get(job,"failure"));assertEquals(2,queue.size(),"Worker cannot release its owner mailbox");assertTrue(stream.drainCancelled());assertTrue(queue.isEmpty());assertNull(get(stream,"job"));
        System.out.println("PURE_NATIVE_WORKER boundedMailbox=2 realWorkerExited=true ownerDrainedAfterExit=true installedCapsuleParity=NOT_RUN");
    }
}
