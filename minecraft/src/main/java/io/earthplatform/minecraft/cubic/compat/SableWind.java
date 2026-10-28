package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The jet stream carries Sable ships too. Above its floor a ship is pulled east toward the wind's
 * speed, which at full strength is the speed an elytra glider rides it at flying east (about
 * 1,650 blocks a second). A ship speeds up gently, about 1 g, and takes about a minute and a half
 * to reach full speed. Sable can't keep a player standing on a deck much past 80 blocks a second,
 * so while a player is aboard the wind carries a ship no faster than {@link #RIDDEN_TOP}. The air moves with the ship,
 * so while it is slower than the wind, the speed Sable's air drag takes off it eastward each tick is
 * given back; propellers still add speed as usual.
 */
public final class SableWind {
    /** Same as a glider's: fraction of the gap to the wind's speed closed each tick. */
    private static final double RESPONSE = 0.03;
    /** Most speed a ship gains or loses from the wind in one tick, in blocks a second: 20 b/s², about 1 g. */
    private static final double MAX_CHANGE = 1.0;
    /** Fastest the wind carries a ship with a player aboard, in blocks a second; riders were tested to stay on at this speed. */
    private static final double RIDDEN_TOP = 40.0;
    private static final Vector3dc NONE = new Vector3d();
    /** Each ship's eastward speed right after last tick's push, to measure what drag took since. */
    private static final Map<Object, Double> EXPECTED = new WeakHashMap<>();
    /** Reflected Sable calls; null once a lookup failed. */
    private static Method container, subLevels, pose, position, handle, velocity, push;
    private static boolean tried, broken;

    private SableWind() {}

    public static void tick(LevelAccessor levelAccessor) {
        if (!SableCompat.LOADED || broken || !(levelAccessor instanceof ServerLevel level) || !Cubic.isCubic(level.dimension())) return;
        if (!tried && !lookUp()) return;
        try {
            Object ships = container.invoke(null, level);
            if (ships == null) return;
            Vector3d current = new Vector3d();
            Set<Object> ridden = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ServerPlayer player : level.players()) {
                Object ship = SableCompat.shipOf(player);
                if (ship != null) ridden.add(ship);
            }
            for (Object ship : (List<?>) subLevels.invoke(ships)) {
                double y = ((Vector3dc) position.invoke(pose.invoke(ship))).y();
                double strength = JetStream.strength(y);
                Object body = strength > 0 ? handle.invoke(null, ship) : null;
                if (body == null) {
                    EXPECTED.remove(ship);
                    continue;
                }
                velocity.invoke(body, current);
                double wind = JetStream.CRUISE * 20 * strength * (1 + JetStream.TAILWIND);
                if (ridden.contains(ship)) wind = Math.min(wind, RIDDEN_TOP);
                double change = Math.max(-MAX_CHANGE, Math.min(MAX_CHANGE, (wind - current.x) * RESPONSE));
                Double expected = EXPECTED.get(ship);
                if (expected != null && current.x < expected && current.x < wind) change += Math.min(expected, wind) - current.x;
                push.invoke(body, new Vector3d(change, 0, 0), NONE);
                EXPECTED.put(ship, current.x + change);
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
