package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.MapItem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@code CubicMapItemMixin} wraps single call sites of vanilla's {@code MapItem.update}. This counts
 * those call sites in the vanilla bytecode and checks that each wrap states, with {@code require} and
 * {@code expect}, exactly the number it selects, so a vanilla change that loses or adds one fails the
 * build here and the mixin's application there, rather than leaving a map grey with nothing said.
 */
class CubicMapItemMixinTest {
    private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

    private static ClassNode read(String resource) throws Exception {
        try (InputStream in = CubicMapItemMixinTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    /** The calls {@code target} ({@code Lowner;name(desc)ret}) in vanilla's {@code update}, in order. */
    private static int callSites(MethodNode update, String target) {
        String owner = target.substring(1, target.indexOf(';')), rest = target.substring(target.indexOf(';') + 1);
        String name = rest.substring(0, rest.indexOf('(')), desc = rest.substring(rest.indexOf('('));
        int count = 0;
        for (AbstractInsnNode insn : update.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) count++;
        }
        return count;
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Test void everyWrapStatesExactlyTheCallSitesItSelects() throws Exception {
        MethodNode update = null;
        for (MethodNode method : read(MapItem.class.getName().replace('.', '/') + ".class").methods) {
            if (method.name.equals("update") && method.desc.startsWith("(Lnet/minecraft/world/level/Level;")) update = method;
        }
        assertNotNull(update, "MapItem.update");

        ClassNode mixin = read("io/earthplatform/minecraft/cubic/mixin/CubicMapItemMixin.class");
        int wraps = 0;
        // Each target's call sites, taken by the wraps that name them, with the ordinals that did.
        java.util.Map<String, List<Integer>> taken = new java.util.HashMap<>();
        for (MethodNode method : mixin.methods) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
            if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
            for (AnnotationNode wrap : annotations) {
                if (!wrap.desc.equals(WRAP)) continue;
                wraps++;
                int selected = 0;
                for (AnnotationNode at : (List<AnnotationNode>) value(wrap, "at")) {
                    String target = (String) value(at, "target");
                    int sites = callSites(update, target);
                    Object ordinal = value(at, "ordinal");
                    assertFalse(sites == 0, method.name + " targets " + target + ", which vanilla's update does not call");
                    if (ordinal == null) {
                        selected += sites;
                        for (int i = 0; i < sites; i++) taken.computeIfAbsent(target, t -> new ArrayList<>()).add(i);
                    } else {
                        assertFalse((Integer) ordinal >= sites, method.name + " takes ordinal " + ordinal + " of " + sites + " calls of " + target);
                        selected++;
                        taken.computeIfAbsent(target, t -> new ArrayList<>()).add((Integer) ordinal);
                    }
                }
                assertEquals(selected, value(wrap, "require"), method.name + " require");
                assertEquals(selected, value(wrap, "expect"), method.name + " expect");
            }
        }
        assertFalse(wraps == 0);
        // And every call site of a target is wrapped by exactly one wrap: none is left to vanilla's own reads, none read twice.
        for (var entry : taken.entrySet()) {
            List<Integer> ordinals = entry.getValue();
            assertEquals(callSites(update, entry.getKey()), ordinals.size(), entry.getKey() + " call sites wrapped");
            assertEquals(ordinals.size(), ordinals.stream().distinct().count(), entry.getKey() + " is wrapped twice at one site");
        }
    }
}
