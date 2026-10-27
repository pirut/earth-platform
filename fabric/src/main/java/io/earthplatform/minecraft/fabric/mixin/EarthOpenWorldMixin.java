package io.earthplatform.minecraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Lifecycle;
import io.earthplatform.minecraft.cubic.Cubic;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Opening an Earth world skips the experimental settings warning its data pack dimension would
 * bring up every time, as creating one does. NeoForge asks once per world and remembers the answer.
 */
@Mixin(WorldOpenFlows.class)
abstract class EarthOpenWorldMixin {
    @WrapOperation(method = "openWorldCheckWorldStemCompatibility", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/WorldData;worldGenSettingsLifecycle()Lcom/mojang/serialization/Lifecycle;"))
    private Lifecycle earthplatform$trustEarth(WorldData data, Operation<Lifecycle> original,
                                               LevelStorageSource.LevelStorageAccess storage, WorldStem stem, PackRepository packs, Runnable onFail) {
        boolean earth = stem.registries().compositeAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(Cubic.LEVEL.location());
        return earth ? Lifecycle.stable() : original.call(data);
    }
}
