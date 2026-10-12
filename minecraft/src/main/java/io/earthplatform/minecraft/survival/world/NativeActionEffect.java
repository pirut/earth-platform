package io.earthplatform.minecraft.survival.world;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, bounded adapter-owned native effect payload. The interaction adapter supplies a
 * versioned registry-aware encoding retaining full stack components and exact entity state.
 * This object never contains a mutable ItemStack, native entity, Runnable, or live callback.
 */
public final class NativeActionEffect {
    public static final int MAX_PAYLOAD_BYTES = 65536;
    private final String type;
    private final byte[] payload;
    public NativeActionEffect(String type, byte[] payload) {
        this.type = Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (!type.matches("[a-z0-9_.:-]{1,64}") || payload.length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Bounded versioned effect type and payload required");
        this.payload = payload.clone();
    }
    public String type() { return type; }
    public int payloadBytes() { return payload.length; }
    public byte[] payload() { return payload.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof NativeActionEffect effect && type.equals(effect.type)
                && Arrays.equals(payload, effect.payload);
    }
    @Override public int hashCode() { return 31 * type.hashCode() + Arrays.hashCode(payload); }
}
