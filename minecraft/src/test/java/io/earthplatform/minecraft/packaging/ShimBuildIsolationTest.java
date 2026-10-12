package io.earthplatform.minecraft.packaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Build/launch contracts run in the inherited isolated-DH partition. No GUI is launched. */
class ShimBuildIsolationTest {
    private static final String SHA256 = "864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab";
    @TempDir Path temporary;

    @Test void legacyPartitionCannotSeeColoadedOfficialDh() {
        assertEquals("legacy-isolated", System.getProperty("earthplatform.test.partition"));
        assertEquals("true", System.getProperty("earthplatform.test.isolatedDhRuntime"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.seibel.distanthorizons.api.DhApi", false, getClass().getClassLoader()));
    }

    @Test void externalProofInputIsTheUnchangedPinnedArtifact() throws Exception {
        String path = System.getProperty("earthplatform.installedDhTestJar");
        assertNotNull(path, "The full aggregate requires the explicit official artifact");
        assertEquals(28_289_212L, Files.size(Path.of(path)));
        assertEquals(SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)))));
        assertEquals(path, System.getProperty("earthplatform.lodProbeJar"));
    }

    @Test void metadataPinsAnOptionalClientOnlySeparatelyInstalledDependency() throws Exception {
        String metadata = Files.readString(projectDirectory().resolve("src/main/templates/META-INF/neoforge.mods.toml"));
        String dependency = java.util.Arrays.stream(metadata.split(java.util.regex.Pattern.quote("[[dependencies.${mod_id}]]")))
                .filter(block -> block.contains("modId=\"distanthorizons\""))
                .findFirst().orElseThrow();
        for (String field : new String[]{"modId=\"distanthorizons\"", "type=\"optional\"", "versionRange=\"[3.3.3]\"", "ordering=\"AFTER\"", "side=\"CLIENT\""})
            assertTrue(dependency.contains(field), field);
        assertFalse(dependency.contains("download" + "URL"));
    }

    @Test void launchRefusesMissingSeparatelyInstalledModBeforeGradle() throws Exception {
        Path candidate = launcherFixture();
        Result result = launch(candidate);
        assertEquals(2, result.exit());
        assertTrue(result.output().contains("Install official DH 3.3.3"));
        assertFalse(result.output().contains("MOCK_GRADLE"));
    }

    @Test void launchRefusesChangedInstalledArtifactBeforeGradle() throws Exception {
        Path candidate = launcherFixture();
        Files.writeString(installedPath(candidate), "not the pinned official artifact");
        Result result = launch(candidate);
        assertEquals(2, result.exit());
        assertTrue(result.output().contains("Refusing unsupported or changed installed DH 3.3.3 artifact"));
        assertFalse(result.output().contains("MOCK_GRADLE"));
    }

    @Test void launchEnablesInstalledIndexedBackendWithNoDiagnosticFallback() throws Exception {
        Path candidate = launcherFixture();
        // Reference the external input in place; never copy an upstream payload into test output.
        Files.createSymbolicLink(installedPath(candidate), Path.of(System.getProperty("earthplatform.installedDhTestJar")).toAbsolutePath());
        for (String[] shader : new String[][]{
                {"iris-neoforge-1.8.14-beta.1+mc1.21.1.jar", "earthplatform.installedIrisTestJar", "mods"},
                {"sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar", "earthplatform.installedSodiumTestJar", "mods"},
                {"photon_v1.3b.zip", "earthplatform.installedShaderTestPack", "shaderpacks"}}) {
            Path target = candidate.resolve("minecraft/run/" + shader[2] + "/" + shader[0]);
            Files.createDirectories(target.getParent());
            Files.createSymbolicLink(target, Path.of(System.getProperty(shader[1])).toAbsolutePath());
        }
        Result result = launch(candidate);
        assertEquals(0, result.exit(), result.output());
        for (String expected : new String[]{"MOCK_GRADLE", "--offline runClient -x downloadAssets -x createMinecraftArtifacts",
                "-Dearthplatform.experimental.installedDhBackend=true", "-Dearthplatform.experimental.installedShaderBackend=true",
                "-Dearthplatform.experimental.longDistanceDepthProbe=false",
                "-Dearthplatform.experimental.indexedWorkspaceGate=true", "-Dearthplatform.experimental.indexedScenario=true",
                "EARTH_INSTALLED_DH_JAR=" + installedPath(candidate)})
            assertTrue(result.output().contains(expected), expected);
        assertFalse(result.output().contains("-Dearthplatform.test.isolatedDhRuntime=true"));
    }

    private static Path projectDirectory() {
        String configured = System.getProperty("earthplatform.test.projectDirectory");
        assertNotNull(configured, "Gradle must provide the project directory independently of NeoForge's test working directory");
        Path directory = Path.of(configured);
        assertTrue(directory.isAbsolute(), "Test source inputs require an explicit absolute project directory");
        return directory;
    }
    private Path launcherFixture() throws Exception {
        Path candidate = temporary.resolve("earth-platform/minecraft/candidate");
        Files.createDirectories(candidate.resolve("minecraft/run/mods"));
        Files.createDirectories(candidate.resolve("minecraft/scripts"));
        Files.copy(projectDirectory().getParent().resolve("launch-client.sh"), candidate.resolve("launch-client.sh"));
        Path mock = candidate.resolve("minecraft/scripts/gradle.sh");
        Files.writeString(mock, "#!/usr/bin/env bash\nprintf '%s\\n' MOCK_GRADLE \"$*\" \"EARTH_INSTALLED_DH_JAR=$EARTH_INSTALLED_DH_JAR\" \"$JAVA_TOOL_OPTIONS\"\n");
        assertTrue(mock.toFile().setExecutable(true));
        // The launch preflight is deterministic here; actual memory admission is tested separately.
        Path bin=Files.createDirectories(candidate.resolve("mock-bin"));
        Path awk=bin.resolve("awk");Files.writeString(awk,"#!/usr/bin/env bash\nprintf '%s\\n' 8388608\n");
        assertTrue(awk.toFile().setExecutable(true));
        return candidate;
    }
    private Path installedPath(Path candidate) {
        return candidate.resolve("minecraft/run/mods/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar");
    }
    private Result launch(Path candidate) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", candidate.resolve("launch-client.sh").toString()).redirectErrorStream(true);
        builder.environment().put("EARTH_ENABLE_INSTALLED_SHADERS", "true");
        builder.environment().put("PATH",candidate.resolve("mock-bin")+java.io.File.pathSeparator+builder.environment().get("PATH"));
        for (String name : new String[]{"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "GRADLE_OPTS"})
            builder.environment().remove(name);
        Process process = builder.start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Only the shell preflight/mock may run");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
    private record Result(int exit, String output) {}
}
