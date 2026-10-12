package io.earthplatform.minecraft.scene;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit outdoor fixture values only; not a propagated light-engine or GPU claim. */
class SceneShaderFixtureLightingTest {
    private static final WorldIdentity WORLD = new WorldIdentity(0,"shader-fixture","1","analytic","native-palette");
    private static EnginePaletteContext palette;
    @BeforeAll static void registry() {
        var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(r.freeze());
    }
    private static SceneCube cube(CubePos p) {
        return new SceneCube(EngineCubeSnapshot.capture(p,WORLD,1,1,0,
                EngineCubeSection.uniform(palette,Blocks.STONE.defaultBlockState())),palette);
    }
    private static SceneNeighborhood scene(CubePos p,SceneNeighborhood.LightingPolicy policy) {
        return new SceneNeighborhood(Map.of(p,cube(p)),SceneRenderEnvelope.NATIVE,policy);
    }
    @Test void legacyDefaultsRemainExplicitlyFullbright() {
        var p=new CubePos(0,8,0);var s=new SceneNeighborhood(Map.of(p,cube(p)));var b=new BlockPos(1,129,1);
        assertEquals(SceneNeighborhood.LightingPolicy.DIAGNOSTIC_FULLBRIGHT,s.lightingPolicy());
        assertEquals(15,s.getBrightness(LightLayer.BLOCK,b));assertEquals(15,s.getBrightness(LightLayer.SKY,b));
    }
    @Test void outdoorStoneHasZeroBlockLightAndFullSkyAtBothSignedEndpoints() {
        for(int y:new int[]{-11008,128,8850}) {
            var b=new BlockPos(-1,y,-1);var p=CubePos.fromBlock(-1,y,-1);
            var s=scene(p,SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE);
            assertSame(Blocks.STONE.defaultBlockState(),s.getBlockState(b));
            assertEquals(0,s.getBlockState(b).getLightEmission(s,b));
            assertEquals(0,s.getBrightness(LightLayer.BLOCK,b));assertEquals(15,s.getBrightness(LightLayer.SKY,b));
            assertEquals(0x00f00000,net.minecraft.client.renderer.LightTexture.pack(
                    s.getBrightness(LightLayer.BLOCK,b),s.getBrightness(LightLayer.SKY,b)));
        }
    }
    @Test void ambientAdjustmentIsClampedWithoutInventingBlockEmission() {
        var s=scene(new CubePos(0,8,0),SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE);var b=new BlockPos(1,129,1);
        assertEquals(15,s.getRawBrightness(b,Integer.MIN_VALUE));assertEquals(15,s.getRawBrightness(b,0));
        assertEquals(8,s.getRawBrightness(b,7));assertEquals(0,s.getRawBrightness(b,15));assertEquals(0,s.getRawBrightness(b,Integer.MAX_VALUE));
        assertEquals(0,s.getBrightness(LightLayer.BLOCK,b));
    }
    @Test void unknownOrUnsupportedLightingNeverBecomesInventedAir() {
        var s=scene(new CubePos(0,8,0),SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE);var outside=new BlockPos(16,129,1);
        assertThrows(SparseFacadeException.class,()->s.getBrightness(LightLayer.BLOCK,outside));
        assertThrows(SparseFacadeException.class,()->s.getBrightness(LightLayer.SKY,outside));
        assertThrows(SparseFacadeException.class,()->s.getRawBrightness(outside,0));
        assertThrows(SparseFacadeException.class,s::getLightEngine);
        assertThrows(SparseFacadeException.class,()->s.getBrightness(null,new BlockPos(1,129,1)));
    }
    @Test void trackedLightValuesRetainPolicyAndRejectChangedValueReplay() {
        var p=new CubePos(0,8,0);var s=scene(p,SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE);
        var recorder=new SceneReadFootprint.Recorder();var reads=new AtomicInteger();var tracked=s.tracking(c->reads.incrementAndGet(),recorder);
        assertSame(s.lightingPolicy(),tracked.lightingPolicy());var b=new BlockPos(1,129,1);
        assertEquals(0,tracked.getBrightness(LightLayer.BLOCK,b));assertEquals(15,tracked.getBrightness(LightLayer.SKY,b));
        var footprint=recorder.seal();assertEquals(2,reads.get());assertTrue(footprint.validate(Set.of(p),s::replay).matches());
        assertFalse(footprint.validate(Set.of(p),scene(p,SceneNeighborhood.LightingPolicy.DIAGNOSTIC_FULLBRIGHT)::replay).matches());
    }
    @Test void capturedDirectionalPolicyRemovesOnlyRequestedVanillaVertexDarkening() {
        var p=new CubePos(0,8,0);var s=new SceneNeighborhood(Map.of(p,cube(p)),SceneRenderEnvelope.NATIVE,
                SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,true);
        assertTrue(s.directionalShadingDisabled());
        for(var d:net.minecraft.core.Direction.values()) { assertEquals(1.0f,s.getShade(d,true));assertEquals(1.0f,s.getShade(d,false)); }
        assertEquals(1.0f,s.getShade(0,-1,0,true));
        var legacy=scene(p,SceneNeighborhood.LightingPolicy.DIAGNOSTIC_FULLBRIGHT);
        assertFalse(legacy.directionalShadingDisabled());assertEquals(0.5f,legacy.getShade(net.minecraft.core.Direction.DOWN,true));
    }
    @Test void directionalPolicyIsTrackedAndGloballyReplayedEvenWithUnchangedOwners() {
        var p=new CubePos(0,8,0);var s=new SceneNeighborhood(Map.of(p,cube(p)),SceneRenderEnvelope.NATIVE,
                SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,true);
        var recorder=new SceneReadFootprint.Recorder();var tracked=s.tracking(c->{},recorder);
        assertTrue(tracked.directionalShadingDisabled());tracked.getShade(net.minecraft.core.Direction.DOWN,true);
        var f=recorder.seal();assertTrue(f.validate(Set.of(),s::replay).matches());
        assertFalse(f.validate(Set.of(),scene(p,SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE)::replay).matches());
    }
    @Test void nullPolicyIsRejectedBeforeAnySampling() {
        var p=new CubePos(0,8,0);assertThrows(NullPointerException.class,()->scene(p,null));
    }
}
