package io.earthplatform.minecraft.cubic.compat.axiom;

import io.earthplatform.minecraft.platform.Loader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Axiom mixins only when Axiom is installed (natively on Fabric or via Sinytra
 * Connector on NeoForge), and after they apply, rewrites Axiom's calls that read the world's
 * height and chunk sections directly into calls to {@link AxiomCubes}, which answers for cubic
 * levels and passes everything else through. Axiom touches sections in dozens of places, so one
 * rewrite of each call beats a hook per place.
 */
public final class AxiomMixins implements IMixinConfigPlugin {
    static final String BRIDGE = AxiomCubes.class.getName().replace('.', '/');

    interface NameMapper {
        String className(String intermediary, String mojang);
        String methodName(String intermediaryOwner, String intermediaryName, String intermediaryDesc, String mojang);
    }

    /** Intermediary and Mojang names of one call to rewrite to {@link AxiomCubes}. */
    private record CallSpec(String name, String mojang, String desc, String receiver, String bridge, String owner, String declaring) {}

    /** Resolved runtime names and descriptor for one call rewrite. */
    record RuntimeCall(String name, String desc, String receiver, String bridge, String owner) {}

    private static final String HEIGHT = "net/minecraft/class_5539";
    private static final String BLOCK_GETTER = "net/minecraft/class_1922";
    private static final String CHUNK_ACCESS = "net/minecraft/class_2791";
    private static final String LEVEL_CHUNK = "net/minecraft/class_2818";
    private static final String CHUNK_SECTION = "net/minecraft/class_2826";
    private static final String HEIGHTMAP = "net/minecraft/class_2902";
    private static final String SKY_LIGHT_SOURCES = "net/minecraft/class_8528";
    private static final String LIGHT_ENGINE = "net/minecraft/class_3568";

    private static final Map<String, String> MOJANG_CLASSES = Map.ofEntries(
            Map.entry(HEIGHT, "net/minecraft/world/level/LevelHeightAccessor"),
            Map.entry(BLOCK_GETTER, "net/minecraft/world/level/BlockGetter"),
            Map.entry(CHUNK_ACCESS, "net/minecraft/world/level/chunk/ChunkAccess"),
            Map.entry(LEVEL_CHUNK, "net/minecraft/world/level/chunk/LevelChunk"),
            Map.entry("net/minecraft/class_2818$class_2819", "net/minecraft/world/level/chunk/LevelChunk$EntityCreationType"),
            Map.entry(CHUNK_SECTION, "net/minecraft/world/level/chunk/LevelChunkSection"),
            Map.entry(HEIGHTMAP, "net/minecraft/world/level/levelgen/Heightmap"),
            Map.entry(SKY_LIGHT_SOURCES, "net/minecraft/world/level/lighting/ChunkSkyLightSources"),
            Map.entry(LIGHT_ENGINE, "net/minecraft/world/level/lighting/LevelLightEngine"),
            Map.entry("net/minecraft/class_2338", "net/minecraft/core/BlockPos"),
            Map.entry("net/minecraft/class_2680", "net/minecraft/world/level/block/state/BlockState"),
            Map.entry("net/minecraft/class_2586", "net/minecraft/world/level/block/entity/BlockEntity"),
            Map.entry("net/minecraft/class_4076", "net/minecraft/core/SectionPos"));

    private static CallSpec call(String name, String mojang, String desc, String receiver, String bridge, String owner) {
        return new CallSpec(name, mojang, desc, receiver, bridge, owner, owner != null ? owner : receiver);
    }

    private static final List<CallSpec> CALLS = List.of(
            call("method_31607", "getMinBuildHeight", "()I", HEIGHT, "minBuildHeight", null),
            call("method_31600", "getMaxBuildHeight", "()I", HEIGHT, "maxBuildHeight", null),
            call("method_31605", "getHeight", "()I", HEIGHT, "height", null),
            call("method_32891", "getMinSection", "()I", HEIGHT, "minSection", null),
            call("method_31597", "getMaxSection", "()I", HEIGHT, "maxSection", null),
            call("method_32890", "getSectionsCount", "()I", HEIGHT, "sectionsCount", null),
            call("method_31602", "getSectionIndex", "(I)I", HEIGHT, "sectionIndex", null),
            call("method_31603", "getSectionIndexFromSectionY", "(I)I", HEIGHT, "sectionIndexFromSectionY", null),
            call("method_31604", "getSectionYFromSectionIndex", "(I)I", HEIGHT, "sectionYFromSectionIndex", null),
            call("method_31606", "isOutsideBuildHeight", "(Lnet/minecraft/class_2338;)Z", HEIGHT, "isOutsideBuildHeight", null),
            call("method_31601", "isOutsideBuildHeight", "(I)Z", HEIGHT, "isOutsideBuildHeight", null),
            call("method_38259", "getSection", "(I)Lnet/minecraft/class_2826;", CHUNK_ACCESS, "getSection", null),
            call("method_12256", "setBlockState", "(IIILnet/minecraft/class_2680;Z)Lnet/minecraft/class_2680;", CHUNK_SECTION, "setBlockState", null),
            call("method_8320", "getBlockState", "(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", CHUNK_ACCESS, "chunkBlockState", LEVEL_CHUNK),
            new CallSpec("method_8320", "getBlockState", "(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", CHUNK_ACCESS, "chunkBlockState", CHUNK_ACCESS, BLOCK_GETTER),
            call("method_12201", "getBlockEntity", "(Lnet/minecraft/class_2338;Lnet/minecraft/class_2818$class_2819;)Lnet/minecraft/class_2586;", LEVEL_CHUNK, "chunkBlockEntity", null),
            call("method_12041", "removeBlockEntity", "(Lnet/minecraft/class_2338;)V", LEVEL_CHUNK, "removeBlockEntity", LEVEL_CHUNK),
            call("method_12216", "setBlockEntity", "(Lnet/minecraft/class_2586;)V", LEVEL_CHUNK, "setBlockEntity", LEVEL_CHUNK),
            call("method_31723", "addAndRegisterBlockEntity", "(Lnet/minecraft/class_2586;)V", LEVEL_CHUNK, "addAndRegisterBlockEntity", null),
            call("method_12597", "update", "(IIILnet/minecraft/class_2680;)Z", HEIGHTMAP, "updateHeightmap", null),
            call("method_51536", "update", "(Lnet/minecraft/class_1922;III)Z", SKY_LIGHT_SOURCES, "updateSkyLightSources", null),
            call("method_15551", "updateSectionStatus", "(Lnet/minecraft/class_4076;Z)V", LIGHT_ENGINE, "updateSectionStatus", null),
            // Axiom's own per-section light bookkeeping on ServerLevel, sized to the carrier's sections.
            call("axiom$getPendingLightUpdates", "axiom$getPendingLightUpdates", "(III)[S", "java/lang/Object", "pendingLightUpdates", "com/moulberry/axiom/hooks/ServerLevelExt"));

    /** The same calls in the names this game runs with (intermediary on Fabric release, Mojang's on NeoForge and in dev). */
    private List<RuntimeCall> runtime = List.of();

    @Override public void onLoad(String mixinPackage) {
        if (!Loader.INSTANCE.hasMod("axiom")) return;
        runtime = resolve(new NameMapper() {
            @Override public String className(String intermediary, String mojang) {
                return Loader.INSTANCE.runtimeClassName(intermediary, mojang);
            }

            @Override public String methodName(String intermediaryOwner, String intermediaryName, String intermediaryDesc, String mojang) {
                return Loader.INSTANCE.runtimeMethodName(intermediaryOwner, intermediaryName, intermediaryDesc, mojang);
            }
        });
    }

    static List<RuntimeCall> resolve(NameMapper mapper) {
        return CALLS.stream().map(call -> {
            if (!call.receiver.startsWith("net/minecraft/") && (call.owner == null || !call.owner.startsWith("net/minecraft/"))) {
                return new RuntimeCall(call.name, call.desc, call.receiver, call.bridge, call.owner);
            }
            String name = mapper.methodName(call.declaring.replace('/', '.'), call.name, call.desc, call.mojang);
            return new RuntimeCall(name, mapDesc(mapper, call.desc), mapClass(mapper, call.receiver), call.bridge,
                    call.owner == null ? null : mapClass(mapper, call.owner));
        }).toList();
    }

    private static String mapClass(NameMapper mapper, String intermediary) {
        String mojang = MOJANG_CLASSES.get(intermediary);
        if (mojang == null) return intermediary;
        return mapper.className(intermediary.replace('/', '.'), mojang.replace('/', '.')).replace('.', '/');
    }

    private static String mapDesc(NameMapper mapper, String desc) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < desc.length(); i++) {
            char c = desc.charAt(i);
            out.append(c);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                out.append(mapClass(mapper, desc.substring(i + 1, end))).append(';');
                i = end;
            }
        }
        return out.toString();
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return !runtime.isEmpty(); }

    @Override public void postApply(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo mixinInfo) {
        if (!mixinClassName.endsWith(".AxiomTargetsMixin")) return;
        rewrite(target, runtime);
    }

    static void rewrite(ClassNode target, List<RuntimeCall> calls) {
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) continue;
                for (RuntimeCall c : calls) {
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
