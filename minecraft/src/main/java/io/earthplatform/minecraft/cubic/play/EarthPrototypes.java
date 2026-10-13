package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * The prototype dimensions (calibration windows coast, everest, trench and their real-data twins,
 * copernicus_ridge, and the scene and survival carriers) live in a built-in data pack that only
 * exists with {@code -Dearthplatform.prototypes=true}, so players' worlds don't load and save nine
 * empty dimensions beside the Earth. The dev client and game test runs set the flag; worlds that
 * already have them keep them.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class EarthPrototypes {
    public static final boolean ENABLED = Boolean.getBoolean("earthplatform.prototypes");

    private EarthPrototypes() {}

    @SubscribeEvent static void packs(AddPackFindersEvent event) {
        if (!ENABLED) return;
        event.addPackFinders(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "packs/prototypes"), PackType.SERVER_DATA,
                Component.literal("Earth Platform prototypes"), PackSource.BUILT_IN, true, Pack.Position.TOP);
    }
}
