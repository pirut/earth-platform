package io.earthplatform.minecraft.transform;

import java.util.ArrayList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact pinned-version repair of the two section-Y serialization sites, with fail-fast shape checks. */
public final class SectionYTransformer {
    private static final String TAG = "net/minecraft/nbt/CompoundTag";
    private static final String CODEC = "io/earthplatform/minecraft/storage/SectionYCodec";
    private static final String READ_DESCRIPTOR = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/ProtoChunk;";
    private static final String WRITE_DESCRIPTOR = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;";
    private record Site(MethodNode method, MethodInsnNode call) {}
    private SectionYTransformer() {}

    public static void apply(ClassNode target) {
        if (!"net/minecraft/world/level/chunk/storage/ChunkSerializer".equals(target.name)) {
            throw new IllegalArgumentException("Unexpected serializer target " + target.name);
        }
        var reads = new ArrayList<Site>();
        var writes = new ArrayList<Site>();
        for (MethodNode method : target.methods) for (AbstractInsnNode node : method.instructions) {
            if (!(node instanceof MethodInsnNode call) || !TAG.equals(call.owner)) continue;
            if ("read".equals(method.name) && READ_DESCRIPTOR.equals(method.desc) && "getByte".equals(call.name) && "(Ljava/lang/String;)B".equals(call.desc)) reads.add(new Site(method, call));
            if ("write".equals(method.name) && WRITE_DESCRIPTOR.equals(method.desc) && "putByte".equals(call.name) && "(Ljava/lang/String;B)V".equals(call.desc)) writes.add(new Site(method, call));
        }
        if (reads.size() != 1 || writes.size() != 1) throw new IllegalStateException("Unsupported ChunkSerializer bytecode: expected exactly one Y read/write, found " + reads.size() + "/" + writes.size());
        Site read = reads.getFirst(), write = writes.getFirst();
        if (!(previousInstruction(read.call()) instanceof LdcInsnNode readKey) || !"Y".equals(readKey.cst)) {
            throw new IllegalStateException("Unexpected section-Y read key");
        }
        AbstractInsnNode cast = previousInstruction(write.call());
        AbstractInsnNode value = previousInstruction(cast);
        AbstractInsnNode key = previousInstruction(value);
        if (cast.getOpcode() != Opcodes.I2B || value.getOpcode() != Opcodes.ILOAD
                || !(key instanceof LdcInsnNode writeKey) || !"Y".equals(writeKey.cst)) {
            throw new IllegalStateException("Unexpected section-Y write shape; refusing a lossy partial patch");
        }
        read.method().instructions.set(read.call(), new MethodInsnNode(Opcodes.INVOKESTATIC, CODEC, "read", "(L" + TAG + ";Ljava/lang/String;)I", false));
        write.method().instructions.remove(cast);
        write.method().instructions.set(write.call(), new MethodInsnNode(Opcodes.INVOKESTATIC, CODEC, "write", "(L" + TAG + ";Ljava/lang/String;I)V", false));
    }
    private static AbstractInsnNode previousInstruction(AbstractInsnNode node) {
        if (node == null) throw new IllegalStateException("Truncated section-Y bytecode");
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        if (node == null) throw new IllegalStateException("Truncated section-Y bytecode");
        return node;
    }
}
