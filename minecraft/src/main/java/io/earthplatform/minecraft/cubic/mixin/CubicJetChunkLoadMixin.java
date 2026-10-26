package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import io.earthplatform.minecraft.cubic.jet.OceanCurrent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge loads the carrier chunk under every entity that moves, on the server thread. A glider
 * in the cubic world crosses several carrier chunks a tick at jet speed, so it skips that load;
 * the terrain it flies over comes from cubes, and the carrier chunk loads through its tickets. So
 * do a boat the ocean current carries and its riders.
 */
@Mixin(Entity.class)
abstract class CubicJetChunkLoadMixin {
    @WrapOperation(method = "setPosRaw(DDD)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk earthplatform$skipForGliders(Level level, int x, int z, Operation<LevelChunk> original) {
        if ((Object) this instanceof LivingEntity living && living.isFallFlying() && JetStream.inCubic(living)) return null;
        if (OceanCurrent.carried((Entity) (Object) this)) return null;
        return original.call(level, x, z);
    }
}
