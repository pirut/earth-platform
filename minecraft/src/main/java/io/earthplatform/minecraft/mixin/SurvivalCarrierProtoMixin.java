package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalCarrierProtoAccess;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ProtoChunk.class)
abstract class SurvivalCarrierProtoMixin implements SurvivalCarrierProtoAccess {
    @Shadow @Final private List<CompoundTag> entities;
    @Shadow @Final private Map<GenerationStep.Carving, CarvingMask> carvingMasks;
    @Shadow private BelowZeroRetrogen belowZeroRetrogen;
    @Shadow @Final private ProtoChunkTicks<Block> blockTicks;
    @Shadow @Final private ProtoChunkTicks<Fluid> fluidTicks;
    @Override public boolean earthplatform$carrierProtoPayloadEmpty() {
        return entities.isEmpty() && carvingMasks.isEmpty() && belowZeroRetrogen == null;
    }
    public ChunkAccess.TicksToSave earthplatform$carrierTicks() {
        return new ChunkAccess.TicksToSave(blockTicks, fluidTicks);
    }
}
