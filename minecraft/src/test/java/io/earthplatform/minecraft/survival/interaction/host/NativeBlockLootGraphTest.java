package io.earthplatform.minecraft.survival.interaction.host;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** No actual loot callback is needed to reject a changed loaded graph or swapped cached function. */
class NativeBlockLootGraphTest {
    private static HolderLookup.Provider registries;
    @org.junit.jupiter.api.BeforeAll static void initialize() { registries = net.minecraft.data.registries.VanillaRegistries.createLookup(); }
    private static LootTable dirt() {
        return LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(Items.DIRT))).build();
    }
    @Test void originalConstructedGraphAndNativeCachesCompareWithoutLootExecution() {
        assertDoesNotThrow(()->NativeBlockLootGraph.requireEquivalent(dirt(),dirt(),ResourceLocation.withDefaultNamespace("blocks/dirt")));
    }
    @Test void exactPinnedNativeRecordTraversesOwnFieldsButStopsAtJdkRecord() {
        assertTrue(ConstantValue.class.isRecord());
        assertSame(Record.class, ConstantValue.class.getSuperclass());
        NativeBlockBreakPins.require(ConstantValue.class);
        var id=ResourceLocation.withDefaultNamespace("blocks/dirt");
        assertDoesNotThrow(()->NativeBlockLootGraph.requireEquivalent(dirt(),dirt(),id));
        var changed=LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(2))
                .add(LootItem.lootTableItem(Items.DIRT))).build();
        assertThrows(SurvivalRefusal.class,()->NativeBlockLootGraph.requireEquivalent(changed,dirt(),id));
    }
    @Test void differentNativeOutputAndRollCountRefuseBeforeEvaluation() {
        var id=ResourceLocation.withDefaultNamespace("blocks/dirt");
        var expected=dirt();
        var changedItem=LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(Items.STONE))).build();
        var changedRoll=LootTable.lootTable().withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(2))
                .add(LootItem.lootTableItem(Items.DIRT))).build();
        assertThrows(SurvivalRefusal.class,()->NativeBlockLootGraph.requireEquivalent(changedItem,expected,id));
        assertThrows(SurvivalRefusal.class,()->NativeBlockLootGraph.requireEquivalent(changedRoll,expected,id));
    }
    @Test void opaqueCachedLambdaCannotBecomeAuthorityEvenWithIdenticalDeclarativeData() throws Exception {
        var actual=dirt();var expected=dirt();boolean[] invoked={false};
        var field=LootTable.class.getDeclaredField("compositeFunction");assertTrue(field.trySetAccessible());
        java.util.function.BiFunction<net.minecraft.world.item.ItemStack,net.minecraft.world.level.storage.loot.LootContext,
                net.minecraft.world.item.ItemStack> hostile=(stack,context)->{invoked[0]=true;return stack;};
        field.set(actual,hostile);
        assertThrows(SurvivalRefusal.class,()->NativeBlockLootGraph.requireEquivalent(actual,expected,
                ResourceLocation.withDefaultNamespace("blocks/dirt")));
        assertFalse(invoked[0]);
    }

    @Test void originalGrassAndDirtResourcesCompareThroughNativeDecodersAndGrassClosure() throws Exception {
        for (String name : List.of("grass_block", "dirt")) {
            var actual = nativeTable(name);
            var reference = nativeTable(name);
            if (name.equals("grass_block")) {
                Object a = composedChildren(firstEntry(actual));
                Object e = composedChildren(firstEntry(reference));
                assertNotSame(a, e);
                assertSame(a.getClass(), e.getClass());
                assertNativeComposedClosure(a);
                assertNativeComposedClosure(e);
                // Do not depend on VM-generated class names or captured-field names/order.
                List<?> children = children(firstEntry(actual));
                int captures = 0;
                for (Field field : a.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    assertTrue(field.trySetAccessible());
                    Object captured = field.get(a);
                    assertTrue(children.stream().anyMatch(child -> child == captured));
                    captures++;
                }
                assertEquals(2, captures);
            }
            assertDoesNotThrow(() -> NativeBlockLootGraph.requireEquivalent(actual, reference, lootId(name)));
        }
    }

    @Test void sameCallsiteCachedClosureWithAlteredCapturedChildRefusesUnchangedDeclaredGraph() throws Exception {
        var actual = nativeTable("grass_block");
        var reference = nativeTable("grass_block");
        Object entry = firstEntry(actual);
        List<?> declaredChildren = children(entry);
        assertDoesNotThrow(() -> NativeBlockLootGraph.requireEquivalent(actual, reference, lootId("grass_block")));

        // Hidden closure captures are final. Obtain a different capture via the same original
        // decoder/callsite, then substitute only the cache; do not modify actual's declared children.
        JsonObject changedJson = originalJson("grass_block");
        changedJson.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries")
                .get(0).getAsJsonObject().getAsJsonArray("children").get(0).getAsJsonObject()
                .addProperty("weight", 2);
        Object changedEntry = firstEntry(decode(changedJson));
        Object changedClosure = composedChildren(changedEntry);
        assertNativeComposedClosure(changedClosure);
        assertSame(composedChildren(entry).getClass(), changedClosure.getClass());
        assertEquals(2, read(LootPoolSingletonContainer.class, children(changedEntry).getFirst(), "weight"));
        write(CompositeEntryBase.class, entry, "composedChildren", changedClosure);
        assertSame(declaredChildren, children(entry));
        assertEquals(1, read(LootPoolSingletonContainer.class, declaredChildren.getFirst(), "weight"));

        assertThrows(SurvivalRefusal.class,
                () -> NativeBlockLootGraph.requireEquivalent(actual, reference, lootId("grass_block")));
    }

    @Test void sameCallsiteCacheCannotSubstituteIndependentButEqualCapturedGraph() throws Exception {
        var actual = nativeTable("grass_block");
        var reference = nativeTable("grass_block");
        Object entry = firstEntry(actual);
        Object independentCache = composedChildren(firstEntry(nativeTable("grass_block")));
        assertSame(composedChildren(entry).getClass(), independentCache.getClass());
        write(CompositeEntryBase.class, entry, "composedChildren", independentCache);
        var refusal = assertThrows(SurvivalRefusal.class,
                () -> NativeBlockLootGraph.requireEquivalent(actual, reference, lootId("grass_block")));
        assertTrue(refusal.getMessage().contains("alias"));
    }

    @Test void differentCallsiteFromSamePinnedNestHostRefusesBeforeEvaluation() throws Exception {
        var actual = nativeTable("grass_block");
        var reference = nativeTable("grass_block");
        Object entry = firstEntry(actual);
        Object original = composedChildren(entry);
        Class<?> nativeHost = original.getClass().getNestHost();
        var and = nativeHost.getDeclaredMethod("and", nativeHost);
        assertTrue(and.trySetAccessible());
        List<?> children = children(entry);
        Object changed = and.invoke(children.getFirst(), children.getLast());
        assertNativeComposedClosure(changed);
        assertSame(nativeHost, changed.getClass().getNestHost());
        assertNotSame(original.getClass(), changed.getClass());
        write(CompositeEntryBase.class, entry, "composedChildren", changed);
        var refusal = assertThrows(SurvivalRefusal.class,
                () -> NativeBlockLootGraph.requireEquivalent(actual, reference, lootId("grass_block")));
        assertTrue(refusal.getMessage().contains("concrete type/identity"));
    }

    @Test void sameClassHiddenSyntheticCacheWithForeignNestHostRefusesWithoutInvocation() throws Exception {
        var actual = dirt();
        var reference = dirt();
        boolean[] actualInvoked = {false}, referenceInvoked = {false};
        var a = foreignCache(actualInvoked);
        var e = foreignCache(referenceInvoked);
        assertNotSame(a, e);
        assertSame(a.getClass(), e.getClass());
        assertTrue(a.getClass().isHidden());
        assertTrue(a.getClass().isSynthetic());
        assertSame(NativeBlockLootGraphTest.class, a.getClass().getNestHost());
        write(LootTable.class, actual, "compositeFunction", a);
        write(LootTable.class, reference, "compositeFunction", e);
        var refusal = assertThrows(SurvivalRefusal.class,
                () -> NativeBlockLootGraph.requireEquivalent(actual, reference, lootId("dirt")));
        assertTrue(refusal.getMessage().contains("cached closure provenance"));
        assertFalse(actualInvoked[0]);
        assertFalse(referenceInvoked[0]);
    }

    private static BiFunction<ItemStack, LootContext, ItemStack> foreignCache(boolean[] invoked) {
        return (stack, context) -> { invoked[0] = true; return stack; };
    }
    private static ResourceLocation lootId(String name) {
        return ResourceLocation.withDefaultNamespace("blocks/" + name);
    }
    private static LootTable nativeTable(String name) throws Exception {
        return decode(originalJson(name));
    }
    private static LootTable decode(JsonObject json) {
        return LootTable.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), json).getOrThrow();
    }
    private static JsonObject originalJson(String name) throws Exception {
        String expected = switch (name) {
            case "grass_block" -> "ef73269d2687815bc3bb1b693caa2aaf61fdd7acd3fc67acaa4a6581c217e582";
            case "dirt" -> "9222e5df0ffbb258af7ad3c42a563b432d46bdaf398bf505eccd1df89db24d25";
            default -> throw new IllegalArgumentException(name);
        };
        try (var input = Block.class.getResourceAsStream("/data/minecraft/loot_table/blocks/" + name + ".json")) {
            assertNotNull(input);
            byte[] bytes = input.readNBytes(16385);
            assertTrue(bytes.length <= 16384);
            assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static Object firstEntry(LootTable table) throws Exception {
        var pools = (List<?>) read(LootTable.class, table, "pools");
        assertEquals(1, pools.size());
        var entries = (List<?>) read(LootPool.class, pools.getFirst(), "entries");
        assertEquals(1, entries.size());
        assertSame(AlternativesEntry.class, entries.getFirst().getClass());
        return entries.getFirst();
    }
    private static List<?> children(Object entry) throws Exception {
        return (List<?>) read(CompositeEntryBase.class, entry, "children");
    }
    private static Object composedChildren(Object entry) throws Exception {
        return read(CompositeEntryBase.class, entry, "composedChildren");
    }
    private static void assertNativeComposedClosure(Object closure) throws Exception {
        Class<?> type = closure.getClass();
        assertTrue(type.isHidden());
        assertTrue(type.isSynthetic());
        Class<?> host = Class.forName("net.minecraft.world.level.storage.loot.entries.ComposableEntryContainer",
                false, LootTable.class.getClassLoader());
        assertSame(host, type.getNestHost());
        assertSame(LootTable.class.getClassLoader(), type.getClassLoader());
        NativeBlockBreakPins.require(host);
    }
    private static Object read(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        assertTrue(field.trySetAccessible());
        return field.get(target);
    }
    private static void write(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        assertTrue(field.trySetAccessible());
        field.set(target, value);
    }

}
