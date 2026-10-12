package io.earthplatform.minecraft.coregate;

import java.util.Arrays;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Exact producer-attributed completed work and an independent server-tick liveness observation. */
public record NativeDistanceProgressPayload(long episode, long requestEpoch, long serial,
        int selected, int effective, int serverLimit, long actorEpoch, long resourceGeneration,
        long publication, String digest, String planIdentity, long observation, long peerTick,
        boolean server, long[] completed) implements CustomPacketPayload {
    public NativeDistanceProgressPayload {
        if (episode <= 0 || observation < 0 || peerTick < 0)
            throw new IllegalArgumentException("Invalid native-distance progress episode or observation");
        NativeDistanceProgressWatchdog.validateIdentity(requestEpoch, serial, selected, effective,
                serverLimit, actorEpoch, resourceGeneration, publication, digest, planIdentity);
        if (completed == null || completed.length != NativeDistanceProgressWatchdog.LANE_COUNT)
            throw new IllegalArgumentException("Progress vector must have exactly one entry per lane");
        completed = completed.clone();
        for (NativeDistanceProgressWatchdog.Lane lane : NativeDistanceProgressWatchdog.Lane.values()) {
            long value = completed[lane.ordinal()];
            if (value < 0 || value > lane.cap() || lane.server() != server && value != 0)
                throw new IllegalArgumentException("Invalid native-distance completed work: " + lane);
        }
    }
    @Override public long[] completed() { return completed.clone(); }

    public static final int WIRE_VERSION = 2;
    public static final Type<NativeDistanceProgressPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("earthplatform", "native_distance_progress_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NativeDistanceProgressPayload> CODEC = new StreamCodec<>() {
        @Override public NativeDistanceProgressPayload decode(RegistryFriendlyByteBuf b) {
            long episode = b.readLong(), requestEpoch = b.readLong(), serial = b.readLong();
            int selected = b.readVarInt(), effective = b.readVarInt(), serverLimit = b.readVarInt();
            long actorEpoch = b.readLong(), resources = b.readLong(), publication = b.readLong();
            String digest = b.readUtf(64), planIdentity = b.readUtf(64);
            long observation = b.readLong(), peerTick = b.readLong();
            int origin = b.readUnsignedByte();
            if (origin > 1) throw new IllegalArgumentException("Invalid native-distance progress origin");
            long[] completed = new long[NativeDistanceProgressWatchdog.LANE_COUNT];
            for (int i = 0; i < completed.length; i++) completed[i] = b.readLong();
            return new NativeDistanceProgressPayload(episode, requestEpoch, serial, selected, effective,
                    serverLimit, actorEpoch, resources, publication, digest, planIdentity,
                    observation, peerTick, origin == 1, completed);
        }
        @Override public void encode(RegistryFriendlyByteBuf b, NativeDistanceProgressPayload p) {
            b.writeLong(p.episode); b.writeLong(p.requestEpoch); b.writeLong(p.serial);
            b.writeVarInt(p.selected); b.writeVarInt(p.effective); b.writeVarInt(p.serverLimit);
            b.writeLong(p.actorEpoch); b.writeLong(p.resourceGeneration); b.writeLong(p.publication);
            b.writeUtf(p.digest, 64); b.writeUtf(p.planIdentity, 64);
            b.writeLong(p.observation); b.writeLong(p.peerTick); b.writeBoolean(p.server);
            for (long completed : p.completed) b.writeLong(completed);
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    @Override public boolean equals(Object other) {
        return other instanceof NativeDistanceProgressPayload p && episode == p.episode
                && requestEpoch == p.requestEpoch && serial == p.serial && selected == p.selected
                && effective == p.effective && serverLimit == p.serverLimit && actorEpoch == p.actorEpoch
                && resourceGeneration == p.resourceGeneration && publication == p.publication
                && observation == p.observation && peerTick == p.peerTick && server == p.server
                && digest.equals(p.digest) && planIdentity.equals(p.planIdentity)
                && Arrays.equals(completed, p.completed);
    }
    @Override public int hashCode() {
        return 31 * Objects.hash(episode, requestEpoch, serial, selected, effective, serverLimit,
                actorEpoch, resourceGeneration, publication, digest, planIdentity, observation,
                peerTick, server) + Arrays.hashCode(completed);
    }
}
