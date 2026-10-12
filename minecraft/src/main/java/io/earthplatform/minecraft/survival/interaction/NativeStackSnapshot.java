package io.earthplatform.minecraft.survival.interaction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/**
 * An immutable, named-registry native stack encoding. Native ItemStack.CODEC retains the complete
 * component patch, including damage and removed defaults. Neither a numeric item ID nor a live
 * ItemStack is retained. The caller binds snapshots to its saved registry/data-pack identity.
 */
public final class NativeStackSnapshot {
    public static final int MAX_BYTES = 24576;
    private static final int MAX_DEPTH = 64;
    private static final byte[] EMPTY = "null".getBytes(StandardCharsets.UTF_8);
    private final byte[] encoded;

    private NativeStackSnapshot(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_BYTES)
            throw new IllegalArgumentException("Native stack component encoding exceeds its bound");
        this.encoded = encoded.clone();
    }

    public static NativeStackSnapshot capture(ItemStack stack, HolderLookup.Provider registries) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(registries, "registries");
        if (stack.isEmpty()) return new NativeStackSnapshot(EMPTY);
        JsonElement encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries),
                stack.copy()).getOrThrow();
        var snapshot = new NativeStackSnapshot(canonical(encoded, 0).toString().getBytes(StandardCharsets.UTF_8));
        // Native DataComponentPatch.CODEC intentionally omits transient components. Saving a
        // reduced stack is not acceptable at this transaction boundary, even if native save code
        // normally does so. Refuse every lossy codec roundtrip before any live mutation occurs.
        if (!ItemStack.matches(stack, snapshot.materialize(registries)))
            throw new IllegalArgumentException("Native stack contains a transient or non-roundtripping component");
        return snapshot;
    }

    /** Decode now, before a world commit. Invalid, trailing, or noncanonical input is refused. */
    public static NativeStackSnapshot decode(byte[] encoded, HolderLookup.Provider registries) {
        var result = new NativeStackSnapshot(encoded);
        ItemStack stack = result.materialize(registries);
        if (!Arrays.equals(result.encoded, capture(stack, registries).encoded))
            throw new IllegalArgumentException("Noncanonical native stack encoding");
        return result;
    }

    public ItemStack materialize(HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        if (Arrays.equals(encoded, EMPTY)) return ItemStack.EMPTY;
        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Malformed native stack UTF-8", invalid);
        }
        checkDepthBeforeParsing(json);
        JsonElement value = JsonParser.parseString(json);
        // Bound recursion even for a corrupt saved payload before invoking the native codec.
        canonical(value, 0);
        return ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), value).getOrThrow();
    }

    public boolean matches(ItemStack live, HolderLookup.Provider registries) {
        return equals(capture(live, registries));
    }
    public byte[] encoded() { return encoded.clone(); }
    public int encodedBytes() { return encoded.length; }
    public boolean isEmpty() { return Arrays.equals(encoded, EMPTY); }

    private static void checkDepthBeforeParsing(String json) {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH) throw new IllegalArgumentException("Native stack component nesting exceeds its bound");
            } else if (c == '}' || c == ']') depth--;
        }
    }

    private static JsonElement canonical(JsonElement value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Native stack component nesting exceeds its bound");
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonObject()) {
            JsonObject copy = new JsonObject();
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet()))
                copy.add(key, canonical(value.getAsJsonObject().get(key), depth + 1));
            return copy;
        }
        if (value.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) copy.add(canonical(element, depth + 1));
            return copy;
        }
        return value.deepCopy();
    }
    @Override public boolean equals(Object other) {
        return other instanceof NativeStackSnapshot snapshot && Arrays.equals(encoded, snapshot.encoded);
    }
    @Override public int hashCode() { return Arrays.hashCode(encoded); }
    @Override public String toString() { return "NativeStackSnapshot[" + encoded.length + " bytes]"; }
}
