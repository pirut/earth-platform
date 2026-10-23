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
 * The pack's block id is not part of a look's identity: when a pack comes, goes or remaps its
 * blocks, {@link #rebind} rewrites it in place, so far meshes keep their look ids and need no
 * rebuild.
 */
final class FarLooks {
    /** Ints per look: sprite u0, v0, u1, v1 (float bits), average RGB, tint RGB, pack block id, flags. */
    static final int INTS = 8;
    /** Flag: the face shows its block texture rather than the texture's average colour. */
    static final int TEXTURED = 1;

    private record Key(BlockState state, int face, int tint, boolean textured) {}

    private final Object2IntOpenHashMap<Key> ids = new Object2IntOpenHashMap<>();
    private int[] data = new int[INTS * 1024];
    private BlockState[] states = new BlockState[1024];
    private int count, rebinds;
    @Nullable private Object2IntMap<BlockState> blockIds;

    /** The id of a block's look on one side, in a biome. */
    synchronized int id(FarFaces faces, BlockState state, Direction direction, Holder<Biome> biome, boolean textured) {
        FarFaces.Face face = faces.face(state, direction);
        int tint = faces.tint(state, biome, face);
        Key key = new Key(state, direction.get3DDataValue(), tint, textured);
        int id = ids.getOrDefault(key, -1);
        if (id >= 0) return id;
        id = count++;
        if (count * INTS > data.length) data = Arrays.copyOf(data, data.length * 2);
        if (count > states.length) states = Arrays.copyOf(states, states.length * 2);
        states[id] = state;
        int at = id * INTS;
        data[at] = Float.floatToIntBits(face.u0());
        data[at + 1] = Float.floatToIntBits(face.v0());
        data[at + 2] = Float.floatToIntBits(face.u1());
        data[at + 3] = Float.floatToIntBits(face.v1());
        data[at + 4] = face.rgb() & 0xFFFFFF;
        data[at + 5] = tint & 0xFFFFFF;
        data[at + 6] = blockId(state);
        data[at + 7] = textured ? TEXTURED : 0;
        ids.put(key, id);
        return id;
    }

    private int blockId(BlockState state) { return blockIds == null ? 0 : Math.max(0, blockIds.getOrDefault(state, 0)); }

    /** Takes the shader pack's block ids, or null with no pack, rewriting every look's if they changed. */
    synchronized void rebind(@Nullable Object2IntMap<BlockState> ids) {
        if (ids == blockIds) return;
        blockIds = ids;
        for (int id = 0; id < count; id++) data[id * INTS + 6] = blockId(states[id]);
        rebinds++;
    }

    /** Counts {@link #rebind}s that changed the ids, so the render thread knows to upload every look again. */
    synchronized int rebinds() { return rebinds; }

    synchronized int count() { return count; }

    /** The ints of looks {@code from} (inclusive) to {@code to} (exclusive). */
    synchronized int[] copy(int from, int to) { return Arrays.copyOfRange(data, from * INTS, to * INTS); }
}
