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
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server thread. The heights an Earth {@code /forceload} keeps, per forced chunk: the block Y the
 * command was run from, so "keep this spot loaded" keeps the spot and not the whole 32,768-block
 * column, and who ran it, so loaders share the cap fairly (see {@link ChunkTickets}). A chunk forced
 * from the console, or by a mod calling {@code setChunkForced} itself, has none here and keeps its
 * ground band. Saved with the world, beside vanilla's own list of forced chunks.
 */
public final class ForcedHeights extends SavedData {
    static final String NAME = "earthplatform_forced_heights";
    /** Heights one chunk may keep: forced again from far enough above or below an earlier one, a chunk keeps both. */
    static final int MAX_PER_CHUNK = 4;
    /** The owner of heights set from the console or a command block: the server. */
    static final String SERVER = "";

    /** One kept height and who forced it ({@link #SERVER} for no player). */
    public record Height(int y, String owner) {}

    private final Long2ObjectOpenHashMap<List<Height>> heights = new Long2ObjectOpenHashMap<>();

    public static ForcedHeights of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(ForcedHeights::new, ForcedHeights::load, null), NAME);
    }

    /** The heights kept for a forced chunk, lowest first, or null for its ground band. */
    @Nullable public List<Height> of(long chunk) {
        List<Height> kept = heights.get(chunk);
        return kept == null ? null : List.copyOf(kept);
    }

    /** A chunk just forced: kept at {@code y}, or at its ground band when null. */
    public void set(long chunk, @Nullable Integer y, String owner) {
        if (y == null) { clear(chunk); return; }
        List<Height> one = new ArrayList<>(List.of(new Height(y, owner)));
        if (!one.equals(heights.put(chunk, one))) setDirty();
    }

    /**
     * A chunk already forced, forced again from {@code y}: true when that adds a height, being outside
     * every height it keeps already (each keeps {@link Cubic#VERTICAL_RADIUS} cubes up and down).
     */
    public boolean add(long chunk, int y, String owner) {
        List<Height> had = heights.get(chunk);
        if (had == null) return false; // kept at its ground band: forced from the console or by a mod, left so
        if (had.size() >= MAX_PER_CHUNK) return false;
        for (Height kept : had) if (Math.abs((kept.y() >> 4) - (y >> 4)) <= Cubic.VERTICAL_RADIUS) return false;
        had.add(new Height(y, owner));
        had.sort(java.util.Comparator.comparingInt(Height::y));
        setDirty();
        return true;
    }

    public void clear(long chunk) {
        if (heights.remove(chunk) != null) setDirty();
    }

    public void clearAll() {
        if (!heights.isEmpty()) { heights.clear(); setDirty(); }
    }

    /** Chunks with heights kept. */
    public long[] chunks() { return heights.keySet().toLongArray(); }

    /** Drops the heights of these chunks, unforced since they were last seen forced. */
    public void forget(LongSet chunks) {
        if (heights.isEmpty() || chunks.isEmpty()) return;
        if (heights.keySet().removeIf((long chunk) -> chunks.contains(chunk))) setDirty();
    }

    public int size() { return heights.size(); }

    /** Counts changes, so a reader can tell the heights changed without reading them. */
    private int version;
    public int version() { return version; }

    @Override public void setDirty(boolean dirty) {
        super.setDirty(dirty);
        if (dirty) version++;
    }

    private static ForcedHeights load(CompoundTag tag, HolderLookup.Provider registries) {
        ForcedHeights loaded = new ForcedHeights();
        ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int[] ys = entry.getIntArray("y");
            ListTag owners = entry.getList("owners", Tag.TAG_STRING);
            List<Height> kept = new ArrayList<>();
            for (int j = 0; j < Math.min(ys.length, MAX_PER_CHUNK); j++) kept.add(new Height(ys[j], j < owners.size() ? owners.getString(j) : SERVER));
            if (!kept.isEmpty()) loaded.heights.put(entry.getLong("chunk"), kept);
        }
        return loaded;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Long2ObjectMap.Entry<List<Height>> entry : heights.long2ObjectEntrySet()) {
            CompoundTag one = new CompoundTag();
            one.putLong("chunk", entry.getLongKey());
            int[] ys = new int[entry.getValue().size()];
            ListTag owners = new ListTag();
            for (int i = 0; i < ys.length; i++) {
                ys[i] = entry.getValue().get(i).y();
                owners.add(StringTag.valueOf(entry.getValue().get(i).owner()));
            }
            one.putIntArray("y", ys);
            one.put("owners", owners);
            list.add(one);
        }
        tag.put("chunks", list);
        return tag;
    }
}
