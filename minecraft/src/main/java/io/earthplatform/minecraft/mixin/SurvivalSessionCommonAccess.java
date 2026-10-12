package io.earthplatform.minecraft.mixin;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Exact declaring-class bridge for native keepalive while gameplay listener work is held. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface SurvivalSessionCommonAccess {
 @Invoker("keepConnectionAlive") void earthplatform$keepSessionConnectionAlive();
}
