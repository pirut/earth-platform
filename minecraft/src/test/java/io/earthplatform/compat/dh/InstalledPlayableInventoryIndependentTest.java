package io.earthplatform.compat.dh;

import com.google.gson.JsonParser;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.niofs.union.UnionFileSystem;
import cpw.mods.niofs.union.UnionFileSystemProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent released-byte and real FML metadata-parser proof. This does not launch a
 * client or claim that a synthetic fixture is the live NeoForge ModList singleton.
 * All filesystem mounting/closing belongs to these test fixtures, never admission.
 */
class InstalledPlayableInventoryIndependentTest {
    @TempDir Path temp;
    private record Pin(String id, String version, String entry, long bytes, String sha256) {}
    private static final List<Pin> RELEASE = List.of(
        new Pin("sodium", "0.8.12-beta.1+mc1.21.1", "META-INF/jarjar/net.caffeinemc.sodium-neoforge-0.8.12-beta.1+mc1.21.1-mod.jar", 1225252L, "912a12199fe120a99b94e225dc08886671d2052386c6e92beb0146c587eab1e4"),
        new Pin("fabric_api_base", "0.4.42+d1308ded19", "META-INF/jarjar/fabric-api-base-0.4.42+d1308ded19.jar", 34805L, "bdf464c6df587b4aa87df2cbe326acbbb307727d3e27dbaf58d592c429e6eac3"),
        new Pin("fabric_block_view_api_v2", "1.0.10+9afaaf8c19", "META-INF/jarjar/fabric-block-view-api-v2-1.0.10+9afaaf8c19.jar", 25631L, "87da93c4ef283e0ce66f8da67d6cba93d9884c55152014a49c2b6f4f861bec48"),
        new Pin("fabric_renderer_api_v1", "3.4.1+9125b6dc19", "META-INF/jarjar/fabric-renderer-api-v1-3.4.1+9125b6dc19.jar", 58938L, "cfa513ab50123b564421e093a657e15bd28b82b0219f196e518de679187e4845"),
        new Pin("fabric_rendering_data_attachment_v1", "0.3.48+73761d2e19", "META-INF/jarjar/fabric-rendering-data-attachment-v1-0.3.48+73761d2e19.jar", 20497L, "a720c7a1e73583e0fed5110556290df023d158750740bc00e4040d6cd2cb9af1")
    );
    private static final Map<String,String> TEN = Map.of(
        "minecraft", "1.21.1", "neoforge", "21.1.256", "earthplatform", "0.1.0-engine-prototype",
        "distanthorizons", "3.3.3", "iris", "1.8.14-beta.1+mc1.21.1", "sodium", "0.8.12-beta.1+mc1.21.1",
        "fabric_api_base", "0.4.42+d1308ded19", "fabric_block_view_api_v2", "1.0.10+9afaaf8c19",
        "fabric_renderer_api_v1", "3.4.1+9125b6dc19", "fabric_rendering_data_attachment_v1", "0.3.48+73761d2e19");

    private static Path input(String name) {
        String value = System.getProperty("earthplatform.installed" + name + "TestJar");
        assertNotNull(value, "Exact external " + name + " input is mandatory; this proof must not silently skip");
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), path.toString());
        return path;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static InstalledIrisRuntime.InstalledMod mod(String id, String version, Path path) {
        return new InstalledIrisRuntime.InstalledMod(id, version, path,
            InstalledPlayableInventoryIndependentTest.class.getClassLoader(), id, path.toUri());
    }
    private static InstalledIrisRuntime.InstalledMod at(InstalledIrisRuntime.InstalledMod m, Path path) {
        return mod(m.id(), m.version(), path);
    }
    private static FileSystem mount(Path entry) throws Exception {
        return FileSystems.newFileSystem(URI.create("jij:" + entry.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize(),
            Map.of("packagePath", entry));
    }
    private static Pin pin(String id) { return RELEASE.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(); }

    /** The actual FML parser resolves both literal versions and ${file.jarVersion}. */
    private static final class Released implements AutoCloseable {
        final UnionFileSystem outer;
        final List<FileSystem> nested = new ArrayList<>();
        final List<SecureJar> secure = new ArrayList<>();
        final Map<String,InstalledIrisRuntime.InstalledMod> mods = new LinkedHashMap<>();
        Released(Path physical) throws Exception {
            outer = new UnionFileSystemProvider().newFileSystem(null, physical);
            try {
                var metadata = JsonParser.parseString(Files.readString(outer.getPath("/META-INF/jarjar/metadata.json"))).getAsJsonObject().getAsJsonArray("jars");
                Set<String> fromDescriptor = new java.util.HashSet<>();
                for (var item : metadata) {
                    var declared = item.getAsJsonObject();
                    String entry = declared.get("path").getAsString();
                    assertTrue(fromDescriptor.add(entry), "Duplicate released jar descriptor");
                    FileSystem fs = mount(outer.getPath("/" + entry)); nested.add(fs);
                    SecureJar jar = SecureJar.from(fs.getPath("/")); secure.add(jar);
                    var resolved = new ModFile(jar, ModFileParser::modsTomlParser, ModFileDiscoveryAttributes.DEFAULT);
                    assertEquals(1, resolved.getModInfos().size());
                    var info = resolved.getModInfos().getFirst();
                    String id = info.getModId(), version = info.getVersion().toString();
                    assertEquals(declared.getAsJsonObject("version").get("artifactVersion").getAsString(), version,
                        "Production FML substitution must agree with released jarjar metadata");
                    assertNull(mods.put(id, mod(id, version, resolved.getFilePath())), "Duplicate FML-resolved id");
                    assertEquals(entry, pin(id).entry());
                    assertEquals("net.neoforged.jarjar.nio.pathfs.PathFileSystem", resolved.getFilePath().getFileSystem().getClass().getName());
                }
                try (var paths = Files.list(outer.getPath("/META-INF/jarjar"))) {
                    assertEquals(fromDescriptor, paths.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .map(p -> p.toAbsolutePath().normalize().toString().substring(1)).collect(java.util.stream.Collectors.toSet()));
                }
                assertEquals(5, mods.size());
            } catch (Exception | Error failure) { close(); throw failure; }
        }
        List<InstalledIrisRuntime.InstalledMod> complete() {
            var result = new ArrayList<InstalledIrisRuntime.InstalledMod>();
            TEN.forEach((id, version) -> result.add(mods.containsKey(id) ? mods.get(id) : mod(id, version,
                switch (id) {case "distanthorizons" -> input("Dh"); case "iris" -> input("Iris"); default -> input("Sodium");})));
            return result;
        }
        void assertOpenAndReadable() throws Exception {
            assertTrue(outer.isOpen());
            for (var fs : nested) { assertTrue(fs.isOpen()); assertTrue(Files.isReadable(fs.getPath("/META-INF/neoforge.mods.toml"))); }
        }
        @Override public void close() throws Exception {
            for (int i = secure.size() - 1; i >= 0; --i) secure.get(i).close();
            for (int i = nested.size() - 1; i >= 0; --i) nested.get(i).close();
            outer.close();
        }
    }

    @Test void actualReleaseDescriptorsAndProductionParserDeriveAllFiveNestedModules() throws Exception {
        byte[] outerBytes = Files.readAllBytes(input("Sodium"));
        assertEquals(1291422, outerBytes.length);
        assertEquals("dc2838d5538db39414171fe86586ab89d1a30d2fd97f59229cc44cf3777f569d", sha(outerBytes));
        try (var release = new Released(input("Sodium"))) {
            assertEquals("net.caffeinemc.mods.sodium.service.SodiumServiceModLocator",
                Files.readString(release.outer.getPath("/META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator")).trim());
            assertEquals("net.caffeinemc.mods.sodium.service.SodiumWorkarounds",
                Files.readString(release.outer.getPath("/META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper")).trim());
            java.util.jar.Manifest manifest;
            try (var input = Files.newInputStream(release.outer.getPath("/META-INF/MANIFEST.MF"))) { manifest = new java.util.jar.Manifest(input); }
            assertEquals("LIBRARY", manifest.getMainAttributes().getValue("FMLModType"));
            assertEquals("sodium_service", manifest.getMainAttributes().getValue("Automatic-Module-Name"));
            var locator = new ClassNode();
            new ClassReader(Files.readAllBytes(release.outer.getPath("/net/caffeinemc/mods/sodium/service/SodiumServiceModLocator.class"))).accept(locator, 0);
            assertTrue(locator.interfaces.contains("net/neoforged/neoforgespi/locating/IModFileCandidateLocator"));
            assertTrue(calls(locator).contains("java/nio/file/Files.list"));
            assertTrue(calls(locator).contains("java/nio/file/FileSystems.newFileSystem"));
            assertTrue(calls(locator).contains("cpw/mods/jarhandling/JarContents.of"));
            assertTrue(calls(locator).contains("net/neoforged/neoforgespi/locating/IDiscoveryPipeline.addJarContent"));
            assertTrue(strings(locator).containsAll(List.of("META-INF/jarjar", ".jar", "packagePath")));
            for (var p : RELEASE) {
                var resolved = release.mods.get(p.id()); assertNotNull(resolved); assertEquals(p.version(), resolved.version());
                byte[] bytes = Files.readAllBytes(release.outer.getPath("/" + p.entry()));
                assertEquals(p.bytes(), bytes.length, p.id()); assertEquals(p.sha256(), sha(bytes), p.id());
                var actual = InstalledSodiumOrigin.MEMBERS.get(p.id());
                assertEquals(p.entry(), actual.entry()); assertEquals(p.bytes(), actual.bytes()); assertEquals(p.sha256(), actual.sha256());
                assertEquals(p.version(), actual.version());
            }
            assertEquals(TEN, InstalledPlayableInventory.VERSIONS);
            assertEquals(RELEASE.size(), InstalledSodiumOrigin.MEMBERS.size());
        }
    }

    @Test void realFmlResolvedNestedMetadataPassesFullOriginGuardAndLeavesOwnersOpen() throws Exception {
        try (var release = new Released(input("Sodium"))) {
            var inventory = release.complete(); Collections.shuffle(inventory, new Random(28001));
            InstalledPlayableInventory.verify(inventory, InstalledPlayableInventory::verifyOrigins);
            release.assertOpenAndReadable();
            for (var entry : release.mods.entrySet()) {
                var member = InstalledSodiumOrigin.MEMBERS.get(entry.getKey());
                assertTrue(Files.isSameFile(input("Sodium"), InstalledSodiumOrigin.verifiedOuter(entry.getValue(), member)));
            }
        }
    }

    @Test void everyInventoryMutationRefusesBeforeAnyOriginCallback() throws Exception {
        try (var release = new Released(input("Sodium"))) {
            var baseline = release.complete(); var calls = new AtomicInteger();
            InstalledPlayableInventory.OriginCheck origins = ignored -> calls.incrementAndGet();
            for (int i = 0; i < baseline.size(); i++) {
                var missing = new ArrayList<>(baseline); missing.remove(i); refuses(missing, origins, calls);
                var duplicateExtra = new ArrayList<>(baseline); duplicateExtra.add(baseline.get(i)); refuses(duplicateExtra, origins, calls);
                var duplicateSameSize = new ArrayList<>(baseline); duplicateSameSize.set(i, baseline.get((i+1)%baseline.size())); refuses(duplicateSameSize, origins, calls);
                var unknownSameSize = new ArrayList<>(baseline); unknownSameSize.set(i, mod("unreviewed_extra_"+i,"1",input("Sodium"))); refuses(unknownSameSize, origins, calls);
                var unknownExtra = new ArrayList<>(baseline); unknownExtra.add(mod("unreviewed_extra_"+i,"1",input("Sodium"))); refuses(unknownExtra, origins, calls);
                var nullEntry = new ArrayList<>(baseline); nullEntry.set(i, null); refuses(nullEntry, origins, calls);
                for (String version : List.of("", baseline.get(i).version()+"-foreign", "0.0.0", baseline.get(i).version()+" ")) {
                    var wrongVersion = new ArrayList<>(baseline); var old = baseline.get(i);
                    wrongVersion.set(i, mod(old.id(),version,old.path())); refuses(wrongVersion, origins, calls);
                }
            }
            release.assertOpenAndReadable(); assertEquals(0,calls.get());
        }
    }
    private static void refuses(List<InstalledIrisRuntime.InstalledMod> entries, InstalledPlayableInventory.OriginCheck origins, AtomicInteger calls) {
        assertThrows(IOException.class, () -> InstalledPlayableInventory.verify(entries,origins)); assertEquals(0,calls.get());
    }

    @Test void exactBytesExtractedElsewhereWrongMemberAndNonRootPathsAreNotAuthoritativeOrigins() throws Exception {
        try (var release = new Released(input("Sodium"))) {
            for (int i=0;i<RELEASE.size();i++) {
                var pin = RELEASE.get(i); var genuine = release.mods.get(pin.id()); var member = InstalledSodiumOrigin.MEMBERS.get(pin.id());
                for (Path bad : List.of(input("Sodium"),temp,genuine.path().resolve("META-INF"),release.mods.get(RELEASE.get((i+1)%RELEASE.size()).id()).path()))
                    assertThrows(IOException.class, () -> InstalledSodiumOrigin.verifiedOuter(at(genuine,bad),member), pin.id()+" "+bad);
                Path extracted = temp.resolve(Path.of(pin.entry()).getFileName());
                Files.write(extracted,Files.readAllBytes(release.outer.getPath("/"+pin.entry())));
                assertEquals(pin.sha256(),sha(Files.readAllBytes(extracted)));
                assertThrows(IOException.class, () -> InstalledSodiumOrigin.verifiedOuter(at(genuine,extracted),member));
                try (var detached = mount(extracted)) {
                    assertThrows(IOException.class, () -> InstalledSodiumOrigin.verifiedOuter(at(genuine,detached.getPath("/")),member));
                    assertTrue(detached.isOpen());
                }
            }
            release.assertOpenAndReadable();
        }
    }

    @Test void byteIdenticalSameFilenameFromDifferentPhysicalOuterIsRejectedForEveryDependency() throws Exception {
        Path foreignDirectory=Files.createDirectory(temp.resolve("foreign-owner"));
        Path copied=foreignDirectory.resolve(input("Sodium").getFileName()); Files.copy(input("Sodium"),copied);
        assertEquals(sha(Files.readAllBytes(input("Sodium"))),sha(Files.readAllBytes(copied)));
        assertFalse(Files.isSameFile(input("Sodium"),copied));
        try (var own = new Released(input("Sodium")); var foreign = new Released(copied)) {
            Path owner=InstalledSodiumOrigin.verifiedOuter(own.mods.get("sodium"),InstalledSodiumOrigin.SODIUM);
            for (var pin:RELEASE) if(!pin.id().equals("sodium")) {
                assertThrows(IOException.class,()->InstalledSodiumOrigin.verifySameOuter(foreign.mods.get(pin.id()),owner));
                var mixed=own.complete(); for(int i=0;i<mixed.size();i++) if(mixed.get(i).id().equals(pin.id())) mixed.set(i,foreign.mods.get(pin.id()));
                assertThrows(IOException.class,()->InstalledPlayableInventory.verify(mixed,InstalledPlayableInventory::verifyOrigins));
            }
            own.assertOpenAndReadable(); foreign.assertOpenAndReadable();
        }
    }

    @Test void repackingOuterDoesNotBecomeOfficialByPreservingEveryNestedByteAndDescriptor() throws Exception {
        Path repacked=temp.resolve(input("Sodium").getFileName());
        try (var source=new ZipFile(input("Sodium").toFile()); var output=new ZipOutputStream(Files.newOutputStream(repacked))) {
            for(var entries=source.entries();entries.hasMoreElements();) {
                var entry=entries.nextElement(); output.putNextEntry(new ZipEntry(entry.getName()));
                try(var in=source.getInputStream(entry)){in.transferTo(output);} output.closeEntry();
            }
            output.setComment("Independent adversarial repack; original nested bytes retained");
        }
        assertNotEquals(sha(Files.readAllBytes(input("Sodium"))),sha(Files.readAllBytes(repacked)));
        try(var release=new Released(repacked)) {
            for(var pin:RELEASE) {
                assertEquals(pin.sha256(),sha(Files.readAllBytes(release.outer.getPath("/"+pin.entry()))));
                assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(release.mods.get(pin.id()),InstalledSodiumOrigin.MEMBERS.get(pin.id())));
            }
            assertThrows(IOException.class,()->InstalledPlayableInventory.verify(release.complete(),InstalledPlayableInventory::verifyOrigins));
            release.assertOpenAndReadable();
        }
    }

    @Test void unionOverlayCannotHideForeignResourcesBehindAnOfficialPrimaryRoot() throws Exception {
        Path foreign=Files.createDirectory(temp.resolve("foreign-overlay"));
        Files.writeString(foreign.resolve("unapproved-resource.txt"),"foreign overlay bytes");
        try(var release=new Released(input("Sodium"))) {
            for(var pin:RELEASE) {
                var official=release.mods.get(pin.id());
                try(var wrapper=new UnionFileSystemProvider().newFileSystem(null,official.path(),foreign)) {
                    assertEquals(official.path().toAbsolutePath(),wrapper.getPrimaryPath());
                    assertTrue(Files.isReadable(wrapper.getPath("/unapproved-resource.txt")));
                    var wrapped=at(official,wrapper.getPath("/"));
                    assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(wrapped,InstalledSodiumOrigin.MEMBERS.get(pin.id())));
                    var inventory=release.complete();
                    for(int i=0;i<inventory.size();i++) if(inventory.get(i).id().equals(pin.id())) inventory.set(i,wrapped);
                    assertThrows(IOException.class,()->InstalledPlayableInventory.verify(inventory,InstalledPlayableInventory::verifyOrigins));
                    assertTrue(wrapper.isOpen());
                }
            }
            release.assertOpenAndReadable();
        }
    }

    @Test void independentPinObjectCannotAuthorizeInventedOriginOrVersion() throws Exception {
        try(var release=new Released(input("Sodium"))) {
            for(var pin:RELEASE) {
                var clone=new InstalledSodiumOrigin.Member(pin.id(),pin.version(),pin.entry(),pin.bytes(),pin.sha256());
                assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(release.mods.get(pin.id()),clone));
                assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(mod(pin.id(),pin.version()+"-foreign",release.mods.get(pin.id()).path()),InstalledSodiumOrigin.MEMBERS.get(pin.id())));
            }
        }
    }

    @Test void admissionBytecodeHasNoMountCloseDirectorySearchOrAlternateLoaderFallback() throws Exception {
        for(String owner:List.of("InstalledPlayableInventory","InstalledSodiumOrigin")) {
            var type=new ClassNode();
            try(var in=getClass().getResourceAsStream(owner+".class")){assertNotNull(in);new ClassReader(in).accept(type,0);}
            for(String call:calls(type)) {
                assertFalse(call.equals("java/nio/file/FileSystems.newFileSystem"),call);
                assertFalse(call.equals("java/nio/file/Files.list")||call.equals("java/nio/file/Files.walk")||call.equals("java/nio/file/Files.find"),call);
                assertFalse(call.startsWith("java/net/URLClassLoader.")||call.equals("java/lang/Class.forName")||call.endsWith(".loadClass"),call);
                assertFalse(call.endsWith(".close"),call);
            }
            if(owner.equals("InstalledPlayableInventory")) {
                assertTrue(calls(type).stream().anyMatch(s->s.endsWith(".putIfAbsent")));
                assertFalse(calls(type).contains("java/util/stream/Collectors.toSet"));
            }
        }
    }
    private static List<String> calls(ClassNode type) {
        var calls=new ArrayList<String>(); for(var method:type.methods) for(var insn:method.instructions) if(insn instanceof MethodInsnNode m) calls.add(m.owner+"."+m.name); return calls;
    }
    private static List<String> strings(ClassNode type) {
        var strings=new ArrayList<String>(); for(var method:type.methods) for(var insn:method.instructions) if(insn instanceof LdcInsnNode l && l.cst instanceof String s) strings.add(s); return strings;
    }
}
