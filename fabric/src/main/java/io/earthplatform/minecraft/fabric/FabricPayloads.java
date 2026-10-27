package io.earthplatform.minecraft.fabric;

import io.earthplatform.minecraft.platform.Payloads;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@link Payloads} on Fabric. Both sides learn every payload type; the server handlers register at
 * once, and the client ones wait for the client entry point ({@link #registerClient}).
 */
final class FabricPayloads implements Payloads {
    private record ToClient<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        void register() { ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.accept(payload)); }
    }

    private static final List<ToClient<?>> CLIENT = new ArrayList<>();

    @Override
    public <T extends CustomPacketPayload> Payloads toClient(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, Consumer<T> handler) {
        PayloadTypeRegistry.playS2C().register(type, codec);
        CLIENT.add(new ToClient<>(type, handler));
        return this;
    }

    @Override
    public <T extends CustomPacketPayload> Payloads toServer(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) {
        PayloadTypeRegistry.playC2S().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.accept(payload, context.player()));
        return this;
    }

    static void registerClient() {
        for (ToClient<?> receiver : CLIENT) receiver.register();
    }
}
