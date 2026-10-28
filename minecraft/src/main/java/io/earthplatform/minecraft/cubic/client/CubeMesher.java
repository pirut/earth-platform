package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeLight;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.platform.ClientPlatform;
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
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

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
                    @Nullable Object2IntMap<BlockState> shaderIds, Map<BlockPos, Object> modelData,
                    Map<BlockPos, BlockEntity> blockEntities) {}

    record Layer(int index, MeshData mesh, ByteBufferBuilder memory) {}
    record Built(long key, int version, List<Layer> layers, byte[] light) {
        void free() { for (Layer layer : layers) { layer.mesh.close(); layer.memory.close(); } }
    }

    /**
     * A block whose six faces vanilla's own face check ({@link Block#shouldRenderFace}) hides, and whose model is one of
     * vanilla's plain kinds with no faces that are never hidden, draws nothing: it is left out before the model pass, which
     * asked its model for every face's quads first. Below the ground that is nearly every block. {@code false} sends every
     * block through the model pass, for comparing. The same vertices.
     */
    private static final boolean SKIP_BURIED = !"false".equals(System.getProperty("earthplatform.cubes.skipBuried"));
    /**
     * Each block is drawn with one pose set to its place, the same matrices as a copy of the identity pose pushed and
     * moved there, which made two new matrices a block. {@code false} pushes a copy for every block, for comparing.
     */
    private static final boolean REUSE_POSE = !"false".equals(System.getProperty("earthplatform.cubes.reusePose"));
    /**
     * Models draw their random numbers from a plain LCG, the same numbers as vanilla's legacy source without its atomic
     * seed, which every draw updated with a compare-and-set: a block's model reseeds it seven times. {@code false} keeps
     * the legacy source, for comparing (as for generation, the flag {@code cubes.plainRandom}).
     */
    private static final boolean PLAIN_RANDOM = !"false".equals(System.getProperty("earthplatform.cubes.plainRandom"));
    /**
     * With {@code -Dearthplatform.cubes.checkMesh=true}, every cube is also meshed and lit the plain way, every speedup
     * off, and a warning names any cube whose light or vertices differ.
     */
    private static final boolean CHECK_MESH = Boolean.getBoolean("earthplatform.cubes.checkMesh");

    /** Which speedups a mesh uses: the flags', or none, for {@link #CHECK_MESH}. */
    private record Mode(boolean skipBuried, boolean reusePose, boolean plainRandom, boolean fastLight) {}
    private static final Mode FLAGS = new Mode(SKIP_BURIED, REUSE_POSE, PLAIN_RANDOM, CubeLight.FAST_LIGHT);
    private static final Mode PLAIN = new Mode(false, false, false, false);
    private static final java.util.concurrent.atomic.AtomicInteger CHECKED = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DIFFERENT = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger NORMALS = new java.util.concurrent.atomic.AtomicInteger();

    private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);

    private static final class Work {
        final PoseStack pose = new PoseStack(), placed = new PoseStack();
        final RandomSource random = RandomSource.create(), plain = RandomSource.createNewThreadLocalInstance();
        final BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
    }

    static Built build(Snapshot s) {
        Work work = WORK.get();
        Built built = build(s, work, FLAGS);
        if (CHECK_MESH) check(s, work, built, build(s, work, PLAIN));
        return built;
    }

    private static Built build(Snapshot s, Work work, Mode mode) {
        CubeLight.Field field = CubeLight.light(s.states, s.tops, s.cy, mode.fastLight);
        View view = new View(s, field);
        List<Layer> layers = mesh(s, work, field, view, mode);
        return new Built(s.key, s.version, layers, CubeLight.centre(field));
    }

    /**
     * For {@link #CHECK_MESH}: compares a cube's mesh with the one made the plain way, and frees that one. Where they
     * differ, it meshes the cube again with each speedup alone to name the ones that change it.
     */
    private static void check(Snapshot s, Work work, Built built, Built plain) {
        try {
            String differ = differ(built, plain, false);
            int checked = CHECKED.incrementAndGet();
            if (differ == null && differ(built, plain, true) != null) NORMALS.incrementAndGet();
            if (differ != null) {
                StringBuilder culprits = new StringBuilder();
                Mode[] alone = {new Mode(true, false, false, false), new Mode(false, true, false, false),
                        new Mode(false, false, true, false), new Mode(false, false, false, true)};
                String[] names = {"skipBuried", "reusePose", "plainRandom", "fastLight"};
                for (int i = 0; i < alone.length; i++) {
                    Built one = build(s, work, alone[i]);
                    if (differ(one, plain, false) != null) culprits.append(' ').append(names[i]);
                    one.free();
                }
                EarthPlatformMod.LOGGER.warn("Cube mesh check: cube {} differs from the plain mesh in its {}, with{} alone ({} of {} differ)",
                        CubeKey.toString(built.key), differ, culprits, DIFFERENT.incrementAndGet(), checked);
            }
            if (checked % 1000 == 0) EarthPlatformMod.LOGGER.info("Cube mesh check: {} meshes compared, {} differ, {} more in a shader pack's normals alone",
                    checked, DIFFERENT.get(), NORMALS.get());
        } finally {
            plain.free();
        }
    }

    /**
     * Where a mesh differs from the plain one, or null. Under a shader pack, Iris works a quad's normal out again only while
     * the render thread is drawing the level (a flag it shares between threads), so the same mesh made twice can differ in
     * normals alone; {@code normals} false leaves those out of the vertices compared.
     */
    @Nullable private static String differ(Built built, Built plain, boolean normals) {
        if (!java.util.Arrays.equals(built.light, plain.light)) return "light";
        if (built.layers.size() != plain.layers.size()) return "layers " + built.layers.size() + " against " + plain.layers.size();
        for (int i = 0; i < built.layers.size(); i++) {
            Layer a = built.layers.get(i), b = plain.layers.get(i);
            if (a.index != b.index) return "layer " + a.index + " against " + b.index;
            if (!a.mesh.drawState().equals(b.mesh.drawState())) return "layer " + a.index + " draw state " + a.mesh.drawState() + " against " + b.mesh.drawState();
            // Element by element: a format's padding is never written, so it holds whatever the memory held.
            java.nio.ByteBuffer va = a.mesh.vertexBuffer(), vb = b.mesh.vertexBuffer();
            VertexFormat format = a.mesh.drawState().format();
            int size = format.getVertexSize(), vertices = a.mesh.drawState().vertexCount();
            boolean skipNormals = !normals && format != DefaultVertexFormat.BLOCK;
            for (int vertex = 0; vertex < vertices; vertex++) {
                for (com.mojang.blaze3d.vertex.VertexFormatElement element : format.getElements()) {
                    if (skipNormals && element == com.mojang.blaze3d.vertex.VertexFormatElement.NORMAL) continue;
                    int from = vertex * size + format.getOffset(element);
                    for (int k = from; k < from + element.byteSize(); k++) {
                        if (va.get(k) == vb.get(k)) continue;
                        StringBuilder bytes = new StringBuilder();
                        for (int j = vertex * size; j < (vertex + 1) * size; j++) bytes.append(String.format(" %02x/%02x", va.get(j), vb.get(j)));
                        return "layer " + a.index + " vertices, first at byte " + k + " (vertex " + vertex + " of " + vertices + ", " + element + "):" + bytes;
                    }
                }
            }
            if (!java.util.Objects.equals(a.mesh.indexBuffer(), b.mesh.indexBuffer())) return "layer " + a.index + " indices";
        }
        return null;
    }

    private static List<Layer> mesh(Snapshot s, Work work, CubeLight.Field field, View view, Mode mode) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
        BufferBuilder[] builders = new BufferBuilder[LAYERS.size()];
        ByteBufferBuilder[] memory = new ByteBufferBuilder[LAYERS.size()];
        PoseStack pose = mode.reusePose ? work.placed : work.pose;
        RandomSource random = mode.plainRandom ? work.plain : work.random;
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
                Object data = ClientPlatform.INSTANCE.modelData(model, view, pos, state, s.modelData.get(pos));
                long seed = state.getSeed(pos);
                random.setSeed(seed);
                boolean buried = mode.skipBuried && plain(model) && buried(state, view, pos, work.near);
                for (RenderType type : ClientPlatform.INSTANCE.renderTypes(model, state, random, data)) {
                    int layer = LAYERS.indexOf(type);
                    if (layer < 0) continue;
                    if (buried) {
                        // Every face is hidden, so it draws only the quads no face hides, asked for as the model pass does.
                        random.setSeed(seed);
                        if (ClientPlatform.INSTANCE.quads(model, state, null, random, data, type).isEmpty()) continue;
                    }
                    BufferBuilder builder = builder(builders, memory, layer);
                    if (mode.reusePose) {
                        pose.setIdentity();
                    } else {
                        pose.pushPose();
                    }
                    pose.translate(x, y, z);
                    ShaderBlocks.begin(builder, s.shaderIds, state, false, x, y, z);
                    ClientPlatform.INSTANCE.renderBatched(blocks, state, pos, view, pose, builder, random, data, type);
                    ShaderBlocks.end(builder, s.shaderIds);
                    if (!mode.reusePose) pose.popPose();
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

    /**
     * Models of vanilla's plain kinds: their model pass draws a face's quads only where {@link Block#shouldRenderFace}
     * says, and the quads no face hides. A model of any other kind, such as a mod's connected textures, always goes
     * through the model pass.
     */
    private static boolean plain(BakedModel model) {
        Class<?> kind = model.getClass();
        return kind == SimpleBakedModel.class || kind == WeightedBakedModel.class;
    }

    /** Faces in the order most likely to show, so a block in sight is found out at once. */
    private static final Direction[] FACES = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN};

    /** Whether vanilla's face check hides all six faces of the block, as the model pass asks it. */
    private static boolean buried(BlockState state, View view, BlockPos pos, BlockPos.MutableBlockPos near) {
        for (Direction face : FACES) {
            if (Block.shouldRenderFace(state, view, pos, face, near.setWithOffset(pos, face))) return false;
        }
        return true;
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
