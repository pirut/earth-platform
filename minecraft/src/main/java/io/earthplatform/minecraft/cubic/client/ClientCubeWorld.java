package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeCodec;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.CubicMaps;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import io.earthplatform.minecraft.platform.Platform;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Client copy of the cubes the server streamed to this player, plus their meshes. */
public final class ClientCubeWorld extends CubeWorld {
    private final ClientLevel client;
    private final Registry<Biome> biomes;
    final CubeRenderer renderer;
    private final FarRenderer far;
    @Nullable private Object shaderIds;

    public ClientCubeWorld(ClientLevel level) {
        super(level);
        this.client = level;
        this.biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.renderer = new CubeRenderer(this);
        this.far = new FarRenderer(this);
    }

    /** The store for the level the player is in, created on first use. */
    @Nullable public static ClientCubeWorld current() { return attach(Minecraft.getInstance().level); }

    @Nullable public static ClientCubeWorld attach(@Nullable ClientLevel level) {
        if (level == null || !Cubic.isCubic(level.dimension())) return null;
        CubeWorld existing = CubicLevel.of(level);
        if (existing instanceof ClientCubeWorld world) return world;
        ClientCubeWorld created = new ClientCubeWorld(level);
        ((CubicLevel) level).earthplatform$setCubes(created);
        return created;
    }

    public ClientLevel client() { return client; }
    public CubeRenderer renderer() { return renderer; }
    public FarRenderer far() { return far; }

    /** Unknown columns count as open sky until the server sends their tops. */
    @Override protected int baselineTop(int x, int z) { return MIN_Y - 1; }

    @Override public void blockChanged(BlockPos pos) {}

    /** A cube a mod rewrote in place, such as an Axiom edit, is meshed again. */
    @Override protected void onRewritten(Cube cube) { renderer.cubeArrived(cube.key); }

    @Override protected void onStateStored(Cube cube, BlockPos pos, BlockState old, BlockState state) {
        renderer.blockChanged(pos, old, state);
    }

    @Override public int getBrightness(LightLayer layer, BlockPos pos) {
        int light = renderer.light(pos, layer);
        return light >= 0 ? light : super.getBrightness(layer, pos);
    }

    public void receive(CubePayloads.CubeData payload) {
        Cube cube = new Cube(payload.x(), payload.y(), payload.z(), CubeCodec.readSection(payload.section(), biomes));
        adopt(cube);
        for (CompoundTag tag : payload.blockEntities()) {
            BlockEntity entity = getBlockEntity(new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")), true);
            if (entity != null) Platform.INSTANCE.handleUpdateTag(entity, tag, client.registryAccess());
        }
        renderer.cubeArrived(cube.key);
        CubicMaps.columnChanged(client, cube.x, cube.z);
    }

    public void unload(long[] keys) {
        for (long key : keys) {
            Cube cube = cubes.get(key);
            if (cube != null) forget(cube);
            renderer.drop(key);
        }
    }

    public void apply(CubePayloads.BlockChanges payload) {
        for (CubePayloads.Change change : payload.changes()) {
            BlockPos pos = new BlockPos(change.x(), change.y(), change.z());
            if (Cubic.TRACE) io.earthplatform.minecraft.EarthPlatformMod.LOGGER.info("cubic: client change at {} -> {} (cube present: {})",
                    pos, Block.stateById(change.state()), cube(CubeKey.ofBlock(pos)) != null);
            if (cube(CubeKey.ofBlock(pos)) == null) continue;
            client.setServerVerifiedBlockState(pos, Block.stateById(change.state()), 19);
            CubicMaps.columnChanged(client, pos.getX() >> 4, pos.getZ() >> 4);
            if (change.blockEntity() != null) {
                BlockEntity entity = getBlockEntity(pos, true);
                if (entity != null) Platform.INSTANCE.handleUpdateTag(entity, change.blockEntity(), client.registryAccess());
            }
        }
    }

    public void tops(CubePayloads.ColumnTops payload) {
        long column = CubeKey.column(payload.cubeX(), payload.cubeZ());
        int[] old = tops.put(column, payload.tops());
        renderer.topsChanged(payload.cubeX(), payload.cubeZ(), old, payload.tops());
        CubicMaps.columnChanged(client, payload.cubeX(), payload.cubeZ());
    }

    /** Remeshes what carries a shader pack's format and ids when the pack comes, goes or remaps its blocks. */
    public void checkShaders() {
        var ids = ShaderBlocks.ids();
        if (ids == shaderIds) return;
        shaderIds = ids;
        renderer.remeshAll();
        far.shadersChanged(ids);
    }

    /** Drops every cube; the server streams them again, for example after a respawn. */
    public void reset() {
        for (Cube cube : new java.util.ArrayList<>(cubes.values())) {
            forget(cube);
            renderer.drop(cube.key);
        }
        tops.clear();
    }

    public void close() {
        renderer.close();
        far.close();
        for (Cube cube : new java.util.ArrayList<>(cubes.values())) forget(cube);
    }
}
