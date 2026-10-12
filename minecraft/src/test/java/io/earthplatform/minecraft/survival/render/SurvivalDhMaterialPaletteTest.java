package io.earthplatform.minecraft.survival.render;

import io.earthplatform.geography.survival.RegisteredBlockState;
import io.earthplatform.geography.survival.SurvivalMaterials;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.file.Path;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalDhMaterialPaletteTest {
    @TempDir static Path source;
    @TempDir Path root;
    @BeforeAll static void setup()throws Exception{SurvivalDhTestSupport.setup(source);}
    @Test void everyAdmittedRegistryStateMatchesFrozenCategories()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var materials=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget)) {
            for(Block block:new Block[]{Blocks.AIR,Blocks.STONE,Blocks.DIRT,Blocks.GRASS_BLOCK,Blocks.GRAVEL,
                    Blocks.COBBLESTONE,Blocks.OAK_LOG,Blocks.OAK_LEAVES,Blocks.OAK_PLANKS,Blocks.CRAFTING_TABLE}) {
                var expected=SurvivalDhMaterialPalette.material(RegisteredBlockState.of(BuiltInRegistries.BLOCK.getKey(block).toString()));
                for(var state:block.getStateDefinition().getPossibleStates())if(state.getFluidState().isEmpty())
                    assertEquals(expected,materials.state(SurvivalDhTestSupport.source.palette().id(state)),state.toString());
            }
            assertEquals(5,materials.state(SurvivalDhTestSupport.source.palette().id(Blocks.GRAVEL.defaultBlockState())).category());
            assertEquals(3,materials.state(SurvivalDhTestSupport.source.palette().id(Blocks.CRAFTING_TABLE.defaultBlockState())).category());
            assertThrows(SurvivalRefusal.class,()->materials.state(Integer.MAX_VALUE));
        }
    }
    @Test void unknownBlocksNeverUseUnknownFallbackAndAliasChargesDrain()throws Exception{
        assertThrows(SurvivalRefusal.class,()->SurvivalDhMaterialPalette.material(RegisteredBlockState.of("minecraft:diamond_block")));
        for(var state:SurvivalMaterials.supportedStates())assertNotEquals(0,SurvivalDhMaterialPalette.material(state).category());
        try(var f=new SurvivalDhTestSupport(root)) {
            var first=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget);var alias=first.retain();
            String identity=alias.identity();first.close();assertThrows(SurvivalRefusal.class,first::identity);
            assertEquals(identity,alias.identity());assertTrue(f.budget.evidence().bytes()>0);alias.close();assertEquals(0,f.budget.evidence().bytes());
        }
    }
}
