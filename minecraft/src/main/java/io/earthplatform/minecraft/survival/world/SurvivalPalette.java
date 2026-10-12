package io.earthplatform.minecraft.survival.world;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Pinned exact native states for the initial bounded survival capability. No material-ID relabeling. */
public final class SurvivalPalette {
    private final EnginePaletteContext context;
    private final BlockState[] pinned;
    private final Set<Block> supported = Set.of(Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.DIRT,
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.OAK_LOG, Blocks.OAK_LEAVES,
            Blocks.OAK_PLANKS, Blocks.CRAFTING_TABLE);

    public SurvivalPalette(EnginePaletteContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!context.fingerprint().equals(EnginePaletteContext.create(context.biomes()).fingerprint()))
            throw new IllegalArgumentException("Palette fingerprint does not describe current native registries");
        pinned = new BlockState[Block.BLOCK_STATE_REGISTRY.size()];
        for (int id = 0; id < pinned.length; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null || Block.BLOCK_STATE_REGISTRY.getId(state) != id)
                throw new IllegalArgumentException("Native state registry is not dense and exact");
            pinned[id] = state;
        }
    }
    public EnginePaletteContext context() { return context; }
    public String identity() { return NativeCubeCodec.paletteIdentity(context); }
    public int id(BlockState state) {
        Objects.requireNonNull(state, "state");
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (state(id) != state) throw new SurvivalRefusal("state is not the pinned native registry instance");
        return id;
    }
    public BlockState state(int id) {
        if (Block.BLOCK_STATE_REGISTRY.size() != pinned.length)
            throw new SurvivalRefusal("native state registry changed after pinning");
        if (id < 0 || id >= pinned.length || Block.BLOCK_STATE_REGISTRY.byId(id) != pinned[id])
            throw new SurvivalRefusal("unknown or changed native state ID " + id);
        BlockState state = pinned[id];
        if (!supported.contains(state.getBlock()) || !state.getFluidState().isEmpty() || state.hasBlockEntity())
            throw new SurvivalRefusal("unsupported native state " + state);
        return state;
    }
    public void validate(ImmutableCube blocks) {
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.isUniform()) state(blocks.get(0));
        else for (int index = 0; index < CubePos.VOLUME; index++) state(blocks.get(index));
    }
}
