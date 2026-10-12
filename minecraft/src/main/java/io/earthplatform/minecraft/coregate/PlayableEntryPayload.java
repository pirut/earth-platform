package io.earthplatform.minecraft.coregate;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One bounded server challenge and render-thread reply. It never directly moves a player. */
public record PlayableEntryPayload(long challenge, int kind, long reloadSequence, String reason) implements CustomPacketPayload {
    public static final int REQUEST = 0, READY = 1, REFUSED = 2;
    public static final Type<PlayableEntryPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("earthplatform", "playable_entry"));
    public PlayableEntryPayload {
        if (challenge <= 0 || kind < REQUEST || kind > REFUSED || reloadSequence < 0 || reason == null || reason.length() > 256)
            throw new IllegalArgumentException("Invalid playable entry challenge");
        if (kind != REFUSED && !reason.isEmpty() || kind == REQUEST && reloadSequence != 0 || kind == REFUSED && reason.isBlank())
            throw new IllegalArgumentException("Invalid playable entry result");
    }
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayableEntryPayload> CODEC = new StreamCodec<>() {
        public PlayableEntryPayload decode(RegistryFriendlyByteBuf b) { return new PlayableEntryPayload(b.readLong(), b.readUnsignedByte(), b.readLong(), b.readUtf(256)); }
        public void encode(RegistryFriendlyByteBuf b, PlayableEntryPayload p) { b.writeLong(p.challenge); b.writeByte(p.kind); b.writeLong(p.reloadSequence); b.writeUtf(p.reason, 256); }
    };
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
