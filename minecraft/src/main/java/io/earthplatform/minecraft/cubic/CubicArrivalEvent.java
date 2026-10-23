package io.earthplatform.minecraft.cubic;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Posted on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS} on the server thread when a
 * player queued with {@link CubicEvents#arriveWhenReady} has landed in a cubic level.
 */
public final class CubicArrivalEvent extends PlayerEvent {
    public CubicArrivalEvent(ServerPlayer player) { super(player); }

    @Override public ServerPlayer getEntity() { return (ServerPlayer) super.getEntity(); }
}
