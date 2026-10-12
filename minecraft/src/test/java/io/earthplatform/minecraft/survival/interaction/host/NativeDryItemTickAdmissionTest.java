package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.BlockPos;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Profile/authority guards only. Actual native tick/landing remains an official-server gate. */
class NativeDryItemTickAdmissionTest {
    @BeforeAll static void bootstrap() { VanillaRegistries.createLookup(); }
    @Test void exactDryPalettePreservesNativeDefaultLandingAndShapeMethods() {
        for (Block block : new Block[]{Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE, Blocks.COBBLESTONE,
                Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.OAK_PLANKS, Blocks.CRAFTING_TABLE})
            assertDoesNotThrow(() -> NativeDryItemTickAdmission.requireBlock(block.defaultBlockState()));
    }
    @Test void fluidHazardAndBounceBlocksRefuseBeforeNativeExecution() {
        for (Block block : new Block[]{Blocks.WATER, Blocks.LAVA, Blocks.FIRE, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.CHEST})
            assertThrows(SurvivalRefusal.class, () -> NativeDryItemTickAdmission.requireBlock(block.defaultBlockState()));
    }
    @Test void signedSweptReadBoxIncludesFluidEyeAndUnderfootWithoutColumnProjection() {
        var box = NativeDryItemTickAdmission.bounds(new AABB(-17.625, 8849, -3.125, -17.375, 8849.25, -2.875), new Vec3(0.1, -0.2, 0.1));
        assertTrue(box.contains(-18, 8848, -4));
        assertTrue(box.contains(-17, 8850, -2));
        assertFalse(box.contains(-18, 4752, -4));
        assertTrue(box.cells() <= NativeDryItemTickAdmission.MAX_CELLS);
    }
    @Test void motionDimensionsAndTrackingPackingOverflowRefuse() {
        var unit = new AABB(0, 0, 0, 0.25, 0.25, 0.25);
        assertThrows(SurvivalRefusal.class, () -> NativeDryItemTickAdmission.bounds(unit, new Vec3(1.001, 0, 0)));
        assertThrows(SurvivalRefusal.class, () -> NativeDryItemTickAdmission.bounds(unit, new Vec3(0, Double.NaN, 0)));
        assertThrows(SurvivalRefusal.class, () -> NativeDryItemTickAdmission.bounds(new AABB(0, 0, 0, 2, 1, 1), Vec3.ZERO));
        assertThrows(SurvivalRefusal.class, () -> NativeDryItemTickAdmission.bounds(new AABB(0, 8388607, 0, .25, 8388607.25, .25), Vec3.ZERO));
        assertThrows(SurvivalRefusal.class, () -> NativeDryItemTickAdmission.bounds(new AABB(30000512, 0, 0, 30000512.25, .25, .25), Vec3.ZERO));
    }
    @Test void readBoxResourceCountsRefuseMultiplicationOverflow() {
        assertThrows(SurvivalRefusal.class, () -> new NativeDryItemTickAdmission.ReadBounds(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
        assertThrows(SurvivalRefusal.class, () -> new NativeDryItemTickAdmission.ReadBounds(0, 0, 0, 64, 64, 64));
        assertThrows(SurvivalRefusal.class, () -> new NativeDryItemTickAdmission.ReadBounds(1, 0, 0, 0, 0, 0));
    }
    @Test void absentScopeNeverGrantsStateOrResidencyReads() {
        assertFalse(NativeItemTickReadScope.permits(null));
        assertFalse(NativeItemTickReadScope.isExecuting(null));
        assertThrows(SurvivalRefusal.class, () -> NativeItemTickReadScope.read(null, BlockPos.ZERO));
        assertThrows(SurvivalRefusal.class, () -> NativeItemTickReadScope.hasResidentBox(null, 0, 0, 0, 0, 0, 0));
        assertThrows(SurvivalRefusal.class, () -> NativeItemTickReadScope.touchingUnloadedArea(null));
    }
    @Test void activeBindingIdentityCannotFallBackToAnotherOpenBinding() {
        Object exact = new Object(), foreign = new Object();
        assertDoesNotThrow(() -> NativeItemTickReadScope.requireSameBinding(exact, exact));
        assertThrows(SurvivalRefusal.class, () -> NativeItemTickReadScope.requireSameBinding(exact, foreign));
        assertThrows(SurvivalRefusal.class, () -> NativeItemTickReadScope.requireSameBinding(exact, null));
        assertThrows(SurvivalRefusal.class, () -> NativeItemTickReadScope.requireSameBinding(null, null));
    }
    @Test void actualItemBlockItemAndPickaxeClassesHaveTheExactDefaultTickDescriptor() throws Exception {
        for (Item item : new Item[]{Items.STICK, Items.OAK_LOG, Items.OAK_PLANKS, Items.COBBLESTONE,
                Items.CRAFTING_TABLE, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE}) {
            assertDoesNotThrow(() -> NativeDryItemTickAdmission.requireItemCallbacks(item.getClass()));
            var method = item.getClass().getMethod("onEntityItemUpdate", ItemStack.class, ItemEntity.class);
            assertSame(IItemExtension.class, method.getDeclaringClass()); assertSame(boolean.class, method.getReturnType());
        }
        // ItemStack has a forwarding overload, not the IItemExtension method on the contained Item.
        assertThrows(NoSuchMethodException.class, () -> ItemStack.class.getMethod("onEntityItemUpdate", ItemStack.class, ItemEntity.class));
    }
    @Test void aCustomNativeItemTickOverrideIsRefusedWithoutConstructionOrInvocation() {
        assertThrows(SurvivalRefusal.class, () -> NativeDryItemTickAdmission.requireItemCallbacks(CustomTickItem.class));
        assertEquals(0, CustomTickItem.calls);
    }
    static final class CustomTickItem extends Item {
        static int calls;
        CustomTickItem() { super(new Item.Properties()); throw new AssertionError("Must not instantiate against the frozen registry"); }
        @Override public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) { calls++; throw new AssertionError("Must not invoke"); }
    }
    @Test void nativeDryCallbackBinariesMatchTheAuditedPinnedArtifacts() {
        assertDoesNotThrow(NativeDryItemTickAdmission::requirePinnedBinaries);
    }
}
