package io.earthplatform.minecraft.cubic.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies real-°C temperature conversions and mixin registration for Homeostatic, Cold Sweat and Tough as Nails. */
class TemperatureCompatTest {
    private static final List<String> TEMPERATURE_MIXINS = List.of(
            "compat.HomeostaticHeightMixin",
            "compat.HomeostaticSectionMixin",
            "compat.ColdSweatWorldHelperMixin",
            "compat.ColdSweatCaveBiomeMixin",
            "compat.ColdSweatElevationMixin",
            "compat.ColdSweatDepthBoundMixin",
            "compat.ColdSweatBiomeTempMixin",
            "compat.ColdSweatHearthMixin",
            "compat.ToughAsNailsTemperatureMixin");

    @Test void mcTemperatureMatchesKeyBenchmarks() {
        assertEquals(0.15f, EarthTemperature.mcTemperature(0.0), 1e-5f);
        assertEquals(0.80f, EarthTemperature.mcTemperature(20.0), 1e-5f);
        assertEquals(1.45f, EarthTemperature.mcTemperature(40.0), 1e-5f);
        assertEquals(-0.50f, EarthTemperature.mcTemperature(-20.0), 1e-5f);
        assertTrue(EarthTemperature.mcTemperature(-30.0) < EarthTemperature.mcTemperature(10.5));
        assertTrue(EarthTemperature.mcTemperature(10.5) < EarthTemperature.mcTemperature(25.0));
        assertTrue(EarthTemperature.mcTemperature(25.0) < EarthTemperature.mcTemperature(35.0));
    }

    @Test void homeostaticScaleInvertsTempHelperExactly() {
        // Homeostatic TempHelper.convertMcTemp(mc, false): °C = ((25.27027027 + 44.86486486 * mc) - 32) * 0.5556
        for (double c : new double[]{-30.0, -12.2, 0.0, 10.5, 11.0, 20.0, 23.89, 25.0, 35.0, 43.3}) {
            float mc = EarthTemperature.homeostatic(c);
            double roundTrip = ((25.27027027 + 44.86486486 * mc) - 32.0) * 0.5556;
            assertEquals(c, roundTrip, 1e-3, "Homeostatic round-trip at " + c + " °C");
        }
        // Freezing line matches Minecraft 0.15, neutral parity (~23.9 °C) matches 1.108, Antarctica < EXTREME_COLD (-0.340).
        assertEquals(0.150f, EarthTemperature.homeostatic(0.0), 1e-3f);
        assertEquals(1.108f, EarthTemperature.homeostatic(23.89), 5e-3f);
        assertTrue(EarthTemperature.homeostatic(-30.0) < -0.340f, "Antarctica below EXTREME_COLD");
        assertTrue(EarthTemperature.homeostatic(10.5) > 0.15f && EarthTemperature.homeostatic(10.5) < 1.108f, "Denver moderate/cool");
        assertTrue(EarthTemperature.homeostatic(35.0) > 1.50f, "Sahara hot");
    }

    @Test void coldSweatScaleAndDiurnalSwing() {
        assertEquals(0.0, EarthTemperature.coldSweat(0.0), 1e-9);
        assertEquals(1.0, EarthTemperature.coldSweat(25.0), 1e-9);
        assertEquals(-1.2, EarthTemperature.coldSweat(-30.0), 1e-9);

        // Dry desert has wider day/night swing than humid temperate climate.
        double saharaNoon = EarthTemperature.coldSweatDiurnal(30.0, 6000L, false);
        double saharaMidnight = EarthTemperature.coldSweatDiurnal(30.0, 18000L, false);
        double englandNoon = EarthTemperature.coldSweatDiurnal(11.0, 6000L, true);
        double englandMidnight = EarthTemperature.coldSweatDiurnal(11.0, 18000L, true);
        assertTrue(saharaNoon > 1.51, "Sahara afternoon exceeds Cold Sweat normal max habitable (100 °F)");
        assertTrue(saharaMidnight < 1.0, "Sahara midnight cools into comfortable desert night");
        assertTrue((saharaNoon - saharaMidnight) > (englandNoon - englandMidnight), "Dry biome swings wider than humid biome");
        assertTrue(EarthTemperature.coldSweat(-30.0) < 0.40, "Antarctica well below Cold Sweat normal min habitable (50 °F)");
        assertTrue(EarthTemperature.coldSweat(25.0) > EarthTemperature.coldSweat(10.5), "Dead Sea warmer than Denver");
    }

    @Test void toughAsNailsTiersMatchEarthClimates() {
        assertEquals(EarthTemperature.TAN_ICY, EarthTemperature.toughAsNailsOrdinal(-30.0), "Antarctica is ICY");
        assertEquals(EarthTemperature.TAN_ICY, EarthTemperature.toughAsNailsOrdinal(-0.1), "Sub-zero is ICY");
        assertEquals(EarthTemperature.TAN_COLD, EarthTemperature.toughAsNailsOrdinal(0.0), "0 °C is COLD");
        assertEquals(EarthTemperature.TAN_COLD, EarthTemperature.toughAsNailsOrdinal(5.0), "Cool subarctic is COLD");
        assertEquals(EarthTemperature.TAN_NEUTRAL, EarthTemperature.toughAsNailsOrdinal(10.5), "Denver (Y 1660) is NEUTRAL");
        assertEquals(EarthTemperature.TAN_NEUTRAL, EarthTemperature.toughAsNailsOrdinal(11.0), "England is NEUTRAL");
        assertEquals(EarthTemperature.TAN_NEUTRAL, EarthTemperature.toughAsNailsOrdinal(11.5), "Cusco (Y 3460) is NEUTRAL");
        assertEquals(EarthTemperature.TAN_WARM, EarthTemperature.toughAsNailsOrdinal(25.0), "Dead Sea (Y -368) is WARM");
        assertEquals(EarthTemperature.TAN_HOT, EarthTemperature.toughAsNailsOrdinal(32.0), "Sahara is HOT");
    }

    @Test void nullLevelFallsBackSafely() {
        assertFalse(EarthTemperature.hasEarth(null));
        assertTrue(Double.isNaN(EarthTemperature.celsius(null, 0, 64, 0)));
        assertEquals(0, EarthTemperature.depthBelowSurface(null, 0, 64, 0));
    }

    @Test void temperatureMixinsRegisteredAndPseudo() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        Set<String> registered = new HashSet<>();
        try (var in = loader.getResourceAsStream("earthplatform.cubic.mixins.json")) {
            assertNotNull(in, "earthplatform.cubic.mixins.json missing");
            var root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("mixins")) registered.add(el.getAsString());
        }
        for (String mixin : TEMPERATURE_MIXINS) {
            assertTrue(registered.contains(mixin), "Missing mixin registration: " + mixin);
            String resource = "io/earthplatform/minecraft/cubic/mixin/" + mixin.replace('.', '/') + ".class";
            try (var in = loader.getResourceAsStream(resource)) {
                assertNotNull(in, "Compiled class missing: " + resource);
                ClassNode node = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                boolean hasPseudo = false, hasRemapFalse = false;
                if (node.invisibleAnnotations != null) {
                    for (AnnotationNode ann : node.invisibleAnnotations) {
                        if ("Lorg/spongepowered/asm/mixin/Pseudo;".equals(ann.desc)) hasPseudo = true;
                        if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc) && ann.values != null) {
                            for (int i = 0; i + 1 < ann.values.size(); i += 2) {
                                if ("remap".equals(ann.values.get(i)) && Boolean.FALSE.equals(ann.values.get(i + 1))) {
                                    hasRemapFalse = true;
                                }
                            }
                        }
                    }
                }
                assertTrue(hasPseudo, mixin + " must carry @Pseudo");
                assertTrue(hasRemapFalse, mixin + " must set remap = false");
            }
        }
    }
}
