package io.earthplatform.minecraft.cubic.jet;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.platform.Loader;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * Ocean currents of the cubic Earth. On the open sea a boat whose rider sprints and paddles forward
 * catches a current that carries it the way it faces at about 900 blocks a second, so a boat crosses
 * an ocean in an hour or two, as a glider in the jet stream crosses a continent. Surface currents run
 * west in the tropics, with the trade winds, and east in the middle latitudes, with the westerlies, so
 * sailing west near the equator and home again further north is the quick way round, as it was for
 * sailing ships.
 *
 * <p>Like the jet stream the current outruns the terrain (see {@link JetStream#outrunsTerrain}): a
 * carried boat gets no near cubes and rides on the far terrain's sea, held at the height a boat floats
 * on water. Its rider's client looks ahead over the far terrain and slows the boat to a stop before
 * land or ice (see {@link CurrentRide}); the back key and running into something stop it too. A boat
 * that stops where its cubes haven't come yet floats there until they do, and the server readies the
 * ground and the chunk there before it sends them (see {@link ServerCubeWorld#readyGround} and
 * {@link ServerCubeWorld#chunkLoaded}).
 */
public final class OceanCurrent {
    /** Cruise speed in blocks a tick: 900 blocks a second, so New York to Lisbon (about 7.2 million blocks on the map) takes under two hours. */
    public static final double CRUISE = 45.0;
    /** How much the current's own drift adds going with it and takes away going against it, as the jet stream's tailwind does. */
    public static final double DRIFT = 0.15;
    /** Fraction of the gap to the current's speed and heading closed each tick: about five seconds to cruise, as in the jet stream. */
    private static final double RESPONSE = 0.03;
    /** Speed a slowing boat keeps each tick: from cruise to a stop in about 250 blocks. */
    private static final double BRAKE = 0.85;
    /** Speed and turn a drifting boat keeps each tick on the far terrain's sea, as vanilla's on water. */
    public static final double WATER = 0.9;
    /** A slowing boat slower than this, in blocks a tick, is let go: vanilla boats on water top out just under it. */
    public static final double LETS_GO = 0.4;
    /** A boat faster than this, in blocks a tick, outruns any terrain the server could send, as a ship in the jet stream does. */
    public static final double OUTRUNS = 4.0;
    /** Farther than a boat in the current goes in a tick: a rider's move this long is a teleport. */
    public static final double MAX_STEP = 2 * CRUISE * (1 + DRIFT);
    /** Ticks the server counts a boat's last fast move for, so a late packet doesn't bring its terrain back for a moment. */
    private static final int STEP_TICKS = 20;

    /**
     * Server: riders the current let go of, and the ground being readied where it did. A rider goes on outrunning terrain
     * until it is ready and the chunk they are in has loaded: asked for on the server thread as the cubes came back, a new
     * place's map data held the whole server up for seconds, and the chunk under them, loaded as they moved, for a second.
     */
    private static final Map<Entity, Landing> LANDINGS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Landing {
        @Nullable CompletableFuture<?> ground;
        /** Game time the ground was asked for. */
        long since;
    }

    /** Longest a rider waits to land, in ticks, before they do regardless: as long as the ground is waited for. */
    private static final int LANDING_TICKS = 600;

    private OceanCurrent() {}

    /** Added to every boat by CubicBoatMixin: what the current knows of it. */
    public interface Ride {
        /** Client: whether the current has the local player's boat, from catching it until it lets go. */
        boolean earthplatform$riding();
        void earthplatform$setRiding(boolean riding);
        /** Server: how far its rider's last move took it, in blocks, and the game time of that move. */
        double earthplatform$step();
        long earthplatform$steppedAt();
        void earthplatform$stepped(double step, long time);
    }

    /** The height a boat floats at on the sea, where vanilla floating settles it: 0.65 of its height under the water's surface, 8/9 up the sea's top block. */
    public static double floatHeight(Boat boat) { return EarthGenerator.SEA + 8.0 / 9.0 - 0.65 * boat.getBbHeight(); }

    /**
     * A boat in the cubic world floating on the sea: where the current runs, and where the far terrain's sea can hold a boat
     * up. One on the ground a block higher or lower is not.
     */
    public static boolean atSea(Boat boat) {
        return Math.abs(boat.getY() - floatHeight(boat)) < 0.25 && CubicLevel.of(boat.level()) != null;
    }

    /**
     * Whether the current carries this entity, or what it rides, faster than terrain could be sent: it needs no near cubes.
     * On the server, so does a rider the current has let go of until the ground and the chunk where it did are ready.
     */
    public static boolean carried(Entity entity) {
        Boat boat = entity.getRootVehicle() instanceof Boat ridden && atSea(ridden) ? ridden : null;
        if (entity.level().isClientSide) {
            return boat != null && ((Ride) boat).earthplatform$riding() && boat.getDeltaMovement().horizontalDistanceSqr() > OUTRUNS * OUTRUNS;
        }
        if (boat != null && fast(boat)) return true;
        Entity rider = entity instanceof Player ? entity : entity.getRootVehicle().getControllingPassenger();
        return rider != null && landing(rider);
    }

    /** Server: whether a boat's rider last moved it faster than terrain could be sent, lately. */
    private static boolean fast(Boat boat) {
        Ride ride = (Ride) boat;
        return ride.earthplatform$step() > OUTRUNS && boat.level().getGameTime() - ride.earthplatform$steppedAt() <= STEP_TICKS;
    }

    /**
     * Server: whether the current let a rider go where the ground is still being readied, which this starts the first time
     * it's asked, or the chunk they are in is still loading.
     */
    private static boolean landing(Entity rider) {
        Landing landing = LANDINGS.get(rider);
        if (landing == null) return false;
        if (!(CubicLevel.of(rider.level()) instanceof ServerCubeWorld cubes)) {
            LANDINGS.remove(rider);
            return false;
        }
        long now = rider.level().getGameTime();
        if (landing.ground == null) {
            landing.ground = cubes.readyGround(rider.getBlockX(), rider.getBlockZ());
            landing.since = now;
        }
        boolean chunk = cubes.chunkLoaded(rider.getBlockX(), rider.getBlockZ());
        if ((!landing.ground.isDone() || !chunk) && now - landing.since < LANDING_TICKS) return true;
        LANDINGS.remove(rider);
        return false;
    }

    /**
     * Server: whether a rider's move of their boat to x, y, z is one the current could make: afloat on the sea, and no
     * farther from where the boat is than it goes in a tick or two. Vanilla's vehicle speed check adds up every move since
     * the tick began, so moves that come in together after a hitch on either side add up past it; and once it turned one
     * back as the boat sped up, the boat never counted as fast and every move after was turned back too.
     */
    public static boolean currentMove(Entity vehicle, double x, double y, double z) {
        return vehicle instanceof Boat boat && atSea(boat) && Math.abs(y - floatHeight(boat)) < 0.25
                && Math.hypot(x - boat.getX(), z - boat.getZ()) <= MAX_STEP;
    }

    /**
     * Server: a vehicle's rider moved it from where it was. The client moves its own boat and sends a move each tick,
     * so how far one move went is how fast the boat goes, however unevenly the moves arrive.
     */
    public static void moved(Entity vehicle, double fromX, double fromZ) {
        if (!(vehicle instanceof Boat boat)) return;
        double step = Math.hypot(boat.getX() - fromX, boat.getZ() - fromZ);
        if (step > MAX_STEP) step = 0;
        ((Ride) boat).earthplatform$stepped(step, boat.level().getGameTime());
        // Out of the current, by slowing, getting off or its moves stopping, the rider will want the ground where they are.
        if (step > OUTRUNS && atSea(boat) && boat.getControllingPassenger() instanceof Player rider) {
            LANDINGS.computeIfAbsent(rider, carried -> new Landing()).ground = null;
        }
        // Held at the sea's height, a boat never falls, however the moves round its height.
        if (atSea(boat)) boat.resetFallDistance();
    }

    /**
     * A boat in the cubic world keeps ticking and is sent to players where no chunk is loaded, as a player is: in the current
     * no chunks load around it. The level still ticks it only in loaded chunks or in the current. Every boat does, never only
     * one with a rider: the level reads this only as a boat comes in and as its chunk loads or unloads, and a boat a player
     * logs back in aboard comes in before its rider, so one that changed when the rider got in was never sent at all.
     */
    public static boolean keepsTicking(Boat boat) {
        return Cubic.isCubic(boat.level().dimension());
    }

    /** The current's speed in blocks a tick at a latitude, heading a share {@code east} of the way east (west when negative). */
    public static double speed(double latitude, double east) { return CRUISE * (1 + DRIFT * east * flow(latitude)); }

    /**
     * Which way the surface currents drift at a latitude: west (-1) at the equator, with the trade winds; east (1) at
     * 45 degrees, with the westerlies, as the Gulf Stream's drift and the Antarctic Circumpolar Current; west again by the poles.
     */
    static double flow(double latitude) { return -Math.cos(Math.toRadians(4 * latitude)); }

    /** Next velocity of a boat in the current: carried the way it faces, at the current's speed there. */
    public static Vec3 carry(Vec3 velocity, float yaw, double latitude) {
        double hx = -Mth.sin(yaw * Mth.DEG_TO_RAD), hz = Mth.cos(yaw * Mth.DEG_TO_RAD), speed = speed(latitude, hx);
        return new Vec3(velocity.x + (hx * speed - velocity.x) * RESPONSE, 0, velocity.z + (hz * speed - velocity.z) * RESPONSE);
    }

    /** Next velocity of a boat the current is letting go of. */
    public static Vec3 brake(Vec3 velocity) { return new Vec3(velocity.x * BRAKE, 0, velocity.z * BRAKE); }

    /**
     * Boat.floatBoat on the cubic Earth's sea: the velocity a boat goes on with this tick when the current has it, or when
     * its cube hasn't come yet and the far terrain's sea holds it up; null where vanilla floating goes on. Either way it
     * keeps to the height it floats at.
     */
    @Nullable public static Vec3 floatBoat(Boat boat) {
        Ride ride = (Ride) boat;
        if (!atSea(boat)) {
            ride.earthplatform$setRiding(false);
            return null;
        }
        Vec3 velocity = boat.getDeltaMovement(), next = null;
        if (Loader.INSTANCE.isClient() && boat.level().isClientSide && boat.getControllingPassenger() instanceof Player player && player.isLocalPlayer()) {
            next = CurrentRide.steer(boat);
        } else {
            ride.earthplatform$setRiding(false);
        }
        if (next == null && !cubeHere(boat)) next = new Vec3(velocity.x * WATER, 0, velocity.z * WATER);
        if (next == null) return null;
        return new Vec3(next.x, Mth.clamp(floatHeight(boat) - boat.getY(), -0.1, 0.1), next.z);
    }

    /** Whether the cube a boat floats in has come. */
    private static boolean cubeHere(Boat boat) {
        CubeWorld cubes = CubicLevel.at(boat.level(), boat.getBlockX(), boat.getBlockZ());
        return cubes == null || cubes.cubeAtBlock(boat.getBlockX(), boat.getBlockY(), boat.getBlockZ()) != null;
    }
}
