package io.earthplatform.compat.dh.shader;

import java.net.JarURLConnection;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact external helper classpath, not a claim of transformed NeoForge or GPU startup. */
class ShaderClasspathContractTest {
    @Test void installedShaderPartitionSeesExactlyTheSuppliedOuterArtifacts() throws Exception {
        assertEquals("installed-shader", System.getProperty("earthplatform.test.partition"));
        assertNull(System.getProperty("earthplatform.test.isolatedDhRuntime"));
        ClassLoader loader = getClass().getClassLoader();
        assertNull(loader.getResource("net/neoforged/fml/junit/JUnitService.class"));
        Class<?> filesystem = Class.forName("cpw.mods.niofs.union.UnionFileSystem", false, loader);
        assertEquals("cpw.mods.securejarhandler", filesystem.getModule().getName());
        assertTrue(Object.class.getModule().isOpen("java.lang.invoke", filesystem.getModule()));
        assertFalse(Object.class.getModule().isOpen("java.lang.invoke", getClass().getModule()),
                "The inherited FML opening must never be widened to every helper/mod in the unnamed module");
        for (String[] input : new String[][]{
                {"com/seibel/distanthorizons/api/DhApi.class", "earthplatform.installedDhTestJar"},
                {"net/irisshaders/iris/api/v0/IrisApi.class", "earthplatform.installedIrisTestJar"},
                {"net/caffeinemc/mods/sodium/service/SodiumWorkarounds.class", "earthplatform.installedSodiumTestJar"}}) {
            var resources = Collections.list(loader.getResources(input[0]));
            assertEquals(1, resources.size(), input[0]);
            var jar = assertInstanceOf(JarURLConnection.class, resources.getFirst().openConnection());
            assertEquals(Path.of(System.getProperty(input[1])).toRealPath(), Path.of(jar.getJarFileURL().toURI()).toRealPath());
        }
        assertNull(loader.getResource("shaders/world1/dh_terrain.fsh"), "Photon must remain a data-only input");
    }
}
