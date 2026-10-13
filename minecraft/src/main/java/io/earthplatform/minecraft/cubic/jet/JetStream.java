package io.earthplatform.minecraft.cubic.jet;

import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * High-altitude winds of the cubic Earth. Above the highest ground (Everest's top is Y 8912),
 * elytra flight catches a jet stream that carries a player across a continent in about an hour.
 * Real jet streams blow from west to east, so flying east is faster and flying west slower.
 */
public final class JetStream {
    /** Nothing generated reaches this high, so no terrain needs streaming around a rider. */
    public static final int CLEAR_SKY = 9000;
    /** The wind starts here and reaches full strength {@link #FULL_DEPTH} blocks higher. */
    public static final int FLOOR = 9200, FULL_DEPTH = 800;
    /** Gliders can't climb above the top of the wind. */
    public static final int CEILING = 12000;
    /** Cruise speed in blocks per tick at full strength: 1,320 blocks a second, so 1,120 flying west. */
    public static final double CRUISE = 66.0;
    /** How much the westerly wind adds flying east and takes away flying west. */
    public static final double TAILWIND = 0.15;
    /** Fraction of the gap to the target velocity closed each tick, about five seconds to cruise. */
    private static final double RESPONSE = 0.03;
    /** Climb or descent rate, blocks per tick, when pitched fully up or down. */
    private static final double CLIMB = 3.0;
    /** Ordinary elytra flight tops out near this horizontal speed; faster flight below the wind slows down. */
    private static final double NORMAL_TOP = 4.0, THICK_AIR = 0.985;

    private JetStream() {}

    public static boolean inCubic(LivingEntity entity) { return CubicLevel.of(entity.level()) != null; }

    /** Gliding high enough that terrain can't be near. */
    public static boolean aboveTerrain(LivingEntity entity) {
        return entity.isFallFlying() && entity.getY() >= CLEAR_SKY && inCubic(entity);
    }

    /** Gliding in the wind layer. */
    public static boolean riding(LivingEntity entity) {
        return entity.isFallFlying() && entity.getY() >= FLOOR && inCubic(entity);
    }

    /** Wind strength at a height, 0 at the floor rising smoothly to 1. */
    public static double strength(double y) {
        double t = Mth.clamp((y - FLOOR) / FULL_DEPTH, 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    /** Next velocity of a glider in the wind: carried along the way it faces, climbing or sinking with its pitch. */
    public static Vec3 carry(Vec3 velocity, Vec3 look, double y) {
        double flat = Math.sqrt(look.x * look.x + look.z * look.z);
        double hx = flat > 1e-4 ? look.x / flat : 0, hz = flat > 1e-4 ? look.z / flat : 0;
        double speed = CRUISE * strength(y) * (1 + TAILWIND * hx);
        double tx = hx * speed, tz = hz * speed, ty = y >= CEILING ? Math.min(0.0, look.y * CLIMB) : look.y * CLIMB;
        return new Vec3(velocity.x + (tx - velocity.x) * RESPONSE, velocity.y + (ty - velocity.y) * 0.1,
                velocity.z + (tz - velocity.z) * RESPONSE);
    }

    /** Vanilla's rocket boost speed, and the climb a rocket gives pointed straight up in the cubic world. */
    private static final double ROCKET = 1.5, ROCKET_CLIMB = 10.0;

    /**
     * A firework's push on a glider. In the wind it adds nothing, since the wind is faster than any
     * rocket. Pitched steeply up it climbs far faster than vanilla, so a stack of rockets reaches
     * the wind from the ground; otherwise it pushes like vanilla's.
     */
    public static Vec3 rocket(Vec3 velocity, Vec3 look, double y) {
        if (y >= FLOOR) return velocity;
        double t = Mth.clamp((look.y - 0.5) / 0.4, 0.0, 1.0);
        double speed = ROCKET + (ROCKET_CLIMB - ROCKET) * t * t * (3 - 2 * t);
        return velocity.add(look.x * 0.1 + (look.x * speed - velocity.x) * 0.5, look.y * 0.1 + (look.y * speed - velocity.y) * 0.5,
                look.z * 0.1 + (look.z * speed - velocity.z) * 0.5);
    }

    /** Below the wind, thicker air bleeds off jet speed over several kilometres so terrain has time to load. */
    public static Vec3 slow(Vec3 velocity) {
        double horizontal = velocity.horizontalDistance();
        if (horizontal <= NORMAL_TOP) return velocity;
        double scale = Math.max(NORMAL_TOP / horizontal, THICK_AIR);
        return new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
    }
}
