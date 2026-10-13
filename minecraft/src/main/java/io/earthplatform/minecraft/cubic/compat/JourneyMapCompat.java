package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Builds JourneyMap's own chunk objects from {@link CubicMaps.Column} snapshots, by reflection so
 * nothing here links against JourneyMap. Its renderers already read snapshot chunks (the kind it
 * loads from disk) on its worker threads, so they draw the cubic world unchanged.
 */
public final class JourneyMapCompat {
    private static final String SNAPSHOT = "journeymap.common.chunk.JMChunkSnapshot";
    private static final String SNAPSHOT_CHUNK = "journeymap.client.model.chunk.SnapshotChunkMD";
    @Nullable private static volatile Handles handles;
    private static volatile boolean failed;

    private JourneyMapCompat() {}

    private record Handles(Constructor<?> snapshot, MethodHandle chunkMd, MethodHandle snapshotOf, MethodHandle minBuild, MethodHandle levelChunk) {}

    @Nullable private static Handles handles() {
        Handles found = handles;
        if (found != null || failed) return found;
        try {
            ClassLoader loader = JourneyMapCompat.class.getClassLoader();
            Class<?> snapshot = Class.forName(SNAPSHOT, false, loader);
            Class<?> chunkMd = Class.forName(SNAPSHOT_CHUNK, false, loader);
            Constructor<?> make = snapshot.getDeclaredConstructor(ChunkPos.class, LevelChunkSection[].class, int.class, int.class, int.class, Map.class, DataLayer[].class);
            make.setAccessible(true);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle wrap = lookup.findConstructor(chunkMd, MethodType.methodType(void.class, ClientLevel.class, snapshot, boolean.class));
            MethodHandle snapshotOf = lookup.findVirtual(chunkMd.getSuperclass(), "getSnapshot", MethodType.methodType(snapshot));
            MethodHandle minBuild = lookup.findVirtual(snapshot, "getMinBuildHeight", MethodType.methodType(int.class));
            MethodHandle levelChunk = lookup.findVirtual(chunkMd.getSuperclass(), "getChunk", MethodType.methodType(LevelChunk.class));
            found = handles = new Handles(make, wrap, snapshotOf, minBuild, levelChunk);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            EarthPlatformMod.LOGGER.warn("JourneyMap changed in a way the Earth map support does not know; its map stays blank in the Earth world", e);
        }
        return found;
    }

    /** JourneyMap's JMChunkSnapshot for this column, or null. */
    @Nullable public static Object snapshot(@Nullable CubicMaps.Column column) {
        Handles h = column == null ? null : handles();
        if (h == null) return null;
        try {
            Map<Heightmap.Types, int[]> heights = new EnumMap<>(Heightmap.Types.class);
            heights.put(Heightmap.Types.WORLD_SURFACE, column.heights);
            heights.put(Heightmap.Types.MOTION_BLOCKING, column.heights);
            return h.snapshot.newInstance(column.pos, column.sections, column.minSectionY, column.minY(), column.maxY(), heights, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
            return null;
        }
    }

    /**
     * A SnapshotChunkMD for this column, made once per snapshot so JourneyMap's render times stick to it.
     * It holds its empty LevelChunk only weakly, and its renderers give up on the chunk once that is
     * collected, so the column keeps it.
     */
    @Nullable public static Object chunk(int chunkX, int chunkZ) {
        CubicMaps.Column column = CubicMaps.column(chunkX, chunkZ);
        if (column == null) return null;
        Object cached = column.mapObject;
        if (cached != null) return cached;
        Handles h = handles();
        ClientLevel level = Minecraft.getInstance().level;
        Object snapshot = snapshot(column);
        if (h == null || level == null || snapshot == null) return null;
        try {
            Object made = h.chunkMd.invoke(level, snapshot, false);
            column.mapAnchor = h.levelChunk.invoke(made);
            column.mapObject = made;
            return made;
        } catch (Throwable e) {
            fail(e);
            return null;
        }
    }

    /** The bottom Y of a snapshot chunk's column, or null for any other chunk. */
    @Nullable public static Integer minY(Object chunkMd) {
        Handles h = handles();
        if (h == null) return null;
        try {
            Object snapshot = h.snapshotOf.invoke(chunkMd);
            return snapshot == null ? null : (int) h.minBuild.invoke(snapshot);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void fail(Throwable e) {
        if (failed) return;
        failed = true;
        EarthPlatformMod.LOGGER.warn("JourneyMap could not take Earth terrain; its map stays blank in the Earth world", e);
    }
}
