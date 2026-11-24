package io.earthplatform.minecraft.cubic;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server thread. The heights an Earth {@code /forceload} keeps, per forced chunk: the block Y the
 * command was run from, so "keep this spot loaded" keeps the spot and not the whole 32,768-block
 * column. A chunk forced from the console, or by a mod calling {@code setChunkForced} itself, has
 * none here and keeps its ground band (see {@link ChunkTickets}). Saved with the world, beside
 * vanilla's own list of forced chunks, which stays the authority on what is forced.
 */
public final class ForcedHeights extends SavedData {
    static final String NAME = "earthplatform_forced_heights";
    /** Heights one chunk may keep: forced again from far enough above or below an earlier one, a chunk keeps both. */
    static final int MAX_PER_CHUNK = 4;

    private final Long2ObjectOpenHashMap<int[]> heights = new Long2ObjectOpenHashMap<>();

    public static ForcedHeights of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(ForcedHeights::new, ForcedHeights::load, null), NAME);
    }

    /** The block Ys kept for a forced chunk, or null for its ground band. */
    @Nullable public int[] of(long chunk) {
        int[] ys = heights.get(chunk);
        return ys == null ? null : ys.clone();
    }

    /** A chunk just forced: kept at {@code y}, or at its ground band when null. */
    public void set(long chunk, @Nullable Integer y) {
        boolean changed = y == null ? heights.remove(chunk) != null : !Arrays.equals(heights.put(chunk, new int[]{y}), new int[]{y});
        if (changed) setDirty();
    }

    /**
     * A chunk already forced, forced again from {@code y}: true when that adds a height, being outside
     * every height it keeps already (each keeps {@link Cubic#VERTICAL_RADIUS} cubes up and down).
     */
    public boolean add(long chunk, int y) {
        int[] had = heights.get(chunk);
        if (had == null) return false; // kept at its ground band: forced from the console or by a mod, left so
        if (had.length >= MAX_PER_CHUNK) return false;
        for (int kept : had) if (Math.abs((kept >> 4) - (y >> 4)) <= Cubic.VERTICAL_RADIUS) return false;
        int[] more = Arrays.copyOf(had, had.length + 1);
        more[had.length] = y;
        Arrays.sort(more);
        heights.put(chunk, more);
        setDirty();
        return true;
    }

    public void clear(long chunk) {
        if (heights.remove(chunk) != null) setDirty();
    }

    /** Drops heights of chunks no longer forced, as {@code /forceload remove all} leaves them. */
    public void retain(LongSet forced) {
        if (heights.isEmpty()) return;
        if (heights.keySet().removeIf((long chunk) -> !forced.contains(chunk))) setDirty();
    }

    public int size() { return heights.size(); }

    private static ForcedHeights load(CompoundTag tag, HolderLookup.Provider registries) {
        ForcedHeights loaded = new ForcedHeights();
        ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int[] ys = entry.getIntArray("y");
            if (ys.length > 0) loaded.heights.put(entry.getLong("chunk"), Arrays.copyOf(ys, Math.min(ys.length, MAX_PER_CHUNK)));
        }
        return loaded;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Long2ObjectMap.Entry<int[]> entry : heights.long2ObjectEntrySet()) {
            CompoundTag one = new CompoundTag();
            one.putLong("chunk", entry.getLongKey());
            one.putIntArray("y", entry.getValue());
            list.add(one);
        }
        tag.put("chunks", list);
        return tag;
    }
}
