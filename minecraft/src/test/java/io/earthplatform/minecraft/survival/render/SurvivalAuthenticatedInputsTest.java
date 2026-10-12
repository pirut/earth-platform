package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.scene.*;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exact verification/admission tests only; producer wiring is checked separately from native/GL execution. */
class SurvivalAuthenticatedInputsTest {
    @TempDir Path temporary;
    static final SceneNeighborhood.LightingPolicy OUTDOOR=SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE;
    @Test void fullDependenciesAreSlicedAndScratchDrainsBeforeIndependentProof() throws Exception {
        var f=new SurvivalAuthenticatedTestFixture(temporary,false);
        long baseline=f.budget.evidence().bytes();
        long sourceBaseline=f.budget.evidence().pools().get(NativeDistanceCpuBudget.Pool.SOURCE);
        try(f;var v=f.verification(f.cubes)) {
            assertEquals(sourceBaseline+SurvivalNativePageCompiler.Verification.SCRATCH_BYTES,
                    f.budget.evidence().pools().get(NativeDistanceCpuBudget.Pool.SOURCE));
            assertThrows(IllegalStateException.class,v::finish);
            var first=v.step(1,Long.MAX_VALUE);
            assertEquals(1,first.cells());assertEquals(1,first.verifiedCells());assertEquals(0,first.verifiedDependencies());
            assertEquals(1,first.generatedCubes());assertFalse(first.complete());
            assertEquals(f.cubes.size()*CubePos.VOLUME,first.totalCells());
            var stats=first;
            while(!stats.complete()) {stats=v.step(997,Long.MAX_VALUE);assertTrue(stats.cells()<=997);}
            assertEquals(f.cubes.size(),stats.generatedCubes());assertEquals(f.cubes.size(),stats.verifiedDependencies());
            assertTrue(stats.maxDependencySetupNanos()>=0);
            try(var proof=v.finish();var alias=f.plan.retain()) {
                assertEquals(sourceBaseline,f.budget.evidence().pools().get(NativeDistanceCpuBudget.Pool.SOURCE));
                proof.requireExactPage(f.page);proof.requireMatches(alias,f.capture,OUTDOOR,false);
                assertThrows(IllegalStateException.class,()->proof.requireExactPage(new SurvivalDistancePagePlan.Page(
                        f.page.address(),f.page.groundMask(),f.page.treeMask(),f.page.editExceptionMask())));
                assertThrows(IllegalStateException.class,()->proof.requireMatches(alias,SceneNativeShaderContract.defaultPath(),OUTDOOR,false));
                assertThrows(IllegalStateException.class,()->proof.requireMatches(alias,f.capture,SceneNeighborhood.LightingPolicy.DIAGNOSTIC_FULLBRIGHT,false));
                assertThrows(IllegalStateException.class,()->proof.requireMatches(alias,f.capture,OUTDOOR,true));
                f.owner.requestInvalidation();
                assertThrows(SurvivalRefusal.class,()->f.lease.latch(alias));
                proof.requireMatches(alias,f.capture,OUTDOOR,false); // Historical input proof survives; currentness does not.
                assertTrue(f.budget.evidence().bytes()>baseline);
            }
        }
        assertEquals(0,f.budget.evidence().bytes());
    }
    @Test void proofRetainsItsOwnPlanAndRejectsSemanticallyEqualNewPlan() throws Exception {
        var f=new SurvivalAuthenticatedTestFixture(temporary,false);
        try(f;var proof=f.proof();var alias=f.plan.retain();var equal=f.content.plan(f.override,
                new SurvivalDistancePagePlan.DistanceChoice(2,2,0),
                new SurvivalDistancePagePlan.Limits(100000,1000,1000,10000,200000,200000,64,2<<20))) {
            assertEquals(f.plan.identity(),equal.identity());
            assertThrows(SurvivalRefusal.class,()->proof.requireMatches(equal,f.capture,OUTDOOR,false));
            f.lease.close();f.plan.close();f.content.close();f.owner.requestInvalidation();
            proof.requireMatches(alias,f.capture,OUTDOOR,false);
            assertTrue(f.budget.evidence().bytes()>0);
        }
        assertEquals(0,f.budget.evidence().bytes());
    }
    @Test void explicitAirOverridesGeneratedTerrainAndWrongHaloStatePoisonsVerification() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,true)) {
            assertTrue(f.content.hasOverride(f.override));assertTrue(f.page.dependencies().contains(f.override));
            try(var proof=f.proof()) {proof.requireMatches(f.plan,f.capture,OUTDOOR,false);}
            var wrong=new HashMap<>(f.cubes);
            var halo=f.page.dependencies().stream().filter(p->!f.page.members().contains(p)).findFirst().orElseThrow();
            var original=f.source.generate(halo,new CancellationToken());var ids=original.toArray();
            ids[CubePos.VOLUME-1]=f.palette.id(ids[CubePos.VOLUME-1]==f.palette.id(Blocks.STONE.defaultBlockState())
                    ?Blocks.DIRT.defaultBlockState():Blocks.STONE.defaultBlockState());
            wrong.put(halo,f.cube(halo,ImmutableCube.copyOf(ids)));
            long baseline=f.budget.evidence().bytes();
            try(var v=f.verification(Map.copyOf(wrong))) {
                assertThrows(SurvivalRefusal.class,()->{while(!v.step(4096,Long.MAX_VALUE).complete()) {}});
                assertThrows(IllegalStateException.class,v::finish);assertThrows(IllegalStateException.class,()->v.step(1,1));
                assertTrue(f.budget.evidence().bytes()>baseline);
            }
            assertEquals(baseline,f.budget.evidence().bytes());
            wrong=new HashMap<>(f.cubes);wrong.put(f.override,f.cube(f.override,ImmutableCube.uniform(f.palette.id(Blocks.STONE.defaultBlockState()))));
            try(var v=f.verification(Map.copyOf(wrong))) {
                assertThrows(SurvivalRefusal.class,()->{while(!v.step(4096,Long.MAX_VALUE).complete()) {}});
            }
        }
    }
    @Test void completeMembershipWorldAndAddressAreMandatory() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false)) {
            var at=f.cubes.keySet().iterator().next();var missing=new HashMap<>(f.cubes);missing.remove(at);
            long baseline=f.budget.evidence().bytes();
            assertThrows(IllegalArgumentException.class,()->f.verification(Map.copyOf(missing)));
            assertEquals(baseline,f.budget.evidence().bytes());
            var other=f.cubes.keySet().stream().filter(p->!p.equals(at)).findFirst().orElseThrow();
            var wrong=new HashMap<>(f.cubes);wrong.put(at,f.cubes.get(other));
            try(var v=f.verification(Map.copyOf(wrong))) {
                assertThrows(SurvivalRefusal.class,()->{while(!v.step(4096,Long.MAX_VALUE).complete()) {}});
            }
            var foreign=new WorldIdentity(987,"foreign","039",f.world.world().datasetVersion(),f.palette.identity());
            var snap=EngineCubeSnapshot.capture(at,foreign,0,1,0,EngineCubeSection.uniform(f.palette.context(),Blocks.AIR.defaultBlockState()));
            wrong=new HashMap<>(f.cubes);wrong.put(at,new SceneCube(snap,f.palette.context()));
            try(var v=f.verification(Map.copyOf(wrong))) {
                assertThrows(SurvivalRefusal.class,()->{while(!v.step(4096,Long.MAX_VALUE).complete()) {}});
            }
            assertEquals(baseline,f.budget.evidence().bytes());
        }
    }
    @Test void foreignWirePaletteAndCorruptedMaterializedArrayCannotHideBehindSameWorld() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false)) {
            var at=f.cubes.keySet().stream().sorted().findFirst().orElseThrow();
            var registries=net.minecraft.data.registries.VanillaRegistries.createLookup();
            var biomes=new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(
                    net.minecraft.core.registries.Registries.BIOME,com.mojang.serialization.Lifecycle.stable());
            biomes.register(net.minecraft.world.level.biome.Biomes.PLAINS,registries.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value(),net.minecraft.core.RegistrationInfo.BUILT_IN);
            biomes.register(net.minecraft.world.level.biome.Biomes.DESERT,registries.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getOrThrow(net.minecraft.world.level.biome.Biomes.DESERT).value(),net.minecraft.core.RegistrationInfo.BUILT_IN);
            var foreign=io.earthplatform.minecraft.network.EnginePaletteContext.create(biomes.freeze());
            assertNotEquals(f.palette.context().fingerprint(),foreign.fingerprint());
            var wrong=new HashMap<>(f.cubes);
            var snapshot=EngineCubeSnapshot.capture(at,f.world.world(),0,1,0,EngineCubeSection.uniform(foreign,Blocks.AIR.defaultBlockState()));
            wrong.put(at,new SceneCube(snapshot,foreign));
            try(var v=f.verification(Map.copyOf(wrong))) {
                assertThrows(io.netty.handler.codec.DecoderException.class,()->v.step(1,Long.MAX_VALUE));
                assertThrows(IllegalStateException.class,v::finish);
            }
            // White-box fault injection only: the immutable public SceneCube API cannot expose this array.
            var actual=f.cubes.get(at);var field=SceneCube.class.getDeclaredField("states");field.setAccessible(true);
            var states=(net.minecraft.world.level.block.state.BlockState[])field.get(actual);
            var original=states[0];states[0]=original==Blocks.STONE.defaultBlockState()?Blocks.DIRT.defaultBlockState():Blocks.STONE.defaultBlockState();
            try(var v=f.verification(f.cubes)) {assertThrows(SurvivalRefusal.class,()->v.step(1,Long.MAX_VALUE));}
            finally {states[0]=original;}
        }
    }
    @Test void scratchAndMetadataRefusalDoNotRetainPartialFrameOrConsumeSource() throws Exception {
        for(var pool:List.of(NativeDistanceCpuBudget.Pool.SOURCE,NativeDistanceCpuBudget.Pool.OWNER_METADATA)) {
            try(var f=new SurvivalAuthenticatedTestFixture(temporary.resolve(pool.name()),false)) {
                long used=f.budget.evidence().pools().get(pool);
                var pressure=f.budget.reserve(pool,pool.limit()-used);
                long baseline=f.budget.evidence().bytes();
                assertThrows(IllegalStateException.class,()->f.verification(f.cubes));
                assertEquals(baseline,f.budget.evidence().bytes());pressure.releaseAfterPhysicalDrain();
                try(var v=f.verification(f.cubes)) {assertEquals(1,v.step(1,1).cells());}
            }
        }
    }
    @Test void resourceRetirementAndCancellationFailClosedWithoutDroppingLiveScratch() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false)) {
            long baseline=f.budget.evidence().bytes();
            var retired=SceneNativeShaderContract.Capture.class.getDeclaredField("invalidated");retired.setAccessible(true);
            try(var v=f.verification(f.cubes)) {
                assertEquals(1,v.step(1,Long.MAX_VALUE).verifiedCells());
                var wrongThread=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var thread=new Thread(()->{try{v.step(1,1);}catch(Throwable failure){wrongThread.set(failure);}});
                thread.start();thread.join();assertInstanceOf(IllegalStateException.class,wrongThread.get());
                retired.setBoolean(f.capture,true);
                assertThrows(IllegalStateException.class,()->v.step(1,Long.MAX_VALUE));
                assertThrows(IllegalStateException.class,v::finish);
                assertTrue(f.budget.evidence().bytes()>baseline);
                v.close();assertEquals(baseline,f.budget.evidence().bytes());
                assertThrows(IllegalStateException.class,()->v.step(1,Long.MAX_VALUE));
            }
            assertThrows(IllegalStateException.class,()->f.verification(f.cubes));
            assertEquals(baseline,f.budget.evidence().bytes());
        }
    }
    @Test void proofHasNoPublicConstructorOrAttachableGpuEntryPoint() {
        assertEquals(0,SurvivalNativePageCompiler.AuthenticatedContent.class.getConstructors().length);
        for(var method:SurvivalNativeGpuPage.class.getMethods())
            for(var type:method.getParameterTypes()) assertNotEquals(SurvivalNativePageCompiler.AuthenticatedContent.class,type);
        for(var constructor:SurvivalNativeGpuPage.class.getConstructors())
            for(var type:constructor.getParameterTypes()) assertNotEquals(SurvivalNativePageCompiler.AuthenticatedContent.class,type);
    }

    /** Reflect only the private copy seam: no render-thread assertion/model callback/GL side effects. */
    private static final class Copy implements AutoCloseable {
        final NativeDistanceCpuBudget budget=new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);
        final Object owner;final Method reserve,copy,drain;
        Copy(int expected) throws Exception {
            var type=Class.forName(SurvivalNativePageCompiler.class.getName()+"$InputOwnership");
            var ctor=type.getDeclaredConstructor(NativeDistanceCpuBudget.class,long.class);ctor.setAccessible(true);
            owner=ctor.newInstance(budget,32768L);
            reserve=type.getDeclaredMethod("reserve",long.class);reserve.setAccessible(true);reserve.invoke(owner,1024L*expected);
            drain=type.getDeclaredMethod("drain",Runnable.class);drain.setAccessible(true);
            copy=SurvivalNativePageCompiler.class.getDeclaredMethod("copyAuthenticated",Map.class,Set.class,type);copy.setAccessible(true);
        }
        @SuppressWarnings("unchecked") Map<CubePos,SceneCube> run(Map<CubePos,SceneCube> map,Set<CubePos> expected) throws Throwable {
            try{return (Map<CubePos,SceneCube>)copy.invoke(null,map,expected,owner);}
            catch(InvocationTargetException e){throw e.getCause();}
        }
        public void close() throws Exception {drain.invoke(owner,(Runnable)()->{});assertEquals(0,budget.evidence().bytes());}
    }
    @Test void authenticatedCopyNeverPreflightsCallerSizeOrKeySetAndTraversesOnce() throws Throwable {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false);var copy=new Copy(f.cubes.size())) {
            var traversals=new AtomicInteger();
            Map<CubePos,SceneCube> caller=new AbstractMap<>() {
                public int size(){throw new AssertionError("caller size preflight");}
                public Set<CubePos> keySet(){throw new AssertionError("caller keySet preflight");}
                public Set<Entry<CubePos,SceneCube>> entrySet(){assertEquals(1,traversals.incrementAndGet());return f.cubes.entrySet();}
            };
            var owned=copy.run(caller,f.page.dependencies());assertEquals(f.cubes,owned);assertEquals(1,traversals.get());
            assertThrows(UnsupportedOperationException.class,()->owned.clear());
            long expected=32768L+1024L*f.cubes.size();
            for(var cube:f.cubes.values())expected+=CubePos.VOLUME*8L+cube.snapshot().sectionBytes();
            assertEquals(expected,copy.budget.evidence().bytes());
        }
    }
    @Test void copiedPayloadRefusalAndOmittedMembershipKeepOnlyChargedPartialOwnership() throws Throwable {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false)) {
            var positions=f.cubes.keySet().stream().sorted().limit(2).toList();var at=positions.getFirst();var cube=f.cubes.get(at);
            long payload=CubePos.VOLUME*8L+cube.snapshot().sectionBytes();
            try(var copy=new Copy(2)) {
                long admitted=copy.budget.evidence().bytes();
                assertThrows(IllegalArgumentException.class,()->copy.run(Map.of(at,cube),Set.copyOf(positions)));
                assertEquals(admitted+payload,copy.budget.evidence().bytes(),"Partial aliases remain charged until caller drains failed ownership");
            }
            try(var copy=new Copy(1)) {
                var pool=NativeDistanceCpuBudget.Pool.WORKER_UPLOAD;
                long used=copy.budget.evidence().pools().get(pool);
                var pressure=copy.budget.reserve(pool,pool.limit()-used-payload+1);
                long before=copy.budget.evidence().bytes();
                assertThrows(IllegalStateException.class,()->copy.run(Map.of(at,cube),Set.of(at)));
                assertEquals(before,copy.budget.evidence().bytes());pressure.releaseAfterPhysicalDrain();
            }
        }
    }
    @Test void excessDuplicateAndUnplannedInputsRefuseBeforeReadingTheirValues() throws Throwable {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false)) {
            var at=f.cubes.keySet().iterator().next();var read=new AtomicInteger();
            for(var key:List.of(at,at.offset(100000,0,0))) {
                var entries=List.<Map.Entry<CubePos,SceneCube>>of(Map.entry(at,f.cubes.get(at)),new Map.Entry<>() {
                    public CubePos getKey(){return key;}public SceneCube getValue(){read.incrementAndGet();throw new AssertionError("unadmitted value");}
                    public SceneCube setValue(SceneCube ignored){throw new UnsupportedOperationException();}
                });
                Map<CubePos,SceneCube> caller=new AbstractMap<>() {
                    public Set<Entry<CubePos,SceneCube>> entrySet(){return new AbstractSet<>() {
                        public int size(){return entries.size();}public Iterator<Entry<CubePos,SceneCube>> iterator(){return entries.iterator();}
                    };}
                };
                try(var copy=new Copy(1)) {assertThrows(IllegalArgumentException.class,()->copy.run(caller,Set.of(at)));}
                // With spare expected capacity, duplicate/unplanned-key checks also precede value reads.
                var other=f.cubes.keySet().stream().filter(p->!p.equals(at)).findFirst().orElseThrow();
                try(var copy=new Copy(2)) {assertThrows(IllegalArgumentException.class,()->copy.run(caller,Set.of(at,other)));}
            }
            assertEquals(0,read.get());
        }
    }
}
