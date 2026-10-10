package io.earthplatform.minecraft.transform;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Only the exact record-constructor comparison is routed; codecs and global constants stay unchanged. */
public final class SparseDimensionTransformer {
    private static final String TARGET = "net/minecraft/world/level/dimension/DimensionType";
    private static final String CTOR = "(Ljava/util/OptionalLong;ZZZZDZZIIILnet/minecraft/tags/TagKey;Lnet/minecraft/resources/ResourceLocation;FLnet/minecraft/world/level/dimension/DimensionType$MonsterSettings;)V";
    private SparseDimensionTransformer() {}
    public static void apply(ClassNode target) {
        if (!TARGET.equals(target.name)) throw new IllegalArgumentException("Unexpected dimension target " + target.name);
        var constructors = target.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(CTOR)).toList();
        if (constructors.size() != 1) throw new IllegalStateException("Pinned DimensionType constructor unavailable");
        MethodNode method = constructors.getFirst();
        FieldInsnNode comparison = null;
        int maxReads = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(TARGET) && field.name.equals("MAX_Y") && field.desc.equals("I")) {
                maxReads++;
                AbstractInsnNode one = next(field), sum = next(one), branch = next(sum);
                if (one.getOpcode() != Opcodes.ICONST_1 || sum.getOpcode() != Opcodes.IADD) {
                    throw new IllegalStateException("Unexpected dimension bound expression");
                }
                if (branch.getOpcode() == Opcodes.IF_ICMPLE) {
                    if (comparison != null) throw new IllegalStateException("Duplicate dimension bound branch");
                    AbstractInsnNode inputSum = previous(field), inputHeight = previous(inputSum), inputMin = previous(inputHeight);
                    if (inputSum.getOpcode() != Opcodes.IADD || !(inputHeight instanceof VarInsnNode height)
                            || height.getOpcode() != Opcodes.ILOAD || height.var != 11 || !(inputMin instanceof VarInsnNode min)
                            || min.getOpcode() != Opcodes.ILOAD || min.var != 10) throw new IllegalStateException("Unexpected dimension constructor argument layout");
                    comparison = field;
                } else if (!(branch instanceof InvokeDynamicInsnNode)) {
                    throw new IllegalStateException("Unexpected dimension error-message bound use");
                }
            }
        }
        if (maxReads != 2 || comparison == null) throw new IllegalStateException("Expected exactly one bound comparison and one error-message MAX_Y use");
        for (int local : new int[]{10, 11, 12}) method.instructions.insertBefore(comparison, new VarInsnNode(Opcodes.ILOAD, local));
        method.instructions.set(comparison, new MethodInsnNode(Opcodes.INVOKESTATIC,
                "io/earthplatform/minecraft/fixture/SparseFixtureDimensions", "maximumY", "(III)I", false));
        method.maxStack += 3;
    }
    private static AbstractInsnNode next(AbstractInsnNode node) { return step(node, true); }
    private static AbstractInsnNode previous(AbstractInsnNode node) { return step(node, false); }
    private static AbstractInsnNode step(AbstractInsnNode node, boolean forward) {
        do { node = forward ? node.getNext() : node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        if (node == null) throw new IllegalStateException("Truncated dimension constructor");
        return node;
    }
}
