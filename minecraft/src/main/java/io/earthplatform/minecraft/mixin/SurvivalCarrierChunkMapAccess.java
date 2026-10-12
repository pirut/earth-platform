package io.earthplatform.minecraft.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw ownership census only; this supplies no terrain access and creates no native owners. */
@Mixin(ChunkMap.class)
public interface SurvivalCarrierChunkMapAccess {
    @Accessor("updatingChunkMap") Long2ObjectLinkedOpenHashMap<ChunkHolder> earthplatform$carrierUpdating();
    @Accessor("visibleChunkMap") Long2ObjectLinkedOpenHashMap<ChunkHolder> earthplatform$carrierVisible();
    @Accessor("pendingUnloads") Long2ObjectLinkedOpenHashMap<ChunkHolder> earthplatform$carrierPendingUnloads();
}
