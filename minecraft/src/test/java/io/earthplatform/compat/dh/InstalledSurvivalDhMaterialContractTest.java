package io.earthplatform.compat.dh;

import io.earthplatform.minecraft.survival.render.SurvivalDhMaterialPalette;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Installed-DH partition only. No normal-test classpath widening and no packing claim. */
class InstalledSurvivalDhMaterialContractTest {
    @Test void pinnedEnumAndClassifierBytesDefineExactSurvivalCategories()throws Exception{
        assertEquals("installed-dh",System.getProperty("earthplatform.test.partition"));
        Path jar=Path.of(java.util.Objects.requireNonNull(System.getProperty("earthplatform.installedDhTestJar")));
        InstalledDhResolver.verifyArtifact(jar);
        try(var zip=new ZipFile(jar.toFile())) {
            assertEquals(SurvivalDhMaterialPalette.ENUM_SHA256,hash(zip,"com/seibel/distanthorizons/api/enums/rendering/EDhApiBlockMaterial.class"));
            assertEquals(SurvivalDhMaterialPalette.CLASSIFIER_SHA256,hash(zip,"com/seibel/distanthorizons/common/wrappers/block/BlockStateWrapper.class"));
        }
        assertEquals(1,com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.LEAVES.index);
        assertEquals(2,com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.STONE.index);
        assertEquals(3,com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.WOOD.index);
        assertEquals(5,com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.DIRT.index);
        assertEquals(13,com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.GRASS.index);
        assertEquals(14,com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.AIR.index);
    }
    private static String hash(ZipFile zip,String path)throws Exception{
        var entry=zip.getEntry(path);assertNotNull(entry);assertTrue(entry.getSize()>0&&entry.getSize()<=65536);
        byte[] value;try(var input=zip.getInputStream(entry)){value=input.readNBytes(65537);}
        assertTrue(value.length<=65536);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
