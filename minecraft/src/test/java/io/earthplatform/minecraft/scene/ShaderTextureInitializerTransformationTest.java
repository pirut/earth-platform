package io.earthplatform.minecraft.scene;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes original dual-target initializer fixtures through the real pinned Mixin
 * transformer. Mechanically checks the complete relevant external Iris method and
 * annotations. This is fixture + artifact proof, not a co-loaded real RenderSystem.
 */
class ShaderTextureInitializerTransformationTest {
    private static final String ROOT = "io.earthplatform.minecraft.startupfixture.ShaderTextureTargets$";
    private static final String FIXTURE = "io/earthplatform/minecraft/startupfixture/mixin/ShaderTextureStorageMixin.class";
    private static final String IRIS = "net/irisshaders/iris/mixin/MixinGlStateManager.class";
    private static final String PIN = "60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String MODIFY = "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;";

    @Test void exactExternalIrisDualTargetAndEntireConstantMethodMatchTheExecutedFixture() throws Exception {
        Path jar = Path.of(System.getProperty("earthplatform.installedIrisTestJar"));
        assertEquals(PIN, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        ClassNode upstream;
        try (var zip = new ZipFile(jar.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry(IRIS))) { upstream = node(input.readAllBytes()); }
        }
        ClassNode fixture = node(resource(FIXTURE));
        assertEquals(Set.of("com/mojang/blaze3d/platform/GlStateManager", "com/mojang/blaze3d/systems/RenderSystem"),
                targetNames(upstream));
        assertEquals(Set.of(ROOT.replace('.', '/') + "RenderAliases", ROOT.replace('.', '/') + "BindingAliases"),
                targetNames(fixture));
        MethodNode real = method(upstream, "iris$increaseMaximumAllowedTextureUnits");
        MethodNode copy = method(fixture, "fixture$increaseMaximumAllowedTextureUnits");
        assertEquals("(I)I", real.desc); assertEquals(real.desc, copy.desc);
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, real.access);
        assertEquals(real.access, copy.access);
        assertEquals(List.of("17:128", "172"), instructionSequence(real),
                "Compare every executable instruction, not just a claimed return value");
        assertEquals(instructionSequence(real), instructionSequence(copy));
        for (MethodNode method : List.of(real, copy)) {
            AnnotationNode annotation = annotation(method.visibleAnnotations, MODIFY);
            assertEquals(List.of("<clinit>"), value(annotation, "method"));
            assertEquals(1, value(annotation, "require"));
            List<?> constants = (List<?>) value(annotation, "constant"); assertEquals(1, constants.size());
            var constant = (AnnotationNode) constants.getFirst();
            assertEquals("Lorg/spongepowered/asm/mixin/injection/Constant;", constant.desc);
            assertEquals(List.of("intValue", 12), constant.values);
            assertEquals(Set.of("method", "constant", "require"), keys(annotation),
                    "No unreviewed slice/ordinal/remap/cancellation option may hide a different contract");
        }
        ClassNode vanilla = node(resource("com/mojang/blaze3d/systems/RenderSystem.class"));
        MethodNode init = method(vanilla, "<clinit>");
        var field = Arrays.stream(init.instructions.toArray()).filter(i -> i instanceof FieldInsnNode f
                && f.getOpcode() == Opcodes.PUTSTATIC && f.name.equals("shaderTextures") && f.desc.equals("[I"))
                .map(i -> (FieldInsnNode) i).findFirst().orElseThrow();
        assertEquals(Opcodes.NEWARRAY, previousCode(field).getOpcode());
        assertEquals(Opcodes.T_INT, ((IntInsnNode) previousCode(field)).operand);
        assertEquals(12, ((IntInsnNode) previousCode(previousCode(field))).operand,
                "Untransformed vanilla twelve is precisely why artifact correspondence is required");
        assertNull(getClass().getClassLoader().getResource("net/irisshaders/iris/Iris.class"),
                "The legacy test partition must not silently co-load optional mods");
    }

    @Test void realPinnedTransformerProducesBoth128SlotArraysAndHighAliasesAreClearedExactly() throws Exception {
        assertEquals("legacy-isolated", System.getProperty("earthplatform.test.partition"));
        var environment = MixinEnvironment.getCurrentEnvironment();
        assertEquals("0.8.7", environment.getVersion());
        var transformer = assertInstanceOf(IMixinTransformer.class, environment.getActiveTransformer());
        assertEquals(128, NativeShaderTextureAliases.SLOT_COUNT);
        for (String target : List.of("RenderAliases", "BindingAliases")) {
            byte[] original = resource((ROOT + target).replace('.', '/') + ".class");
            int[] unchanged = execute(original);
            assertEquals(12, unchanged.length);
            byte[] transformed = transformer.transformClass(environment, ROOT + target, original);
            assertNotNull(transformed); assertFalse(Arrays.equals(original, transformed),
                    "Missing fixture registration cannot pass as transformation evidence");
            int[] aliases = execute(transformed);
            assertEquals(128, aliases.length, "Execute the actual Mixin-generated initializer");
            for (int slot : new int[]{0, 31, 32, 63, 64, 127}) aliases[slot] = 47;
            aliases[126] = 59;
            Object level = new Object();
            try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
                NativeShaderTextureAliases.beforeDelete(level, aliases, 47);
                for (int slot : new int[]{0, 31, 32, 63, 64, 127}) assertEquals(0, aliases[slot]);
                assertEquals(59, aliases[126]);
                var evidence = NativeShaderTextureAliases.evidence();
                assertEquals(6, evidence.aliasesCleared());
                assertEquals(1L | 1L << 31 | 1L << 32 | 1L << 63, evidence.lastClearedSlotMaskLo());
                assertEquals(1L | 1L << 63, evidence.lastClearedSlotMaskHi());
                assertEquals(6, Long.bitCount(evidence.lastClearedSlotMaskLo()) + Long.bitCount(evidence.lastClearedSlotMaskHi()));
            }
        }
    }

    @Test void unregisteredInitializerRemainsTwelveAndIsRejectedInsteadOfPretendingCompatibility() throws Exception {
        var environment = MixinEnvironment.getCurrentEnvironment();
        var transformer = assertInstanceOf(IMixinTransformer.class, environment.getActiveTransformer());
        byte[] original = resource((ROOT + "Unregistered").replace('.', '/') + ".class");
        byte[] unchanged = transformer.transformClass(environment, ROOT + "Unregistered", original);
        if (unchanged == null) unchanged = original;
        int[] aliases = execute(unchanged);
        assertEquals(12, aliases.length);
        Object level = new Object();
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            assertThrows(IllegalStateException.class, () -> NativeShaderTextureAliases.beforeDelete(level, aliases, 47));
        }
    }

    private static int[] execute(byte[] bytes) throws Exception {
        Class<?> fixture = new ClassLoader(ShaderTextureInitializerTransformationTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return (int[]) fixture.getMethod("raw").invoke(null);
    }
    private static byte[] resource(String name) throws Exception {
        try (InputStream input = ShaderTextureInitializerTransformationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name); return input.readAllBytes();
        }
    }
    private static ClassNode node(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        assertNotNull(annotations); return annotations.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        throw new AssertionError("Missing annotation field " + key);
    }
    private static Set<String> keys(AnnotationNode annotation) {
        var result = new java.util.HashSet<String>();
        for (int i = 0; i < annotation.values.size(); i += 2) result.add((String) annotation.values.get(i));
        return result;
    }
    private static Set<String> targetNames(ClassNode node) {
        var annotation = annotation(node.invisibleAnnotations, MIXIN);
        var values = (List<?>) value(annotation, "value");
        var names = new java.util.HashSet<String>(); for (Object type : values) names.add(((Type) type).getInternalName());
        return names;
    }
    private static List<String> instructionSequence(MethodNode method) {
        var result = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction.getOpcode() < 0) continue;
            if (instruction instanceof IntInsnNode integer) result.add(integer.getOpcode() + ":" + integer.operand);
            else {
                assertInstanceOf(InsnNode.class, instruction, "Unexpected executable instruction in constant method");
                result.add(Integer.toString(instruction.getOpcode()));
            }
        }
        return result;
    }
    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return java.util.Objects.requireNonNull(instruction);
    }
}
