package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Set;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifierManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Callback-negative source-closure tests. Authored off-tree; not executed by this worker. */
class NativeBlockBreakAdmissionTest {
    @BeforeAll static void bootstrap() { VanillaRegistries.createLookup(); }
    @Test void nativeEmptyLootModifierBackingIsAdmitted() {
        assertDoesNotThrow(()->NativeBlockBreakAdmission.requireEmptyLootModifierBacking(new LootModifierManager()));
    }
    @Test void foreignApparentlyEmptyLootMapIsRefusedWithoutAnyMapMethod() throws Exception {
        var manager=new LootModifierManager();var foreign=new ForeignMap();
        var field=LootModifierManager.class.getDeclaredField("registeredLootModifiers");
        assertTrue(field.trySetAccessible());field.set(manager,foreign);
        assertThrows(SurvivalRefusal.class,()->NativeBlockBreakAdmission.requireEmptyLootModifierBacking(manager));
        assertEquals(0,foreign.calls);
    }
    @Test void foreignStatSubclassIsRefusedBeforeNameHashOrEquality() {
        var foreign=new ForeignStat();foreign.calls=0;
        assertThrows(SurvivalRefusal.class,()->NativeBlockBreakAdmission.requireExactStatKey(foreign));
        assertEquals(0,foreign.calls);
        assertSame(Stats.BLOCK_MINED.get(Blocks.DIRT),NativeBlockBreakAdmission.requireExactStatKey(Stats.BLOCK_MINED.get(Blocks.DIRT)));
    }
    private static final class ForeignMap extends AbstractMap<ResourceLocation,IGlobalLootModifier> {
        int calls;
        @Override public Set<Entry<ResourceLocation,IGlobalLootModifier>> entrySet(){calls++;return Set.of();}
        @Override public Collection<IGlobalLootModifier> values(){calls++;return java.util.List.of();}
        @Override public boolean isEmpty(){calls++;return true;}
    }
    private static final class ForeignStat extends Stat<Block> {
        int calls;
        ForeignStat(){super(Stats.BLOCK_MINED,Blocks.DIRT,StatFormatter.DEFAULT);}
        @Override public String getName(){calls++;return super.getName();}
        @Override public int hashCode(){calls++;return super.hashCode();}
        @Override public boolean equals(Object other){calls++;return super.equals(other);}
    }
}
