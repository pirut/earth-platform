package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla asks the chunk it stands in which structures reach it, but the cubic world's structures
 * are placed by its own generator and the carrier's chunks hold no references. Answer from the
 * generator instead, so the checks of what lies inside a structure work: guardians spawn in ocean
 * monuments, witches in swamp huts, nothing in trial chambers, and location advancements trigger.
 */
@Mixin(StructureManager.class)
abstract class CubicStructureManagerMixin {
    @Shadow @Final private LevelAccessor level;

    @Nullable private ServerCubeWorld earthplatform$cubes() {
        return level instanceof ServerLevel server && CubicLevel.of(server) instanceof ServerCubeWorld cubes ? cubes : null;
    }

    @Inject(method = "getAllStructuresAt", at = @At("HEAD"), cancellable = true)
    private void earthplatform$allAt(BlockPos pos, CallbackInfoReturnable<Map<Structure, LongSet>> result) {
        ServerCubeWorld cubes = earthplatform$cubes();
        if (cubes == null) return;
        Map<Structure, LongSet> found = new HashMap<>();
        for (StructureStart start : cubes.generator().structureStarts(pos.getX() >> 4, pos.getZ() >> 4))
            found.computeIfAbsent(start.getStructure(), s -> new LongOpenHashSet()).add(start.getChunkPos().toLong());
        result.setReturnValue(found);
    }

    @Inject(method = "hasAnyStructureAt", at = @At("HEAD"), cancellable = true)
    private void earthplatform$anyAt(BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        ServerCubeWorld cubes = earthplatform$cubes();
        if (cubes != null) result.setReturnValue(!cubes.generator().structureStarts(pos.getX() >> 4, pos.getZ() >> 4).isEmpty());
    }

    /**
     * As a probe: vanilla asks it of the references {@code getAllStructuresAt} gave (mob spawns round
     * every player), and Earth's own lookups by section never come here. A start evicted since must
     * not take the places kept for a player's own lookups.
     */
    @Inject(method = "fillStartsForStructure", at = @At("HEAD"), cancellable = true)
    private void earthplatform$fill(Structure structure, LongSet references, Consumer<StructureStart> consumer, CallbackInfo info) {
        ServerCubeWorld cubes = earthplatform$cubes();
        if (cubes == null) return;
        info.cancel();
        references.forEach(reference -> {
            StructureStart start = cubes.generator().structureStart(structure, ChunkPos.getX(reference), ChunkPos.getZ(reference), true);
            if (start != null && start.isValid()) consumer.accept(start);
        });
    }

    @Inject(method = "startsForStructure(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$startsInChunk(ChunkPos chunk, Predicate<Structure> wanted, CallbackInfoReturnable<List<StructureStart>> result) {
        ServerCubeWorld cubes = earthplatform$cubes();
        if (cubes == null) return;
        result.setReturnValue(new ArrayList<>(cubes.generator().structureStarts(chunk.x, chunk.z, wanted)));
    }

    /** As vanilla's, asked as a probe: mob spawns ask it round every player (the fortress check). */
    @Inject(method = "getStructureAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
            at = @At("HEAD"), cancellable = true)
    private void earthplatform$structureAt(BlockPos pos, Structure structure, CallbackInfoReturnable<StructureStart> result) {
        ServerCubeWorld cubes = earthplatform$cubes();
        if (cubes == null) return;
        for (StructureStart start : cubes.generator().structureStarts(pos.getX() >> 4, pos.getZ() >> 4, s -> s == structure, true)) {
            if (start.getBoundingBox().isInside(pos)) {
                result.setReturnValue(start);
                return;
            }
        }
        result.setReturnValue(StructureStart.INVALID_START);
    }

    @Inject(method = "startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$startsInSection(SectionPos section, Structure structure, CallbackInfoReturnable<List<StructureStart>> result) {
        ServerCubeWorld cubes = earthplatform$cubes();
        if (cubes == null) return;
        result.setReturnValue(new ArrayList<>(cubes.generator().structureStarts(section.x(), section.z(), s -> s == structure)));
    }
}
