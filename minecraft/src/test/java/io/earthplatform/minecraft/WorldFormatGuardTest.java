package io.earthplatform.minecraft;

import io.earthplatform.minecraft.storage.SectionYIntegrityError;
import io.earthplatform.minecraft.storage.WorldFormatGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldFormatGuardTest {
    @TempDir Path directory;

    @Test void defaultWorldIsUntouchedAndWideWorldHasExplicitRequiredModDeclaration() throws Exception {
        assertThrows(SectionYIntegrityError.class, () -> WorldFormatGuard.requireVerifiedWideWorld(directory));
        WorldFormatGuard.preflight(directory, false);
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        WorldFormatGuard.preflight(directory, true);
        assertDoesNotThrow(() -> WorldFormatGuard.requireVerifiedWideWorld(directory));
        byte[] manifest = Files.readAllBytes(directory.resolve(WorldFormatGuard.FILE_NAME));
        String warning = Files.readString(directory.resolve(WorldFormatGuard.WARNING_NAME));
        assertTrue(warning.contains("Vanilla ignores this declaration"));
        assertTrue(warning.contains("No vanilla export/migration"));
        assertDoesNotThrow(() -> WorldFormatGuard.preflight(directory, true));
        assertThrows(SectionYIntegrityError.class, () -> WorldFormatGuard.preflight(directory, false));
        assertThrows(SectionYIntegrityError.class, () -> WorldFormatGuard.requireVerifiedWideWorld(directory));
        assertArrayEquals(manifest, Files.readAllBytes(directory.resolve(WorldFormatGuard.FILE_NAME)));
    }
    @Test void incompatibleOrTruncatedWorldDeclarationsAreNeverOverwritten() throws Exception {
        Path file = directory.resolve(WorldFormatGuard.FILE_NAME);
        for (String contents : new String[]{"format=earthplatform:wide-section-y\nversion=999\n", "format=earthplatform:wide-", "format=" + "\\" + "uZZZZ"}) {
            Files.writeString(file, contents);
            assertThrows(SectionYIntegrityError.class, () -> WorldFormatGuard.preflight(directory, true));
            assertEquals(contents, Files.readString(file));
            assertFalse(Files.exists(directory.resolve(WorldFormatGuard.WARNING_NAME)));
        }
    }
}
