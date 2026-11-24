package io.earthplatform.minecraft.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The block or entity behind a NeoForge forced-chunk ticket (chunk-loader blocks, turtles, entities through
 * {@code TicketController}), so the Earth keeps the cubes round it loaded, not just the carrier chunk.
 */
@Mixin(targets = "net.neoforged.neoforge.common.world.chunk.ForcedChunkManager$TicketOwner")
public interface TicketOwnerAccess {
    @Accessor("owner")
    Comparable<?> earthplatform$owner();
}
