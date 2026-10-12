package io.earthplatform.minecraft.survival.render;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.scene.SceneMesh;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.HolderLookup;
import io.earthplatform.minecraft.scene.SceneNativeShaderContract;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * INTERNAL synthetic diagnostic at the actual signed Marin address. It neither changes gameplay
 * terrain nor certifies generation, propagated lighting, shaders, full-distance coverage or a release.
 * Source runs on its own server owner. Only its immutable exports go to the actual client compiler.
 */
public final class SurvivalNativeModelFixture {
    private SurvivalNativeModelFixture() {}
    public static final CubePos MEMBER = CubePos.fromBlock(MarinSurvivalDefinition.ANCHOR_X,
            MarinSurvivalDefinition.GROUND_Y, MarinSurvivalDefinition.ANCHOR_Z);
    public static final CubeCell BOUNDARY_OCCLUDER = new CubeCell(MEMBER.offset(1, 0, 0), CubePos.index(0, 8, 8));
    public static final class Source {
        private final NativeSurvivalWorld owner;
        private final SurvivalVisualSnapshot.Exporter exporter;
        private long actionSequence;
        public Source(SurvivalPalette palette, HolderLookup.Provider registries) {
            var identity = new WorldIdentity(39, "survival-model-fixture", "039-internal",
                    "synthetic-at-signed-marin", palette.identity());
            owner = new NativeSurvivalWorld(identity, palette,
                    new NativeSurvivalWorld.Bounds(MEMBER.offset(-1, -1, -1), MEMBER.offset(1, 1, 1)),
                    new NativeSurvivalWorld.Limits(27, 27, 1, 2, 32, 16, 1, 8, 1, 27));
            for (CubePos position : SurvivalVisualSnapshot.dependencies(Set.of(MEMBER))) {
                int[] ids = new int[CubePos.VOLUME];
                java.util.Arrays.fill(ids, palette.id(Blocks.AIR.defaultBlockState()));
                if (position.equals(MEMBER)) {
                    BlockState[] samples = {Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                            Blocks.OAK_PLANKS.defaultBlockState(), Blocks.CRAFTING_TABLE.defaultBlockState(),
                            Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                            Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y),
                            Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z),
                            Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true)
                                    .setValue(BlockStateProperties.DISTANCE, 3)};
                    for (int i = 0; i < samples.length; i++)
                        ids[CubePos.index(2 + i % 5 * 2, 6 + i / 5 * 3, 4)] = palette.id(samples[i]);
                    ids[CubePos.index(15, 8, 8)] = palette.id(Blocks.OAK_LOG.defaultBlockState());
                    ids[CubePos.indexOfBlock(MarinSurvivalDefinition.ANCHOR_X, MarinSurvivalDefinition.GROUND_Y,
                            MarinSurvivalDefinition.ANCHOR_Z)] = palette.id(Blocks.GRASS_BLOCK.defaultBlockState());
                }
                if (position.equals(BOUNDARY_OCCLUDER.cube()))
                    ids[BOUNDARY_OCCLUDER.localIndex()] = palette.id(Blocks.STONE.defaultBlockState());
                owner.admitGenerated(position, ImmutableCube.copyOf(ids));
            }
            // This separate synthetic fixture uses core-only edits and cannot deliver native effects.
            // Bind a real action owner nonetheless; never waive the production publication fence.
            var actionOwner = new NativeWholeAction.Owner(owner, registries, (identityIgnored, effects) -> {
                if (!effects.isEmpty()) throw new SurvivalRefusal("synthetic render fixture cannot deliver native effects");
                return receipt -> {};
            });
            exporter = new SurvivalVisualSnapshot.Exporter(owner, actionOwner);
        }
        public SurvivalVisualSnapshot capture() { return exporter.capture(Set.of(MEMBER)); }
        /** Explicit cross-cube edit for invalidating the member's observed +X face dependency. */
        public SurvivalVisualSnapshot removeBoundaryOccluder() {
            try (var action = owner.begin(owner.actionIdentity(new UUID(39, 1), ++actionSequence),
                    Map.of(BOUNDARY_OCCLUDER.cube(), owner.stamp(BOUNDARY_OCCLUDER.cube())))) {
                action.set(BOUNDARY_OCCLUDER, Blocks.AIR.defaultBlockState()); action.commit();
            }
            return capture();
        }
    }

    /** Actual loaded-model requests only. Emitted layer geometry and GL need a completed SceneMesh build. */
    public record LayerEvidence(Map<String, Set<String>> requestedNativeLayers) {
        public LayerEvidence { requestedNativeLayers = Map.copyOf(requestedNativeLayers); }
    }
    public static LayerEvidence inspectLoadedModelLayers(SurvivalNativeScene scene) {
        RenderSystem.assertOnRenderThread();
        var cube = scene.current().member(MEMBER);
        var renderer = Minecraft.getInstance().getBlockRenderer();
        var observed = new LinkedHashMap<String, Set<String>>();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState state = cube.state(x, y, z);
            if (state.isAir() || observed.containsKey(state.toString())) continue;
            var position = new BlockPos(Math.toIntExact(MEMBER.minBlockX()) + x,
                    Math.toIntExact(MEMBER.minBlockY()) + y, Math.toIntExact(MEMBER.minBlockZ()) + z);
            var layers = new TreeSet<String>();
            for (RenderType layer : renderer.getBlockModel(state).getRenderTypes(state,
                    RandomSource.create(state.getSeed(position)), ModelData.EMPTY)) {
                if (layer == RenderType.solid()) layers.add("solid");
                else if (layer == RenderType.cutoutMipped()) layers.add("cutout_mipped");
                else if (layer == RenderType.cutout()) layers.add("cutout");
                else throw new IllegalStateException("Survival native model requests unsupported layer " + layer);
            }
            if (layers.isEmpty()) throw new IllegalStateException("Survival native model has no terrain layer: " + state);
            observed.put(state.toString(), Set.copyOf(layers));
        }
        if (observed.size() != 10) throw new IllegalStateException("Incomplete ten-state signed native-model fixture");
        return new LayerEvidence(observed);
    }
    /** The caller retains this build through sliced compilation, finish/upload, and any retryable cleanup. */
    public static SceneMesh.Build begin(SurvivalNativeScene scene, SceneNativeShaderContract.Capture shader) {
        inspectLoadedModelLayers(scene);
        return scene.begin(MEMBER, shader);
    }
}
