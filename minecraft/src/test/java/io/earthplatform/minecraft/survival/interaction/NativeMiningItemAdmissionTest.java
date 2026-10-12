package io.earthplatform.minecraft.survival.interaction;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Guard tests only. They are not evidence of real ServerPlayer/Level action execution. */
class NativeMiningItemAdmissionTest {
    private static SurvivalPalette palette;
    @BeforeAll static void setup() {
        var lookup = VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
    }

    @Test void exactNativeProgressionItemsAreAdmittedAndUnrelatedBlocksRefuse() {
        for (Item item : new Item[]{Items.OAK_LOG, Items.OAK_PLANKS, Items.CRAFTING_TABLE, Items.COBBLESTONE,
                Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.STICK})
            assertDoesNotThrow(() -> NativeVanillaPreparation.requireSupportedTool(new ItemStack(item), palette));
        assertDoesNotThrow(() -> NativeVanillaPreparation.requireSupportedTool(ItemStack.EMPTY, palette));
        assertThrows(SurvivalRefusal.class, () -> NativeVanillaPreparation.requireSupportedTool(new ItemStack(Items.CHEST), palette));
        assertThrows(SurvivalRefusal.class, () -> NativeVanillaPreparation.requireSupportedTool(new ItemStack(Items.DIAMOND_PICKAXE), palette));
    }

    @Test void customBlockItemIsRefusedBeforeAnyOverriddenQueryCanExecute() throws Exception {
        // No item is registered and no frozen native registry is changed. Only the class guard is
        // under test; all behavior below that guard is intentionally unavailable on this object.
        var unsafeClass = Class.forName("sun.misc.Unsafe"); var field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var item = (BlockItem) unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), HostileBlockItem.class);
        assertThrows(SurvivalRefusal.class, () -> NativeVanillaPreparation.requireOrdinaryMiningBlockItem(item, palette));
    }

    private static final class HostileBlockItem extends BlockItem {
        private HostileBlockItem() { super(Blocks.OAK_LOG, new Item.Properties()); }
        @Override public Block getBlock() { throw new AssertionError("Unadmitted callback was executed"); }
    }
}
