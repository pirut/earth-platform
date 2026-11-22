package io.earthplatform.minecraft.cubic.compat.axiom;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies Axiom bytecode rewriting in both NeoForge (Mojang names) and Fabric (intermediary names). */
class AxiomMixinsTest {
    private static final AxiomMixins.NameMapper MOJANG = new AxiomMixins.NameMapper() {
        @Override public String className(String intermediary, String mojang) { return mojang; }
        @Override public String methodName(String owner, String name, String desc, String mojang) { return mojang; }
    };

    private static final AxiomMixins.NameMapper INTERMEDIARY = new AxiomMixins.NameMapper() {
        @Override public String className(String intermediary, String mojang) { return intermediary; }
        @Override public String methodName(String owner, String name, String desc, String mojang) { return name; }
    };

    @Test void everyMojangRewriteTargetsExistingBridgeMethod() {
        List<AxiomMixins.RuntimeCall> calls = AxiomMixins.resolve(MOJANG);
        assertFalse(calls.isEmpty());
        for (AxiomMixins.RuntimeCall call : calls) {
            String expectedDesc = "(L" + call.receiver() + ";" + call.desc().substring(1);
            Method match = null;
            for (Method method : AxiomCubes.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
                        && method.getName().equals(call.bridge())
                        && Type.getMethodDescriptor(method).equals(expectedDesc)) {
                    match = method;
                    break;
                }
            }
            assertNotNull(match, "Missing public static bridge AxiomCubes." + call.bridge() + expectedDesc);
        }
    }

    @Test void rewritesMojangBytecodeAndPassesVerification() throws Exception {
        ClassNode target = new ClassNode();
        target.version = Opcodes.V21;
        target.access = Opcodes.ACC_PUBLIC;
        target.name = "com/moulberry/axiom/test/SyntheticSelector";
        target.superName = "java/lang/Object";

        // Method 1: LevelHeightAccessor methods on ClientLevel, ServerLevel, LevelChunk, BlockAndTintGetter
        MethodNode heightMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "heightCalls",
                "(Lnet/minecraft/client/multiplayer/ClientLevel;"
                        + "Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;"
                        + "Lnet/minecraft/world/level/BlockAndTintGetter;"
                        + "Lnet/minecraft/core/BlockPos;)I", null, null);
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/multiplayer/ClientLevel", "getMinBuildHeight", "()I", false));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/multiplayer/ClientLevel", "getMaxBuildHeight", "()I", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/world/level/BlockAndTintGetter", "getHeight", "()I", true));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/level/ServerLevel", "getMinSection", "()I", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/level/ServerLevel", "getMaxSection", "()I", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "getSectionsCount", "()I", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        heightMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "getSectionIndex", "(I)I", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        heightMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "getSectionIndexFromSectionY", "(I)I", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        heightMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "getSectionYFromSectionIndex", "(I)I", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/multiplayer/ClientLevel", "isOutsideBuildHeight", "(Lnet/minecraft/core/BlockPos;)Z", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        heightMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        heightMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/multiplayer/ClientLevel", "isOutsideBuildHeight", "(I)Z", false));
        heightMethod.instructions.add(new InsnNode(Opcodes.IADD));
        heightMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        target.methods.add(heightMethod);

        // Method 2: Chunk, section, block state, block entity, heightmap, skylight, light engine, and non-matching calls
        MethodNode chunkMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "chunkAndLightCalls",
                "(Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;"
                        + "Lnet/minecraft/world/level/block/entity/BlockEntity;"
                        + "Lnet/minecraft/world/level/levelgen/Heightmap;"
                        + "Lnet/minecraft/world/level/lighting/ChunkSkyLightSources;"
                        + "Lnet/minecraft/world/level/lighting/LevelLightEngine;"
                        + "Lnet/minecraft/core/SectionPos;)V", null, null);
        // chunk.getSection(0).setBlockState(0, 0, 0, state, false)
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "getSection", "(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunkSection", "setBlockState", "(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.POP));
        // chunk.getBlockState(pos) and chunkAccess.getBlockState(pos) -> rewritten
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "getBlockState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.POP));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/ChunkAccess", "getBlockState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.POP));
        // level.getBlockState(pos) -> MUST NOT be rewritten (Level handles cubic worlds itself)
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/Level", "getBlockState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.POP));
        // LevelChunk block entity calls -> rewritten
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "getBlockEntity", "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.POP));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "removeBlockEntity", "(Lnet/minecraft/core/BlockPos;)V", false));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "setBlockEntity", "(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", false));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "addAndRegisterBlockEntity", "(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", false));
        // level.removeBlockEntity / level.setBlockEntity -> MUST NOT be rewritten
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/Level", "removeBlockEntity", "(Lnet/minecraft/core/BlockPos;)V", false));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/Level", "setBlockEntity", "(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", false));
        // Heightmap, ChunkSkyLightSources, LevelLightEngine -> rewritten
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/Heightmap", "update", "(IIILnet/minecraft/world/level/block/state/BlockState;)Z", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.POP));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/lighting/ChunkSkyLightSources", "update", "(Lnet/minecraft/world/level/BlockGetter;III)Z", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.POP));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        chunkMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        chunkMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chunkMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/lighting/LevelLightEngine", "updateSectionStatus", "(Lnet/minecraft/core/SectionPos;Z)V", false));
        chunkMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        target.methods.add(chunkMethod);

        // Method 3: ServerLevelExt.axiom$getPendingLightUpdates -> rewritten to AxiomCubes.pendingLightUpdates(Object, int, int, int)[S
        MethodNode lightExtMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pendingLight",
                "(Ljava/lang/Object;)[S", null, null);
        lightExtMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        lightExtMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
        lightExtMethod.instructions.add(new InsnNode(Opcodes.ICONST_1));
        lightExtMethod.instructions.add(new InsnNode(Opcodes.ICONST_2));
        lightExtMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/moulberry/axiom/hooks/ServerLevelExt", "axiom$getPendingLightUpdates", "(III)[S", true));
        lightExtMethod.instructions.add(new InsnNode(Opcodes.ARETURN));
        target.methods.add(lightExtMethod);

        AxiomMixins.rewrite(target, AxiomMixins.resolve(MOJANG));

        // Verify heightCalls: all 11 calls rewritten to AxiomCubes
        List<MethodInsnNode> heightInsns = methodCalls(heightMethod);
        assertEquals(11, heightInsns.size());
        for (MethodInsnNode call : heightInsns) {
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
            assertFalse(call.itf);
            assertEquals(AxiomMixins.BRIDGE, call.owner);
        }
        assertEquals(List.of("minBuildHeight", "maxBuildHeight", "height", "minSection", "maxSection",
                "sectionsCount", "sectionIndex", "sectionIndexFromSectionY", "sectionYFromSectionIndex",
                "isOutsideBuildHeight", "isOutsideBuildHeight"),
                heightInsns.stream().map(m -> m.name).toList());

        // Verify chunkAndLightCalls: 11 rewritten to AxiomCubes, 3 Level.* calls untouched
        List<MethodInsnNode> chunkInsns = methodCalls(chunkMethod);
        assertEquals(14, chunkInsns.size());
        assertEquals(11, chunkInsns.stream().filter(m -> m.owner.equals(AxiomMixins.BRIDGE)).count());
        assertEquals(List.of("getBlockState", "removeBlockEntity", "setBlockEntity"),
                chunkInsns.stream().filter(m -> m.owner.equals("net/minecraft/world/level/Level")).map(m -> m.name).toList());

        // Verify pendingLight call rewritten to AxiomCubes.pendingLightUpdates(Object, int, int, int)[S
        List<MethodInsnNode> extInsns = methodCalls(lightExtMethod);
        assertEquals(1, extInsns.size());
        assertEquals(Opcodes.INVOKESTATIC, extInsns.get(0).getOpcode());
        assertFalse(extInsns.get(0).itf);
        assertEquals(AxiomMixins.BRIDGE, extInsns.get(0).owner);
        assertEquals("pendingLightUpdates", extInsns.get(0).name);
        assertEquals("(Ljava/lang/Object;III)[S", extInsns.get(0).desc);

        // Emit class bytes with COMPUTE_FRAMES and verify every method with ASM SimpleVerifier
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        target.accept(writer);
        ClassNode verified = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(verified, 0);
        for (MethodNode method : verified.methods) {
            var verifier = new SimpleVerifier(Type.getObjectType(verified.name), Type.getObjectType(verified.superName), false);
            verifier.setClassLoader(AxiomCubes.class.getClassLoader());
            new Analyzer<>(verifier).analyze(verified.name, method);
        }
    }

    @Test void rewritesIntermediaryBytecodeForFabricRelease() {
        ClassNode target = new ClassNode();
        target.version = Opcodes.V21;
        target.access = Opcodes.ACC_PUBLIC;
        target.name = "com/moulberry/axiom/test/FabricSynthetic";
        target.superName = "java/lang/Object";

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fabricCalls", "()V", null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_638", "method_31607", "()I", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2818", "method_38259", "(I)Lnet/minecraft/class_2826;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2826", "method_12256", "(IIILnet/minecraft/class_2680;Z)Lnet/minecraft/class_2680;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2818", "method_8320", "(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2791", "method_8320", "(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_638", "method_8320", "(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/moulberry/axiom/hooks/ServerLevelExt", "axiom$getPendingLightUpdates", "(III)[S", true));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraft/class_638", "method_31607", "()I", false));
        target.methods.add(method);

        AxiomMixins.rewrite(target, AxiomMixins.resolve(INTERMEDIARY));

        List<MethodInsnNode> calls = methodCalls(method);
        assertEquals(AxiomMixins.BRIDGE, calls.get(0).owner);
        assertEquals("minBuildHeight", calls.get(0).name);
        assertEquals("(Lnet/minecraft/class_5539;)I", calls.get(0).desc);

        assertEquals(AxiomMixins.BRIDGE, calls.get(1).owner);
        assertEquals("getSection", calls.get(1).name);
        assertEquals("(Lnet/minecraft/class_2791;I)Lnet/minecraft/class_2826;", calls.get(1).desc);

        assertEquals(AxiomMixins.BRIDGE, calls.get(2).owner);
        assertEquals("setBlockState", calls.get(2).name);
        assertEquals("(Lnet/minecraft/class_2826;IIILnet/minecraft/class_2680;Z)Lnet/minecraft/class_2680;", calls.get(2).desc);

        assertEquals(AxiomMixins.BRIDGE, calls.get(3).owner);
        assertEquals("chunkBlockState", calls.get(3).name);
        assertEquals("(Lnet/minecraft/class_2791;Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", calls.get(3).desc);

        assertEquals(AxiomMixins.BRIDGE, calls.get(4).owner);
        assertEquals("chunkBlockState", calls.get(4).name);
        assertEquals("(Lnet/minecraft/class_2791;Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", calls.get(4).desc);

        // ClientLevel.getBlockState must NOT be rewritten
        assertEquals("net/minecraft/class_638", calls.get(5).owner);
        assertEquals("method_8320", calls.get(5).name);

        // ServerLevelExt.axiom$getPendingLightUpdates -> pendingLightUpdates
        assertEquals(AxiomMixins.BRIDGE, calls.get(6).owner);
        assertEquals("pendingLightUpdates", calls.get(6).name);
        assertEquals("(Ljava/lang/Object;III)[S", calls.get(6).desc);

        // INVOKESPECIAL (super call) must NOT be rewritten
        assertEquals(Opcodes.INVOKESPECIAL, calls.get(7).getOpcode());
        assertEquals("net/minecraft/class_638", calls.get(7).owner);
    }

    private static List<MethodInsnNode> methodCalls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
