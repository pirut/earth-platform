package io.earthplatform.compat.dh;

import cpw.mods.niofs.union.UnionFileSystemProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real FML filesystem-only fixtures; no Minecraft, Sodium or Iris class initialization. */
class InstalledSodiumOriginTest {
    private Path artifact() {
        String root = System.getProperty("earthplatform.installedSodiumTestJar");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        return Path.of(root);
    }
    private InstalledIrisRuntime.InstalledMod mod(Path root) {
        return new InstalledIrisRuntime.InstalledMod("sodium", InstalledIrisRuntime.SODIUM_VERSION, root,
                getClass().getClassLoader(), "sodium", URI.create("file:/fake-authoritative-module-location"));
    }
    private FileSystem mount(Path entry) throws Exception {
        URI uri = URI.create("jij:" + entry.toAbsolutePath().toUri().getRawSchemeSpecificPart());
        return FileSystems.newFileSystem(uri, Map.of("packagePath", entry));
    }
    @Test void realReleasedNestedArtifactAndOuterServiceBothVerifyWithoutClosingTheirOwners() throws Exception {
        try (var outer = new UnionFileSystemProvider().newFileSystem(null, artifact());
             var nested = mount(outer.getPath("/" + InstalledSodiumOrigin.ENTRY))) {
            InstalledSodiumOrigin.verify(mod(nested.getPath("/")));
            assertTrue(outer.isOpen()); assertTrue(nested.isOpen());
            try (var wrapper = new UnionFileSystemProvider().newFileSystem(null, nested.getPath("/"))) {
                InstalledSodiumOrigin.verify(mod(wrapper.getPath("/")));
                assertTrue(wrapper.isOpen()); assertTrue(outer.isOpen()); assertTrue(nested.isOpen());
            }
        }
    }
    @Test void wrongNestedMemberNonRootAndOuterJarCannotMasqueradeAsLoadedSodium() throws Exception {
        assertThrows(IOException.class, () -> InstalledSodiumOrigin.verify(mod(artifact())));
        try (var outer = new UnionFileSystemProvider().newFileSystem(null, artifact());
             var nested = mount(outer.getPath("/META-INF/jarjar/fabric-api-base-0.4.42+d1308ded19.jar"))) {
            assertThrows(IOException.class, () -> InstalledSodiumOrigin.verify(mod(nested.getPath("/"))));
        }
        try (var outer = new UnionFileSystemProvider().newFileSystem(null, artifact());
             var nested = mount(outer.getPath("/" + InstalledSodiumOrigin.ENTRY))) {
            assertThrows(IOException.class, () -> InstalledSodiumOrigin.verify(mod(nested.getPath("/META-INF"))));
            assertTrue(outer.isOpen()); assertTrue(nested.isOpen());
        }
    }
}
