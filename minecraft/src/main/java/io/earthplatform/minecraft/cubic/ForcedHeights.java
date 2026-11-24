package io.earthplatform.minecraft.cubic;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server thread. What the Earth keeps of each forced chunk, saved with the world beside vanilla's own
 * list of forced chunks: the heights it keeps (the block Y {@code /forceload} was run from, so "keep this
 * spot loaded" keeps the spot and not the whole 32,768-block column), or {@link #GROUND} for its ground
 * band (forced from the console, or by a mod calling {@code setChunkForced}); and for each, the place in
 * the queue it took when first forced, so loaders keep their order across a restart (see {@link ChunkTickets}).
 */
public final class ForcedHeights extends SavedData {
    static final String NAME = "earthplatform_forced_heights";
    /** Heights one chunk may keep: forced again from far enough above or below an earlier one, a chunk keeps both. */
    static final int MAX_PER_CHUNK = 4;
    /** A height meaning the chunk's ground band. */
    public static final int GROUND = Integer.MIN_VALUE;

    /** One kept height ({@link #GROUND} for the ground band) and its place in the queue. */
    public record Height(int y, long seq) {}

    private final Long2ObjectOpenHashMap<List<Height>> heights = new Long2ObjectOpenHashMap<>();
    private long nextSeq;
    /** Counts changes, so a reader can tell the heights changed without reading them. */
    private int version;

    public static ForcedHeights of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(ForcedHeights::new, ForcedHeights::load, null), NAME);
    }

    /** The heights kept for a forced chunk, lowest first, or null when it has none yet. */
    @Nullable public List<Height> of(long chunk) {
        List<Height> kept = heights.get(chunk);
        return kept == null ? null : List.copyOf(kept);
    }

    /** The next place in the queue; saved, so places keep counting up across restarts. */
    public long nextSeq() {
        setDirty();
        return nextSeq++;
    }

    /** A forced chunk with no heights yet keeps its ground band, behind everything before it. */
    public void ensure(long chunk) {
        if (heights.containsKey(chunk)) return;
        heights.put(chunk, new ArrayList<>(List.of(new Height(GROUND, nextSeq()))));
        changed();
    }

    /** A chunk just forced from block Y {@code y}: kept there, in the place it was given when forced. */
    public void set(long chunk, int y) {
        List<Height> had = heights.get(chunk);
        long seq = had != null && !had.isEmpty() ? had.get(0).seq() : nextSeq();
        heights.put(chunk, new ArrayList<>(List.of(new Height(y, seq))));
        changed();
    }

    /**
     * A chunk already forced, forced again from {@code y}: true when that adds a height, being outside
     * every height it keeps already (each keeps {@link Cubic#VERTICAL_RADIUS} cubes up and down). A chunk
     * kept at its ground band stays so.
     */
    public boolean add(long chunk, int y) {
        List<Height> had = heights.get(chunk);
        if (had == null || had.size() >= MAX_PER_CHUNK) return false;
        for (Height kept : had) if (kept.y() == GROUND || Math.abs((kept.y() >> 4) - (y >> 4)) <= Cubic.VERTICAL_RADIUS) return false;
        had.add(new Height(y, nextSeq()));
        had.sort(java.util.Comparator.comparingInt(Height::y));
        changed();
        return true;
    }

    public void clear(long chunk) {
        if (heights.remove(chunk) != null) changed();
    }

    /** Forgets the heights of chunks not in {@code forced}; how many it forgot. */
    public int retain(LongSet forced) {
        int before = heights.size();
        if (heights.keySet().removeIf((long chunk) -> !forced.contains(chunk))) changed();
        return before - heights.size();
    }

    /** Every chunk with heights, as (chunk, its heights). */
    public Iterable<Long2ObjectMap.Entry<List<Height>>> all() { return heights.long2ObjectEntrySet(); }

    public boolean has(long chunk) { return heights.containsKey(chunk); }
    public int size() { return heights.size(); }
    public int version() { return version; }

    /** The heights changed: saved, and seen by readers of {@link #version}. */
    private void changed() {
        version++;
        setDirty();
    }

    static ForcedHeights load(CompoundTag tag, HolderLookup.Provider registries) {
        ForcedHeights loaded = new ForcedHeights();
        loaded.nextSeq = tag.getLong("next");
        ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int[] ys = entry.getIntArray("y");
            long[] seqs = entry.getLongArray("seq");
            List<Height> kept = new ArrayList<>();
            for (int j = 0; j < Math.min(ys.length, MAX_PER_CHUNK); j++) {
                long seq = j < seqs.length ? seqs[j] : loaded.nextSeq++;
                kept.add(new Height(ys[j], seq));
                loaded.nextSeq = Math.max(loaded.nextSeq, seq + 1);
            }
            if (!kept.isEmpty()) loaded.heights.put(entry.getLong("chunk"), kept);
        }
        return loaded;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("next", nextSeq);
        ListTag list = new ListTag();
        for (Long2ObjectMap.Entry<List<Height>> entry : heights.long2ObjectEntrySet()) {
            CompoundTag one = new CompoundTag();
            one.putLong("chunk", entry.getLongKey());
            int[] ys = new int[entry.getValue().size()];
            long[] seqs = new long[ys.length];
            for (int i = 0; i < ys.length; i++) { ys[i] = entry.getValue().get(i).y(); seqs[i] = entry.getValue().get(i).seq(); }
            one.putIntArray("y", ys);
            one.putLongArray("seq", seqs);
            list.add(one);
        }
        tag.put("chunks", list);
        return tag;
    }
}
