package io.earthplatform.minecraft.fabric.axiom;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Axiom mixins only when Axiom is installed, and after they apply, rewrites Axiom's
 * calls that read the world's height and chunk sections directly into calls to {@link AxiomCubes},
 * which answers for cubic levels and passes everything else through. Axiom touches sections in
 * dozens of places, so one rewrite of each call beats a hook per place.
 */
public final class AxiomMixins implements IMixinConfigPlugin {
    private static final String BRIDGE = AxiomCubes.class.getName().replace('.', '/');

    /** Intermediary name and descriptor (receiver left out), the receiver type the bridge takes, the bridge method, and the only owner to match (null: any). */
    private record Call(String name, String desc, String receiver, String bridge, String owner) {}

    private static final String HEIGHT = "net/minecraft/class_5539";
    private static final List<Call> CALLS = List.of(
            new Call("method_31607", "()I", HEIGHT, "minBuildHeight", null),
            new Call("method_31600", "()I", HEIGHT, "maxBuildHeight", null),
            new Call("method_31605", "()I", HEIGHT, "height", null),
            new Call("method_32891", "()I", HEIGHT, "minSection", null),
            new Call("method_31597", "()I", HEIGHT, "maxSection", null),
            new Call("method_32890", "()I", HEIGHT, "sectionsCount", null),
            new Call("method_31602", "(I)I", HEIGHT, "sectionIndex", null),
            new Call("method_31603", "(I)I", HEIGHT, "sectionIndexFromSectionY", null),
            new Call("method_31604", "(I)I", HEIGHT, "sectionYFromSectionIndex", null),
            new Call("method_31606", "(Lnet/minecraft/class_2338;)Z", HEIGHT, "isOutsideBuildHeight", null),
            new Call("method_31601", "(I)Z", HEIGHT, "isOutsideBuildHeight", null),
            new Call("method_38259", "(I)Lnet/minecraft/class_2826;", "net/minecraft/class_2791", "getSection", null),
            new Call("method_12256", "(IIILnet/minecraft/class_2680;Z)Lnet/minecraft/class_2680;", "net/minecraft/class_2826", "setBlockState", null),
            new Call("method_8320", "(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", "net/minecraft/class_2818", "chunkBlockState", "net/minecraft/class_2818"),
            new Call("method_12201", "(Lnet/minecraft/class_2338;Lnet/minecraft/class_2818$class_2819;)Lnet/minecraft/class_2586;", "net/minecraft/class_2818", "chunkBlockEntity", null),
            new Call("method_12041", "(Lnet/minecraft/class_2338;)V", "net/minecraft/class_2818", "removeBlockEntity", "net/minecraft/class_2818"),
            new Call("method_12216", "(Lnet/minecraft/class_2586;)V", "net/minecraft/class_2818", "setBlockEntity", "net/minecraft/class_2818"),
            new Call("method_31723", "(Lnet/minecraft/class_2586;)V", "net/minecraft/class_2818", "addAndRegisterBlockEntity", null),
            new Call("method_12597", "(IIILnet/minecraft/class_2680;)Z", "net/minecraft/class_2902", "updateHeightmap", null),
            new Call("method_51536", "(Lnet/minecraft/class_1922;III)Z", "net/minecraft/class_8528", "updateSkyLightSources", null),
            new Call("method_15551", "(Lnet/minecraft/class_4076;Z)V", "net/minecraft/class_3568", "updateSectionStatus", null),
            // Axiom's own per-section light bookkeeping on ServerLevel, sized to the carrier's sections.
            new Call("axiom$getPendingLightUpdates", "(III)[S", "java/lang/Object", "pendingLightUpdates", "com/moulberry/axiom/hooks/ServerLevelExt"));

    /** The same calls in the names this game runs with (intermediary in a release, Mojang's in development). */
    private List<Call> runtime = List.of();

    @Override public void onLoad(String mixinPackage) {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) return;
        MappingResolver names = FabricLoader.getInstance().getMappingResolver();
        runtime = CALLS.stream().map(call -> {
            if (!call.receiver.startsWith("net/minecraft/") && (call.owner == null || !call.owner.startsWith("net/minecraft/"))) return call;
            String owner = call.owner != null ? call.owner : call.receiver;
            String name = names.mapMethodName("intermediary", owner.replace('/', '.'), call.name, call.desc);
            return new Call(name, mapDesc(names, call.desc), mapClass(names, call.receiver), call.bridge,
                    call.owner == null ? null : mapClass(names, call.owner));
        }).toList();
    }

    private static String mapClass(MappingResolver names, String internal) {
        return names.mapClassName("intermediary", internal.replace('/', '.')).replace('.', '/');
    }

    private static String mapDesc(MappingResolver names, String desc) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < desc.length(); i++) {
            char c = desc.charAt(i);
            out.append(c);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                out.append(mapClass(names, desc.substring(i + 1, end))).append(';');
                i = end;
            }
        }
        return out.toString();
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return !runtime.isEmpty(); }

    @Override public void postApply(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo mixinInfo) {
        if (!mixinClassName.endsWith(".AxiomTargetsMixin")) return;
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) continue;
                for (Call c : runtime) {
                    if (!c.name.equals(call.name) || !c.desc.equals(call.desc) || (c.owner != null && !c.owner.equals(call.owner))) continue;
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.itf = false;
                    call.desc = "(L" + c.receiver + ";" + call.desc.substring(1);
                    call.owner = BRIDGE;
                    call.name = c.bridge;
                    break;
                }
            }
        }
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo mixinInfo) {}
}
