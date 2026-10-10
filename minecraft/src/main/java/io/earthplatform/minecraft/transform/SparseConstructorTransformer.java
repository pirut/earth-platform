package io.earthplatform.minecraft.transform;

import java.util.ArrayList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pins the two array-length sites before allocation, including EmptyLevelChunk's superclass path. */
public final class SparseConstructorTransformer {
    private static final String TARGET = "net/minecraft/world/level/chunk/ChunkAccess";
    private static final String ACCESSOR = "net/minecraft/world/level/LevelHeightAccessor";
    private static final String SECTION = "net/minecraft/world/level/chunk/LevelChunkSection";
    private static final String CTOR = "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;L" + ACCESSOR
            + ";Lnet/minecraft/core/Registry;J[L" + SECTION + ";Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V";
    private SparseConstructorTransformer() {}
    public static void apply(ClassNode target) {
        if (!TARGET.equals(target.name)) throw new IllegalArgumentException("Unexpected sparse constructor target " + target.name);
        var methods = target.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(CTOR)).toList();
        if (methods.size() != 1) throw new IllegalStateException("Pinned ChunkAccess constructor missing or duplicated");
        MethodNode constructor = methods.getFirst();
        var sites = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(ACCESSOR)
                    && call.name.equals("getSectionsCount") && call.desc.equals("()I")) sites.add(call);
        }
        if (sites.size() != 2) throw new IllegalStateException("Expected exactly two unpatched constructor section counts, found " + sites.size());
        String[] fields = {"sections", "postProcessing"};
        String[] components = {SECTION, "it/unimi/dsi/fastutil/shorts/ShortList"};
        for (int index = 0; index < 2; index++) {
            var call = sites.get(index);
            var before = significant(call, false); var allocation = significant(call, true); var assignment = significant(allocation, true);
            if (call.getOpcode() != Opcodes.INVOKEINTERFACE || !(before instanceof VarInsnNode arg)
                    || arg.getOpcode() != Opcodes.ALOAD || arg.var != 3
                    || !(allocation instanceof TypeInsnNode array) || array.getOpcode() != Opcodes.ANEWARRAY || !array.desc.equals(components[index])
                    || !(assignment instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(TARGET) || !field.name.equals(fields[index]) || !field.desc.equals("[L" + components[index] + ";")) {
                throw new IllegalStateException("Unexpected constructor array shape at " + fields[index] + "; refusing partial sparse patch");
            }
        }
        for (MethodInsnNode call : sites) {
            constructor.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 7));
            constructor.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
            constructor.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "io/earthplatform/minecraft/residency/SparseFacadeHooks", "constructorSectionCount",
                    "(L" + ACCESSOR + ";[L" + SECTION + ";L" + TARGET + ";)I", false));
        }
        constructor.maxStack += 2;
    }
    private static AbstractInsnNode significant(AbstractInsnNode node, boolean next) {
        do { node = next ? node.getNext() : node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        if (node == null) throw new IllegalStateException("Truncated sparse constructor bytecode");
        return node;
    }
}
