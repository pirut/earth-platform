package io.earthplatform.minecraft.cubic.mixin;

import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a chunk ticket is for: a portal ticket's exit, a mod ticket's owner. */
@Mixin(Ticket.class)
public interface TicketAccess {
    @Accessor("key")
    Object earthplatform$key();
}
