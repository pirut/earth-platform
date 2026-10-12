package io.earthplatform.minecraft.storage;

import io.earthplatform.compat.neoforge.TempFileShimActivation;
import io.earthplatform.minecraft.mixin.EarthTempFileMixinPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TempFileShimActivationTest {
    @TempDir Path temp;
    @Test void absentOptInNeverNeedsMetadataOrChangesTarget() {
        assertFalse(TempFileShimActivation.select(false,null,null));
        String prior=System.getProperty(TempFileShimActivation.PROPERTY);
        try{System.clearProperty(TempFileShimActivation.PROPERTY);assertFalse(new EarthTempFileMixinPlugin().shouldApplyMixin("unknown","unused"));}
        finally{if(prior==null)System.clearProperty(TempFileShimActivation.PROPERTY);else System.setProperty(TempFileShimActivation.PROPERTY,prior);}
    }
    @Test void exactKnownPairAdmits() {assertTrue(TempFileShimActivation.select(true,"21.1.256","3.3.3"));}
    @Test void missingOrChangedInstalledVersionsFailClosed() {
        for(String neo:new String[]{null,"21.1.255","21.1.256-fork",""})assertThrows(IllegalStateException.class,()->TempFileShimActivation.select(true,neo,"3.3.3"));
        for(String dh:new String[]{null,"3.3.2","3.3.3-fork",""})assertThrows(IllegalStateException.class,()->TempFileShimActivation.select(true,"21.1.256",dh));
    }
    @Test void changedMissingOrDirectoryArtifactsRefuse() throws Exception {
        Path changed=temp.resolve("changed.jar");Files.writeString(changed,"not the pinned official bytes");
        assertThrows(IllegalStateException.class,()->TempFileShimActivation.verifyInstalledDh(changed));
        assertThrows(IllegalStateException.class,()->TempFileShimActivation.verifyInstalledDh(temp.resolve("absent.jar")));
        assertThrows(IllegalStateException.class,()->TempFileShimActivation.verifyInstalledDh(temp));
    }
    @Test void suppliedOfficialArtifactActuallyMatches() {
        String path=System.getProperty("earthplatform.installedDhTestJar");
        assertNotNull(path,"The existing mandatory official DH input must be supplied to the aggregate");
        assertDoesNotThrow(()->TempFileShimActivation.verifyInstalledDh(Path.of(path)));
    }
    @Test void actualLegacyLoaderCannotAdmitRequestedShimWithoutLoadedDhEvenWithJarOnDisk() {
        String previous=System.getProperty(TempFileShimActivation.PROPERTY);
        try {
            System.setProperty(TempFileShimActivation.PROPERTY,"true");
            var mods=net.neoforged.fml.loading.LoadingModList.get();
            assertNotNull(mods,"Real FML loading metadata must exist for this admission proof");
            var neo=mods.getMods().stream().filter(m->m.getModId().equals("neoforge")).toList();
            assertEquals(1,neo.size());assertEquals("21.1.256",neo.getFirst().getVersion().toString());
            assertTrue(mods.getMods().stream().noneMatch(m->m.getModId().equals("distanthorizons")));
            String official=System.getProperty("earthplatform.installedDhTestJar");assertNotNull(official);
            assertDoesNotThrow(()->TempFileShimActivation.verifyInstalledDh(Path.of(official)));
            IllegalStateException refused=assertThrows(IllegalStateException.class,()->new EarthTempFileMixinPlugin().shouldApplyMixin(
                "net.neoforged.neoforge.common.IOUtilities","io.earthplatform.minecraft.mixin.NeoForgeTempFileMixin"));
            assertEquals("Temporary-file shim requires explicitly selected NeoForge21.1.256 and installed DH3.3.3; observed 21.1.256 / null",refused.getMessage());
        } finally {if(previous==null)System.clearProperty(TempFileShimActivation.PROPERTY);else System.setProperty(TempFileShimActivation.PROPERTY,previous);}
    }
    @Test void productionConfigKeepsRealAdmissionAndOnlyTestConfigClaimsEarlierRegistration() throws Exception {
        ClassLoader loader=getClass().getClassLoader();
        try(var main=loader.getResourceAsStream("earthplatform.temp-files.mixins.json");
            var test=loader.getResourceAsStream("earthplatform.savedata-scan-tests.mixins.json")) {
            assertNotNull(main);assertNotNull(test);
            var production=com.google.gson.JsonParser.parseString(new String(main.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var fixture=com.google.gson.JsonParser.parseString(new String(test.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("io.earthplatform.minecraft.mixin.EarthTempFileMixinPlugin",production.get("plugin").getAsString());
            assertEquals("io.earthplatform.minecraft.startupfixture.SavedDataScanTestPlugin",fixture.get("plugin").getAsString());
            assertEquals(production.get("mixins"),fixture.get("mixins"));
            assertEquals("NeoForgeTempFileMixin",production.getAsJsonArray("mixins").get(0).getAsString());
            assertEquals(1,production.getAsJsonArray("mixins").size());
            assertTrue(fixture.get("priority").getAsInt()<(production.has("priority")?production.get("priority").getAsInt():1000));
        }
    }
    @Test void realPinnedIoUtilitiesHasTheProductionRedirectAfterActualTransformation() {
        assertTrue(Arrays.stream(IOUtilities.class.getDeclaredMethods()).anyMatch(m->m.getName().contains("earthplatform$namesOnlyTempFiles")),
            "Actual pinned IOUtilities must be transformed, not a helper-only surrogate");
    }
}
