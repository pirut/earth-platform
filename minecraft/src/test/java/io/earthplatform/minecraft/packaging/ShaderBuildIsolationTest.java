package io.earthplatform.minecraft.packaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Source/launcher contracts only. Shell fixtures replace Gradle and never launch a game or copy upstream bytes. */
class ShaderBuildIsolationTest {
    @TempDir Path temporary;
    private int fixtureNumber;
    private record Input(String directory, String filename, String property, String label) {}
    private static final List<Input> INPUTS = List.of(
            new Input("mods", "DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar", "earthplatform.installedDhTestJar", "DH 3.3.3"),
            new Input("mods", "iris-neoforge-1.8.14-beta.1+mc1.21.1.jar", "earthplatform.installedIrisTestJar", "Iris 1.8.14-beta.1"),
            new Input("mods", "sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar", "earthplatform.installedSodiumTestJar", "Sodium 0.8.12-beta.1"),
            new Input("shaderpacks", "photon_v1.3b.zip", "earthplatform.installedShaderTestPack", "Photon v1.3b"));

    @Test void isolatedPartitionCannotSeeAnyInstalledOptionalModOrShaderPack() throws Exception {
        assertEquals("legacy-isolated", System.getProperty("earthplatform.test.partition"));
        ClassLoader loader = getClass().getClassLoader();
        for (String name : List.of("com/seibel/distanthorizons/api/DhApi.class", "net/irisshaders/iris/api/v0/IrisApi.class",
                "net/caffeinemc/mods/sodium/service/SodiumWorkarounds.class", "shaders/world1/dh_terrain.fsh"))
            assertNull(loader.getResource(name), name);
    }

    @Test void shaderOptInMustBeAnExplicitExactTrue() throws Exception {
        for (String value : new String[]{"", "false", "1", "TRUE"}) {
            Result result = launch(fixture(), Map.of("EARTH_ENABLE_INSTALLED_SHADERS", value));
            assertRefused(result, "Explicit shader opt-in required");
        }
    }

    @Test void everySeparatelyInstalledArtifactIsRequiredBeforeGradle() throws Exception {
        for (Input missing : INPUTS) {
            Path candidate = fixture();
            linkInputs(candidate, missing);
            assertRefused(launch(candidate, Map.of("EARTH_ENABLE_INSTALLED_SHADERS", "true")), "Install official " + missing.label());
            assertFalse(Files.exists(installed(candidate, missing)), "Preflight must not manufacture the missing installation");
        }
    }

    @Test void everyChangedArtifactFailsHashValidationWithoutGradle() throws Exception {
        for (Input changed : INPUTS) {
            Path candidate = fixture();
            linkInputs(candidate, changed);
            Path invalid = installed(candidate, changed);
            Files.writeString(invalid, "deliberately unsupported input");
            assertRefused(launch(candidate, Map.of("EARTH_ENABLE_INSTALLED_SHADERS", "true")),
                    "Refusing unsupported or changed installed " + changed.label() + " artifact");
            assertEquals("deliberately unsupported input", Files.readString(invalid));
        }
    }

    @Test void explicitlySelectedExactInputsPreserveAllCoreFlagsAndDisableDiagnosticProbes() throws Exception {
        Path candidate = fixture();
        linkInputs(candidate, null);
        Result result = launch(candidate, Map.of("EARTH_ENABLE_INSTALLED_SHADERS", "true"));
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("--offline runClient -x downloadAssets -x createMinecraftArtifacts"));
        for (String enabled : List.of("authoritativeCoreGate", "coreCollisionGate", "fullHeightPlayerGate", "rollingCubeGate",
                "authoritativeReplacement", "originalCubeBoundaryEdits", "twoWorkspaceGate", "adjacentWorkspaceGate",
                "indexedWorkspaceGate", "indexedScenario", "earthPlayerGate", "installedDhBackend", "installedShaderBackend"))
            assertTrue(result.output().contains("-Dearthplatform.experimental." + enabled + "=true"), enabled);
        for (String disabled : List.of("renderSubmissionProbe", "nativeSubmissionPhases", "sparseTerrainView", "longDistanceDepthProbe"))
            assertTrue(result.output().contains("-Dearthplatform.experimental." + disabled + "=false"), disabled);
        for (int i = 0; i < INPUTS.size(); i++)
            assertTrue(result.output().contains(List.of("EARTH_INSTALLED_DH_JAR", "EARTH_INSTALLED_IRIS_JAR",
                    "EARTH_INSTALLED_SODIUM_JAR", "EARTH_INSTALLED_SHADER_PACK").get(i) + "=" + installed(candidate, INPUTS.get(i))));
        assertFalse(result.output().contains("-Dearthplatform.test.isolatedDhRuntime=true"));
    }

    @Test void inheritedJvmOptionsCannotReenableFallbackOrOverrideTheSelectedBackend() throws Exception {
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "GRADLE_OPTS")) {
            for (String flag : List.of("earthplatform.test.isolatedDhRuntime=true", "earthplatform.experimental.installedShaderBackend=false",
                    "earthplatform.experimental.installedDhBackend=false", "earthplatform.experimental.longDistanceDepthProbe=true")) {
                Result result = launch(fixture(), Map.of("EARTH_ENABLE_INSTALLED_SHADERS", "true", variable, "-D" + flag));
                assertRefused(result, "Refusing conflicting or diagnostic JVM option: " + flag);
            }
        }
    }

    @Test void upstreamInputsHaveNoDownloaderInstallerOrBundlingRecipe() throws Exception {
        String launcher = Files.readString(project().getParent().resolve("launch-client.sh"));
        String build = Files.readString(project().resolve("build.gradle"));
        for (String input : List.of("officialDhFiles", "officialIrisFiles", "officialSodiumFiles")) {
            assertTrue(build.contains("compileOnly " + input));
            assertTrue(build.contains("testCompileOnly " + input));
            for (String global : List.of("implementation ", "runtimeOnly ", "localRuntime ", "testRuntimeOnly "))
                assertFalse(build.contains(global + input), global + input);
        }
        for (String forbidden : List.of("curl ", "wget ", "unzip ", " cp ", " ln ", "install -", "URLClassLoader", "DhOfficialRuntime"))
            assertFalse(launcher.contains(forbidden), forbidden);
        for (String forbidden : List.of("zipTree(", "zipTree ", "new URL(", "download.run", "artifact.downloader"))
            assertFalse(build.contains(forbidden), forbidden);
        assertTrue(build.contains("fingerprintNestedArchive(bytes, origin)"), "Nested upstream resources must be fingerprinted without extraction");
        assertTrue(build.contains("upstreamPayloads.containsKey(digest)"), "Renaming copied entries cannot evade the packaging guard");
        assertTrue(build.contains("requiredTestPartitions:['test', 'installedDhTest', 'shaderCompatibilityTest']"));
        assertTrue(build.contains("xml.@skipped.toInteger() != 0"), "Zero-skip aggregate required");
        assertTrue(build.contains("partition.filter.commandLineIncludePatterns.isEmpty()"), "A focused filter cannot issue a full receipt");
    }

    @Test void installedCompatibilitySourcesCannotCallDiagnosticLoaderOrFetchUpstreamCode() throws Exception {
        try (var sources = Files.walk(project().resolve("src/main/java/io/earthplatform/compat/dh"))) {
            for (Path file : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String forbidden : List.of("URLClassLoader", "DhOfficialRuntime", "java.net.http", ".openConnection(", "Files.copy("))
                    assertFalse(source.contains(forbidden), file + ": " + forbidden);
            }
        }
    }

    private Path fixture() throws Exception {
        Path candidate = temporary.resolve("fixture" + fixtureNumber++ + "/earth-platform/minecraft/candidate");
        Files.createDirectories(candidate.resolve("minecraft/run/mods"));
        Files.createDirectories(candidate.resolve("minecraft/run/shaderpacks"));
        Files.createDirectories(candidate.resolve("minecraft/scripts"));
        Files.copy(project().getParent().resolve("launch-client.sh"), candidate.resolve("launch-client.sh"));
        Path mock = candidate.resolve("minecraft/scripts/gradle.sh");
        Files.writeString(mock, "#!/usr/bin/env bash\nprintf '%s\\n' MOCK_GRADLE \"$*\" \"EARTH_INSTALLED_DH_JAR=$EARTH_INSTALLED_DH_JAR\" \"EARTH_INSTALLED_IRIS_JAR=$EARTH_INSTALLED_IRIS_JAR\" \"EARTH_INSTALLED_SODIUM_JAR=$EARTH_INSTALLED_SODIUM_JAR\" \"EARTH_INSTALLED_SHADER_PACK=$EARTH_INSTALLED_SHADER_PACK\" \"$JAVA_TOOL_OPTIONS\"\n");
        assertTrue(mock.toFile().setExecutable(true));
        // Deterministic preflight fixture; do not depend on concurrent test JVM memory.
        Path bin=Files.createDirectories(candidate.resolve("mock-bin"));
        Path awk=bin.resolve("awk");
        Files.writeString(awk,"#!/usr/bin/env bash\nprintf '%s\\n' \"${FIXTURE_AVAILABLE_KIB:-8388608}\"\n");
        assertTrue(awk.toFile().setExecutable(true));
        return candidate;
    }
    @Test void insufficientOrUnknownAvailableMemoryRefusesBeforeGradle() throws Exception {
        for (String available : List.of("6291455", "unknown")) {
            Path candidate=fixture();linkInputs(candidate,null);
            assertRefused(launch(candidate,Map.of("EARTH_ENABLE_INSTALLED_SHADERS","true","FIXTURE_AVAILABLE_KIB",available)),
                    "Shader launch requires at least 6 GiB currently available RAM");
        }
    }
    @Test void authorizedClientHeapIsExplicitAndIndependentOfEngineBudgets() throws Exception {
        assertTrue(Files.readString(project().resolve("build.gradle")).contains("jvmArgument '-Xmx4G'"));
        Path candidate=fixture();linkInputs(candidate,null);
        Result result=launch(candidate,Map.of("EARTH_ENABLE_INSTALLED_SHADERS","true","FIXTURE_AVAILABLE_KIB","6291456"));
        assertEquals(0,result.exit(),result.output());
        assertTrue(result.output().contains("Shader client maximum heap 4 GiB"));
    }
    private void linkInputs(Path candidate, Input omitted) throws Exception {
        for (Input input : INPUTS) if (!input.equals(omitted)) {
            String source = System.getProperty(input.property());
            assertNotNull(source, input.property());
            Files.createSymbolicLink(installed(candidate, input), Path.of(source).toAbsolutePath());
        }
    }
    private static Path installed(Path candidate, Input input) {
        return candidate.resolve("minecraft/run/" + input.directory() + "/" + input.filename());
    }
    private static Path project() {
        String property = System.getProperty("earthplatform.test.projectDirectory");
        assertNotNull(property); Path path = Path.of(property); assertTrue(path.isAbsolute()); return path;
    }
    private static void assertRefused(Result result, String explanation) {
        assertEquals(2, result.exit(), result.output());
        assertTrue(result.output().contains(explanation), result.output());
        assertFalse(result.output().contains("MOCK_GRADLE"), result.output());
    }
    private static Result launch(Path candidate, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", candidate.resolve("launch-client.sh").toString()).redirectErrorStream(true);
        for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "GRADLE_OPTS", "EARTH_ENABLE_INSTALLED_SHADERS"))
            builder.environment().remove(name);
        builder.environment().remove("FIXTURE_AVAILABLE_KIB");
        builder.environment().put("PATH",candidate.resolve("mock-bin")+java.io.File.pathSeparator+builder.environment().get("PATH"));
        builder.environment().putAll(environment);
        Process process = builder.start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Only the shell preflight/mock may run");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
    private record Result(int exit, String output) {}
}
