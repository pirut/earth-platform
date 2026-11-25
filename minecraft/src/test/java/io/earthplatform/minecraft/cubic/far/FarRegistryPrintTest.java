package io.earthplatform.minecraft.cubic.far;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The far store's stamp holds a print of the registries the kept columns' ids come from. It is the same on every boot
 * of the same registries (so kept columns survive a restart), and it changes with any id they depend on (so they never
 * outlive the ids). {@code FarServer.registryPrint} reads the real registries, so these build biome registries in the
 * orders a boot could give.
 */
class FarRegistryPrintTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<Holder.Reference<Biome>> vanillaBiomes() {
        return new ArrayList<>(VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements().toList());
    }

    private static Registry<Biome> registry(List<Holder.Reference<Biome>> biomes) {
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomes.forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        return registry.freeze();
    }

    @Test void theSameRegistriesGiveTheSamePrintEveryTime() {
        List<Holder.Reference<Biome>> biomes = vanillaBiomes();
        String first = FarServer.registryPrint(registry(biomes));
        // Built again, as a second boot builds them.
        assertEquals(first, FarServer.registryPrint(registry(biomes)));
        assertEquals(first, FarServer.registryPrint(registry(new ArrayList<>(biomes))));
    }

    @Test void thePrintNamesWhatItCovers() {
        String print = FarServer.registryPrint(registry(vanillaBiomes()));
        assertTrue(print.matches("blocks=[0-9a-f-]+/\\d+,biomes=[0-9a-f-]+/\\d+"), print);
    }

    @Test void aBiomeAddedOrRemovedChangesOnlyTheBiomeHalf() {
        List<Holder.Reference<Biome>> biomes = vanillaBiomes();
        String full = FarServer.registryPrint(registry(biomes));
        String fewer = FarServer.registryPrint(registry(biomes.subList(0, biomes.size() - 1)));
        assertEquals(blocks(full), blocks(fewer));
        assertNotEquals(biomes(full), biomes(fewer));
    }

    @Test void biomesInAnotherOrderHaveOtherIdsSoAnotherPrint() {
        List<Holder.Reference<Biome>> biomes = vanillaBiomes();
        List<Holder.Reference<Biome>> swapped = new ArrayList<>(biomes);
        Collections.reverse(swapped);
        // Kept columns hold biome ids, so an order the ids follow must not share a print with another.
        assertNotEquals(biomes(FarServer.registryPrint(registry(biomes))), biomes(FarServer.registryPrint(registry(swapped))));
    }

    private static String blocks(String print) { return print.substring(0, print.indexOf(",biomes=")); }
    private static String biomes(String print) { return print.substring(print.indexOf(",biomes=")); }
}
