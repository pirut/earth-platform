package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;

/** Exact pinned field reads only. Never writes a private native field or substitutes a native owner. */
final class NativeDeliveryReadAccess {
    private final Field stuckSpeed = field(Entity.class, "stuckSpeedMultiplier");
    private final Field fluidHeight = field(Entity.class, "fluidHeight");
    private final Field forgeFluidHeight = field(Entity.class, "forgeFluidTypeHeight");
    private final Field fluidEyes = field(Entity.class, "fluidOnEyes");
    private final Field entityCallback = field(Entity.class, "levelCallback");
    private final Field manager = field(ServerLevel.class, "entityManager");
    private final Field knownUuids = field(PersistentEntitySectionManager.class, "knownUuids");
    private final Field gameListeners = field(LevelChunk.class, "gameEventListenerRegistrySections");
    private final Field listenerProcessing = field(net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry.class, "processing");
    private final Field listenersToAdd = field(net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry.class, "listenersToAdd");

    void requireDryTickState(net.minecraft.world.entity.item.ItemEntity item) {
        if (item.portalProcess != null || item.getPortalCooldown() != 0 || item.getRemainingFireTicks() > 0
                || item.wasOnFire || item.isInWater() || item.isInLava() || item.isInPowderSnow || item.wasInPowderSnow
                || item.getTicksFrozen() != 0 || item.isSprinting() || item.isSwimming() || item.isNoGravity() || item.noPhysics
                || !Float.isFinite(item.fallDistance) || item.fallDistance < 0 || item.tickCount < 0 || item.tickCount == Integer.MAX_VALUE
                || !net.minecraft.world.phys.Vec3.ZERO.equals(read(stuckSpeed, item))
                || !((Map<?, ?>) read(fluidHeight, item)).isEmpty() || !((Map<?, ?>) read(forgeFluidHeight, item)).isEmpty()
                || !((Set<?>) read(fluidEyes, item)).isEmpty()
                || item.getEyeInFluidType() != net.neoforged.neoforge.common.NeoForgeMod.EMPTY_TYPE.value())
            throw new SurvivalRefusal("Native item tick has unadmitted portal/fluid/fire/frozen/physics state");
    }
    void requireRemovedEntityDetached(Entity entity) {
        if (entity.getRemovalReason() != Entity.RemovalReason.DISCARDED || entity.isAddedToLevel()
                || entity.isPassenger() || entity.isVehicle() || entity.hasAttachments()
                || read(entityCallback, entity) != net.minecraft.world.level.entity.EntityInLevelCallback.NULL)
            throw new SurvivalRefusal("Consumed native item cleanup is incomplete or uncertain");
    }
    void requireDetachedEntity(Entity entity) {
        if (entity.isAddedToLevel() || entity.isRemoved() || entity.isPassenger() || entity.isVehicle()
                || entity.hasAttachments() || read(entityCallback, entity) != net.minecraft.world.level.entity.EntityInLevelCallback.NULL)
            throw new SurvivalRefusal("Native pickup copy is not detached from all live tracking state");
    }
    @SuppressWarnings("unchecked")
    PersistentEntitySectionManager<Entity> manager(ServerLevel level) {
        Object value = read(manager, level);
        if (value.getClass() != PersistentEntitySectionManager.class) throw new SurvivalRefusal("Custom native entity manager is unsupported");
        return (PersistentEntitySectionManager<Entity>) value;
    }
    @SuppressWarnings("unchecked")
    Set<UUID> knownUuids(ServerLevel level, int maximum) {
        Set<UUID> ids = (Set<UUID>) read(knownUuids, manager(level));
        if (ids.size() > maximum) throw new SurvivalRefusal("Native entity UUID roster exceeds the admitted bound");
        Set<UUID> copy = Set.copyOf(ids);
        if (copy.size() != ids.size()) throw new SurvivalRefusal("Native entity UUID roster changed during inspection");
        return copy;
    }
    @SuppressWarnings("unchecked")
    void requireTickGameListeners(ServerLevel level, BlockPos center, int radius, int maximumSectionsPerCarrier) {
        requireNoGameListeners(level, center, radius);
        if (maximumSectionsPerCarrier < 1 || maximumSectionsPerCarrier > 4096)
            throw new SurvivalRefusal("Native game-event metadata requires a fixed bounded session allowance");
        int minX = Math.subtractExact(center.getX(), radius) >> 4, maxX = Math.addExact(center.getX(), radius) >> 4;
        int minY = Math.subtractExact(center.getY(), radius) >> 4, maxY = Math.addExact(center.getY(), radius) >> 4;
        int minZ = Math.subtractExact(center.getZ(), radius) >> 4, maxZ = Math.addExact(center.getZ(), radius) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z); if (chunk == null) continue;
            Map<Integer, GameEventListenerRegistry> registries = (Map<Integer, GameEventListenerRegistry>) read(gameListeners, chunk);
            int current = registries.size(), added = 0;
            if (current > maximumSectionsPerCarrier) throw new SurvivalRefusal("Native game-event metadata roster exceeds its bound");
            for (int y = minY; y <= maxY; y++) if (!registries.containsKey(y)) added++;
            NativeEffectDeliveryHost.requireCapacity(current, added, maximumSectionsPerCarrier, "native game-event section metadata");
        }
    }
    @SuppressWarnings("unchecked")
    void requireNoGameListeners(ServerLevel level, BlockPos center, int radius) {
        if (radius < 0 || radius > 32) throw new SurvivalRefusal("Native game event radius exceeds the admitted bound");
        int minX = Math.subtractExact(center.getX(), radius) >> 4;
        int maxX = Math.addExact(center.getX(), radius) >> 4;
        int minY = Math.subtractExact(center.getY(), radius) >> 4;
        int maxY = Math.addExact(center.getY(), radius) >> 4;
        int minZ = Math.subtractExact(center.getZ(), radius) >> 4;
        int maxZ = Math.addExact(center.getZ(), radius) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            // getListenerRegistry creates a live registry; do not call it during preflight.
            Map<Integer, GameEventListenerRegistry> registries =
                    (Map<Integer, GameEventListenerRegistry>) read(gameListeners, chunk);
            for (int y = minY; y <= maxY; y++) {
                var registry = registries.get(y);
                if (registry != null) {
                    if (registry.getClass() != net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry.class
                            || Boolean.TRUE.equals(read(listenerProcessing, registry))
                            || !((Collection<?>) read(listenersToAdd, registry)).isEmpty() || !registry.isEmpty())
                        throw new SurvivalRefusal("Native game-event listeners require captured callback admission");
                }
            }
        }
    }
    private static Field field(Class<?> type, String name) {
        try {
            Field result = type.getDeclaredField(name);
            if (!result.trySetAccessible()) throw new SurvivalRefusal("Pinned native state inspection is unavailable: " + name);
            return result;
        } catch (ReflectiveOperationException | SecurityException failure) {
            var refusal = new SurvivalRefusal("Pinned native state inspection is unavailable: " + name);
            refusal.initCause(failure); throw refusal;
        }
    }
    private static Object read(Field field, Object target) {
        try { return field.get(target); }
        catch (IllegalAccessException failure) {
            var refusal = new SurvivalRefusal("Pinned native state inspection failed"); refusal.initCause(failure); throw refusal;
        }
    }
}
