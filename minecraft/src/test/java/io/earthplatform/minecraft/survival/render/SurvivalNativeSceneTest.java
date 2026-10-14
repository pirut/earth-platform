package io.earthplatform.minecraft.survival.render;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import java.util.concurrent.atomic.AtomicReference;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native registry/section/admission tests; does not claim loaded models, GL or whole-distance proof. */
class SurvivalNativeSceneTest {
    private static final UUID ACTOR = new UUID(3, 9);
    private static SurvivalPalette palette;
    private static HolderLookup.Provider registries;
    private static WorldIdentity world;
    @BeforeAll static void registry() {
        registries = VanillaRegistries.createLookup();
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, registries.lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(registry.freeze()));
        world = new WorldIdentity(39, "survival-visual-test", "039-internal", "signed-marin", palette.identity());
    }
    private static void admit(NativeSurvivalWorld owner, Set<CubePos> positions) {
        for (CubePos position : positions)
            owner.admitGenerated(position, ImmutableCube.uniform(palette.id(Blocks.AIR.defaultBlockState())));
    }
    /** Tests below use core-only edits. This modeled host refuses every native effect. */
    private static NativeWholeAction.Owner noEffectOwner(NativeSurvivalWorld owner) {
        return new NativeWholeAction.Owner(owner, registries, (identity, effects) -> {
            if (!effects.isEmpty()) throw new SurvivalRefusal("test core-only fixture cannot deliver native effects");
            return receipt -> {};
        });
    }
    private static final NativeWholeAction.InventoryView EMPTY_INVENTORY = new NativeWholeAction.InventoryView() {
        public int size() { return 41; }
        public ItemStack getItem(int slot) { return ItemStack.EMPTY; }
        public int selectedSlot() { return 0; }
    };
    private static void set(NativeSurvivalWorld owner, CubeCell cell, net.minecraft.world.level.block.state.BlockState state) {
        try (var action = owner.begin(owner.actionIdentity(ACTOR, 1), Map.of(cell.cube(), owner.stamp(cell.cube())))) {
            action.set(cell, state); action.commit();
        }
    }

    @Test void unsupportedFallingBlocksEntitiesAndWaterloggedLeavesRemainRefused() {
        for (var state : java.util.List.of(Blocks.GRAVEL.defaultBlockState(), Blocks.SAND.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)))
            assertThrows(SurvivalRefusal.class, () -> palette.id(state));
    }
}
