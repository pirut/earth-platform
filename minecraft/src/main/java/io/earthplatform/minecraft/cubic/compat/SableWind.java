package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The jet stream carries Sable ships too. Above its floor a ship is pulled east toward the wind's
 * speed, which at full strength is the speed an elytra glider rides it at flying east (about
 * 1,650 blocks a second), closing the gap at the same rate a glider does.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class SableWind {
    /** Same as a glider's: fraction of the gap to the wind's speed closed each tick. */
    private static final double RESPONSE = 0.03;
    private static final Vector3dc NONE = new Vector3d();
    /** Reflected Sable calls; null once a lookup failed. */
    private static Method container, subLevels, pose, position, handle, velocity, push;
    private static boolean tried, broken;

    private SableWind() {}

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        if (!SableCompat.LOADED || broken || !(event.getLevel() instanceof ServerLevel level) || !Cubic.isCubic(level.dimension())) return;
        if (!tried && !lookUp()) return;
        try {
            Object ships = container.invoke(null, level);
            if (ships == null) return;
            Vector3d current = new Vector3d();
            for (Object ship : (List<?>) subLevels.invoke(ships)) {
                double y = ((Vector3dc) position.invoke(pose.invoke(ship))).y();
                double strength = JetStream.strength(y);
                if (strength <= 0) continue;
                Object body = handle.invoke(null, ship);
                if (body == null) continue;
                velocity.invoke(body, current);
                double wind = JetStream.CRUISE * 20 * strength * (1 + JetStream.TAILWIND);
                push.invoke(body, new Vector3d((wind - current.x) * RESPONSE, 0, 0), NONE);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Jet stream could not move Sable ships; leaving them to their own propellers", failure);
            broken = true;
        }
    }

    private static boolean lookUp() {
        tried = true;
        try {
            Class<?> containers = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> serverContainers = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
            Class<?> ship = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
            Class<?> handles = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");
            container = containers.getMethod("getContainer", ServerLevel.class);
            subLevels = serverContainers.getMethod("getAllSubLevels");
            pose = ship.getMethod("logicalPose");
            position = pose.getReturnType().getMethod("position");
            handle = handles.getMethod("of", ship);
            velocity = handles.getMethod("getLinearVelocity", Vector3d.class);
            push = handles.getMethod("addLinearAndAngularVelocity", Vector3dc.class, Vector3dc.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            EarthPlatformMod.LOGGER.warn("Sable ship hooks not found; the jet stream won't carry ships", failure);
            broken = true;
            return false;
        }
    }
}
