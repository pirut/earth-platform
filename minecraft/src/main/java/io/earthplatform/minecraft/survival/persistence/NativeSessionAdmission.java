package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Objects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/**
 * Read-only native half of the transition adapter. No teleport, mode edit, inventory edit,
 * menu close, checkpoint, save, collision-cache reset or hold is implemented here. Those need
 * exact host-owned capabilities and real-server integration tests before runtime installation.
 */
public final class NativeSessionAdmission {
    private NativeSessionAdmission() {}

    public static SurvivalSessionTransitions.OrdinaryState captureOrdinary(ServerPlayer player, Binding binding, int maximumBytes) {
        requirePlayer(player, binding);
        if (player.level().dimension().location().equals(binding.dimension()))
            throw new SurvivalRefusal("Ordinary escrow cannot be captured from the Earth carrier");
        if (maximumBytes < 1) throw new SurvivalRefusal("Bounded ordinary snapshot allowance required");
        var inventory = NativeInventorySnapshot.capture(player, maximumBytes);
        var food = NativeFoodSnapshot.capture(player.getFoodData());
        var pose = NativePlayerPoseSnapshot.capture(player);
        if ((long) inventory.encodedBytes() + food.encodedBytes() + pose.encodedBytes() > maximumBytes)
            throw new SurvivalRefusal("Complete ordinary escrow exceeds its admitted byte allowance");
        var result = new SurvivalSessionTransitions.OrdinaryState(inventory, food, pose);
        requireOrdinaryExact(player, binding, result);
        return result;
    }
    public static void requireOrdinaryExact(ServerPlayer player, Binding binding, Session session) {
        Objects.requireNonNull(session);
        requireOrdinaryExact(player, binding, new SurvivalSessionTransitions.OrdinaryState(
                session.ordinaryEscrow(), session.ordinaryFood(), session.ordinaryPose()));
    }
    public static void requireOrdinaryExact(ServerPlayer player, Binding binding, SurvivalSessionTransitions.OrdinaryState expected) {
        requirePlayer(player, binding); Objects.requireNonNull(expected);
        if (expected.pose().dimension().equals(binding.dimension())
                || !expected.inventory().equals(NativeInventorySnapshot.capture(player, expected.inventory().encodedBytes()))
                || !expected.food().equals(NativeFoodSnapshot.capture(player.getFoodData()))
                || !expected.pose().equals(NativePlayerPoseSnapshot.capture(player)))
            throw new SurvivalRefusal("Live ordinary inventory, food or pose differs from the exact held escrow");
    }
    /**
     * Resolve the real server's exact dimension; no overworld fallback and no position clamping.
     * Full finite/range admission is provided by the immutable pose constructor. Materialize every
     * stack and food value now, before any future mutator can touch a live native slot.
     */
    public static ServerLevel requireReturnTarget(ServerPlayer player, Binding binding, Session session) {
        requirePlayer(player, binding); Objects.requireNonNull(session);
        if (session.ordinaryPose().dimension().equals(binding.dimension()))
            throw new SurvivalRefusal("Ordinary return target cannot name the Earth carrier");
        var key = ResourceKey.create(Registries.DIMENSION, session.ordinaryPose().dimension());
        ServerLevel target = player.server.getLevel(key);
        if (target == null || target.getClass() != ServerLevel.class || target.getServer() != player.server
                || !target.dimension().location().equals(session.ordinaryPose().dimension()))
            throw new SurvivalRefusal("Exact ordinary return dimension is unavailable");
        session.ordinaryEscrow().materialize(player.registryAccess());
        session.ordinaryFood().materialize();
        return target;
    }
    /** Call again immediately before install; dimension replacement must not reuse a stale plan. */
    public static void requireSameReturnTarget(ServerPlayer player, Binding binding, Session session, ServerLevel prepared) {
        if (prepared == null || requireReturnTarget(player, binding, session) != prepared)
            throw new SurvivalRefusal("Prepared ordinary return Level was replaced");
    }
    private static void requirePlayer(ServerPlayer player, Binding binding) {
        Objects.requireNonNull(player); Objects.requireNonNull(binding);
        if (player.getClass() != ServerPlayer.class || !player.server.isSameThread()
                || !player.getUUID().equals(binding.player()) || player.serverLevel().getClass() != ServerLevel.class
                || player.serverLevel().getServer() != player.server || player.hasDisconnected()
                || !player.isAlive() || player.isRemoved())
            throw new SurvivalRefusal("Native transition requires its exact live server-thread player");
        // Reuses existing fail-closed cursor/crafting/mount/sleep/portal/use/effect/camera/pose admission.
        NativePlayerPoseSnapshot.capture(player);
    }
}
