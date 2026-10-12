package io.earthplatform.compat.neoforge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Original structural admission guard; no upstream implementation is embedded. */
public final class PinnedTempScanContract {
    public static final String TARGET = "net/neoforged/neoforge/common/IOUtilities";
    private static final Map<String,String> EXPECTED = Map.of(
        "tryListTempFiles", "f7a8eda0986e958fa3eafd479491773e8e789b1b591a7360157852de7339b024",
        "tryCleanupTempFiles", "fefd5c42305316a30dff308dbea298f6411e65682463956cdb6ccf8c5c07f081",
        "createPredicate", "1a929d9d6d4c4d7d67917d5aa2e55bf0523b5c82443915b42235ad5d66a1fb16",
        "lambda$createPredicate$0", "d19ea51eafd3071470864fd18ba4394cbebc786c9b2527d5177fa5b01cd4a1b6");
    private PinnedTempScanContract() {}
    public static void verify(ClassNode node) {
        if (!TARGET.equals(node.name)) throw new IllegalStateException("Unexpected temporary-file shim target: "+node.name);
        Map<String,String> actual=fingerprints(node);
        if (!EXPECTED.equals(actual)) throw new IllegalStateException("Unsupported NeoForge temporary-file traversal/predicate bytecode: "+actual);
    }
    public static Map<String,String> fingerprints(ClassNode node) {
        Map<String,String> result=new TreeMap<>();
        for(MethodNode method:node.methods) if(EXPECTED.containsKey(method.name)) {
            if(result.put(method.name,digest(canonical(method)))!=null)throw new IllegalStateException("Duplicate pinned method: "+method.name);
        }
        return Map.copyOf(result);
    }
    private static String canonical(MethodNode method) {
        StringBuilder out=new StringBuilder(method.name).append('|').append(method.desc).append('|').append(method.access).append('\n');
        IdentityHashMap<AbstractInsnNode,Integer> positions=new IdentityHashMap<>(); int position=0;
        for(AbstractInsnNode insn:method.instructions){positions.put(insn,position);if(insn.getOpcode()>=0)position++;}
        for(AbstractInsnNode i:method.instructions) {
            if(i.getOpcode()<0)continue;
            out.append(i.getOpcode()).append(':');
            if(i instanceof VarInsnNode n)out.append(n.var);
            else if(i instanceof IntInsnNode n)out.append(n.operand);
            else if(i instanceof TypeInsnNode n)out.append(n.desc);
            else if(i instanceof FieldInsnNode n)out.append(n.owner).append('|').append(n.name).append('|').append(n.desc);
            else if(i instanceof MethodInsnNode n)out.append(n.owner).append('|').append(n.name).append('|').append(n.desc).append('|').append(n.itf);
            else if(i instanceof InvokeDynamicInsnNode n){out.append(n.name).append('|').append(n.desc).append('|').append(constant(n.bsm));for(Object arg:n.bsmArgs)out.append('|').append(constant(arg));}
            else if(i instanceof LdcInsnNode n)out.append(constant(n.cst));
            else if(i instanceof JumpInsnNode n)out.append(positions.get(n.label));
            else if(i instanceof IincInsnNode n)out.append(n.var).append('|').append(n.incr);
            else if(i instanceof TableSwitchInsnNode n){out.append(n.min).append('|').append(n.max).append('|').append(positions.get(n.dflt));for(LabelNode label:n.labels)out.append('|').append(positions.get(label));}
            else if(i instanceof LookupSwitchInsnNode n){out.append(n.keys).append('|').append(positions.get(n.dflt));for(LabelNode label:n.labels)out.append('|').append(positions.get(label));}
            else if(i instanceof MultiANewArrayInsnNode n)out.append(n.desc).append('|').append(n.dims);
            else if(!(i instanceof InsnNode))throw new IllegalStateException("Unknown instruction in pinned traversal");
            out.append('\n');
        }
        for(TryCatchBlockNode block:method.tryCatchBlocks)out.append("catch:").append(positions.get(block.start)).append('|').append(positions.get(block.end)).append('|').append(positions.get(block.handler)).append('|').append(block.type).append('\n');
        return out.toString();
    }
    private static String constant(Object value) {
        if(value instanceof Handle h)return "H:"+h.getTag()+":"+h.getOwner()+":"+h.getName()+":"+h.getDesc()+":"+h.isInterface();
        if(value instanceof Type t)return "T:"+t.getDescriptor();
        if(value instanceof String s)return "S:"+s.length()+":"+s;
        if(value instanceof Number)return value.getClass().getName()+":"+value;
        throw new IllegalStateException("Unsupported pinned constant: "+value);
    }
    private static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
