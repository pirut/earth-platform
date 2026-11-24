package io.earthplatform.minecraft.cubic.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.SortedArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A level's chunk tickets by chunk, which {@link io.earthplatform.minecraft.cubic.ChunkTickets} turns into cube holds in the Earth. */
@Mixin(DistanceManager.class)
public interface DistanceManagerAccess {
    @Accessor("tickets")
    Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> earthplatform$tickets();
}
