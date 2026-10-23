package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.ModList;

/**
 * What map mods see of the cubic world. Their chunk readers look at the carrier chunk, which is
 * empty and 16 blocks tall, so the compat mixins route those reads here instead:
 * <ul>
 * <li>render-thread readers (Xaero's Minimap and World Map) read the client cubes directly;</li>
 * <li>worker-thread readers (JourneyMap) get {@link Column} snapshots, rebuilt on the client thread
 * whenever cubes in that column arrive or change, since cube reads only work on the owner thread.</li>
 * </ul>
 * Columns also tell Xaero's mods to redraw a chunk by clearing the "clean" flags they add to LevelChunk.
 */
public final class CubicMaps {
    /** Cubes below the surface kept in a snapshot, for caves and lakes seen from above. */
    private static final int BELOW_SURFACE = 2, AROUND_PLAYER = 2, MAX_SECTIONS = 24;
    private static final int REBUILDS_PER_TICK = 48;

    private static final boolean XAERO = loaded("xaerominimap") || loaded("xaeroworldmap");
    private static final boolean SNAPSHOTS = loaded("journeymap");
    private static final boolean ANY = XAERO || SNAPSHOTS;

    @Nullable private static final Field MINIMAP_CLEAN = cleanField("xaero_chunkClean");
    @Nullable private static final Field WORLDMAP_CLEAN = cleanField("xaero_wm_chunkClean");

    private static final Map<Long, Column> COLUMNS = new ConcurrentHashMap<>();
    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    /** Stand-in chunks for map mods that need a LevelChunk object; the client gets no carrier chunks. */
    private static final Long2ObjectOpenHashMap<LevelChunk> STAND_INS = new Long2ObjectOpenHashMap<>();
    @Nullable private static ClientLevel standInLevel;
    @Nullable private static ClientLevel snapshotLevel;
    private static int playerCubeY;

    private CubicMaps() {}

    /** A copy of one chunk column's cubes near its surface, safe to read from any thread. */
    public static final class Column {
        public final ChunkPos pos;
        public final int minSectionY;
        public final LevelChunkSection[] sections;
        /** First air Y above the highest sky-blocking block, per x + z * 16. */
        public final int[] heights;
        /** The map mod's own object built from this column, cached until the column changes. */
        public volatile Object mapObject;
        /** Whatever that object needs kept alive alongside it. */
        public volatile Object mapAnchor;

        Column(ChunkPos pos, int minSectionY, LevelChunkSection[] sections, int[] heights) {
            this.pos = pos; this.minSectionY = minSectionY; this.sections = sections; this.heights = heights;
        }
        public int minY() { return minSectionY << 4; }
        public int maxY() { return (minSectionY + sections.length) << 4; }
    }

    static boolean loaded(String mod) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(mod);
    }

    // Render-thread readers --------------------------------------------------------------------------

    /** The client cube store behind this level, if it is the cubic world and we are on its thread. */
    @Nullable public static ClientCubeWorld cubes(@Nullable Level level) {
        if (level == null || !level.isClientSide || !Cubic.isCubic(level.dimension())) return null;
        return CubicLevel.of(level) instanceof ClientCubeWorld world && world.isOwnerThread() ? world : null;
    }

    @Nullable public static ClientCubeWorld cubes(@Nullable ChunkAccess chunk) {
        return chunk instanceof LevelChunk level ? cubes(level.getLevel()) : null;
    }

    /** Lowest Y a map scan should reach: the bottom of the cubes streamed around the player. */
    public static int bottom(ClientCubeWorld world) {
        var player = Minecraft.getInstance().player;
        int cubeY = player == null ? 0 : player.getBlockY() >> 4;
        return Math.max(CubeWorld.MIN_Y, (cubeY - Cubic.VERTICAL_RADIUS) << 4);
    }

    public static int top() { return CubeWorld.MAX_Y + 1; }

    /** Heightmap value (first free Y) of a column; {@code x} and {@code z} may be chunk-local. */
    public static int height(ClientCubeWorld world, ChunkAccess chunk, int x, int z) {
        ChunkPos pos = chunk.getPos();
        return world.skyTop((pos.x << 4) + (x & 15), (pos.z << 4) + (z & 15)) + 1;
    }

    public static BlockState state(ClientCubeWorld world, ChunkAccess chunk, BlockPos local) {
        ChunkPos pos = chunk.getPos();
        return world.getBlockState(new BlockPos((pos.x << 4) + (local.getX() & 15), local.getY(), (pos.z << 4) + (local.getZ() & 15)));
    }

    /**
     * The chunk a map mod gets for a cubic column: an empty stand-in, since the client holds no carrier
     * chunks, so the mod goes on to read it (through the hooks above). Null until the column's sky tops
     * have arrived, which map mods treat as not loaded yet.
     */
    @Nullable public static LevelChunk standIn(ClientCubeWorld world, int chunkX, int chunkZ) {
        ClientLevel level = world.client();
        if (standInLevel != level) { STAND_INS.clear(); standInLevel = level; }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        LevelChunk chunk = STAND_INS.get(key);
        if (chunk != null) return chunk;
        if (world.columnTops(chunkX, chunkZ) == null) return null;
        chunk = new LevelChunk(level, new ChunkPos(chunkX, chunkZ));
        STAND_INS.put(key, chunk);
        return chunk;
    }

    /** The stand-in for a column whose ground is loaded, else null (not loaded yet), so a map never draws a hole. */
    @Nullable public static LevelChunk mapChunk(ClientCubeWorld world, int chunkX, int chunkZ) {
        return surfaceLoaded(world, chunkX, chunkZ) ? standIn(world, chunkX, chunkZ) : null;
    }

    /**
     * A height a map mod stored in 12 bits, unwrapped to the value nearest {@code near}; the "unknown"
     * marker 32767 passes through. Outside the cubic world heights never wrap, so it changes nothing.
     */
    public static int unwrapHeight(int stored, int near) {
        if (stored == 32767 || !inCubicWorld()) return stored;
        return near - (((near - stored) + 2048 & 4095) - 2048);
    }

    /** Whether the cube holding this column's ground near its centre is loaded, so a map can draw it. */
    public static boolean surfaceLoaded(ClientCubeWorld world, int chunkX, int chunkZ) {
        int x = (chunkX << 4) + 8, z = (chunkZ << 4) + 8, top = world.skyTop(x, z);
        return top > CubeWorld.MIN_Y && world.cube(CubeKey.pack(chunkX, top >> 4, chunkZ)) != null;
    }

    // Change tracking ---------------------------------------------------------------------------------

    /** Called on the client thread when cubes in a chunk column arrive, change or get new sky tops. */
    public static void columnChanged(ClientLevel level, int chunkX, int chunkZ) {
        if (!ANY) return;
        if (XAERO) {
            LevelChunk chunk = standInLevel == level ? STAND_INS.get(ChunkPos.asLong(chunkX, chunkZ)) : null;
            if (chunk != null) {
                clear(chunk, MINIMAP_CLEAN);
                clear(chunk, WORLDMAP_CLEAN);
            }
        }
        if (SNAPSHOTS) DIRTY.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Rebuilds a few dirty snapshots and forgets far stand-ins; runs every client tick while in the cubic world. */
    public static void tick(ClientCubeWorld world) {
        if (XAERO && STAND_INS.size() > 8192) {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                int px = player.getBlockX() >> 4, pz = player.getBlockZ() >> 4;
                STAND_INS.keySet().removeIf((long key) -> Math.abs(ChunkPos.getX(key) - px) > 48 || Math.abs(ChunkPos.getZ(key) - pz) > 48);
            }
        }
        if (!SNAPSHOTS) return;
        ClientLevel level = world.client();
        if (snapshotLevel != level) { COLUMNS.clear(); snapshotLevel = level; }
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        int cubeY = player.getBlockY() >> 4;
        if (Math.abs(cubeY - playerCubeY) >= AROUND_PLAYER) {
            // The cave band moved: refresh what is already mapped.
            playerCubeY = cubeY;
            for (Long key : COLUMNS.keySet()) DIRTY.add(key.longValue());
        }
        int radius = Math.min(Cubic.MAX_HORIZONTAL_RADIUS, Minecraft.getInstance().options.getEffectiveRenderDistance()) + 2;
        int px = player.getBlockX() >> 4, pz = player.getBlockZ() >> 4;
        for (int done = 0; done < REBUILDS_PER_TICK && !DIRTY.isEmpty(); done++) {
            long key = DIRTY.removeFirstLong();
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            if (Math.abs(x - px) > radius || Math.abs(z - pz) > radius) continue;
            Column column = build(world, x, z, cubeY);
            if (column != null) COLUMNS.put(key, column);
        }
        if (COLUMNS.size() > 4 * (2 * radius + 1) * (2 * radius + 1))
            COLUMNS.keySet().removeIf(key -> Math.abs(ChunkPos.getX(key) - px) > 2 * radius || Math.abs(ChunkPos.getZ(key) - pz) > 2 * radius);
    }

    public static void leave() {
        STAND_INS.clear();
        standInLevel = null;
        COLUMNS.clear();
        DIRTY.clear();
        snapshotLevel = null;
    }

    /** The latest snapshot of a column, from any thread; null until its cubes have arrived. */
    @Nullable public static Column column(int chunkX, int chunkZ) {
        return COLUMNS.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Whether the player is in the cubic world, from any thread. */
    public static boolean inCubicWorld() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && Cubic.isCubic(level.dimension());
    }

    @Nullable private static Column build(ClientCubeWorld world, int chunkX, int chunkZ, int playerCubeY) {
        int[] tops = world.columnTops(chunkX, chunkZ);
        if (tops == null) return null;
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int top : tops) { low = Math.min(low, top); high = Math.max(high, top); }
        if (high <= CubeWorld.MIN_Y) return null;
        int from = Math.min((low >> 4) - BELOW_SURFACE, playerCubeY - AROUND_PLAYER);
        int to = Math.max(high >> 4, playerCubeY + AROUND_PLAYER);
        if (to - from + 1 > MAX_SECTIONS) from = to - MAX_SECTIONS + 1;
        var biomes = world.client().registryAccess().registryOrThrow(Registries.BIOME);
        LevelChunkSection[] sections = new LevelChunkSection[to - from + 1];
        boolean any = false;
        LevelChunkSection empty = null;
        for (int y = from; y <= to; y++) {
            Cube cube = world.cube(CubeKey.pack(chunkX, y, chunkZ));
            if (cube != null) {
                // Biomes never change after generation, so the snapshot can share them.
                sections[y - from] = new LevelChunkSection(cube.section.getStates().copy(), cube.section.getBiomes());
                any = true;
            } else {
                if (empty == null) empty = new LevelChunkSection(biomes);
                sections[y - from] = empty;
            }
        }
        if (!any) return null;
        int[] heights = new int[256];
        for (int i = 0; i < 256; i++) heights[i] = tops[i] + 1;
        return new Column(new ChunkPos(chunkX, chunkZ), from, sections, heights);
    }

    @Nullable private static Field cleanField(String name) {
        if (!XAERO) return null;
        try {
            Field field = LevelChunk.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void clear(LevelChunk chunk, @Nullable Field field) {
        if (field == null) return;
        try {
            field.setBoolean(chunk, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Cubic.LOGGER.debug("cubic maps: cannot mark chunk dirty for {}", field.getName(), e);
        }
    }
}
