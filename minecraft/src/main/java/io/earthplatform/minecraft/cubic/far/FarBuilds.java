package io.earthplatform.minecraft.cubic.far;

import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * What players built, kept small enough for the coarsest far levels: for each edited cube, the top
 * of what was built in each of its block columns, how low the build reaches, and the block most of
 * it is. The far levels whose voxels cover several cubes (32 to 256 blocks) read these instead of
 * the cubes, so a build shows to the far edge of the view without any far column reading saves.
 * From afar a build reads as its outline: each block column counts as filled from the build's base
 * up to its own top, so a house or a tower with floors is as solid as it looks from outside.
 *
 * <p>Summaries are kept in one small file per 256x256 cube columns (one coarsest far column), in a
 * {@code builds} folder beside the cubes, and only where something was built.
 */
public final class FarBuilds {
    /** Cube columns per side of one file: the cubes under one coarsest far column. */
    private static final int SQUARE_SHIFT = Far.MAX_LEVEL;
    private static final int VERSION = 2;
    /** Written once every save from before these summaries has been read, so it happens once a world. */
    private static final String SCANNED = "scanned-v" + VERSION;
    /**
     * How much of a coarse voxel a build must fill to show, as 1/this: 8 shows a building as wide as
     * a voxel once it is an eighth as tall, or one as tall as a voxel once it covers an eighth of its
     * footprint (about a third as wide). Set with {@code -Dearthplatform.far.buildFill}.
     */
    static final int FILL = Math.clamp(Integer.getInteger("earthplatform.far.buildFill", 8), 1, 64);

    /**
     * One edited cube's build: for each block column (x + 16 z) the layer of its highest built
     * block, or -1; the lowest built layer; and the block it is mostly made of.
     */
    public record Summary(byte[] tops, int low, BlockState block) {
        /** Block columns with something built in them. */
        public int area() { int n = 0; for (byte top : tops) if (top >= 0) n++; return n; }
        boolean same(Summary other) { return other != null && block == other.block && low == other.low && java.util.Arrays.equals(tops, other.tops); }
    }

    private final Path directory;
    private final Map<Long, Square> squares = new ConcurrentHashMap<>();
    /** Squares with a file, from the folder listing plus squares written since. */
    private final Set<Long> known = ConcurrentHashMap.newKeySet();
    private volatile boolean listed;

    public FarBuilds(Path directory) { this.directory = directory; }

    private static long squareKey(int cubeX, int cubeZ) { return CubeKey.pack(cubeX >> SQUARE_SHIFT, 0, cubeZ >> SQUARE_SHIFT); }

    // Summarizing --------------------------------------------------------------------------------

    /**
     * Measures what was built in a cube: its solid blocks above the generated ground of their block
     * column ({@code surface} gives it for block x, z). Leaves, plants, torches and liquids don't
     * count, so the trees and grass the generator made don't read as builds. Null when nothing
     * stands above ground.
     */
    @Nullable
    public static Summary summarize(PalettedContainerRO<BlockState> states, int cubeX, int cubeY, int cubeZ, IntBinaryOperator surface) {
        Map<BlockState, Integer> seen = new IdentityHashMap<>();
        byte[] tops = new byte[256];
        java.util.Arrays.fill(tops, (byte) -1);
        int baseY = cubeY << 4, low = 16;
        boolean any = false;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int ground = surface.applyAsInt((cubeX << 4) + x, (cubeZ << 4) + z);
            for (int y = Math.max(0, ground + 1 - baseY); y < 16; y++) {
                BlockState state = states.get(x, y, z);
                if (!built(state)) continue;
                any = true;
                low = Math.min(low, y);
                tops[x + 16 * z] = (byte) y;
                seen.merge(state, 1, Integer::sum);
            }
        }
        if (!any) return null;
        BlockState best = Blocks.STONE.defaultBlockState();
        int most = 0;
        for (var entry : seen.entrySet()) if (entry.getValue() > most) { most = entry.getValue(); best = entry.getKey(); }
        return new Summary(tops, low, best);
    }

    /** Whether a block counts as part of a build from afar. */
    static boolean built(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock) && !(state.getBlock() instanceof LeavesBlock) && state.blocksMotion();
    }

    // The index ----------------------------------------------------------------------------------

    /** Any thread: records a cube's build, or that it has none. True when that changed what was kept. */
    public boolean put(long cubeKey, @Nullable Summary summary) {
        Square square = square(CubeKey.x(cubeKey), CubeKey.z(cubeKey), summary != null);
        return square != null && square.put(cubeKey, summary, false);
    }

    /** Any thread: as {@link #put}, but keeps a summary already there, for reading old saves. */
    boolean putIfAbsent(long cubeKey, Summary summary) {
        Square square = square(CubeKey.x(cubeKey), CubeKey.z(cubeKey), true);
        return square != null && square.put(cubeKey, summary, true);
    }

    /** Any thread: the builds in cube columns minX..maxX, minZ..maxZ, by cube key. */
    public Long2ObjectMap<Summary> in(int minX, int maxX, int minZ, int maxZ) {
        Long2ObjectOpenHashMap<Summary> found = new Long2ObjectOpenHashMap<>();
        for (int sz = minZ >> SQUARE_SHIFT; sz <= maxZ >> SQUARE_SHIFT; sz++) {
            for (int sx = minX >> SQUARE_SHIFT; sx <= maxX >> SQUARE_SHIFT; sx++) {
                Square square = square(sx << SQUARE_SHIFT, sz << SQUARE_SHIFT, false);
                if (square != null) square.collect(minX, maxX, minZ, maxZ, found);
            }
        }
        return found;
    }

    @Nullable
    private Square square(int cubeX, int cubeZ, boolean create) {
        long key = squareKey(cubeX, cubeZ);
        Square square = squares.get(key);
        if (square != null) return square;
        list();
        if (!create && !known.contains(key)) return null;
        return squares.computeIfAbsent(key, k -> {
            Square made = new Square(k, directory.resolve("b." + CubeKey.x(k) + "." + CubeKey.z(k) + ".nbt"));
            try { made.load(); }
            catch (IOException | RuntimeException failure) { Cubic.LOGGER.warn("Could not read far builds {}", made.file, failure); }
            return made;
        });
    }

    private void list() {
        if (listed) return;
        synchronized (this) {
            if (listed) return;
            if (Files.isDirectory(directory)) {
                try (Stream<Path> files = Files.list(directory)) {
                    files.forEach(file -> {
                        String[] parts = file.getFileName().toString().split("\\.");
                        if (parts.length != 4 || !parts[0].equals("b") || !parts[3].equals("nbt")) return;
                        try { known.add(CubeKey.pack(Integer.parseInt(parts[1]), 0, Integer.parseInt(parts[2]))); }
                        catch (NumberFormatException ignored) {}
                    });
                } catch (IOException failure) {
                    Cubic.LOGGER.warn("Could not list far builds in {}", directory, failure);
                }
            }
            listed = true;
        }
    }

    /** Whether the saves from before these summaries existed have been read. */
    boolean scanned() { return Files.exists(directory.resolve(SCANNED)); }

    void markScanned() throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(SCANNED), "Builds in every cube saved before far builds were kept have been read.\n");
    }

    /** Any thread: writes every changed file. */
    public synchronized void flush() {
        for (Square square : squares.values()) {
            try {
                if (square.flush()) known.add(square.key);
            } catch (IOException | RuntimeException failure) {
                Cubic.LOGGER.error("Could not write far builds {}", square.file, failure);
            }
        }
    }

    /** Cubes with builds among the squares held in memory, for diagnostics. */
    public int cubes() { int sum = 0; for (Square square : squares.values()) sum += square.size(); return sum; }

    private static final class Square {
        final long key;
        final Path file;
        private final Long2ObjectOpenHashMap<Summary> cubes = new Long2ObjectOpenHashMap<>();
        private boolean dirty;

        Square(long key, Path file) { this.key = key; this.file = file; }

        synchronized boolean put(long key, @Nullable Summary summary, boolean keep) {
            Summary old = cubes.get(key);
            if (summary == null) {
                if (old == null) return false;
                cubes.remove(key);
            } else {
                if (keep && old != null || summary.same(old)) return false;
                cubes.put(key, summary);
            }
            dirty = true;
            return true;
        }

        synchronized int size() { return cubes.size(); }

        synchronized void collect(int minX, int maxX, int minZ, int maxZ, Long2ObjectOpenHashMap<Summary> into) {
            for (var entry : cubes.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                int x = CubeKey.x(key), z = CubeKey.z(key);
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) into.put(key, entry.getValue());
            }
        }

        synchronized void load() throws IOException {
            if (!Files.exists(file)) return;
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (root.getInt("v") != VERSION) return;
            List<BlockState> palette = new ArrayList<>();
            for (Tag entry : root.getList("palette", Tag.TAG_COMPOUND)) {
                palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), (CompoundTag) entry));
            }
            for (Tag entry : root.getList("cubes", Tag.TAG_COMPOUND)) {
                CompoundTag cube = (CompoundTag) entry;
                byte[] tops = cube.getByteArray("t");
                int block = cube.getInt("b");
                if (tops.length != 256 || block < 0 || block >= palette.size()) continue;
                BlockState state = palette.get(block);
                // A block from a mod since removed reads as air; stone stands in for it from afar.
                cubes.put(cube.getLong("k"), new Summary(tops, cube.getInt("lo"), state.isAir() ? Blocks.STONE.defaultBlockState() : state));
            }
        }

        /** True when it wrote the file. */
        boolean flush() throws IOException {
            CompoundTag root = new CompoundTag();
            boolean empty;
            synchronized (this) {
                if (!dirty) return false;
                dirty = false;
                empty = cubes.isEmpty();
                Map<BlockState, Integer> index = new HashMap<>();
                ListTag palette = new ListTag(), list = new ListTag();
                for (var entry : cubes.long2ObjectEntrySet()) {
                    Summary summary = entry.getValue();
                    Integer id = index.get(summary.block());
                    if (id == null) {
                        index.put(summary.block(), id = palette.size());
                        palette.add(NbtUtils.writeBlockState(summary.block()));
                    }
                    CompoundTag cube = new CompoundTag();
                    cube.putLong("k", entry.getLongKey());
                    cube.putByteArray("t", summary.tops());
                    cube.putInt("lo", summary.low());
                    cube.putInt("b", id);
                    list.add(cube);
                }
                root.putInt("v", VERSION);
                root.put("palette", palette);
                root.put("cubes", list);
            }
            try {
                if (empty) { Files.deleteIfExists(file); return false; }
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                NbtIo.writeCompressed(root, temp);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (IOException | RuntimeException failure) {
                synchronized (this) { dirty = true; }
                throw failure;
            }
        }
    }

    // Folding into far columns -------------------------------------------------------------------

    /**
     * Stands the builds under a coarse far column (level 5 and up) on its ground. Each cube column's
     * build counts, block column by block column, as filled from the build's base in that cube
     * column up to the block column's own top, and each of those blocks is placed in the voxel it
     * falls in once the voxel column is lowered by its drop, so a build stands on the far ground as
     * it does on the real ground. Only voxels above the ground change, and only where builds fill at
     * least 1/{@link #FILL} of one; they take the block the builds there are mostly made of. Returns
     * how many voxels it set.
     */
    public static int fold(int level, int columnX, int columnZ, Long2ObjectMap<Summary> builds, FarColumn column, Sections sections) {
        if (builds.isEmpty()) return 0;
        int cubes = 1 << level, perVoxel = cubes >> 4, voxel = 1 << level;
        int minX = columnX * cubes, minZ = columnZ * cubes;
        // The cubes of each cube column, by cube column.
        Map<Long, List<Long>> stacks = new HashMap<>();
        for (long key : builds.keySet()) stacks.computeIfAbsent(CubeKey.pack(CubeKey.x(key), 0, CubeKey.z(key)), k -> new ArrayList<>()).add(key);
        int[] grounds = new int[256];
        boolean[] measured = new boolean[256];
        // Built blocks per (voxel column, voxel height), and what they are.
        Map<Long, Map<BlockState, Integer>> bins = new HashMap<>();
        Map<Long, Long> counts = new HashMap<>();
        int[] tops = new int[256];
        for (List<Long> stack : stacks.values()) {
            long first = stack.get(0);
            int vx = (CubeKey.x(first) - minX) / perVoxel, vz = (CubeKey.z(first) - minZ) / perVoxel;
            if (vx < 0 || vx > 15 || vz < 0 || vz > 15) continue;
            int at = vx + 16 * vz;
            if (!measured[at]) { grounds[at] = groundTop(column, vx, vz); measured[at] = true; }
            // Drops run to a whole voxel, past a short at the coarsest level, so they are read unsigned as the client reads them.
            float drop = Short.toUnsignedInt(column.drops[at]) / (float) Far.DROP_UNIT;
            int base = Integer.MAX_VALUE;
            java.util.Arrays.fill(tops, Integer.MIN_VALUE);
            Map<BlockState, Integer> made = new IdentityHashMap<>();
            for (long key : stack) {
                Summary summary = builds.get(key);
                int baseY = CubeKey.y(key) << 4;
                base = Math.min(base, baseY + summary.low());
                for (int i = 0; i < 256; i++) if (summary.tops()[i] >= 0) tops[i] = Math.max(tops[i], baseY + summary.tops()[i]);
                made.merge(summary.block(), summary.area(), Integer::sum);
            }
            BlockState block = Blocks.STONE.defaultBlockState();
            int most = 0;
            for (var entry : made.entrySet()) if (entry.getValue() > most) { most = entry.getValue(); block = entry.getKey(); }
            // The lowest block above the far ground: the first one in the voxel above the ground's.
            int above = (int) Math.ceil((grounds[at] + 1) * (double) voxel - drop - 0.5);
            int from = Math.max(base, above);
            for (int i = 0; i < 256; i++) {
                int top = tops[i];
                if (top < from) continue;
                // Blocks from..top, in voxels: block y is in voxel floor((y + 0.5 + drop) / voxel).
                for (int k = Math.floorDiv((int) Math.floor(from + 0.5 + drop), voxel), last = Math.floorDiv((int) Math.floor(top + 0.5 + drop), voxel); k <= last; k++) {
                    int low = Math.max(from, (int) Math.ceil(k * (double) voxel - drop - 0.5));
                    int high = Math.min(top, (int) Math.ceil((k + 1) * (double) voxel - drop - 0.5) - 1);
                    if (high < low) continue;
                    long bin = (long) at << 32 | (k & 0xFFFFFFFFL);
                    counts.merge(bin, (long) (high - low + 1), Long::sum);
                    bins.computeIfAbsent(bin, b -> new IdentityHashMap<>()).merge(block, high - low + 1, Integer::sum);
                }
            }
        }
        long needed = (long) voxel * voxel * voxel / FILL;
        int shown = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() < needed) continue;
            long bin = entry.getKey();
            int at = (int) (bin >>> 32), k = (int) bin;
            if (k <= grounds[at]) continue;
            int index = (k >> 4) - column.bottom;
            if (index < 0 || index >= Far.MAX_SECTIONS) continue;
            BlockState best = Blocks.STONE.defaultBlockState();
            int most = 0;
            for (var block : bins.get(bin).entrySet()) if (block.getValue() > most) { most = block.getValue(); best = block.getKey(); }
            sections.section(column, index).setBlockState(at & 15, k & 15, at >> 4, best, false);
            shown++;
        }
        return shown;
    }

    /** Makes (or finds) a column's section by index from its bottom. */
    public interface Sections { LevelChunkSection section(FarColumn column, int index); }

    /** The highest voxel of a voxel column holding ground (anything but air and water), in voxels; below the bottom when none. */
    static int groundTop(FarColumn column, int vx, int vz) {
        for (int i = column.sections.size() - 1; i >= 0; i--) {
            LevelChunkSection section = column.sections.get(i);
            if (section == null) continue;
            for (int y = 15; y >= 0; y--) {
                BlockState state = section.getBlockState(vx, y, vz);
                if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)) return ((column.bottom + i) << 4) + y;
            }
        }
        return (column.bottom << 4) - 1;
    }
}
