package io.earthplatform.minecraft.survival.host;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class SurvivalInstalledModIdentityTest {
    @TempDir Path temporary;
    private static Map<String,String> inventory(){return new HashMap<>(Map.of("minecraft","1.21.1","neoforge","21.1.256","earthplatform","0.1.0-alpha.39","distanthorizons","3.3.3","iris","1.8.14-beta.1+mc1.21.1","sodium","0.8.12-beta.1+mc1.21.1",
            "fabric_api_base","0.4.42+d1308ded19","fabric_rendering_data_attachment_v1","0.3.48+73761d2e19",
            "fabric_block_view_api_v2","1.0.10+9afaaf8c19","fabric_renderer_api_v1","3.4.1+9125b6dc19"));}
    @Test void exactVersionInventoryRequiredWithoutSilentlyIgnoringAdditionalMods()throws Exception{
        var good=inventory();SurvivalInstalledModIdentity.requireVersions(good);
        good.put("extra-gameplay","1");assertThrows(IOException.class,()->SurvivalInstalledModIdentity.requireVersions(good));
        good.remove("extra-gameplay");good.put("iris","different");assertThrows(IOException.class,()->SurvivalInstalledModIdentity.requireVersions(good));
        good.remove("iris");assertThrows(IOException.class,()->SurvivalInstalledModIdentity.requireVersions(good));
        var withoutShim=inventory();withoutShim.remove("fabric_api_base");
        assertThrows(IOException.class,()->SurvivalInstalledModIdentity.requireVersions(withoutShim));
    }
    @Test void artifactHashUsesCompleteBytesAndRejectsSymlinksDirectoriesAndEmptyFiles()throws Exception{
        Path p=temporary.resolve("a.jar");Files.writeString(p,"abc");assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",SurvivalInstalledModIdentity.hashInstalledJar(p));
        Files.writeString(p,"abcd");assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",SurvivalInstalledModIdentity.hashInstalledJar(p));
        Path link=temporary.resolve("link.jar");Files.createSymbolicLink(link,p);assertThrows(IOException.class,()->SurvivalInstalledModIdentity.hashInstalledJar(link));
        assertThrows(IOException.class,()->SurvivalInstalledModIdentity.hashInstalledJar(temporary));
        Files.write(p,new byte[0]);assertThrows(IOException.class,()->SurvivalInstalledModIdentity.hashInstalledJar(p));
    }
}
