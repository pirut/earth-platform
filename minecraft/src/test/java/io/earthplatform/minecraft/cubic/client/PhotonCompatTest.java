package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhotonCompatTest {
    @Test void aMissingAnchorLeavesThePackAlone() {
        assertThrows(IllegalStateException.class, () -> PhotonCompat.replace("#define OTHER\n", "(?m)^#define SHADOW_COLOR$", "", 1));
    }

    /** Runs the edits on a real Photon v1.3b when EARTH_PHOTON_ZIP names one. */
    @Test void adaptsPhoton(@TempDir Path out) throws Exception {
        String zip = System.getenv("EARTH_PHOTON_ZIP");
        assumeTrue(zip != null && Files.isRegularFile(Path.of(zip)));
        try (FileSystem pack = FileSystems.newFileSystem(URI.create("jar:" + Path.of(zip).toUri()), Map.of())) {
            // The sky light pass alone shares 256 x 9 three-component vectors: more than llvmpipe's 32 KB.
            assertTrue(PhotonCompat.sharedMemory(pack.getPath("shaders")) > 32768);
            Path copy = out.resolve("shaders");
            PhotonCompat.build(pack.getPath("shaders"), copy);
            String settings = Files.readString(copy.resolve("settings.glsl"));
            assertTrue(settings.contains("//#define SHADOW_COLOR") && settings.contains("//#define CLOUD_SHADOWS"));
            String properties = Files.readString(copy.resolve("shaders.properties"));
            assertTrue(properties.contains("program.world0/deferred4_a.enabled = false"));
            assertTrue(properties.contains("program.world0/deferred4.enabled = SH_SKYLIGHT"));
            assertFalse(properties.matches("(?s).*profile\\.ultra[^\\n]*\\s(SHADOW_COLOR|COLORED_LIGHTS)\\s.*"));
            assertEquals(Files.readString(pack.getPath("shaders/world0/deferred4.fsh")), Files.readString(copy.resolve("world0/deferred5.fsh")));
            assertTrue(Files.readString(copy.resolve("world0/deferred4.fsh")).contains("earth_sky_sh.fsh"));
            String sky = Files.readString(copy.resolve("program/earth_sky_sh.fsh"));
            assertTrue(sky.contains("uniform sampler2D colortex4;") && sky.contains("uniform vec3 sun_dir;"));
            assertFalse(sky.contains("image2D") || sky.contains("shared ") || sky.contains("local_size"));
            if (System.getenv("EARTH_PHOTON_OUT") != null) {
                try (var walk = Files.walk(copy)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        Path target = Path.of(System.getenv("EARTH_PHOTON_OUT")).resolve(copy.relativize(file).toString());
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }
}
