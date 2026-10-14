package io.earthplatform.minecraft.scene;

import io.earthplatform.minecraft.startupfixture.Hooks;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual pinned Mixin application under the normal FML test bootstrap, using
 * original no-game fixtures. Upstream bytes are checked read-only for structural
 * correspondence. This is not a transformed real LevelRenderer or a GL proof.
 */
class TerrainMixinTransformationTest {
    private static final String ROOT = "io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority";
    private static final String FIXTURES = "io/earthplatform/minecraft/startupfixture/mixin/TerrainPriorityMixins$";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String SODIUM = "net/caffeinemc/mods/sodium/mixin/core/render/world/LevelRendererMixin.class";
    private static final String ANCHOR_OWNER = "net/caffeinemc/mods/sodium/client/services/PlatformLevelRenderHooks";
    private static final String LAYER_DESCRIPTOR = "(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V";

    @Test void actualPinnedTransformerAdmits1001AndRejects900AndEqual1000AfterOverwrite() throws Exception {
        assertEquals("legacy-isolated", System.getProperty("earthplatform.test.partition"));
        var environment = MixinEnvironment.getCurrentEnvironment();
        assertEquals("0.8.7", environment.getVersion());
        IMixinTransformer transformer = assertInstanceOf(IMixinTransformer.class, environment.getActiveTransformer(),
                "The real FML bootstrap must supply the active pinned transformer");
        byte[] original = resource(ROOT.replace('.', '/') + "1001.class");
        assertEquals(List.of("vanilla"), calls(method(node(original), "renderSectionLayer")).stream().map(c -> c.name).toList());
        byte[] transformed = transformer.transformClass(environment, ROOT + "1001", original);
        assertFalse(Arrays.equals(original, transformed), "Unregistered fixtures must not pass as transformation proof");
        ClassNode result = node(transformed);
        MethodNode render = method(result, "renderSectionLayer");
        AnnotationNode merged = annotation(render.visibleAnnotations, "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        assertEquals(1000, value(merged, "priority", -1));
        assertTrue(value(merged, "mixin", "").toString().endsWith("TerrainPriorityMixins$SodiumOverwrite1001"));
        List<MethodInsnNode> calls = calls(render);
        assertFalse(calls.stream().anyMatch(c -> c.name.equals("vanilla")), "The original method must actually be overwritten");
        int enter = index(calls, "enterManagedCode"), draw = index(calls, "drawChunkLayer");
        int exit = lastIndex(calls, "exitManagedCode"), anchor = index(calls, "getInstance");
        assertEquals(2, calls.stream().filter(c -> c.name.equals("exitManagedCode")).count(), "Normal and exceptional finally exits");
        assertFalse(render.tryCatchBlocks.isEmpty());
        int nativeHook = containingIndex(calls, "fixture$nativeTerrain"), tail = containingIndex(calls, "fixture$irisTail");
        int head = containingIndex(calls, "fixture$irisHead");
        assertTrue(head < enter && enter < draw && draw < exit && exit < nativeHook && nativeHook < anchor && anchor < tail,
                "Actual transformed instructions must retain HEAD -> managed draw/exit -> native -> platform -> Iris RETURN");
        String nativeName = calls.get(nativeHook).name;
        assertEquals(1, calls(method(result, nativeName)).stream().filter(c -> c.name.equals("dispatch")).count());
        Hooks.EVENTS.clear();
        Class<?> executable = new FixtureLoader(getClass().getClassLoader()).define(transformed);
        executable.getMethod("renderSectionLayer").invoke(executable.getConstructor().newInstance());
        assertEquals(List.of("iris-head", "managed-enter", "sodium-draw", "managed-exit", "native-dispatch", "platform-hook", "iris-tail"),
                List.copyOf(Hooks.EVENTS), "Execute the actual Mixin-generated method and injected callbacks");
        Hooks.EVENTS.clear();
        Hooks.FAIL_DRAW = true;
        try {
            var failedDraw = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> executable.getMethod("renderSectionLayer").invoke(executable.getConstructor().newInstance()));
            assertInstanceOf(IllegalStateException.class, failedDraw.getCause());
            assertEquals("fixture draw failure", failedDraw.getCause().getMessage());
            assertEquals(List.of("iris-head", "managed-enter", "sodium-draw", "managed-exit"), List.copyOf(Hooks.EVENTS),
                    "A failed managed draw must exit managed code and rethrow without native/platform/RETURN callbacks");
        } finally { Hooks.FAIL_DRAW = false; Hooks.EVENTS.clear(); }
        for (int priority : new int[]{900, 1000}) {
            String target = ROOT + priority;
            byte[] input = resource(target.replace('.', '/') + ".class");
            Throwable failure = assertThrows(Throwable.class, () -> transformer.transformClass(environment, target, input));
            InvalidInjectionException rejected = cause(failure, InvalidInjectionException.class);
            assertNotNull(rejected, "Negative control must fail for the real priority guard, not an unrelated bootstrap error: " + failure);
            assertTrue(rejected.getMessage().contains("with priority " + priority + " cannot inject into"), rejected.getMessage());
            assertTrue(rejected.getMessage().contains("merged by " + FIXTURES.replace('/', '.') + "SodiumOverwrite" + priority + " with priority 1000"), rejected.getMessage());
            assertTrue(rejected.getMessage().contains("INJECT_PREPARE"), rejected.getMessage());
        }
        // A handled negative fixture must not leave Mixin's processor in error mode.
        byte[] restored = transformer.transformClass(environment, ROOT + "1001", original);
        assertEquals(calls.stream().map(c -> c.name).toList(), calls(method(node(restored), "renderSectionLayer")).stream().map(c -> c.name).toList());
    }


    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader(ClassLoader parent) { super(parent); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = TerrainMixinTransformationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name); return in.readAllBytes();
        }
    }
    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(owner.name + "::" + name));
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }
    private static int index(List<MethodInsnNode> calls, String name) {
        var matches = calls.stream().filter(c -> c.name.equals(name)).toList();
        assertEquals(1, matches.size(), "Expected one " + name); return calls.indexOf(matches.getFirst());
    }
    private static int lastIndex(List<MethodInsnNode> calls, String name) {
        for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).name.equals(name)) return i;
        throw new AssertionError("Missing " + name);
    }
    private static int containingIndex(List<MethodInsnNode> calls, String name) {
        var matches = calls.stream().filter(c -> c.name.contains(name)).toList();
        assertEquals(1, matches.size(), "Expected one merged " + name); return calls.indexOf(matches.getFirst());
    }
    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        assertNotNull(annotations, descriptor);
        return annotations.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElseThrow(() -> new AssertionError("Missing " + descriptor));
    }
    private static Object value(AnnotationNode annotation, String name, Object fallback) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        return fallback;
    }
    private static int priority(ClassNode type) { return (Integer) value(annotation(type.invisibleAnnotations, MIXIN), "priority", 1000); }
    private static AnnotationNode at(AnnotationNode injection) {
        return (AnnotationNode) ((List<?>) value(injection, "at", null)).getFirst();
    }
    private static <T extends Throwable> T cause(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (type.isInstance(current)) return type.cast(current);
        return null;
    }
    private static byte[] upstream(String property, String classFile) throws Exception {
        String filename = System.getProperty(property); assertNotNull(filename, property);
        Path file = Path.of(filename); assertTrue(Files.isRegularFile(file));
        try (ZipFile jar = new ZipFile(file.toFile())) {
            var direct = jar.getEntry(classFile);
            if (direct != null) try (InputStream in = jar.getInputStream(direct)) { return in.readAllBytes(); }
            // Sodium's mod is nested inside its unchanged service JAR. Inspect only in memory.
            var nested = jar.getEntry("META-INF/jarjar/net.caffeinemc.sodium-neoforge-0.8.12-beta.1+mc1.21.1-mod.jar");
            assertNotNull(nested, classFile);
            try (ZipInputStream entries = new ZipInputStream(jar.getInputStream(nested))) {
                java.util.zip.ZipEntry entry;
                while ((entry = entries.getNextEntry()) != null)
                    if (entry.getName().equals(classFile)) return entries.readAllBytes();
            }
        }
        throw new AssertionError("Missing pinned upstream " + classFile);
    }
}
