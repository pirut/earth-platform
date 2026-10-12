package io.earthplatform.minecraft.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent exact-bytecode admission/deletion fences. Does not claim transformed-client or driver proof. */
class NativeShaderTextureAliasWiringReviewTest {
    private static final String BASE = "io/earthplatform/minecraft/";
    private static final String ALIASES = BASE + "scene/NativeShaderTextureAliases";
    private static final String SCOPE = ALIASES + "$Scope";
    private static final String RELOAD = BASE + "mixin/NativeShaderReloadScopeMixin";
    private static final String DELETE = BASE + "mixin/NativeShaderTextureDeletionMixin";
    private static final String ACCESS = BASE + "mixin/NativeShaderRenderSystemAccessor";
    private static final String HOOKS = "io/earthplatform/compat/dh/shader/ShaderCarrierHooks";
    private static final String MC = "net/minecraft/client/Minecraft";
    private static final String GL = "com/mojang/blaze3d/platform/GlStateManager";
    private static final String RENDER = "com/mojang/blaze3d/systems/RenderSystem";
    private static final String RESOURCE_MANAGER = "net/minecraft/server/packs/resources/ReloadableResourceManager";
    private static final String RELOAD_INSTANCE = "net/minecraft/server/packs/resources/ReloadInstance";
    private static final String RELOAD_DESCRIPTOR = "(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;";
    private static final String CREATE_DESCRIPTOR = "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;";

    private static ClassNode read(String name) throws IOException {
        try (var stream = NativeShaderTextureAliasWiringReviewTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name); var node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String type) {
        assertNotNull(annotations, type); return annotations.stream().filter(value -> value.desc.equals(type)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        return method.instructions.indexOf(calls(method).stream().filter(call -> call.owner.equals(owner) && call.name.equals(name)).findFirst().orElseThrow());
    }

    @Test void pinnedVanillaDeletionReallyLeavesRenderSystemAliasesUntouchedAndHasOneExactGlSite() throws Exception {
        var gl = read(GL); var render = read(RENDER);
        var shaderTextures = render.fields.stream().filter(field -> field.name.equals("shaderTextures")).findFirst().orElseThrow();
        assertEquals("[I", shaderTextures.desc); assertTrue((shaderTextures.access & Opcodes.ACC_STATIC) != 0);
        for (String deletion : List.of("_deleteTexture", "_deleteTextures")) {
            var body = method(gl, deletion);
            var sites = calls(body).stream().filter(call -> call.owner.equals("org/lwjgl/opengl/GL11") && call.name.equals("glDeleteTextures")).toList();
            assertEquals(1, sites.size()); assertEquals(deletion.equals("_deleteTexture") ? "(I)V" : "([I)V", sites.getFirst().desc);
            assertTrue(callIndex(body, RENDER, "assertOnRenderThreadOrInit") < body.instructions.indexOf(sites.getFirst()));
            assertTrue(calls(body).stream().noneMatch(call -> call.name.contains("ShaderTexture")));
            for (var instruction : body.instructions) if (instruction instanceof FieldInsnNode field)
                assertFalse(field.owner.equals(RENDER) && field.name.equals("shaderTextures"));
        }
        var delegate = method(render, "deleteTexture");
        assertEquals(1, calls(delegate).stream().filter(call -> call.owner.equals(GL) && call.name.equals("_deleteTexture")).count());
        assertFalse(calls(delegate).stream().anyMatch(call -> call.name.contains("ShaderTexture")));
    }

    @Test void bothDeleteHooksTargetOnlyExactNativeDeletionAndCannotCancelOrCatchIt() throws Exception {
        var node = read(DELETE);
        for (String methodName : List.of("earthplatform$beforeTextureDelete", "earthplatform$beforeTexturesDelete")) {
            var body = method(node, methodName); boolean bulk = methodName.equals("earthplatform$beforeTexturesDelete");
            var inject = annotation(body.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            assertEquals(List.of(bulk ? "_deleteTextures([I)V" : "_deleteTexture(I)V"), value(inject, "method"));
            assertEquals(1, value(inject, "require")); assertEquals(1, value(inject, "allow"));
            assertNotEquals(Boolean.TRUE, value(inject, "cancellable"));
            var at = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
            assertEquals("INVOKE", value(at, "value")); assertEquals(Boolean.FALSE, value(at, "remap"));
            assertEquals("Lorg/lwjgl/opengl/GL11;glDeleteTextures" + (bulk ? "([I)V" : "(I)V"), value(at, "target"));
            assertArrayEquals(new String[]{"Lorg/spongepowered/asm/mixin/injection/At$Shift;", "BEFORE"}, (String[]) value(at, "shift"));
            assertTrue(body.tryCatchBlocks.isEmpty(), "Delete hook must propagate validation failure");
            assertTrue(callIndex(body, ALIASES, "active") < callIndex(body, ACCESS, "earthplatform$rawShaderTextures"));
            assertTrue(callIndex(body, ACCESS, "earthplatform$rawShaderTextures") < callIndex(body, ALIASES, "beforeDelete"));
            assertEquals(1, calls(body).stream().filter(call -> call.owner.equals(ALIASES) && call.name.equals("beforeDelete")).count());
            assertTrue(calls(body).stream().noneMatch(call -> call.owner.startsWith("org/lwjgl/") || call.name.equals("setShaderTexture") || call.name.equals("cancel")));
        }
    }

    @Test void accessorReadsTheRawFixedArrayWithoutSetterOrIrisCallback() throws Exception {
        var node = read(ACCESS); var body = method(node, "earthplatform$rawShaderTextures");
        assertEquals("()[I", body.desc); assertTrue((body.access & Opcodes.ACC_STATIC) != 0);
        var accessor = annotation(body.visibleAnnotations, "Lorg/spongepowered/asm/mixin/gen/Accessor;");
        assertEquals("shaderTextures", value(accessor, "value"));
        var mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of(Type.getObjectType(RENDER)), value(mixin, "value"));
        assertTrue(calls(body).stream().allMatch(call -> call.owner.equals("java/lang/AssertionError")));
    }

    @Test void reloadRedirectSelectsPrivateActualCreationAndNeverHeadOrReturnOfEarlyExit() throws Exception {
        var node = read(RELOAD); var body = method(node, "earthplatform$scopedTextureAliases");
        var redirect = annotation(body.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        assertEquals(List.of("reloadResourcePacks" + RELOAD_DESCRIPTOR), value(redirect, "method"));
        assertEquals(1, value(redirect, "require")); assertEquals(1, value(redirect, "allow"));
        var at = (AnnotationNode) value(redirect, "at");
        assertEquals("INVOKE", value(at, "value"));
        assertEquals("L" + RESOURCE_MANAGER + ";createReload" + CREATE_DESCRIPTOR, value(at, "target"));
        var pinned = read(MC).methods.stream().filter(method -> method.name.equals("reloadResourcePacks") && method.desc.equals(RELOAD_DESCRIPTOR)).findFirst().orElseThrow();
        var creation = calls(pinned).stream().filter(call -> call.owner.equals(RESOURCE_MANAGER) && call.name.equals("createReload") && call.desc.equals(CREATE_DESCRIPTOR)).toList();
        assertEquals(1, creation.size()); int creationIndex = pinned.instructions.indexOf(creation.getFirst());
        int earlierReturns = 0, laterReturns = 0;
        for (int i = 0; i < pinned.instructions.size(); i++) if (pinned.instructions.get(i).getOpcode() == Opcodes.ARETURN) {
            if (i < creationIndex) earlierReturns++; else laterReturns++;
        }
        assertEquals(2, earlierReturns, "Existing pending and LoadingOverlay returns must precede actual reload");
        assertEquals(1, laterReturns);
    }

    @Test void exactAdmittedOwnerIsCheckedBeforeCreationAndExactDoneFutureOwnsCompletion() throws Exception {
        var node = read(RELOAD); var body = method(node, "earthplatform$scopedTextureAliases");
        int owner = callIndex(body, HOOKS, "admittedReloadOwner"), begin = callIndex(body, ALIASES, "begin");
        var creations = calls(body).stream().filter(call -> call.owner.equals(RESOURCE_MANAGER) && call.name.equals("createReload")).toList();
        assertEquals(2, creations.size(), "One unchanged ordinary branch and one scoped branch");
        int ordinary = body.instructions.indexOf(creations.getFirst()), scoped = body.instructions.indexOf(creations.getLast());
        assertTrue(owner < ordinary && ordinary < begin && begin < scoped);
        assertTrue(scoped < callIndex(body, RELOAD_INSTANCE, "done"));
        assertTrue(callIndex(body, RELOAD_INSTANCE, "done") < callIndex(body, SCOPE, "retainUntil"));
        assertEquals(1, calls(body).stream().filter(call -> call.name.equals("retainUntil")).count());
        assertFalse(calls(body).stream().anyMatch(call -> Set.of("join", "get", "complete", "cancel", "exceptionally", "handle", "setShaderTexture").contains(call.name)));
        var admission = node.methods.stream().filter(method -> calls(method).stream().anyMatch(call -> call.owner.equals(HOOKS) && call.name.equals("requireReloadOwner"))).toList();
        assertEquals(1, admission.size());
        assertTrue(calls(body).stream().anyMatch(call -> call.name.equals("addSuppressed")), "Cleanup must retain original failure");
        assertTrue(java.util.Arrays.stream(body.instructions.toArray()).anyMatch(instruction -> instruction.getOpcode() == Opcodes.ATHROW));
    }

    @Test void admissionQueryUsesExistingExactCarrierWithoutCreatingOrReacquiringPipeline() throws Exception {
        var hooks = read(HOOKS); var query = method(hooks, "admittedReloadOwner"); var require = method(hooks, "requireReloadOwner");
        assertEquals(List.of("owns", "pipelineCurrent"), calls(query).stream().map(call -> call.name).toList());
        assertEquals(1, calls(require).stream().filter(call -> call.name.equals("admittedReloadOwner")).count());
        assertFalse(calls(require).stream().anyMatch(call -> Set.of("equals", "lease", "prepare", "start", "terrain").contains(call.name)));
        assertTrue(java.util.Arrays.stream(require.instructions.toArray()).anyMatch(instruction -> instruction.getOpcode() == Opcodes.IF_ACMPEQ));
    }

    @Test void lifecycleHelperHasNoGlQueriesSettersScheduledWorkOrRetainedDeletedNameCollection() throws Exception {
        for (String type : List.of(ALIASES, SCOPE)) {
            var node = read(type);
            for (var field : node.fields) {
                assertFalse(field.desc.startsWith("["), "No retained raw arrays or deleted-name arrays: " + field.name);
                assertFalse(field.desc.contains("Map;") || field.desc.contains("Set;") || field.desc.contains("List;"), field.name);
            }
            for (var body : node.methods) for (var call : calls(body)) {
                assertFalse(call.owner.startsWith("org/lwjgl/") || call.owner.startsWith("net/irisshaders/") || call.owner.equals(RENDER), call.owner + "." + call.name);
                assertFalse(Set.of("execute", "submit", "runAsync", "supplyAsync", "setShaderTexture", "glGetError", "glIsTexture", "cancel", "complete", "completeExceptionally").contains(call.name), call.name);
            }
        }
        var done = method(read(SCOPE), "retainUntil");
        assertEquals(1, calls(done).stream().filter(call -> call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("whenComplete")).count());
        assertTrue(calls(done).stream().noneMatch(call -> call.name.equals("whenCompleteAsync")));
    }
}
