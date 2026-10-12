package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Exact read-only render states, never a simulation palette or admission capability.
 * Gravel is required by immutable WorldCover water-class columns; rendering it does not
 * authorize falling-block updates. Mutable SurvivalPalette intentionally still refuses it.
 */
public final class SurvivalRenderPalette {
    public static final String POLICY_IDENTITY = "marin-native-read-only-render-states-v1;air,grass_block,dirt,stone,gravel,"
            + "cobblestone,oak_log,oak_leaves,oak_planks,crafting_table;all-exact-properties;no-fluids;no-block-entities";
    private final EnginePaletteContext context;
    private final BlockState[] pinned;
    private final Set<Block> supported = Set.of(Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.DIRT,
            Blocks.STONE, Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.OAK_LOG, Blocks.OAK_LEAVES,
            Blocks.OAK_PLANKS, Blocks.CRAFTING_TABLE);

    public SurvivalRenderPalette(SurvivalPalette palette) {
        this(Objects.requireNonNull(palette, "palette").context());
    }
    public SurvivalRenderPalette(EnginePaletteContext context) {
        this.context = Objects.requireNonNull(context, "context");
        if (!context.fingerprint().equals(EnginePaletteContext.create(context.biomes()).fingerprint()))
            throw new IllegalArgumentException("Render palette fingerprint does not describe current native registries");
        pinned = new BlockState[Block.BLOCK_STATE_REGISTRY.size()];
        for (int id = 0; id < pinned.length; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null || Block.BLOCK_STATE_REGISTRY.getId(state) != id)
                throw new IllegalArgumentException("Native state registry is not dense and exact");
            pinned[id] = state;
        }
    }
    public EnginePaletteContext context() { return context; }
    /** Registry IDs share the exact world palette identity; policy identity remains separately explicit. */
    public String identity() { return NativeCubeCodec.paletteIdentity(context); }
    public String policyIdentity() { return POLICY_IDENTITY; }
    public int id(BlockState state) {
        Objects.requireNonNull(state, "state");
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (state(id) != state) throw new SurvivalRefusal("render state is not the pinned native registry instance");
        return id;
    }
    public BlockState state(int id) {
        if (Block.BLOCK_STATE_REGISTRY.size() != pinned.length)
            throw new SurvivalRefusal("native state registry changed after render pinning");
        if (id < 0 || id >= pinned.length || Block.BLOCK_STATE_REGISTRY.byId(id) != pinned[id])
            throw new SurvivalRefusal("unknown or changed native render state ID " + id);
        BlockState state = pinned[id];
        if (!supported.contains(state.getBlock()) || !state.getFluidState().isEmpty() || state.hasBlockEntity())
            throw new SurvivalRefusal("unsupported native read-only render state " + state);
        return state;
    }
    public void validate(ImmutableCube blocks) {
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.isUniform()) state(blocks.get(0));
        else for (int index = 0; index < CubePos.VOLUME; index++) state(blocks.get(index));
    }
}
