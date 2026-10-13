package io.earthplatform.minecraft.scene;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneCarrierResourceTest {
    private static com.google.gson.JsonObject resource(String path) throws Exception {
        try(var in=SceneCarrierResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(in,path);
            return JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void actualDimensionCodecKeepsStockHeightCarrier() throws Exception {
        var json=resource("/data/earthplatform/dimension_type/native_view_carrier.json");
        var type=DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();
        assertEquals(-64,type.minY());assertEquals(384,type.height());assertEquals(384,type.logicalHeight());
        assertEquals(24,type.height()/16);
    }
    @Test void actualFlatCodecKeepsCarrierEmptyAndStructureFree() throws Exception {
        var json=resource("/packs/prototypes/data/earthplatform/dimension/native_view_carrier.json");
        assertEquals("earthplatform:native_view_carrier",json.get("type").getAsString());
        var generator=json.getAsJsonObject("generator");assertEquals("minecraft:flat",generator.get("type").getAsString());
        var settings=generator.getAsJsonObject("settings");
        var decoded=FlatLevelGeneratorSettings.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,VanillaRegistries.createLookup()),settings).getOrThrow();
        assertTrue(decoded.getLayersInfo().isEmpty());assertTrue(decoded.getLayers().isEmpty());
        assertEquals(0,settings.getAsJsonArray("structure_overrides").size());
        assertFalse(settings.get("lakes").getAsBoolean());assertFalse(settings.get("features").getAsBoolean());
    }
}
