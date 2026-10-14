package io.earthplatform.minecraft.cubic.client;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every distinct look a far face can have, shared by all far columns so each face only carries a
 * small id, as Voxy keeps one table of block models: the sprite the block shows on that side, its
 * average colour (for faces drawn without the texture), the biome tint, and the shader pack's block
 * id. Ids are handed out on mesher threads and never reused; the render thread uploads new ones.
 */
final class FarLooks {
    /** Ints per look: sprite u0, v0, u1, v1 (float bits), average RGB, tint RGB, pack block id, flags. */
    static final int INTS = 8;
    /** Flag: the face shows its block texture rather than the texture's average colour. */
    static final int TEXTURED = 1;

    private record Key(BlockState state, int face, int tint, boolean textured, int blockId) {}

    private final Object2IntOpenHashMap<Key> ids = new Object2IntOpenHashMap<>();
    private int[] data = new int[INTS * 1024];
    private int count;

    /** The id of a block's look on one side, in a biome; blockIds are the shader pack's, or null with no pack. */
    synchronized int id(FarFaces faces, BlockState state, Direction direction, Holder<Biome> biome, boolean textured,
                        @Nullable Object2IntMap<BlockState> blockIds) {
        FarFaces.Face face = faces.face(state, direction);
        int tint = faces.tint(state, biome, face);
        int blockId = blockIds == null ? 0 : Math.max(0, blockIds.getOrDefault(state, 0));
        Key key = new Key(state, direction.get3DDataValue(), tint, textured, blockId);
        int id = ids.getOrDefault(key, -1);
        if (id >= 0) return id;
        id = count++;
        if (count * INTS > data.length) data = Arrays.copyOf(data, data.length * 2);
        int at = id * INTS;
        data[at] = Float.floatToIntBits(face.u0());
        data[at + 1] = Float.floatToIntBits(face.v0());
        data[at + 2] = Float.floatToIntBits(face.u1());
        data[at + 3] = Float.floatToIntBits(face.v1());
        data[at + 4] = face.rgb() & 0xFFFFFF;
        data[at + 5] = tint & 0xFFFFFF;
        data[at + 6] = blockId;
        data[at + 7] = textured ? TEXTURED : 0;
        ids.put(key, id);
        return id;
    }

    synchronized int count() { return count; }

    /** The ints of looks {@code from} (inclusive) to {@code to} (exclusive). */
    synchronized int[] copy(int from, int to) { return Arrays.copyOfRange(data, from * INTS, to * INTS); }
}
