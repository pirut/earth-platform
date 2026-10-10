package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubicWorld;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.Objects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Full 16^3 core/native bridge for one explicitly pinned numeric state palette and world.
 * Prepare after registries freeze. Each conversion owns its temporary native section entirely
 * on the calling thread; only an immutable cube or immutable engine snapshot escapes.
 * This codec does not grant residency or admit an epoch/lease into a renderer.
 */
public final class NativeCubeCodec {
    public static final String PALETTE_VERSION = "mc1.21.1-nf21.1.256-native-ids-v1";
    private final WorldIdentity world;
    private final EnginePaletteContext palette;
    private final BlockState[] pinnedStates;

    public NativeCubeCodec(WorldIdentity world, EnginePaletteContext palette) {
        this.world = Objects.requireNonNull(world, "world");
        this.palette = Objects.requireNonNull(palette, "palette");
        if (!world.blockPaletteVersion().equals(paletteIdentity(palette)))
            throw new IllegalArgumentException("World must explicitly pin the native numeric state palette");
        // A caller-supplied hash is not proof that these are the registries it describes.
        if (!palette.fingerprint().equals(EnginePaletteContext.create(palette.biomes()).fingerprint()))
            throw new IllegalArgumentException("Palette fingerprint does not describe the current native registries");
        for (String component : new String[]{world.generatorId(), world.generatorVersion(),
                world.datasetVersion(), world.blockPaletteVersion()}) {
            if (component.length() > 128) throw new IllegalArgumentException("World identity exceeds native snapshot schema");
        }
        pinnedStates = new BlockState[Block.BLOCK_STATE_REGISTRY.size()];
        for (int id = 0; id < pinnedStates.length; id++) pinnedStates[id] = registeredState(id);
    }

    /** Persist this exact value in WorldIdentity; never relabel unrelated core material IDs. */
    public static String paletteIdentity(EnginePaletteContext palette) {
        return PALETTE_VERSION + ":" + Objects.requireNonNull(palette, "palette").fingerprint();
    }

    public WorldIdentity world() { return world; }

    /** Materialize the authoritative overlay, including explicit air, at its exact durable revision. */
    public EngineCubeSnapshot materialize(CubicWorld.CubeView view, long epoch, long leaseGeneration) {
        Objects.requireNonNull(view, "view");
        return materialize(view.position(), view.materialize(), epoch, leaseGeneration, view.edits().revision());
    }

    /** No height summary or Y remapping: every core X/Z/Y cell is converted at its native address. */
    public EngineCubeSnapshot materialize(CubePos position, ImmutableCube blocks,
                                          long epoch, long leaseGeneration, long revision) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blocks, "blocks");
        if (epoch < 0 || leaseGeneration <= 0 || revision < 0)
            throw new IllegalArgumentException("Nonnegative epoch/revision and positive lease generation required");
        checkRegistrySize();
        EngineCubeSection section;
        if (blocks.isUniform()) {
            section = EngineCubeSection.uniform(palette, decodeState(blocks.get(0)));
        } else {
            section = new EngineCubeSection(palette);
            for (int y = 0; y < CubePos.SIZE; y++)
                for (int z = 0; z < CubePos.SIZE; z++)
                    for (int x = 0; x < CubePos.SIZE; x++)
                        section.set(x, y, z, decodeState(blocks.get(x, y, z)));
        }
        return EngineCubeSnapshot.capture(position, world, epoch, leaseGeneration, revision, section);
    }

    /**
     * Copy all native states into the existing core value type. The source retains its address,
     * epoch, lease and revision; ImmutableCube deliberately has no transport metadata or biomes.
     * Callers must keep that source stamp and perform their normal admission checks separately.
     */
    public ImmutableCube capture(EngineCubeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!world.equals(snapshot.world())) throw new IllegalArgumentException("Snapshot world identity mismatch");
        checkRegistrySize();
        EngineCubeSection section = snapshot.materialize(palette);
        int[] blocks = new int[CubePos.VOLUME];
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    BlockState state = section.get(x, y, z);
                    int id = Block.BLOCK_STATE_REGISTRY.getId(state);
                    if (decodeState(id) != state) throw new IllegalArgumentException("Native block state mapping changed");
                    blocks[CubePos.index(x, y, z)] = id;
                }
            }
        }
        return ImmutableCube.copyOf(blocks);
    }

    private void checkRegistrySize() {
        if (Block.BLOCK_STATE_REGISTRY.size() != pinnedStates.length)
            throw new IllegalStateException("Native block registry changed after palette pinning");
    }

    private BlockState decodeState(int id) {
        BlockState state = registeredState(id);
        if (id >= pinnedStates.length || pinnedStates[id] != state)
            throw new IllegalStateException("Native block state mapping changed after palette pinning");
        return state;
    }

    /** Unlike Block.stateById, this never maps an unknown ID to air. */
    static BlockState registeredState(int id) {
        BlockState state = id < 0 ? null : Block.BLOCK_STATE_REGISTRY.byId(id);
        if (state == null || Block.BLOCK_STATE_REGISTRY.getId(state) != id)
            throw new IllegalArgumentException("Unknown native block state ID: " + id);
        return state;
    }
}
