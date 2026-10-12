package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForgeEventHandler;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PinnedNativeEventAdmissionTest {
    public static class ParentEvent extends Event {}
    public static final class ChildEvent extends ParentEvent {}
    public static final class CustomHandler {
        int calls;
        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onEntityJoinWorld(EntityJoinLevelEvent event) { calls++; }
        @SubscribeEvent(priority = EventPriority.LOW)
        public void wrongPriority(EntityJoinLevelEvent event) { calls++; }
        @SubscribeEvent(priority = EventPriority.HIGH, receiveCanceled = true)
        public void wrongCancellation(EntityJoinLevelEvent event) { calls++; }
    }

    @Test void emptyExactPinnedBusIsAdmitted() {
        var bus = BusBuilder.builder().build();
        var admission = new PinnedNativeEventAdmission(bus);
        assertDoesNotThrow(() -> admission.requireEmpty(ChildEvent.class));
        assertDoesNotThrow(admission::requireDeliveryCallbacks);
    }
    @Test void inheritedListenerRefusesWithoutExecutingIt() {
        var bus = BusBuilder.builder().build();
        int[] invoked = {0};
        bus.addListener(ParentEvent.class, event -> invoked[0]++);
        var admission = new PinnedNativeEventAdmission(bus);
        assertThrows(SurvivalRefusal.class, () -> admission.requireEmpty(ChildEvent.class));
        assertEquals(0, invoked[0]);
    }
    @Test void lateRegistrationRevokesPreviouslyAdmittedChild() {
        var bus = BusBuilder.builder().build();
        var admission = new PinnedNativeEventAdmission(bus);
        admission.requireEmpty(ChildEvent.class);
        bus.addListener(ParentEvent.class, event -> {});
        assertThrows(SurvivalRefusal.class, () -> admission.requireEmpty(ChildEvent.class));
        assertThrows(SurvivalRefusal.class, () -> admission.requireEmpty(ChildEvent.class));
    }
    @Test void exactBuiltinsAreAdmittedWithoutInvokingGameplayHandlers() throws Exception {
        var bus = BusBuilder.builder().build();
        var target = new NeoForgeEventHandler();
        install(bus, builtin(target, "onEntityJoinWorld"));
        install(bus, builtin(target, "builtinMobSpawnBlocker"));
        var admission = new PinnedNativeEventAdmission(bus);
        // These callbacks would dereference an event if invoked. Admission only allocates wrappers.
        assertDoesNotThrow(admission::requireDeliveryCallbacks);
        assertDoesNotThrow(admission::requireDeliveryCallbacks);
    }
    @Test void abstractEntityEventRegistrationIsRejectedByPinnedBus() throws Exception {
        var bus = BusBuilder.builder().build();
        install(bus, builtin(new NeoForgeEventHandler(), "onEntityJoinWorld"));
        var admission = new PinnedNativeEventAdmission(bus);
        admission.requireVanillaItemJoinCallbacks();
        int[] calls = {0};
        // bus8 rejects abstract parents and deliberately excludes their disconnected lists.
        // A direct reflected register on EntityEvent would manufacture an impossible profile.
        assertThrows(IllegalArgumentException.class, () -> bus.addListener(EntityEvent.class, event -> calls[0]++));
        assertDoesNotThrow(admission::requireVanillaItemJoinCallbacks);
        assertEquals(0, calls[0]);
    }
    @Test void additionalAndLateJoinListenerRefuses() throws Exception {
        var bus = BusBuilder.builder().build();
        var target = new NeoForgeEventHandler();
        install(bus, builtin(target, "onEntityJoinWorld"));
        install(bus, builtin(target, "builtinMobSpawnBlocker"));
        var admission = new PinnedNativeEventAdmission(bus);
        admission.requireVanillaItemJoinCallbacks();
        int[] calls = {0};
        bus.addListener(EntityJoinLevelEvent.class, event -> calls[0]++);
        assertThrows(SurvivalRefusal.class, admission::requireVanillaItemJoinCallbacks);
        assertThrows(SurvivalRefusal.class, admission::requireVanillaItemJoinCallbacks);
        assertEquals(0, calls[0]);
    }
    @Test void duplicateBuiltinMethodRefusesEvenWithDifferentExactTargets() throws Exception {
        var bus = BusBuilder.builder().build();
        install(bus, builtin(new NeoForgeEventHandler(), "onEntityJoinWorld"));
        install(bus, builtin(new NeoForgeEventHandler(), "onEntityJoinWorld"));
        assertThrows(SurvivalRefusal.class, new PinnedNativeEventAdmission(bus)::requireVanillaItemJoinCallbacks);
    }
    @Test void wrongTargetAndSubclassRefuse() throws Exception {
        for (Object target : new Object[]{new Object(), new NeoForgeEventHandler() {}}) {
            var bus = BusBuilder.builder().build();
            install(bus, builtin(target, "onEntityJoinWorld"));
            assertThrows(SurvivalRefusal.class, new PinnedNativeEventAdmission(bus)::requireVanillaItemJoinCallbacks);
        }
    }
    @Test void matchingMethodNameOnCustomHandlerDoesNotGrantAdmission() throws Exception {
        var bus = BusBuilder.builder().build();
        var target = new CustomHandler();
        install(bus, new SubscribeEventListener(target, CustomHandler.class.getMethod("onEntityJoinWorld", EntityJoinLevelEvent.class)));
        assertThrows(SurvivalRefusal.class, new PinnedNativeEventAdmission(bus)::requireVanillaItemJoinCallbacks);
        assertEquals(0, target.calls);
    }
    @Test void alteredPriorityOrReceiveCanceledRefuses() throws Exception {
        for (String method : new String[]{"wrongPriority", "wrongCancellation"}) {
            var bus = BusBuilder.builder().build();
            var listener = builtin(new NeoForgeEventHandler(), "onEntityJoinWorld");
            Field field = SubscribeEventListener.class.getDeclaredField("subInfo");
            assertTrue(field.trySetAccessible());
            field.set(listener, CustomHandler.class.getMethod(method, EntityJoinLevelEvent.class).getAnnotation(SubscribeEvent.class));
            install(bus, listener);
            assertThrows(SurvivalRefusal.class, new PinnedNativeEventAdmission(bus)::requireVanillaItemJoinCallbacks);
        }
    }
    @Test void ordinaryVanillaItemsAreAdmittedAndCustomSubclassRefused() throws Exception {
        for (Item item : new Item[]{Items.STICK, Items.COBBLESTONE, Items.WOODEN_PICKAXE})
            assertDoesNotThrow(() -> PinnedNativeEventAdmission.requireOrdinaryItem(new ItemStack(item)));
        // Allocate only an inert class-guard fixture: no frozen registry mutation or constructor.
        var unsafeClass = Class.forName("sun.misc.Unsafe"); var field = unsafeClass.getDeclaredField("theUnsafe");
        assertTrue(field.trySetAccessible());
        Item custom = (Item) unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), HostileItem.class);
        assertThrows(SurvivalRefusal.class, () -> PinnedNativeEventAdmission.requireOrdinaryItemType(custom));
    }
    private static final class HostileItem extends Item {
        private HostileItem() { super(new Item.Properties()); }
        @Override public boolean hasCustomEntity(ItemStack stack) { throw new AssertionError("Admission must not invoke this callback"); }
    }
    private static SubscribeEventListener builtin(Object target, String name) throws Exception {
        return new SubscribeEventListener(target, NeoForgeEventHandler.class.getMethod(name, EntityJoinLevelEvent.class));
    }
    private static void install(IEventBus bus, SubscribeEventListener listener) throws Exception {
        listeners(bus, EntityJoinLevelEvent.class).register(listener.getPriority(), listener);
    }
    private static ListenerList listeners(IEventBus bus, Class<? extends Event> type) throws Exception {
        Method method = EventBus.class.getDeclaredMethod("getListenerList", Class.class);
        assertTrue(method.trySetAccessible());
        return (ListenerList) method.invoke(bus, type);
    }
}
