package io.earthplatform.compat.dh.shader;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned input/compiled shim ABI inspection. This is deliberately not a transformed-client or GPU claim. */
class IntegratedShaderHookIndependentTest {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String NATIVE = "io/earthplatform/minecraft/mixin/NativeIrisTerrainLayerMixin";
    private static final String TERRAIN_DESC = "(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V";
    private static ClassNode parse(byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    private static ClassNode own(String name) throws IOException {
        try (var in = IntegratedShaderHookIndependentTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(in, name); return parse(in.readAllBytes());
        }
    }
    private static ClassNode external(String property, String name) throws IOException {
        String input = System.getProperty(property); assertNotNull(input, "Exact installed-shader input is required");
        try (var zip = new ZipFile(Path.of(input).toFile())) {
            var entry = zip.getEntry(name + ".class");
            if (entry != null) try (var in = zip.getInputStream(entry)) { return parse(in.readAllBytes()); }
            var nested = zip.getEntry("META-INF/jarjar/net.caffeinemc.sodium-neoforge-0.8.12-beta.1+mc1.21.1-mod.jar");
            assertNotNull(nested, "Class is neither in the supplied artifact nor its exact pinned Sodium member: " + name);
            try (var in = new ZipInputStream(zip.getInputStream(nested))) {
                java.util.zip.ZipEntry member;
                while ((member = in.getNextEntry()) != null)
                    if (member.getName().equals(name + ".class")) return parse(in.readAllBytes());
            }
        }
        throw new AssertionError("Missing exact pinned class: " + name);
    }
    private static ClassNode iris(String name) throws IOException { return external("earthplatform.installedIrisTestJar", name); }
    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var all = new ArrayList<AnnotationNode>(); if (visible != null) all.addAll(visible); if (invisible != null) all.addAll(invisible); return all;
    }
    private static AnnotationNode annotation(List<AnnotationNode> all, String descriptor) {
        return all.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }
    private static Set<String> registered() throws IOException {
        var names = new HashSet<String>();
        for (var config : Map.of("earthplatform.shaders.mixins.json", "io.earthplatform.minecraft.mixin",
                "earthplatform.far-shaders.mixins.json", "io.earthplatform.compat.dh.shader.mixin").entrySet()) {
            try (var in = IntegratedShaderHookIndependentTest.class.getClassLoader().getResourceAsStream(config.getKey())) {
                assertNotNull(in, config.getKey()); var json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(config.getValue(), json.get("package").getAsString(), "Only exact mixin-only packages may be reserved");
                assertEquals("io.earthplatform.minecraft.mixin.EarthShaderMixinPlugin", json.get("plugin").getAsString());
                for (var name : json.getAsJsonArray("client"))
                    assertTrue(names.add((json.get("package").getAsString() + "." + name.getAsString()).replace('.', '/')), "No duplicate mixin entry");
                assertTrue(json.get("required").getAsBoolean());
            }
        }
        return names;
    }

    @Test void everyNativeStateAccessorCastAndStaticAccessorIsActuallyRegistered() throws Exception {
        var registered = registered(); var referenced = new HashSet<String>();
        var queue = new ArrayDeque<>(List.of("io/earthplatform/minecraft/scene/NativeIrisState", "io/earthplatform/minecraft/scene/NativeIrisShaderStages"));
        var visited = new HashSet<String>();
        while (!queue.isEmpty()) {
            String owner = queue.removeFirst(); if (!visited.add(owner)) continue; var node = own(owner);
            for (var inner : node.innerClasses) if (inner.name.startsWith("io/earthplatform/minecraft/scene/NativeIris")) queue.add(inner.name);
            for (var method : node.methods) for (var instruction : method.instructions) {
                String name = instruction instanceof TypeInsnNode type ? type.desc : instruction instanceof MethodInsnNode call ? call.owner : "";
                if (name.startsWith("io/earthplatform/minecraft/mixin/NativeIris") && name.endsWith("Accessor")) referenced.add(name);
            }
        }
        assertTrue(referenced.size() >= 7, "Exercise the actual state owner's accessor dependencies");
        assertTrue(registered.containsAll(referenced), "Referenced but unregistered native accessors: " + referenced.stream().filter(n -> !registered.contains(n)).toList());
    }

    @Test void allRegisteredNativeAccessorFieldsMatchExactPinnedTypesAndStaticness() throws Exception {
        int accessors = 0;
        for (String name : registered()) {
            if (!name.contains("/NativeIris") || !name.endsWith("Accessor")) continue;
            ClassNode shim = own(name); var mixin = annotation(annotations(shim.visibleAnnotations, shim.invisibleAnnotations), MIXIN);
            Object classes = value(mixin, "value"), targets = value(mixin, "targets");
            String target = classes instanceof List<?> list ? ((Type) list.getFirst()).getInternalName() : ((String) ((List<?>) targets).getFirst()).replace('.', '/');
            ClassNode upstream = target.startsWith("net/irisshaders/") ? iris(target) : own(target);
            for (var accessor : shim.methods) {
                var all = annotations(accessor.visibleAnnotations, accessor.invisibleAnnotations);
                if (all.stream().noneMatch(a -> a.desc.equals(ACCESSOR))) continue;
                String field = (String) value(annotation(all, ACCESSOR), "value");
                var actual = upstream.fields.stream().filter(f -> f.name.equals(field)).findFirst().orElseThrow(() -> new AssertionError(target + "." + field));
                Type[] args = Type.getArgumentTypes(accessor.desc); Type returns = Type.getReturnType(accessor.desc);
                assertEquals(actual.desc, returns.getSort() == Type.VOID ? args[0].getDescriptor() : returns.getDescriptor(), name + "." + accessor.name);
                assertEquals((actual.access & Opcodes.ACC_STATIC) != 0, (accessor.access & Opcodes.ACC_STATIC) != 0, name + "." + accessor.name);
                accessors++;
            }
        }
        assertTrue(accessors >= 12, "Inspect all native state getter/setter ABI bindings");
    }

    @Test void nativeHookIsAfterSodiumManagedExitAndBeforeItsPlatformCallbacks() throws Exception {
        var shim = own(NATIVE); var mixin = annotation(annotations(shim.visibleAnnotations, shim.invisibleAnnotations), MIXIN);
        assertEquals(1001, value(mixin, "priority"));
        var hook = method(shim, "earthplatform$nativeTerrain");
        var inject = annotation(annotations(hook.visibleAnnotations, hook.invisibleAnnotations), INJECT);
        assertEquals(List.of("renderSectionLayer" + TERRAIN_DESC), value(inject, "method")); assertEquals(1, value(inject, "require"));
        var at = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
        assertEquals("INVOKE", value(at, "value"));
        assertEquals("Lnet/caffeinemc/mods/sodium/client/services/PlatformLevelRenderHooks;getInstance()Lnet/caffeinemc/mods/sodium/client/services/PlatformLevelRenderHooks;", value(at, "target"));
        ClassNode sodium = external("earthplatform.installedSodiumTestJar", "net/caffeinemc/mods/sodium/mixin/core/render/world/LevelRendererMixin");
        var target = method(sodium, "renderSectionLayer"); assertEquals(TERRAIN_DESC, target.desc);
        var calls = calls(target); int anchor = -1, exit = -1, draw = -1, callbacks = -1;
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).name.equals("exitManagedCode")) exit = i;
            if (calls.get(i).name.equals("drawChunkLayer")) draw = i;
            if (calls.get(i).owner.endsWith("/PlatformLevelRenderHooks") && calls.get(i).name.equals("getInstance")) { assertEquals(-1, anchor); anchor = i; }
            if (calls.get(i).name.equals("runChunkLayerEvents")) callbacks = i;
        }
        assertTrue(draw >= 0 && exit > draw && anchor > exit && callbacks > anchor);
    }

    @Test void irisTerrainPhaseAndActualShadowCallsCoverTheSameTargetDescriptor() throws Exception {
        var upstream = iris("net/irisshaders/iris/mixin/MixinLevelRenderer");
        for (String[] spec : new String[][]{{"iris$beginTerrainLayer", "HEAD"}, {"iris$endTerrainLayer", "RETURN"}}) {
            var hook = method(upstream, spec[0]); var inject = annotation(annotations(hook.visibleAnnotations, hook.invisibleAnnotations), INJECT);
            var at = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
            assertEquals(spec[1], value(at, "value"));
            assertTrue(((List<?>) value(inject, "method")).stream().anyMatch(v -> v.toString().startsWith("renderSectionLayer")));
        }
        var accessor = method(iris("net/irisshaders/iris/mixin/LevelRendererAccessor"), "invokeRenderSectionLayer");
        assertEquals(TERRAIN_DESC, accessor.desc);
        var invoker = annotation(annotations(accessor.visibleAnnotations, accessor.invisibleAnnotations), "Lorg/spongepowered/asm/mixin/gen/Invoker;");
        assertEquals("renderSectionLayer", value(invoker, "value"));
        int shadows = 0;
        for (var method : iris("net/irisshaders/iris/shadows/ShadowRenderer").methods)
            for (var call : calls(method)) if (call.owner.equals("net/irisshaders/iris/mixin/LevelRendererAccessor") && call.name.equals("invokeRenderSectionLayer")) {
                assertEquals(TERRAIN_DESC, call.desc); shadows++;
            }
        assertEquals(4, shadows, "Actual pinned Iris solid/cutout/cutout-mipped/translucent shadow calls must target the native hook");
    }

    @Test void dhHookRunsAfterFullPassSetupAndReplacesOnlyTheNullGuardedStockList() throws Exception {
        var shim = own("io/earthplatform/compat/dh/shader/mixin/DhIrisTerrainPassMixin");
        var draw = method(shim, "earth$submitOwnedPass");
        var injection = annotation(annotations(draw.visibleAnnotations, draw.invisibleAnnotations), INJECT);
        var at = (AnnotationNode) ((List<?>) value(injection, "at")).getFirst();
        assertEquals(1, value(at, "ordinal")); assertEquals("AFTER", ((String[]) value(at, "shift"))[1]);
        assertEquals("Lcom/seibel/distanthorizons/common/wrappers/minecraft/MinecraftGLWrapper;enableFaceCulling()V", value(at, "target"));
        var replace = method(shim, "earth$emptyOwnedStockBuffers");
        var modifier = annotation(annotations(replace.visibleAnnotations, replace.invisibleAnnotations), "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
        assertEquals(true, value(modifier, "argsOnly")); assertEquals(0, value(modifier, "ordinal"));
        assertTrue(calls(replace).stream().anyMatch(c -> c.owner.endsWith("/ShaderCarrierHooks") && c.name.equals("owns")));
        assertFalse(shim.methods.stream().flatMap(m -> calls(m).stream()).anyMatch(c -> Set.of("cancel", "clear", "close", "delete").contains(c.name)));
        var upstream = method(external("earthplatform.installedDhTestJar", "com/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram_neoforge"), "render");
        var culls = calls(upstream).stream().filter(c -> c.owner.endsWith("/MinecraftGLWrapper") && c.name.equals("enableFaceCulling")).toList();
        // Exact pinned calls: initial setup, post-dispatch Iris setup, and final wireframe cleanup.
        assertEquals(3, culls.size()); int anchor = upstream.instructions.indexOf(culls.get(1));
        assertTrue(calls(upstream).stream().anyMatch(c -> c.name.equals("fireAllEvents") && upstream.instructions.indexOf(c) < anchor));
        var load = culls.get(1).getNext(); while (load.getOpcode() < 0) load = load.getNext();
        assertInstanceOf(VarInsnNode.class, load); assertEquals(Opcodes.ALOAD, load.getOpcode()); assertEquals(3, ((VarInsnNode) load).var);
        var nullGuard = load.getNext(); while (nullGuard.getOpcode() < 0) nullGuard = nullGuard.getNext();
        assertEquals(Opcodes.IFNULL, nullGuard.getOpcode(), "Null suppresses only the stock loop after Iris/DH setup");
        int cleanup = upstream.instructions.indexOf(((JumpInsnNode) nullGuard).label);
        assertTrue(cleanup > anchor && cleanup < upstream.instructions.indexOf(culls.get(2)),
                "Null stock buffers must retain the upstream tail's third cull/reset call");
    }

    @Test void allResizeAndAttachmentLifecycleHooksMatchPinnedMethodsBeforeMutation() throws Exception {
        int hooks = 0;
        for (String simple : List.of("IrisTargetResizeMixin", "IrisDhTargetResizeMixin", "ShaderMainTargetResizeMixin")) {
            String name = "io/earthplatform/compat/dh/shader/mixin/" + simple; assertTrue(registered().contains(name));
            var shim = own(name); var mixin = annotation(annotations(shim.visibleAnnotations, shim.invisibleAnnotations), MIXIN);
            Object classes = value(mixin, "value"), targets = value(mixin, "targets");
            String target = classes instanceof List<?> list ? ((Type) list.getFirst()).getInternalName() : ((String) ((List<?>) targets).getFirst()).replace('.', '/');
            var upstream = target.startsWith("net/irisshaders/") ? iris(target) : own(target);
            for (var field : shim.fields) if (annotations(field.visibleAnnotations, field.invisibleAnnotations).stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;")))
                assertTrue(upstream.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)), target + "." + field.name);
            for (var hook : shim.methods) {
                var all = annotations(hook.visibleAnnotations, hook.invisibleAnnotations);
                if (all.stream().noneMatch(a -> a.desc.equals(INJECT))) continue;
                var inject = annotation(all, INJECT); var at = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
                assertEquals("HEAD", value(at, "value")); assertEquals(1, value(inject, "require"));
                String methodName = ((List<?>) value(inject, "method")).getFirst().toString();
                var targetsByName = upstream.methods.stream().filter(m -> m.name.equals(methodName)).toList();
                assertEquals(1, targetsByName.size(), target + "." + methodName);
                var args = new ArrayList<>(List.of(Type.getArgumentTypes(hook.desc))); args.removeLast();
                assertEquals(List.of(Type.getArgumentTypes(targetsByName.getFirst().desc)), args, simple + "." + hook.name);
                hooks++;
            }
        }
        assertEquals(6, hooks);
    }
}
