package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Compares a loaded native graph to a construction-proven, original-codec reference.
 * No method on an unqualified actual object is invoked. In particular class identity alone is
 * insufficient for a cached predicate: its complete captured graph is checked, including the
 * synthetic outer reference of a singleton loot entry and the composed native child closures.
 */
final class NativeBlockLootGraph {
    private static final Set<String> LISTS = Set.of("java.util.ArrayList", "java.util.Arrays$ArrayList", "java.util.Collections$EmptyList",
            "java.util.Collections$SingletonList", "java.util.ImmutableCollections$ListN", "java.util.ImmutableCollections$List12",
            "com.google.common.collect.RegularImmutableList", "com.google.common.collect.SingletonImmutableList");
    private static final Set<String> MAPS = Set.of("java.util.HashMap", "java.util.Collections$EmptyMap", "java.util.ImmutableCollections$MapN",
            "java.util.ImmutableCollections$Map1", "com.google.common.collect.RegularImmutableMap",
            "com.google.common.collect.SingletonImmutableBiMap");
    private static final Set<String> LAMBDA_OWNERS = Set.of("net.minecraft.Util",
            "net.minecraft.world.level.storage.loot.functions.LootItemFunctions",
            "net.minecraft.world.level.storage.loot.entries.ComposableEntryContainer",
            "net.minecraft.world.level.storage.loot.entries.AlternativesEntry");
    private final IdentityHashMap<Object,Object> paired = new IdentityHashMap<>();
    private final IdentityHashMap<Object,Object> inverse = new IdentityHashMap<>();
    private int visits;
    private final ResourceLocation expectedId;
    private NativeBlockLootGraph(ResourceLocation expectedId) { this.expectedId = expectedId; }
    static void requireEquivalent(LootTable actual, LootTable reference, ResourceLocation id) {
        new NativeBlockLootGraph(id).visit(actual, reference, 0);
    }
    private void visit(Object actual, Object expected, int depth) {
        if (++visits > 1024 || depth > 48) throw refused("Native loot graph exceeds bounded source census");
        if (actual == expected) return; // Original decoded registry/static singleton, not caller-supplied reference.
        if (actual == null || expected == null || actual.getClass() != expected.getClass())
            throw refused("Loaded loot graph concrete type/identity differs from native resource");
        Class<?> type = expected.getClass();
        if (type == String.class || type == Integer.class || type == Long.class || type == Float.class
                || type == Double.class || type == Boolean.class || type == Byte.class || type == Short.class
                || type == Character.class || type == ResourceLocation.class) {
            if (!actual.equals(expected)) throw refused("Loaded native loot scalar changed");
            return;
        }
        if (type.isEnum() || type == Class.class) throw refused("Loaded native loot constant identity changed");
        Object previous = paired.putIfAbsent(actual, expected);
        if (previous != null) {
            if (previous != expected) throw refused("Loaded native loot graph aliases a different source node");
            return;
        }
        Object other = inverse.putIfAbsent(expected, actual);
        if (other != null && other != actual) throw refused("Loaded native loot cache broke source aliasing");
        if (type == Optional.class) {
            var a = (Optional<?>) actual; var e = (Optional<?>) expected;
            if (a.isPresent() != e.isPresent()) throw refused("Loaded native loot optional changed");
            if (a.isPresent()) visit(a.get(), e.get(), depth + 1);
            return;
        }
        if (actual instanceof List<?>) {
            if (!LISTS.contains(type.getName())) throw refused("Unqualified native loot list implementation");
            List<?> a = (List<?>) actual, e = (List<?>) expected;
            if (a.size() != e.size() || a.size() > 64) throw refused("Loaded native loot list shape changed");
            for (int i = 0; i < e.size(); i++) visit(a.get(i), e.get(i), depth + 1);
            return;
        }
        if (actual instanceof Map<?,?>) {
            if (!MAPS.contains(type.getName())) throw refused("Unqualified native loot map implementation");
            Map<?,?> a = (Map<?,?>) actual, e = (Map<?,?>) expected;
            if (a.size() != e.size() || a.size() > 16) throw refused("Loaded native loot map shape changed");
            // No hashCode/equals from a native predicate key is invoked.
            var ai = a.entrySet().iterator(); var ei = e.entrySet().iterator();
            while (ei.hasNext()) {
                var av = ai.next(); var ev = ei.next();
                if (av.getKey() != ev.getKey()) throw refused("Loaded native loot predicate-key identity changed");
                visit(av.getValue(), ev.getValue(), depth + 1);
            }
            return;
        }
        if (type.isHidden() && type.isSynthetic()) {
            Class<?> nest = type.getNestHost();
            if (nest.getClassLoader() != LootTable.class.getClassLoader() || !LAMBDA_OWNERS.contains(nest.getName()))
                throw refused("Unqualified native loot cached closure provenance");
            NativeBlockBreakPins.require(nest);
            // Hidden-class names are not a lambda identity contract. Exact actual/reference Class
            // identity above proves the native construction callsite; every capture is still visited below.
        } else NativeBlockBreakPins.require(type);
        // The exact concrete native record is pinned above. Record is the JDK fieldless
        // hierarchy terminus, never an admitted native graph node or a substitute for its fields.
        for (Class<?> owner = type; owner != Object.class && owner != Record.class; owner = owner.getSuperclass()) {
            if (owner == null) throw refused("Unexpected native loot graph hierarchy");
            if (owner != type) NativeBlockBreakPins.require(owner);
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object a = read(field, actual), e = read(field, expected);
                // Native loader bookkeeping is not executed by getRandomItems. Admit only its exact safe scalar domain.
                if ((owner == LootTable.class || owner == LootPool.class) && field.getName().equals("isFrozen")) {
                    if (field.getType() != boolean.class) throw refused("Native loot frozen descriptor changed");
                    continue;
                }
                if (owner == LootTable.class && field.getName().equals("lootTableId")) {
                    if (a != null && !expectedId.equals(a)) throw refused("Loaded native loot table ID changed");
                    continue;
                }
                if (owner == LootPool.class && field.getName().equals("name")) {
                    if (a != null && (!(a instanceof String name) || name.length() > 64))
                        throw refused("Native loot pool name is outside the bounded scalar profile");
                    continue;
                }
                visit(a, e, depth + 1);
            }
        }
    }
    private static Object read(Field field, Object target) {
        try {
            if (!field.trySetAccessible()) throw refused("Native loot source field is inaccessible: " + field.getName());
            return field.get(target);
        } catch (IllegalAccessException | SecurityException failure) {
            throw refused("Native loot source field inspection failed");
        }
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
