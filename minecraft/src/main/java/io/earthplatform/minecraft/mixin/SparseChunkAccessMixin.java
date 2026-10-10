package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.residency.SparseFacadeHooks;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
abstract class SparseChunkAccessMixin implements io.earthplatform.minecraft.residency.SparseFacadeDiagnostics {
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;
    @Shadow @Final protected net.minecraft.world.level.chunk.LevelChunkSection[] sections;
    @Shadow @Final protected it.unimi.dsi.fastutil.shorts.ShortList[] postProcessing;
    @Override public int earthplatform$legacySectionSlots() { return sections.length; }
    @Override public int earthplatform$legacyPostprocessingSlots() { return postProcessing.length; }
    // getSection also rejects mutable section escape; Gate 0 exposes only guarded block/fluid access.
    @Inject(method = {"getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;",
            "getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;",
            "getPostProcessing()[Lit/unimi/dsi/fastutil/shorts/ShortList;",
            "getHighestFilledSectionIndex()I", "isYSpaceEmpty(II)Z", "isSectionEmpty(I)Z",
            "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",
            "getHeightmaps()Ljava/util/Collection;",
            "getOrCreateHeightmapUnprimed(Lnet/minecraft/world/level/levelgen/Heightmap$Types;)Lnet/minecraft/world/level/levelgen/Heightmap;",
            "hasPrimedHeightmap(Lnet/minecraft/world/level/levelgen/Heightmap$Types;)Z",
            "getNoiseBiome(III)Lnet/minecraft/core/Holder;",
            "getSkyLightSources()Lnet/minecraft/world/level/lighting/ChunkSkyLightSources;"}, at = @At("HEAD"))
    private void earthplatform$rejectColumnAccess(CallbackInfoReturnable<?> result) {
        SparseFacadeHooks.rejectLegacy(levelHeightAccessor, result.getId());
    }
    @Inject(method = {"initializeLightSources()V", "addPackedPostProcess(SI)V",
            "findBlocks(Ljava/util/function/Predicate;Ljava/util/function/BiPredicate;Ljava/util/function/BiConsumer;)V"}, at = @At("HEAD"))
    private void earthplatform$rejectColumnScan(CallbackInfo result) {
        SparseFacadeHooks.rejectLegacy(levelHeightAccessor, result.getId());
    }
}
