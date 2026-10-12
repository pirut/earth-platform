package io.earthplatform.minecraft.survival.render;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.persistence.*;
import io.earthplatform.minecraft.survival.source.MarinSurvivalResources;
import io.earthplatform.minecraft.survival.source.MarinVisualCubeSource;
import io.earthplatform.minecraft.survival.world.*;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;

/** Test-only real owner/capture inputs. No reflective creation of production capabilities. */
public final class SurvivalDhTestSupport implements AutoCloseable {
    public static HolderLookup.Provider registries;
    public static SurvivalPalette palette;
    public static MarinSurvivalDefinition definition;
    public static MarinVisualCubeSource source;
    public static synchronized void setup(Path root) throws Exception {
        if(source!=null)return;
        registries=VanillaRegistries.createLookup();
        var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        biomes.register(Biomes.PLAINS,registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
        definition=MarinSurvivalResources.installAndLoad(root.resolve("source"));
        source=new MarinVisualCubeSource(definition,new SurvivalRenderPalette(palette));
    }
    public final NativeDistanceCpuBudget budget=new NativeDistanceCpuBudget(128L<<20);
    public final NativeSurvivalWorld world;
    public final SurvivalCheckpointStore store;
    public final SurvivalCompleteOverlay.CaptureOwner capture;
    public final SurvivalCheckpointCoordinator coordinator;
    private final NativeSurvivalCheckpoint.Binding binding;
    public SurvivalDhTestSupport(Path root) throws Exception {
        var identity=new WorldIdentity(39,"survival-dh-test","039",definition.identity(),palette.identity());
        var e=definition.envelope();
        var bounds=new NativeSurvivalWorld.Bounds(new CubePos(e.minCenterX(),e.minCenterY(),e.minCenterZ()),
                new CubePos(e.maxCenterXExclusive()-1,e.maxCenterYExclusive()-1,e.maxCenterZExclusive()-1));
        world=new NativeSurvivalWorld(identity,palette,bounds,new NativeSurvivalWorld.Limits(16,16,8,16,16384,8192,64,65536,16,16));
        binding=new NativeSurvivalCheckpoint.Binding(new UUID(31,32),"dh-test",identity,definition.identity(),palette.identity(),
                "test-data-packs",new UUID(33,34),ResourceLocation.parse("earthplatform:survival_carrier"));
        var checkpointLimits=new NativeSurvivalCheckpoint.Limits(32,0,0,8,1<<20);
        var storeLimits=new SurvivalCheckpointStore.Limits(1,2<<20,2<<20,4096);
        var codec=new SurvivalCheckpointCodec(binding,checkpointLimits,registries,palette,bounds);
        store=new SurvivalCheckpointStore(root.resolve("checkpoint"),codec.storageIdentity(),storeLimits);
        var actions=new NativeWholeAction.Owner(world,registries,(action,effects)->receipt->{});
        coordinator=new SurvivalCheckpointCoordinator(actions,store,storeLimits,codec,new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
        capture=new SurvivalCompleteOverlay.CaptureOwner(world,actions,coordinator,source,budget,
                new SurvivalCompleteOverlay.Limits(64,4<<20,1<<20));
    }
    public void override(CubePos position,BlockState state){world.admitGenerated(position,ImmutableCube.uniform(palette.id(state)));}
    public void saveAndRelease(CubePos cube) {
        var slots=new ArrayList<NativeStackSnapshot>();for(int i=0;i<41;i++)slots.add(NativeStackSnapshot.capture(ItemStack.EMPTY,registries));
        var inventory=new NativeInventorySnapshot(slots,0);var food=new NativeFoodSnapshot(20,0,5,0);var p=definition.anchor().center();
        var pose=new NativePlayerPoseSnapshot(binding.dimension(),p.minBlockX()+0.5,p.minBlockY()+1,p.minBlockZ()+0.5,
                0,0,0,0,0,0,true,GameType.SURVIVAL,null,new NativePlayerPoseSnapshot.AbilityState(false,false,false,false,false,0.05F,0.1F));
        var session=new NativeSurvivalCheckpoint.Session(new UUID(41,42),NativeSurvivalCheckpoint.SessionPhase.ENTERING,inventory,food,pose);
        coordinator.capture(session,()->new NativeSurvivalCheckpoint.Boundary(1,inventory,food,pose,List.of(),List.of()));
        coordinator.submit(Runnable::run);if(!coordinator.poll())throw new AssertionError("Synchronous test checkpoint did not finish");world.releaseClean(cube);
    }
    public SurvivalCompleteOverlay.PlanInput plan(int distance){
        try(var content=capture.capture()) {
            return content.plan(definition.anchor().center(),new SurvivalDistancePagePlan.DistanceChoice(distance,distance,0),
                    new SurvivalDistancePagePlan.Limits(2_000_000,5000,4000,100000,1000000,1000000,64,8L<<20));
        }
    }
    public static Map<CubePos,Long> masks(SurvivalCompleteOverlay.PlanInput plan){
        var result=new TreeMap<CubePos,Long>();for(int i=0;i<plan.pageCount();i++){
            var p=plan.page(i);result.put(p.address().minimum(),p.memberMask());
        }return result;
    }
    public long centerX(){return MarinSurvivalDefinition.snapCubeCenter(definition.anchor().center().x());}
    public long centerZ(){return MarinSurvivalDefinition.snapCubeCenter(definition.anchor().center().z());}
    @Override public void close()throws Exception{store.close();}
}
