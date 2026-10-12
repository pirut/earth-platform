package io.earthplatform.compat.dh;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exact-input tests use the same opt-in artifact properties as the installed-shader test build. */
class InstalledShaderArtifactTest {
    @TempDir Path temp;
    private Path input(String property) {
        String path = System.getProperty(property);
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null, "Authorized external artifact property is absent: " + property);
        return Path.of(path);
    }
    private Path iris() { return input("earthplatform.installedIrisTestJar"); }
    private Path sodium() { return input("earthplatform.installedSodiumTestJar"); }
    private Path photon() { return input("earthplatform.installedShaderTestPack"); }
    @Test void exactReleasedIrisAndSodiumOuterBytesMatchPins() throws Exception {
        InstalledIrisRuntime.verifyBytes(iris(), 2_540_723,
                InstalledIrisRuntime.IRIS_SHA256, "Iris");
        InstalledIrisRuntime.verifyBytes(sodium(), 1_291_422,
                InstalledIrisRuntime.SODIUM_SHA256, "Sodium");
    }
    @Test void exactPhysicalAndMountedPhotonBytesAreAttestedWithoutClosingBorrowedFilesystem() throws Exception {
        Path photon = photon();
        Path root = photon.getParent();
        byte[] before = InstalledIrisRuntime.hash(photon);
        // This test creates and owns the ZIP handle. The runtime must not close it.
        try (var zip = FileSystems.newFileSystem(photon)) {
            var s = new InstalledIrisRuntime.Snapshot(new Object(), new Object(), new Object(), new Object(), new Object(),
                    zip, 0, InstalledIrisRuntime.PHOTON_NAME, root, 2, InstalledIrisRuntime.STRINGS, InstalledIrisRuntime.BOOLEANS);
            InstalledIrisRuntime.verifyPack(s);
            assertTrue(zip.isOpen()); assertArrayEquals(before, InstalledIrisRuntime.hash(photon));
        }
    }
    @Test void sameSizeWrongHashMissingFileAndDirectoryFail() throws Exception {
        Path file = temp.resolve("input.jar"); Files.writeString(file, "abcdefgh");
        assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyBytes(file, 8, "0".repeat(64), "fake test artifact"));
        assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyBytes(file, 9, "0".repeat(64), "fake test artifact"));
        assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyBytes(temp.resolve("absent.jar"), 8, "0".repeat(64), "fake test artifact"));
        assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyBytes(temp, 8, "0".repeat(64), "fake test artifact"));
        assertEquals("abcdefgh", Files.readString(file));
    }
    @Test void foreignMountedArchiveCannotBeAttestedFromCorrectSelectedFilename() throws Exception {
        Path root = photon().getParent();
        try (var zip = FileSystems.newFileSystem(iris())) {
            var s = new InstalledIrisRuntime.Snapshot(new Object(), new Object(), new Object(), new Object(), new Object(),
                    zip, 0, InstalledIrisRuntime.PHOTON_NAME, root, 2, InstalledIrisRuntime.STRINGS, InstalledIrisRuntime.BOOLEANS);
            assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyPack(s)); assertTrue(zip.isOpen());
        }
    }
    @Test void missingAndDirectoryPacksAreNotAccepted() throws Exception {
        var s = new InstalledIrisRuntime.Snapshot(new Object(), new Object(), new Object(), new Object(), new Object(),
                FileSystems.getDefault(), 0, InstalledIrisRuntime.PHOTON_NAME, temp, 2, InstalledIrisRuntime.STRINGS, InstalledIrisRuntime.BOOLEANS);
        assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyPack(s));
        Files.createDirectory(temp.resolve(InstalledIrisRuntime.PHOTON_NAME));
        assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyPack(s));
    }
}
