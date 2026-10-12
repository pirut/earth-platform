package io.earthplatform.compat.dh;

import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Confirms the separate installed test JVM sees exactly the operator-supplied official artifact. */
class InstalledDhClasspathContractTest {
    @Test void installedPartitionUsesOneExistingOfficialClassIdentity() throws Exception {
        assertEquals("installed-dh", System.getProperty("earthplatform.test.partition"));
        assertNull(System.getProperty("earthplatform.test.isolatedDhRuntime"), "Installed tests cannot authorize the diagnostic loader");
        ClassLoader loader = getClass().getClassLoader();
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.neoforged.fml.junit.JUnitService", false, loader),
                "Plain helper contracts must not bootstrap NeoForge without the normal test task's launch wiring");
        Class<?> api = Class.forName("com.seibel.distanthorizons.api.DhApi", false, loader);
        assertSame(loader, api.getClassLoader());
        Path actual = Path.of(api.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        assertEquals(Path.of(System.getProperty("earthplatform.installedDhTestJar")).toRealPath(), actual);
        assertEquals(1, Collections.list(loader.getResources("com/seibel/distanthorizons/api/DhApi.class")).size(), "No duplicate official API payload");
    }
}
