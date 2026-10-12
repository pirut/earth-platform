package io.earthplatform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.survival.interaction.host.NativeLocalCausalObservation;
import io.netty.channel.Channel;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Original accepted LocalChannel initializer and genuine native connection roster addition. */
@Mixin(targets="net.minecraft.server.network.ServerConnectionListener$2")
abstract class NativeLocalAcceptedObservationMixin {
    @WrapOperation(method="initChannel(Lio/netty/channel/Channel;)V",
            at=@At(value="NEW", target="(Lnet/minecraft/network/protocol/PacketFlow;)Lnet/minecraft/network/Connection;"), require=1, allow=1)
    private Connection earthplatform$observeAcceptedConstructor(PacketFlow flow, Operation<Connection> original, @Local(argsOnly=true) Channel channel) {
        Connection result = original.call(flow);
        try { NativeLocalCausalObservation.serverConstructed(result, channel, flow); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        return result;
    }
    @WrapOperation(method="initChannel(Lio/netty/channel/Channel;)V",
            at=@At(value="INVOKE", target="Ljava/util/List;add(Ljava/lang/Object;)Z", remap=false), require=1, allow=1)
    private boolean earthplatform$observeRosterAdd(List<?> roster, Object value, Operation<Boolean> original) {
        boolean result = original.call(roster, value);
        if (value instanceof Connection connection) try { NativeLocalCausalObservation.serverRegistered(connection, result); }
        catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        return result;
    }
}
