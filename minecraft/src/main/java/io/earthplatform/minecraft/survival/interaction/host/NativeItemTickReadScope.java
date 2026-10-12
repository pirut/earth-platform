package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Revocable read capability for one already-accepted native item tick. No public installer,
 * mutable world view, reusable approval boolean, or callback retry is exposed. The host's private
 * captured plan checks the exact original object, current action, Owner and retained receipt on
 * every read. Merely retaining a BlockGetter never retains this capability after the scope exits.
 */
public final class NativeItemTickReadScope {
    private static final ThreadLocal<NativeEffectDeliveryHost.ItemTickPlan> ACTIVE = new ThreadLocal<>();
    private NativeItemTickReadScope() {}

    static void execute(NativeEffectDeliveryHost.ItemTickPlan plan) {
        if (ACTIVE.get() != null) throw new SurvivalRefusal("Accepted native item tick cannot reenter");
        plan.enter(); ACTIVE.set(plan);
        try { plan.runNative(); }
        finally { ACTIVE.remove(); plan.close(); }
    }
    public static boolean permits(SurvivalLevelBinding binding) {
        var plan = ACTIVE.get(); return plan != null && plan.permits(binding);
    }
    public static BlockState read(SurvivalLevelBinding binding, BlockPos position) {
        return require(binding).read(position);
    }
    public static boolean hasResidentBox(SurvivalLevelBinding binding,
            int x1, int y1, int z1, int x2, int y2, int z2) {
        return require(binding).hasResidentBox(x1, y1, z1, x2, y2, z2);
    }
    public static boolean isExecuting(Entity source) {
        var plan = ACTIVE.get();
        if (plan == null) return false;
        if (!(source.level() instanceof ServerLevel receiver)) throw new SurvivalRefusal("Accepted native tick changed native level type");
        return plan.isExecuting(receiver, source);
    }
    public static boolean isExecuting(ServerLevel receiver, Entity source) {
        var plan = ACTIVE.get(); return plan != null && plan.isExecuting(receiver, source);
    }
    /** Exact replacement for the native item's old two-dimensional fluid-availability probe. */
    public static boolean touchingUnloadedArea(Entity source) {
        var plan = ACTIVE.get();
        if (plan == null || !isExecuting(source))
            throw new SurvivalRefusal("Native cubic fluid probe has no exact accepted tick source");
        AABB box = source.getBoundingBox().inflate(1.0);
        // Preserve native floor/ceil extents, while adding the missing true vertical coordinate.
        boolean loaded = plan.hasResidentBox(floor(box.minX), floor(box.minY), floor(box.minZ),
                ceil(box.maxX), ceil(box.maxY), ceil(box.maxZ));
        return !loaded; // Outside the sealed box throws above; it never silently skips fluid work.
    }
    static void requireSameBinding(Object expected, Object requested) {
        if (expected == null || expected != requested)
            throw new SurvivalRefusal("Native accepted tick cannot grant unrelated binding reads");
    }
    private static NativeEffectDeliveryHost.ItemTickPlan require(SurvivalLevelBinding binding) {
        var plan = ACTIVE.get();
        if (plan == null || !plan.permits(binding)) throw new SurvivalRefusal("Accepted native tick read scope is absent, foreign or revoked");
        return plan;
    }
    static int floor(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value >= Integer.MAX_VALUE)
            throw new SurvivalRefusal("Native tick read exceeds signed coordinates");
        return (int) Math.floor(value);
    }
    static int ceil(double value) {
        if (!Double.isFinite(value) || value <= Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new SurvivalRefusal("Native tick read exceeds signed coordinates");
        return (int) Math.ceil(value);
    }
}
