package io.earthplatform.minecraft.cubic.jet;

import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.client.FarRenderer;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * Client: the ocean current's hold on the local player's boat (see {@link OceanCurrent}). Sprinting and
 * paddling forward catches it where the far terrain shows open sea ahead; it then carries the boat the
 * way it faces, with no key held, until the back key, land or ice ahead, or running into something
 * lets the boat go, slowing it to a stop unless it is caught again. The far terrain is all a carried
 * boat sees of the sea, so it is what the boat looks ahead over: far enough that it stops before the
 * shore at its coarsest. Where the far terrain hasn't come yet the boat slows down until it does.
 */
public final class CurrentRide {
    /**
     * Blocks ahead that must be open sea for the current to carry a boat on: the 250 blocks it takes to stop from
     * cruise, a tick of travel, and the widest far voxel it reads at speed (64 blocks) to spare.
     */
    static final int LOOK_AHEAD = 448, LOOK_STEP = 32;
    /** What the action bar says about the current (see {@link JetHud}): nothing, that it can be caught here, riding it, or slowing down. */
    enum State { NONE, OPEN, RIDING, SLOWING }

    private static State state = State.NONE;

    private CurrentRide() {}

    /** The state at the local player's boat's last tick at sea; JetHud asks only while the player rides one there. */
    static State state() { return state; }

    /** The local player's boat's velocity this tick when the current has it, or null; see {@link OceanCurrent#floatBoat}. */
    @Nullable static Vec3 steer(Boat boat) {
        OceanCurrent.Ride ride = (OceanCurrent.Ride) boat;
        ClientCubeWorld world = ClientCubeWorld.current();
        double scale = world == null ? 0 : world.far().metresPerBlock();
        if (scale <= 0 || !(boat.getControllingPassenger() instanceof LocalPlayer player)) {
            // Only the Earth has a sea to read and latitudes for the currents.
            ride.earthplatform$setRiding(false);
            state = State.NONE;
            return null;
        }
        FarRenderer far = world.far();
        Vec3 velocity = boat.getDeltaMovement();
        double speed = velocity.horizontalDistance();
        float yaw = boat.getYRot();
        // Looking ahead the way the boat goes, or the way it faces until it gets going.
        double dx = speed > 1 ? velocity.x / speed : -Mth.sin(yaw * Mth.DEG_TO_RAD), dz = speed > 1 ? velocity.z / speed : Mth.cos(yaw * Mth.DEG_TO_RAD);
        int ahead = ahead(far, boat.getX(), boat.getZ(), dx, dz);
        boolean catching = player.input.up && ahead == 1 && Minecraft.getInstance().options.keySprint.isDown();
        boolean riding = ride.earthplatform$riding();
        if (!riding) {
            state = player.input.up && ahead == 1 ? State.OPEN : State.NONE;
            if (!catching) return null;
            riding = true;
            state = State.RIDING;
        }
        // Once let go, a boat slows all the way to a stop unless the current is caught again.
        boolean letGo = state == State.SLOWING && !catching || player.input.down || boat.horizontalCollision || ahead == 0;
        Vec3 next;
        if (letGo || ahead < 0) {
            next = OceanCurrent.brake(velocity);
            riding = next.horizontalDistance() >= OceanCurrent.LETS_GO;
            state = !riding ? State.NONE : letGo ? State.SLOWING : State.RIDING;
        } else {
            double latitude = new MercatorGrid(scale).geographic(boat.getX(), boat.getZ()).latitude();
            next = OceanCurrent.carry(velocity, yaw, latitude);
            state = State.RIDING;
        }
        ride.earthplatform$setRiding(riding);
        return next;
    }

    /**
     * What the far terrain shows from a spot to {@link #LOOK_AHEAD} blocks along a direction: 1 for open sea all the
     * way, 0 for land or ice anywhere on it, -1 where it hasn't come yet.
     */
    private static int ahead(FarRenderer far, double x, double z, double dx, double dz) {
        int seen = 1;
        for (int along = 0; along <= LOOK_AHEAD; along += LOOK_STEP) {
            int sea = far.sea(Mth.floor(x + dx * along), Mth.floor(z + dz * along), EarthGenerator.SEA);
            if (sea == 0) return 0;
            seen = Math.min(seen, sea);
        }
        return seen;
    }
}
