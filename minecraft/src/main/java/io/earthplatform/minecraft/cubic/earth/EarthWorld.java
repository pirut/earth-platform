package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.github.pirut.chunks3.Cubic;
import io.github.pirut.chunks3.CubicArrivalEvent;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.cubic.play.EarthMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * The Earth's cubic dimension. It keeps the key the first Earth worlds were saved with, so they load
 * as before, and registers with the cubic engine before any level loads.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class EarthWorld {
    public static final ResourceKey<Level> LEVEL =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "cubic"));

    private EarthWorld() {}

    /** Called once from the mod's constructor. */
    public static void register() {
        var dimension = Cubic.register(LEVEL, (level, folder) ->
                CubicSettings.open(folder, level.getSeed(), level.registryAccess().registryOrThrow(Registries.BIOME)));
        if (FMLEnvironment.dist.isClient()) dimension.cloudHeight(() -> EarthConfig.CLOUD_HEIGHT.get());
        Cubic.addTransit(entity -> entity instanceof LivingEntity living && JetStream.aboveTerrain(living));
    }

    @SubscribeEvent public static void arrived(CubicArrivalEvent event) { EarthMap.arrived(event.getEntity()); }
}
