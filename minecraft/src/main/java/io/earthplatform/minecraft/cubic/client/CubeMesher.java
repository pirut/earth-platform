package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.earthplatform.minecraft.cubic.CubeLight;
import io.earthplatform.minecraft.cubic.CubeWorld;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Off-thread cube compile: lights the 48-block neighbourhood with {@link CubeLight} and
 * tessellates the centre cube with the vanilla block and fluid renderers.
 */
final class CubeMesher {
    private static final int SIZE = CubeLight.SIZE;
    static final List<RenderType> LAYERS = RenderType.chunkBufferLayers();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Copied on the client thread; the worker only reads it. */
    record Snapshot(long key, int version, int cx, int cy, int cz,
                    @Nullable PalettedContainer<BlockState>[] states, @Nullable PalettedContainerRO<Holder<Biome>>[] biomes,
                    @Nullable int[][] tops, float camX, float camY, float camZ, boolean constantAmbient,
                    @Nullable Object2IntMap<BlockState> shaderIds, Map<BlockPos, ModelData> modelData,
                    Map<BlockPos, BlockEntity> blockEntities) {}

    record Layer(int index, MeshData mesh, ByteBufferBuilder memory) {}
    record Built(long key, int version, List<Layer> layers, byte[] light) {
        void free() { for (Layer layer : layers) { layer.mesh.close(); layer.memory.close(); } }
    }

    private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);

    private static final class Work {
        final PoseStack pose = new PoseStack();
        final RandomSource random = RandomSource.create();
    }

    static Built build(Snapshot s) {
        Work work = WORK.get();
        CubeLight.Field field = CubeLight.light(s.states, s.tops, s.cy);
        View view = new View(s, field);
        List<Layer> layers = mesh(s, work, field, view);
        return new Built(s.key, s.version, layers, CubeLight.centre(field));
    }

    private static List<Layer> mesh(Snapshot s, Work work, CubeLight.Field field, View view) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
        BufferBuilder[] builders = new BufferBuilder[LAYERS.size()];
        ByteBufferBuilder[] memory = new ByteBufferBuilder[LAYERS.size()];
        PoseStack pose = work.pose;
        RandomSource random = work.random;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x0 = s.cx << 4, y0 = s.cy << 4, z0 = s.cz << 4;
        ModelBlockRenderer.enableCaching();
        try {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = field.states[CubeLight.index(x + 16, y + 16, z + 16)];
                if (state.isAir()) continue;
                pos.set(x0 + x, y0 + y, z0 + z);
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    BufferBuilder builder = builder(builders, memory, LAYERS.indexOf(ItemBlockRenderTypes.getRenderLayer(fluid)));
                    ShaderBlocks.begin(builder, s.shaderIds, fluid.createLegacyBlock(), true, x, y, z);
                    blocks.renderLiquid(pos, view, builder, state, fluid);
                    ShaderBlocks.end(builder, s.shaderIds);
                }
                if (state.getRenderShape() != RenderShape.MODEL) continue;
                BakedModel model = blocks.getBlockModel(state);
                ModelData data = model.getModelData(view, pos, state, s.modelData.getOrDefault(pos, ModelData.EMPTY));
                random.setSeed(state.getSeed(pos));
                for (RenderType type : model.getRenderTypes(state, random, data)) {
                    int layer = LAYERS.indexOf(type);
                    if (layer < 0) continue;
                    BufferBuilder builder = builder(builders, memory, layer);
                    pose.pushPose();
                    pose.translate(x, y, z);
                    ShaderBlocks.begin(builder, s.shaderIds, state, false, x, y, z);
                    blocks.renderBatched(state, pos, view, pose, builder, true, random, data, type);
                    ShaderBlocks.end(builder, s.shaderIds);
                    pose.popPose();
                }
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }
        List<Layer> layers = new java.util.ArrayList<>();
        for (int i = 0; i < builders.length; i++) {
            if (builders[i] == null) continue;
            MeshData mesh = builders[i].build();
            if (mesh == null) { memory[i].close(); continue; }
            if (LAYERS.get(i) == RenderType.translucent()) {
                mesh.sortQuads(memory[i], VertexSorting.byDistance(s.camX - x0, s.camY - y0, s.camZ - z0));
            }
            layers.add(new Layer(i, mesh, memory[i]));
        }
        return layers;
    }

    private static BufferBuilder builder(BufferBuilder[] builders, ByteBufferBuilder[] memory, int layer) {
        if (builders[layer] == null) {
            memory[layer] = new ByteBufferBuilder(layer == 0 ? 131072 : 16384);
            builders[layer] = new BufferBuilder(memory[layer], VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        }
        return builders[layer];
    }

    /** The lit 48-block neighbourhood as a vanilla block getter. */
    private static final class View implements BlockAndTintGetter {
        private final Snapshot s;
        private final CubeLight.Field field;
        private final int baseX, baseY, baseZ;

        View(Snapshot s, CubeLight.Field field) {
            this.s = s; this.field = field;
            baseX = (s.cx - 1) << 4; baseY = (s.cy - 1) << 4; baseZ = (s.cz - 1) << 4;
        }

        private int at(BlockPos pos) {
            int x = pos.getX() - baseX, y = pos.getY() - baseY, z = pos.getZ() - baseZ;
            return x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE ? -1 : CubeLight.index(x, y, z);
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            int i = at(pos);
            if (i < 0) return AIR;
            int rx = pos.getX() - baseX, ry = pos.getY() - baseY, rz = pos.getZ() - baseZ;
            if (ry >> 4 != 1 || s.states[(ry >> 4) * 9 + (rz >> 4) * 3 + (rx >> 4)] != null) return field.states[i];
            // Beside a cube not loaded yet (the edge of the streamed area), water carries on as it
            // does in the far terrain past it, instead of ending in a wall.
            BlockState edge = field.states[CubeLight.index(Mth.clamp(rx, 16, 31), ry, Mth.clamp(rz, 16, 31))];
            return edge.getFluidState().isEmpty() ? AIR : edge;
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override @Nullable public BlockEntity getBlockEntity(BlockPos pos) { return s.blockEntities.get(pos); }
        @Override public int getHeight() { return CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1; }
        @Override public int getMinBuildHeight() { return CubeWorld.MIN_Y; }

        @Override public float getShade(Direction direction, boolean shade) {
            boolean flat = s.constantAmbient;
            if (!shade) return flat ? 0.9F : 1.0F;
            return switch (direction) {
                case DOWN -> flat ? 0.9F : 0.5F;
                case UP -> flat ? 0.9F : 1.0F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
            };
        }

        @Override public LevelLightEngine getLightEngine() { return Minecraft.getInstance().level.getLightEngine(); }

        @Override public int getBrightness(LightLayer layer, BlockPos pos) {
            int i = at(pos);
            if (i < 0) return layer == LightLayer.SKY ? 15 : 0;
            return layer == LightLayer.SKY ? field.sky[i] : field.block[i];
        }

        @Override public int getRawBrightness(BlockPos pos, int amount) {
            return Math.max(getBrightness(LightLayer.SKY, pos) - amount, getBrightness(LightLayer.BLOCK, pos));
        }

        @Nullable private Holder<Biome> biome(int x, int y, int z) {
            int rx = x - baseX, ry = y - baseY, rz = z - baseZ;
            if (rx < 0 || ry < 0 || rz < 0 || rx >= SIZE || ry >= SIZE || rz >= SIZE) return null;
            PalettedContainerRO<Holder<Biome>> container = s.biomes[(ry >> 4) * 9 + (rz >> 4) * 3 + (rx >> 4)];
            return container == null ? null : container.get((rx & 15) >> 2, (ry & 15) >> 2, (rz & 15) >> 2);
        }

        /**
         * Blend over 4-block biome cells weighted by each block's distance to the cells' centres, falling to nothing two
         * cells (8 blocks) away. The tint then changes block by block across a border, as a gradient, instead of in
         * 4-block steps: a box blend gives every block of a cell the same samples, so a deep-sea edge became a staircase.
         */
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            // The block's centre in cell units, measured from the centre of cell 0.
            double u = (pos.getX() + 0.5 - 2) / 4.0, v = (pos.getZ() + 0.5 - 2) / 4.0;
            int i0 = Mth.floor(u), j0 = Mth.floor(v);
            double r = 0, g = 0, b = 0, n = 0;
            for (int j = j0 - 1; j <= j0 + 2; j++) {
                double wz = Math.max(0, 1 - Math.abs(v - j) / 2);
                for (int i = i0 - 1; i <= i0 + 2; i++) {
                    double w = wz * Math.max(0, 1 - Math.abs(u - i) / 2);
                    if (w <= 0) continue;
                    int x = i * 4 + 2, z = j * 4 + 2;
                    Holder<Biome> biome = biome(x, pos.getY(), z);
                    if (biome == null) continue;
                    int color = resolver.getColor(biome.value(), x, z);
                    r += (color >> 16 & 255) * w; g += (color >> 8 & 255) * w; b += (color & 255) * w; n += w;
                }
            }
            if (n == 0) return 0xFFFFFF;
            return (int) Math.round(r / n) << 16 | (int) Math.round(g / n) << 8 | (int) Math.round(b / n);
        }
    }
}
