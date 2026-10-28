package io.earthplatform.minecraft.fabric.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A connection's network channel, which NeoForge exposes and vanilla keeps private. */
@Mixin(Connection.class)
public interface ConnectionAccess {
    @Accessor("channel") Channel earthplatform$channel();
}
