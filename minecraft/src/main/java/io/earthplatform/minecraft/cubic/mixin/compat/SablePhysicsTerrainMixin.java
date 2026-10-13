package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable hands the physics engine the terrain sections around each ship, indexed within the level's
 * build range, so above the carrier ships met no ground and fell through it. In the cubic level every
 * height gets an index (see {@link SableCompat#sectionIndex}) and the section comes from its cube.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager", remap = false)
abstract class SablePhysicsTerrainMixin {
    @WrapOperation(method = "update", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getSectionIndexFromSectionY(I)I"))
    private int earthplatform$cubicIndex(ServerLevel level, int sectionY, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? SableCompat.sectionIndex(sectionY) : original.call(level, sectionY);
    }

    @WrapOperation(method = "update", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getSectionsCount()I"))
    private int earthplatform$cubicCount(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? SableCompat.SECTION_COUNT : original.call(level);
    }

    @WrapOperation(method = "addTicket", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection earthplatform$cubeSection(LevelChunk chunk, int index, Operation<LevelChunkSection> original) {
        return CubicLevel.of(chunk.getLevel()) != null ? SableCompat.section(chunk, index) : original.call(chunk, index);
    }
}
