package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * /place jigsaw (and jigsaw blocks' "Generate") in a cubic level: pieces fit to the real ground
 * and may stand at any height of the world, not the 16-block carrier's, and each piece's cubes
 * load before it is placed, so none of its blocks is lost.
 */
@Mixin(JigsawPlacement.class)
abstract class CubicJigsawPlaceMixin {
    @WrapOperation(method = "generateJigsaw", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;getGenerator()Lnet/minecraft/world/level/chunk/ChunkGenerator;"))
    private static ChunkGenerator earthplatform$realGround(ServerChunkCache chunks, Operation<ChunkGenerator> original, @Local(argsOnly = true) ServerLevel level) {
        ChunkGenerator view = CubicLevel.of(level) instanceof ServerCubeWorld cubes ? cubes.generator().structureView() : null;
        return view != null ? view : original.call(chunks);
    }

    @ModifyArg(method = "generateJigsaw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;<init>(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/LevelHeightAccessor;Ljava/util/function/Predicate;)V"),
            index = 7)
    private static LevelHeightAccessor earthplatform$cubicHeights(LevelHeightAccessor heights, @Local(argsOnly = true) ServerLevel level) {
        return CubicLevel.of(level) != null ? LevelHeightAccessor.create(CubeWorld.MIN_Y, CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1) : heights;
    }

    @WrapOperation(method = "generateJigsaw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/PoolElementStructurePiece;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/core/BlockPos;Z)V"))
    private static void earthplatform$loadPiece(PoolElementStructurePiece piece, WorldGenLevel world, StructureManager structures, ChunkGenerator generator,
                                                RandomSource random, BoundingBox box, BlockPos pos, boolean keepJigsaws, Operation<Void> original,
                                                @Local(argsOnly = true) ServerLevel level) {
        if (CubicLevel.of(level) instanceof ServerCubeWorld cubes) {
            BoundingBox b = piece.getBoundingBox();
            int bottom = Math.max(b.minY(), CubeWorld.MIN_Y) >> 4, top = Math.min(b.maxY(), CubeWorld.MAX_Y) >> 4;
            for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++)
                for (int cy = bottom; cy <= top; cy++) cubes.loadNow(cx, cy, cz);
        }
        original.call(piece, world, structures, generator, random, box, pos, keepJigsaws);
    }
}
