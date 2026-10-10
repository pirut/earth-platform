package io.earthplatform.minecraft.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;

/** Validate all nested counts, IDs and bit storage before invoking Minecraft's palette reader. */
final class SectionWireValidator {
    private SectionWireValidator() {}
    static void validate(FriendlyByteBuf buffer, EnginePaletteContext context) {
        try {
            int declaredNonAir = buffer.readUnsignedShort();
            if (declaredNonAir > 4096) throw bad("Nonempty block count exceeds cube volume");
            int nonAir = container(buffer, 4096, 4, 8, Block.BLOCK_STATE_REGISTRY.size(), true, context);
            container(buffer, 64, 1, 3, context.biomes().size(), false, context);
            if (nonAir != declaredNonAir) throw bad("Nonempty block count disagrees with actual states");
            if (buffer.isReadable()) throw bad("Trailing section bytes");
        } catch (RuntimeException failure) {
            if (failure instanceof DecoderException malformed) throw malformed;
            throw new DecoderException("Truncated or malformed cube section", failure);
        }
    }
    private static int container(FriendlyByteBuf buffer, int cells, int minimumIndirect, int maximumIndirect, int registrySize, boolean blocks, EnginePaletteContext context) {
        int bits = buffer.readUnsignedByte();
        if (bits == 0) {
            int id = buffer.readVarInt(); validateId(id, blocks, registrySize, context);
            if (buffer.readVarInt() != 0) throw bad("Single-value palette must have no packed longs");
            return blocks && !Block.BLOCK_STATE_REGISTRY.byId(id).isEmpty() ? cells : 0;
        }
        int globalBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(registrySize - 1));
        boolean indirect = bits >= minimumIndirect && bits <= maximumIndirect;
        if (!indirect && (bits <= maximumIndirect || bits != globalBits)) throw bad("Unsupported palette bit width " + bits);
        int[] palette = null;
        if (indirect) {
            int count = buffer.readVarInt();
            if (count <= 0 || count > Math.min(cells, 1 << bits)) throw bad("Palette entry count exceeds its bit capacity");
            if (count > buffer.readableBytes()) throw bad("Truncated palette entries");
            palette = new int[count]; // count is checked before allocation: at most 256 blocks / 8 biomes.
            for (int i = 0; i < count; i++) { palette[i] = buffer.readVarInt(); validateId(palette[i], blocks, registrySize, context); }
        }
        int perLong = 64 / bits;
        int expected = (cells + perLong - 1) / perLong;
        int count = buffer.readVarInt();
        if (count != expected || count > buffer.readableBytes() / 8) throw bad("Packed-long length is invalid or truncated");
        long mask = (1L << bits) - 1;
        int remaining = cells, nonAir = 0;
        for (int word = 0; word < count; word++) {
            long values = buffer.readLong();
            for (int offset = 0; offset < perLong && remaining-- > 0; offset++) {
                int value = (int) ((values >>> (offset * bits)) & mask);
                int id;
                if (indirect) {
                    if (value >= palette.length) throw bad("Packed palette index out of range");
                    id = palette[value];
                } else { id = value; validateId(id, blocks, registrySize, context); }
                if (blocks && !Block.BLOCK_STATE_REGISTRY.byId(id).isEmpty()) nonAir++;
            }
        }
        return nonAir;
    }
    private static void validateId(int id, boolean blocks, int size, EnginePaletteContext context) {
        if (id < 0 || id >= size || (blocks ? Block.BLOCK_STATE_REGISTRY.byId(id) : context.biomes().byId(id)) == null) throw bad("Unknown registry ID " + id);
    }
    private static DecoderException bad(String message) { return new DecoderException(message); }
}
