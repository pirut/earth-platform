package io.earthplatform.minecraft.survival.interaction.host;
import net.minecraft.core.DefaultedMappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.stats.Stats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Actual native registry implementation regression, not a registry replacement/mock. */
class NativeBlockBreakRegistryTest {
 @BeforeAll static void bootstrap(){VanillaRegistries.createLookup();}
 @Test void minedBlockRegistryUsesExactDefaultedNativeImplementation(){
  assertSame(DefaultedMappedRegistry.class,BuiltInRegistries.BLOCK.getClass());
  assertSame(BuiltInRegistries.BLOCK,Stats.BLOCK_MINED.getRegistry());
  assertDoesNotThrow(NativeBlockBreakAdmission::requireNativeMinedRegistry);
 }
}
