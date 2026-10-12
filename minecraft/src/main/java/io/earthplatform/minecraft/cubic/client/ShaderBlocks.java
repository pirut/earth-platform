package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Tells an active shader pack which block each vertex belongs to, as Iris does for its own chunk
 * meshes, so packs can pick out water, leaves, lights and the rest. While a pack is in use Iris
 * widens block-format buffers to its terrain format; this fills in the block for each quad.
 * Does nothing without Iris or with no pack in use.
 */
final class ShaderBlocks {
    static final boolean IRIS = ModList.get() != null && ModList.get().isLoaded("iris");

    private ShaderBlocks() {}

    /** The pack's block ids, or null when no pack is in use. Taken once per mesh, on the client thread. */
    @Nullable static Object2IntMap<BlockState> ids() { return IRIS ? IrisIds.ids() : null; }

    /** True while a shader pack is in use. */
    static boolean packInUse() { return IRIS && net.irisshaders.iris.Iris.isPackInUseQuick(); }

    /** The pack's Voxy fragment code for the current dimension, or null when it has none (see {@link FarPackPatch}). */
    @Nullable static String voxyPatch() { return IRIS ? FarPackPatch.current() : null; }

    /** True while a shader pack draws its shadow map, when only shadow casters are wanted. */
    static boolean shadowPass() {
        return IRIS && net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();
    }

    /** Marks the quads written next as the given block's, until {@link #end}. */
    static void begin(BufferBuilder builder, @Nullable Object2IntMap<BlockState> ids, BlockState state, boolean fluid,
                      int x, int y, int z) {
        if (ids != null) IrisIds.begin(builder, ids, state, fluid, x, y, z);
    }

    static void end(BufferBuilder builder, @Nullable Object2IntMap<BlockState> ids) {
        if (ids != null) IrisIds.end(builder);
    }

    /** Only loaded when Iris is installed. */
    private static final class IrisIds {
        @Nullable static Object2IntMap<BlockState> ids() {
            if (!net.irisshaders.iris.Iris.isPackInUseQuick()) return null;
            return net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockStateIds();
        }

        static void begin(BufferBuilder builder, Object2IntMap<BlockState> ids, BlockState state, boolean fluid, int x, int y, int z) {
            if (!(builder instanceof net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder sensitive)) return;
            byte type = (byte) (fluid ? net.irisshaders.iris.vertices.ExtendedDataHelper.FLUID_RENDER_TYPE
                    : net.irisshaders.iris.vertices.ExtendedDataHelper.BLOCK_RENDER_TYPE);
            sensitive.beginBlock(ids.getOrDefault(state, -1), type, (byte) state.getLightEmission(), x, y, z);
        }

        static void end(BufferBuilder builder) {
            if (builder instanceof net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder sensitive) sensitive.endBlock();
        }
    }
}
