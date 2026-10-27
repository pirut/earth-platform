package io.earthplatform.minecraft.neoforge;

import io.earthplatform.minecraft.platform.Payloads;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** {@link Payloads} on NeoForge: one versioned registrar per group. */
record NeoForgePayloads(PayloadRegistrar registrar) implements Payloads {
    @Override
    public <T extends CustomPacketPayload> Payloads toClient(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, Consumer<T> handler) {
        registrar.playToClient(type, codec, (payload, context) -> handler.accept(payload));
        return this;
    }

    @Override
    public <T extends CustomPacketPayload> Payloads toServer(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) {
        registrar.playToServer(type, codec, (payload, context) -> { if (context.player() instanceof ServerPlayer player) handler.accept(payload, player); });
        return this;
    }
}
