package io.earthplatform.minecraft.fabric.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A player's connection, which NeoForge exposes and vanilla keeps protected. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface PacketListenerAccess {
    @Accessor("connection") Connection earthplatform$connection();
}
