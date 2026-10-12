package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.GeneratedEventListener;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForgeEventHandler;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Read-only, fail-closed admission for exact bus8.0.5 and NeoForge21.1.256 binaries. Effective
 * arrays include inherited handlers and are re-read on every call. No listener is suppressed,
 * removed, preinvoked or replaced. Only the two verified built-in entity-join handlers are
 * admitted: they are no-ops for exact ordinary vanilla ItemEntity stacks verified below.
 *
 * <p>This is the pinned single-server-thread lifecycle, not a sandbox against asynchronous mod
 * registration between validation and a native call. Other callback/mod profiles are refused.
 */
public final class PinnedNativeEventAdmission {
    private static final List<Class<? extends Event>> EMPTY_DELIVERY_EVENTS = List.of(
            EntityEvent.Size.class, EntityEvent.EntityConstructing.class, EntityLeaveLevelEvent.class,
            PlayerEvent.StartTracking.class, PlayerEvent.StopTracking.class,
            StatAwardEvent.class, PlayLevelSoundEvent.AtPosition.class, VanillaGameEvent.class);
    private final IEventBus bus;
    private final Method listenerList;

    public PinnedNativeEventAdmission(IEventBus bus) {
        this.bus = Objects.requireNonNull(bus);
        if (bus.getClass() != EventBus.class) throw new SurvivalRefusal("Unrecognized native event bus implementation");
        requireClassBytes(EventBus.class, "cda8876c904d1374f77bbff02e8cdb01fba48ae0f528c69713eaefa11b5128a4");
        requireClassBytes(ListenerList.class, "3daccbcb1ef602dff0edfbbd1ed5483e4633d5bed6c0a1ccb35c01cef5c83888");
        try {
            listenerList = EventBus.class.getDeclaredMethod("getListenerList", Class.class);
            if (listenerList.getReturnType() != ListenerList.class || !listenerList.trySetAccessible())
                throw new SurvivalRefusal("Pinned native event listener inspection is unavailable");
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            throw refused("Pinned native event listener inspection is unavailable", unavailable);
        }
    }

    public void requireDeliveryCallbacks() {
        for (var event : EMPTY_DELIVERY_EVENTS) requireEmpty(event);
        requireVanillaItemJoinCallbacks();
    }
    public void requireItemTickEventsEmpty() {
        requireEmpty(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre.class);
        requireEmpty(net.neoforged.neoforge.event.tick.EntityTickEvent.Post.class);
        requireEmpty(net.neoforged.neoforge.event.entity.item.ItemExpireEvent.class);
    }
    public void requirePickupEventsEmpty() {
        requireEmpty(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre.class);
        requireEmpty(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post.class);
    }
    public void requireEmpty(Class<? extends Event> event) {
        if (listeners(event).length != 0)
            throw new SurvivalRefusal("Native callbacks are not admitted for " + event.getName());
    }

    /** Proves the exact target method, target instance class, full annotation flags and wrapper. */
    public void requireVanillaItemJoinCallbacks() {
        EventListener[] listeners = listeners(EntityJoinLevelEvent.class);
        if (listeners.length == 0) return;
        if (listeners.length > 2) throw new SurvivalRefusal("Additional native entity-join callbacks are not admitted");
        requireClassBytes(SubscribeEventListener.class, "2dc270df84436f3458564e2a9542cf4a175d9b2426db70917672397998e0efd0");
        requireClassBytes(GeneratedEventListener.class, "57f93a48701152d66414a84655fb7cd60cc19e83d03502daf2336240c222c4ad");
        requireClassBytes(pinnedFactoryClass(), "807424c87435f908d9a75b9fe52b43ab2e2925e97affb7b4e4057c8808485ed0");
        requireClassBytes(NeoForgeEventHandler.class, "b9b9592eac4bf7a548a85993509d3614d7e1f6f76012bb4252a11f3eb46aa9fa");
        requireClassBytes(IItemExtension.class, "d65a413962ab6bf6cf8c33119b010dd16fcf482c114b8ae8abe5c59da951b088");
        Set<Method> found = new HashSet<>();
        for (EventListener listener : listeners) {
            if (listener.getClass() != SubscribeEventListener.class)
                throw new SurvivalRefusal("Unrecognized entity-join listener wrapper: " + listener.getClass().getName());
            SubscribeEventListener subscribed = (SubscribeEventListener) listener;
            EventListener handler = subscribed.getWithoutCheck();
            if (handler.getClass().getSuperclass() != GeneratedEventListener.class)
                throw new SurvivalRefusal("Unrecognized native generated event target");
            Object target = readInstance(handler);
            if (target == null || target.getClass() != NeoForgeEventHandler.class)
                throw new SurvivalRefusal("Custom entity-join target instance is not admitted");
            Method matched = null;
            for (String name : List.of("onEntityJoinWorld", "builtinMobSpawnBlocker")) {
                Method expected = joinMethod(name);
                // The audited factory caches a unique generated implementation class per Method.
                // Allocate a reference wrapper only; invoking the gameplay handler is forbidden.
                EventListener reference = new SubscribeEventListener(target, expected).getWithoutCheck();
                if (handler.getClass() == reference.getClass()) {
                    if (readInstance(reference) != target)
                        throw new SurvivalRefusal("Native reference wrapper did not preserve its sole target");
                    matched = expected; break;
                }
            }
            if (matched == null || !found.add(matched)) throw new SurvivalRefusal("Unknown/duplicate native entity-join target method");
            SubscribeEvent expected = matched.getAnnotation(SubscribeEvent.class);
            SubscribeEvent actual = readSubscription(subscribed);
            if (actual.annotationType() != SubscribeEvent.class || actual.priority() != expected.priority()
                    || actual.receiveCanceled() != expected.receiveCanceled() || subscribed.getPriority() != expected.priority())
                throw new SurvivalRefusal("Native entity-join subscription flags changed from the pinned profile");
        }
    }

    /** The built-in onEntityJoinWorld callback cannot replace these exact native item classes. */
    public static void requireOrdinaryItem(ItemStack stack) {
        if (stack.isEmpty()) throw new SurvivalRefusal("Empty native dropped item");
        requireOrdinaryItemType(stack.getItem());
    }
    static void requireOrdinaryItemType(Item item) {
        Class<?> type = item.getClass();
        if ((type != Item.class && type != BlockItem.class && type != PickaxeItem.class)
                || !BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft"))
            throw new SurvivalRefusal("Custom native dropped-item callbacks are not admitted");
        requireClassBytes(IItemExtension.class, "d65a413962ab6bf6cf8c33119b010dd16fcf482c114b8ae8abe5c59da951b088");
        try {
            if (type.getMethod("hasCustomEntity", ItemStack.class).getDeclaringClass() != IItemExtension.class)
                throw new SurvivalRefusal("Native item has a custom entity override");
        } catch (ReflectiveOperationException failure) { throw refused("Cannot verify native custom-entity behavior", failure); }
    }

    private EventListener[] listeners(Class<? extends Event> event) {
        Objects.requireNonNull(event);
        try {
            Object value = listenerList.invoke(bus, event);
            if (!(value instanceof ListenerList listeners)) throw new SurvivalRefusal("Unrecognized native listener list");
            return listeners.getListeners();
        } catch (IllegalAccessException | InvocationTargetException unavailable) {
            throw refused("Cannot verify the effective native event listeners", unavailable);
        }
    }
    private static Class<?> pinnedFactoryClass() {
        try { return Class.forName("net.neoforged.bus.EventListenerFactory", false, EventBus.class.getClassLoader()); }
        catch (ClassNotFoundException | LinkageError failure) { throw refused("Pinned native listener factory is unavailable", failure); }
    }
    private static Method joinMethod(String name) {
        try {
            Method method = NeoForgeEventHandler.class.getDeclaredMethod(name, EntityJoinLevelEvent.class);
            SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
            EventPriority expected = name.equals("onEntityJoinWorld") ? EventPriority.HIGH : EventPriority.HIGHEST;
            if (method.getDeclaringClass() != NeoForgeEventHandler.class || method.getReturnType() != void.class
                    || method.getModifiers() != Modifier.PUBLIC || method.isBridge() || method.isSynthetic()
                    || annotation == null || annotation.priority() != expected || annotation.receiveCanceled())
                throw new SurvivalRefusal("Native entity-join method descriptor/flags differ from the pinned profile");
            return method;
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw refused("Pinned native join method is unavailable", failure);
        }
    }
    private static SubscribeEvent readSubscription(SubscribeEventListener listener) {
        try {
            Field field = SubscribeEventListener.class.getDeclaredField("subInfo");
            if (field.getType() != SubscribeEvent.class || !field.trySetAccessible())
                throw new SurvivalRefusal("Native subscription annotation is unavailable");
            Object value = field.get(listener);
            if (!(value instanceof SubscribeEvent annotation))
                throw new SurvivalRefusal("Native subscription annotation is missing");
            return annotation;
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw refused("Cannot verify native subscription annotation", failure);
        }
    }
    private static Object readInstance(EventListener handler) {
        try {
            Field[] fields = handler.getClass().getDeclaredFields();
            if (fields.length != 1) throw new SurvivalRefusal("Native generated listener has additional captured state");
            Field field = fields[0];
            if (!field.getName().equals("instance") || field.getType() != Object.class
                    || field.getModifiers() != (Modifier.PRIVATE | Modifier.FINAL) || !field.trySetAccessible())
                throw new SurvivalRefusal("Native generated listener instance is unavailable");
            return field.get(handler);
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw refused("Cannot verify native generated listener instance", failure);
        }
    }
    static void requireClassBytes(Class<?> type, String expected) {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null || !expected.equals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readNBytes(1024 * 1024 + 1)))))
                throw new SurvivalRefusal("Native event binary differs from the audited profile: " + type.getName());
        } catch (IOException | NoSuchAlgorithmException unavailable) {
            throw refused("Cannot verify pinned native event binary", unavailable);
        }
    }
    private static SurvivalRefusal refused(String text, Throwable cause) {
        var refusal = new SurvivalRefusal(text); refusal.initCause(cause); return refusal;
    }
}
