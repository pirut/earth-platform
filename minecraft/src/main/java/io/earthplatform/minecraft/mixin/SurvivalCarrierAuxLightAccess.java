package io.earthplatform.minecraft.mixin;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reject unbudgeted auxiliary-light payload without invoking its serializer or clearing it. */
@Mixin(value=LevelChunkAuxiliaryLightManager.class, remap=false)
public interface SurvivalCarrierAuxLightAccess {
    @Accessor("owner") LevelChunk earthplatform$carrierAuxOwner();
    @Accessor("lights") Map<BlockPos, Byte> earthplatform$carrierAuxLights();
}
