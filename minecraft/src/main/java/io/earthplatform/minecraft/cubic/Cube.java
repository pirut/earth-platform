package io.earthplatform.minecraft.cubic;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** One loaded 16³ cube: vanilla section storage plus its block entities, at a true cube coordinate. */
public final class Cube {
    public final int x, y, z;
    public final long key;
    public final LevelChunkSection section;
    final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    /** To be stored when it unloads (with {@code dirty}): generated ground and every edited cube are, as vanilla saves the chunks it makes. */
    volatile boolean modified;
    /**
     * Changed by play (blocks or block entities) since it was generated. Cubes saved untouched are
     * marked so in the save, so far terrain need not read them and a later version could remake them.
     */
    volatile boolean edited;
    /** Changed since the last save. */
    volatile boolean dirty;
    /** Blocks changed since the last save, so far terrain showing this cube must be remade. */
    volatile boolean reshaped;

    public Cube(int x, int y, int z, LevelChunkSection section) {
        this.x = x; this.y = y; this.z = z; this.key = CubeKey.pack(x, y, z); this.section = section;
    }
    public BlockState get(int bx, int by, int bz) { return section.getBlockState(bx & 15, by & 15, bz & 15); }
    public BlockPos origin() { return new BlockPos(x << 4, y << 4, z << 4); }
    public boolean isEmpty() { return section.hasOnlyAir(); }
    public Map<BlockPos, BlockEntity> blockEntities() { return blockEntities; }
    public boolean modified() { return modified; }
    /** A change by play: blocks or block entities. */
    public void markModified() { modified = true; dirty = true; edited = true; }
    /** To be saved again without counting as a change by play, as when its first animals spawn. */
    void markUnsaved() { modified = true; dirty = true; }
    public boolean contains(BlockPos pos) {
        return pos.getX() >> 4 == x && pos.getY() >> 4 == y && pos.getZ() >> 4 == z;
    }
}
