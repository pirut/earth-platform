package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalCarrierChunkAccess;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkAccess.class)
abstract class SurvivalChunkAccessMixin implements SurvivalCarrierChunkAccess {
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;
    @Shadow @Final protected LevelChunkSection[] sections;
    @Shadow @Final protected Map<Heightmap.Types,Heightmap> heightmaps;
    @Shadow @Final protected Map<BlockPos,CompoundTag> pendingBlockEntities;
    @Shadow @Final protected Map<BlockPos,BlockEntity> blockEntities;
    @Shadow @Final protected it.unimi.dsi.fastutil.shorts.ShortList[] postProcessing;
    @Shadow @Final private Map<net.minecraft.world.level.levelgen.structure.Structure,
            net.minecraft.world.level.levelgen.structure.StructureStart> structureStarts;
    @Shadow @Final private Map<net.minecraft.world.level.levelgen.structure.Structure,
            it.unimi.dsi.fastutil.longs.LongSet> structuresRefences;
    @Shadow @Final protected net.minecraft.world.level.chunk.UpgradeData upgradeData;
    @Shadow protected net.minecraft.world.level.levelgen.blending.BlendingData blendingData;
    @Override public LevelHeightAccessor earthplatform$carrierHeightOwner() { return levelHeightAccessor; }
    @Override public boolean earthplatform$carrierSerializationMetadataEmpty() {
        return earthplatform$carrierMetadataEmpty() && structureStarts.isEmpty() && structuresRefences.isEmpty()
                && upgradeData.isEmpty() && blendingData == null;
    }
    @Override public LevelChunkSection[] earthplatform$carrierSections() { return sections; }
    @Override public Map<Heightmap.Types,Heightmap> earthplatform$carrierHeightmaps() { return heightmaps; }
    @Override public boolean earthplatform$carrierMetadataEmpty() {
        if(!pendingBlockEntities.isEmpty() || !blockEntities.isEmpty()) return false;
        for(var list:postProcessing) if(list!=null && !list.isEmpty()) return false;
        return true;
    }
    @Inject(method={"getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;",
            "getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;",
            "getHighestFilledSectionIndex()I","isYSpaceEmpty(II)Z","isSectionEmpty(I)Z",
            "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",
            "getHeightmaps()Ljava/util/Collection;",
            "getOrCreateHeightmapUnprimed(Lnet/minecraft/world/level/levelgen/Heightmap$Types;)Lnet/minecraft/world/level/levelgen/Heightmap;",
            "hasPrimedHeightmap(Lnet/minecraft/world/level/levelgen/Heightmap$Types;)Z",
            "getNoiseBiome(III)Lnet/minecraft/core/Holder;",
            "getSkyLightSources()Lnet/minecraft/world/level/lighting/ChunkSkyLightSources;"},at=@At("HEAD"))
    private void earthplatform$survivalTerrainRead(CallbackInfoReturnable<?> result) {
        if(levelHeightAccessor instanceof Level level) SurvivalLevelBinding.rejectTerrain(level,result.getId());
    }
    @Inject(method={"initializeLightSources()V","addPackedPostProcess(SI)V",
            "findBlocks(Ljava/util/function/Predicate;Ljava/util/function/BiPredicate;Ljava/util/function/BiConsumer;)V",
            "setHeightmap(Lnet/minecraft/world/level/levelgen/Heightmap$Types;[J)V",
            "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V"},at=@At("HEAD"))
    private void earthplatform$survivalTerrainWrite(CallbackInfo result) {
        if(levelHeightAccessor instanceof Level level) SurvivalLevelBinding.rejectTerrain(level,result.getId());
    }
}
