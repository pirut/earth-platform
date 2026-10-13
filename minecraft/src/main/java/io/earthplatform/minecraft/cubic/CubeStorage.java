package io.earthplatform.minecraft.cubic;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Edited cubes only, grouped into 8x8x8-cube region files. Untouched cubes are regenerated from
 * the seed, so a world's save size follows what players changed, not what they visited. Asking for
 * a cube in a region with no file costs nothing, and regions read a while ago are let go once
 * saved, so memory stays flat however far players travel.
 */
public final class CubeStorage {
    private static final int REGION_SHIFT = 3;
    /** How long a saved region stays in memory after it was last used. */
    private static final long KEEP_NANOS = 60_000_000_000L;
    private final Path directory;
    private final Map<Long, Region> regions = new ConcurrentHashMap<>();
    /** Region keys with saved cubes, from the directory listing plus regions written since. */
    private final Set<Long> known = ConcurrentHashMap.newKeySet();
    private volatile boolean listed;

    public CubeStorage(Path directory) { this.directory = directory; }

    private static long regionKey(long cubeKey) {
        return CubeKey.pack(CubeKey.x(cubeKey) >> REGION_SHIFT, CubeKey.y(cubeKey) >> REGION_SHIFT, CubeKey.z(cubeKey) >> REGION_SHIFT);
    }

    private Region region(long cubeKey) {
        return regions.computeIfAbsent(regionKey(cubeKey), key -> new Region(directory.resolve(
                "r." + CubeKey.x(key) + "." + CubeKey.y(key) + "." + CubeKey.z(key) + ".nbt")));
    }

    /** Any thread. */
    @Nullable public CompoundTag read(long cubeKey) throws IOException {
        listRegions();
        if (!known.contains(regionKey(cubeKey))) return null;
        return region(cubeKey).get(cubeKey);
    }

    /** Owner thread: replaces the stored form of one cube; written by the next {@link #flush}. */
    public void put(long cubeKey, CompoundTag tag) throws IOException {
        region(cubeKey).put(cubeKey, tag);
        known.add(regionKey(cubeKey));
    }

    /** Any thread: keys of saved cubes in the cube columns minX..maxX, minZ..maxZ, at any height. */
    public LongList savedIn(int minX, int maxX, int minZ, int maxZ) throws IOException {
        listRegions();
        LongArrayList found = new LongArrayList();
        for (long region : known) {
            int rx = CubeKey.x(region), rz = CubeKey.z(region);
            if (rx < minX >> REGION_SHIFT || rx > maxX >> REGION_SHIFT || rz < minZ >> REGION_SHIFT || rz > maxZ >> REGION_SHIFT) continue;
            int ry = CubeKey.y(region);
            for (long key : region(CubeKey.pack(rx << REGION_SHIFT, ry << REGION_SHIFT, rz << REGION_SHIFT)).keys()) {
                int x = CubeKey.x(key), z = CubeKey.z(key);
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) found.add(key);
            }
        }
        return found;
    }

    private void listRegions() throws IOException {
        if (listed) return;
        synchronized (this) {
            if (listed) return;
            if (Files.isDirectory(directory)) {
                try (Stream<Path> files = Files.list(directory)) {
                    files.forEach(file -> {
                        String[] parts = file.getFileName().toString().split("\\.");
                        if (parts.length != 5 || !parts[0].equals("r") || !parts[4].equals("nbt")) return;
                        try { known.add(CubeKey.pack(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]))); }
                        catch (NumberFormatException ignored) {}
                    });
                }
            }
            listed = true;
        }
    }

    /** Owner thread: writes changed regions, then lets go of ones nobody has used for a while. */
    public void flush() throws IOException {
        Files.createDirectories(directory);
        for (Region region : regions.values()) region.flush();
        long now = System.nanoTime();
        regions.values().removeIf(region -> region.idle(now));
    }

    /** Regions held in memory, for diagnostics. */
    public int loadedRegions() { return regions.size(); }

    private static final class Region {
        private final Path file;
        private Map<Long, CompoundTag> cubes;
        private boolean dirty;
        private long used = System.nanoTime();

        Region(Path file) { this.file = file; }

        private Map<Long, CompoundTag> load() throws IOException {
            used = System.nanoTime();
            if (cubes != null) return cubes;
            Map<Long, CompoundTag> loaded = new HashMap<>();
            if (Files.exists(file)) {
                CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                CompoundTag all = root.getCompound("cubes");
                for (String name : all.getAllKeys()) loaded.put(Long.parseLong(name), all.getCompound(name));
            }
            return cubes = loaded;
        }

        synchronized CompoundTag get(long key) throws IOException { return load().get(key); }

        synchronized long[] keys() throws IOException {
            long[] keys = new long[load().size()];
            int i = 0;
            for (long key : cubes.keySet()) keys[i++] = key;
            return keys;
        }

        synchronized void put(long key, CompoundTag tag) throws IOException { load().put(key, tag); dirty = true; }

        synchronized boolean idle(long now) { return !dirty && now - used > KEEP_NANOS; }

        synchronized void flush() throws IOException {
            if (!dirty) return;
            CompoundTag all = new CompoundTag();
            for (var entry : cubes.entrySet()) all.put(Long.toString(entry.getKey()), entry.getValue());
            CompoundTag root = new CompoundTag();
            root.putInt("v", CubeCodec.VERSION);
            root.put("cubes", all);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        }
    }
}
