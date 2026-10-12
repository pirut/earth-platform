package io.earthplatform.minecraft.cubic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Edited cubes only, grouped into 8x8x8-cube region files. Untouched cubes are regenerated from
 * the seed, so a world's save size follows what players changed, not what they visited.
 */
public final class CubeStorage {
    private static final int REGION_SHIFT = 3;
    private final Path directory;
    private final Map<Long, Region> regions = new ConcurrentHashMap<>();

    public CubeStorage(Path directory) { this.directory = directory; }

    private static long regionKey(long cubeKey) {
        return CubeKey.pack(CubeKey.x(cubeKey) >> REGION_SHIFT, CubeKey.y(cubeKey) >> REGION_SHIFT, CubeKey.z(cubeKey) >> REGION_SHIFT);
    }

    private Region region(long cubeKey) {
        return regions.computeIfAbsent(regionKey(cubeKey), key -> new Region(directory.resolve(
                "r." + CubeKey.x(key) + "." + CubeKey.y(key) + "." + CubeKey.z(key) + ".nbt")));
    }

    /** Any thread. */
    @Nullable public CompoundTag read(long cubeKey) throws IOException { return region(cubeKey).get(cubeKey); }

    /** Owner thread: replaces the stored form of one cube; written by the next {@link #flush}. */
    public void put(long cubeKey, CompoundTag tag) throws IOException { region(cubeKey).put(cubeKey, tag); }

    public void flush() throws IOException {
        Files.createDirectories(directory);
        for (Region region : regions.values()) region.flush();
    }

    private static final class Region {
        private final Path file;
        private Map<Long, CompoundTag> cubes;
        private boolean dirty;

        Region(Path file) { this.file = file; }

        private Map<Long, CompoundTag> load() throws IOException {
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

        synchronized void put(long key, CompoundTag tag) throws IOException { load().put(key, tag); dirty = true; }

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
