package io.earthplatform.minecraft.cubic.jet;

import io.earthplatform.minecraft.cubic.play.client.EarthCompassHud;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;

/**
 * Tells a glider about the jet stream above the action bar: a hint when it climbs toward the wind,
 * its speed and heading while riding, and a word when it leaves and slows down. A boat at sea hears
 * the same of the ocean current (see {@link CurrentRide}). A compass in hand adds what it points to
 * and how far, so a rider sees the distance to where they're going.
 */
public final class JetHud {
    /** Gliders this high hear about the wind above. */
    public static final int HINT_FROM = 8000;
    private static final String[] HEADINGS = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
    private static int ticks;
    /** Whether the action bar carries this glider's line now. */
    private static boolean showing;

    private JetHud() {}

    public static void tick() {
        if (++ticks % 10 != 0) return;
        showing = false;
        var player = Minecraft.getInstance().player;
        if (player == null || !JetStream.inCubic(player)) return;
        Component text = null;
        if (player.isFallFlying()) {
            text = message(player.getY(), player.getDeltaMovement().horizontalDistance() * 20, player.getDeltaMovement().y, player.getYRot());
        } else if (player.getVehicle() instanceof Boat boat && boat.getControllingPassenger() == player && OceanCurrent.atSea(boat)) {
            text = current(CurrentRide.state(), boat.getDeltaMovement().horizontalDistance() * 20, boat.getYRot());
        }
        if (text == null) return;
        showing = true;
        Component compass = EarthCompassHud.line(player);
        Minecraft.getInstance().gui.setOverlayMessage(compass == null ? text : Component.empty().append(text).append("  ·  ").append(compass), false);
    }

    /** Whether a glider's or a boat's line has the action bar, which the compass's own line then leaves alone. */
    public static boolean showing() { return showing; }

    /** The action bar line for a glider at a height, horizontal speed in blocks a second, climb in blocks a tick and yaw, or null for none. */
    static Component message(double y, double speed, double climb, float yaw) {
        if (y >= JetStream.FLOOR) {
            return Component.translatableWithFallback("earthplatform.jet.riding", "Jet stream: %s blocks/s heading %s",
                    String.format("%,d", Math.round(speed)), heading(yaw));
        }
        if (speed > 100) {
            return Component.translatableWithFallback("earthplatform.jet.leaving", "Out of the jet stream: slowing down, %s blocks/s",
                    String.format("%,d", Math.round(speed)));
        }
        if (y >= HINT_FROM && climb > 0) {
            return Component.translatableWithFallback("earthplatform.jet.hint", "The jet stream blows above Y %s. Keep climbing to catch it.",
                    String.format("%,d", JetStream.FLOOR));
        }
        return null;
    }

    /** The action bar line for a boat at sea as the current has it, at a speed in blocks a second and a yaw, or null for none. */
    @Nullable static Component current(CurrentRide.State state, double speed, float yaw) {
        return switch (state) {
            case RIDING -> Component.translatableWithFallback("earthplatform.current.riding", "Ocean current: %s blocks/s heading %s",
                    String.format("%,d", Math.round(speed)), heading(yaw));
            case SLOWING -> Component.translatableWithFallback("earthplatform.current.leaving", "Out of the current: slowing down, %s blocks/s",
                    String.format("%,d", Math.round(speed)));
            case OPEN -> Component.translatableWithFallback("earthplatform.current.hint", "An ocean current runs here. Sprint and paddle forward to ride it.");
            case NONE -> null;
        };
    }

    /** The compass point a yaw faces, as a word. */
    private static Component heading(float yaw) {
        String heading = HEADINGS[Mth.floor(Mth.wrapDegrees(yaw) / 45.0F + 0.5F) & 7];
        return Component.translatableWithFallback("earthplatform.jet.heading." + heading, heading);
    }
}
