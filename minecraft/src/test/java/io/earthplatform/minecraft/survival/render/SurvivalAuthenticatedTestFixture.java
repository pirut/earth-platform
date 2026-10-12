package io.earthplatform.minecraft.survival.render;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.*;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.*;
import io.earthplatform.minecraft.survival.source.*;
import io.earthplatform.minecraft.survival.world.*;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;

/** Test-only immutable-input fixture. It performs no native model compilation, upload or GL draw. */
public final class SurvivalAuthenticatedTestFixture implements AutoCloseable {
    public final NativeDistanceCpuBudget budget = new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);
    public final NativeSurvivalWorld world;
    public final SurvivalPalette palette;
    public final MarinVisualCubeSource source;
    public final SurvivalCompleteOverlay.CaptureOwner owner;
    public final SurvivalCompleteOverlay.Content content;
    public final SurvivalCompleteOverlay.PlanInput plan;
    public final SurvivalCompleteOverlay.CurrentLease lease;
    public final SceneNativeShaderContract.Capture capture = SceneNativeShaderContract.defaultPath();
    public final int pageIndex;
    public final SurvivalDistancePagePlan.Page page;
    public final Map<CubePos, SceneCube> cubes;
    public final CubePos override;
    public final NativeCubeCodec codec;
    private final SurvivalCheckpointStore store;
    public SurvivalAuthenticatedTestFixture(Path path, boolean explicitAir) throws Exception {
        java.nio.file.Files.createDirectories(path);
        var registries = VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
        var definition = MarinSurvivalResources.installAndLoad(path.resolve("source"));
        source = new MarinVisualCubeSource(definition, new SurvivalRenderPalette(palette));
        var identity = new WorldIdentity(39, "authenticated-test", "039", definition.identity(), palette.identity());
        var envelope = definition.envelope();
        var bounds = new NativeSurvivalWorld.Bounds(new CubePos(envelope.minCenterX(), envelope.minCenterY(), envelope.minCenterZ()),
                new CubePos(envelope.maxCenterXExclusive()-1, envelope.maxCenterYExclusive()-1, envelope.maxCenterZExclusive()-1));
        world = new NativeSurvivalWorld(identity, palette, bounds,
                new NativeSurvivalWorld.Limits(16,16,8,16,16384,8192,64,65536,16,16));
        override = definition.anchor().center();
        if (explicitAir) world.admitGenerated(override, ImmutableCube.uniform(palette.id(Blocks.AIR.defaultBlockState())));
        var actions = new NativeWholeAction.Owner(world, registries, (action, effects) -> receipt -> {});
        var binding = new NativeSurvivalCheckpoint.Binding(new UUID(9,1), "proof-test", identity, definition.identity(), palette.identity(),
                "test-data-packs", new UUID(9,2), ResourceLocation.parse("earthplatform:survival_carrier"));
        var storeLimits = new SurvivalCheckpointStore.Limits(1, 2<<20, 2<<20,4096);
        var codecLimits = new NativeSurvivalCheckpoint.Limits(32,0,0,8,1<<20);
        var checkpointCodec = new SurvivalCheckpointCodec(binding,codecLimits,registries,palette,bounds);
        store = new SurvivalCheckpointStore(path.resolve("checkpoints"), checkpointCodec.storageIdentity(),storeLimits);
        var checkpoints = new SurvivalCheckpointCoordinator(actions,store,storeLimits,checkpointCodec,new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
        owner = new SurvivalCompleteOverlay.CaptureOwner(world,actions,checkpoints,source,budget,
                new SurvivalCompleteOverlay.Limits(64,4<<20,1<<20));
        content = owner.capture();
        plan = content.plan(override,new SurvivalDistancePagePlan.DistanceChoice(2,2,0),
                new SurvivalDistancePagePlan.Limits(100000,1000,1000,10000,200000,200000,64,2<<20));
        int chosen = 0;
        for (int i=0;i<plan.pageCount();i++) {
            if (explicitAir && plan.page(i).members().contains(override)) { chosen=i; break; }
            if (!explicitAir && plan.page(i).dependencies().size()<plan.page(chosen).dependencies().size()) chosen=i;
        }
        pageIndex=chosen; page=plan.page(chosen); lease=owner.authorize(plan);
        codec = new NativeCubeCodec(identity,palette.context());
        var map = new TreeMap<CubePos,SceneCube>();
        for (var at:page.dependencies()) {
            var blocks = explicitAir && at.equals(override) ? ImmutableCube.uniform(palette.id(Blocks.AIR.defaultBlockState()))
                    : source.generate(at,new CancellationToken());
            map.put(at,cube(at,blocks));
        }
        cubes=Map.copyOf(map);
    }
    public SceneCube cube(CubePos at, ImmutableCube blocks) {
        return new SceneCube(codec.materialize(at,blocks,0,1,0),palette.context(),SceneRenderEnvelope.NATIVE,
                state -> { source.palette().id(state); return true; });
    }
    SurvivalNativePageCompiler.Verification verification(Map<CubePos,SceneCube> owned) {
        return new SurvivalNativePageCompiler.Verification(lease,plan,pageIndex,owned,capture,
                SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,false,budget);
    }
    public SurvivalNativePageCompiler.AuthenticatedContent proof() { return proof(capture); }
    public SurvivalNativePageCompiler.AuthenticatedContent proof(SceneNativeShaderContract.Capture resource) {
        try(var verification=new SurvivalNativePageCompiler.Verification(lease,plan,pageIndex,cubes,resource,
                SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,false,budget)) {
            while(!verification.step(4096,Long.MAX_VALUE).complete()) {}
            return verification.finish();
        }
    }
    public void close() throws Exception { lease.close();plan.close();content.close();owner.close();store.close(); }
}
