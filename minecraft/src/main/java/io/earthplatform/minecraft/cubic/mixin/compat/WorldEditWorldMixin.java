package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.WorldEditCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * WorldEdit's world: its height is the cubic world's, not the carrier's, so selections, //up and
 * pastes reach any Y; block, block entity and biome reads and //setbiome go to the cubes. //regen is
 * {@link WorldEditRegenMixin}'s; should that miss, rebuilding the region from the carrier's flat
 * generator would wipe it to air, so it is refused.
 */
@Pseudo
@Mixin(targets = {"com.sk89q.worldedit.neoforge.NeoForgeWorld", "com.sk89q.worldedit.neoforge.internal.NeoForgeServerLevelDelegateProxy"}, remap = false)
abstract class WorldEditWorldMixin {
    @WrapOperation(method = "getMinY", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinBuildHeight()I"))
    private int earthplatform$bottom(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "getMaxY", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxBuildHeight()I"))
    private int earthplatform$top(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @WrapOperation(method = "getBlock", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$cubeState(LevelChunk chunk, BlockPos pos, Operation<BlockState> original) {
        CubeWorld cubes = WorldEditCompat.cubes(chunk.getLevel(), pos);
        return cubes != null ? cubes.getBlockState(pos) : original.call(chunk, pos);
    }

    @WrapOperation(method = "getFullBlock", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity earthplatform$cubeEntity(ChunkAccess chunk, BlockPos pos, Operation<BlockEntity> original) {
        return chunk instanceof LevelChunk carrier && WorldEditCompat.cubes(carrier.getLevel(), pos) != null
                ? carrier.getLevel().getBlockEntity(pos) : original.call(chunk, pos);
    }

    @WrapOperation(method = "getBlockEntity", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity earthplatform$proxyEntity(LevelChunk chunk, BlockPos pos, Operation<BlockEntity> original) {
        return WorldEditCompat.cubes(chunk.getLevel(), pos) != null ? chunk.getLevel().getBlockEntity(pos) : original.call(chunk, pos);
    }

    @WrapOperation(method = "getBiomeInChunk", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getNoiseBiome(III)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> earthplatform$cubeBiome(ChunkAccess chunk, int x, int y, int z, Operation<Holder<Biome>> original) {
        if (chunk instanceof LevelChunk carrier) {
            CubeWorld cubes = WorldEditCompat.cubes(carrier.getLevel(), new BlockPos(x << 2, y << 2, z << 2));
            Holder<Biome> biome = cubes == null ? null : cubes.getNoiseBiome(x, y, z);
            if (biome != null) return biome;
        }
        return original.call(chunk, x, y, z);
    }

    @WrapOperation(method = "setBiome", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSectionIndex(I)I"))
    private int earthplatform$biomeY(LevelChunk chunk, int y, Operation<Integer> original, @Share("y") LocalIntRef biomeY) {
        biomeY.set(y);
        return CubicLevel.of(chunk.getLevel()) != null ? 0 : original.call(chunk, y);
    }

    @WrapOperation(method = "setBiome", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection earthplatform$biomeSection(LevelChunk chunk, int index, Operation<LevelChunkSection> original, @Share("y") LocalIntRef biomeY) {
        BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX(), biomeY.get(), chunk.getPos().getMinBlockZ());
        CubeWorld cubes = WorldEditCompat.cubes(chunk.getLevel(), pos);
        if (cubes == null) return original.call(chunk, index);
        LevelChunkSection section = WorldEditCompat.biomeSection(cubes, pos);
        // A cube that can't be had takes the biome in a throwaway section rather than the carrier's.
        return section != null ? section : new LevelChunkSection(chunk.getLevel().registryAccess().registryOrThrow(Registries.BIOME));
    }

    @WrapOperation(method = "doRegen", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;getGenerator()Lnet/minecraft/world/level/chunk/ChunkGenerator;"))
    private ChunkGenerator earthplatform$noRegen(ServerChunkCache chunks, Operation<ChunkGenerator> original) {
        if (CubicLevel.of(chunks.getLevel()) != null) throw new UnsupportedOperationException("//regen does not work in the Earth world yet");
        return original.call(chunks);
    }
}
