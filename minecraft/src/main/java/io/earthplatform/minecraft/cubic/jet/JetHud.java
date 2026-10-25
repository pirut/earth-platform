package io.earthplatform.minecraft.cubic.jet;

import io.earthplatform.minecraft.cubic.play.client.EarthCompassHud;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Tells a glider about the jet stream above the action bar: a hint when it climbs toward the wind,
 * its speed and heading while riding, and a word when it leaves and slows down. A compass in hand
 * adds what it points to and how far, so a rider sees the distance to where they're going.
 */
@EventBusSubscriber(modid = "earthplatform", value = Dist.CLIENT)
public final class JetHud {
    /** Gliders this high hear about the wind above. */
    public static final int HINT_FROM = 8000;
    private static final String[] HEADINGS = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
    private static int ticks;
    /** Whether the action bar carries this glider's line now. */
    private static boolean showing;

    private JetHud() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (++ticks % 10 != 0) return;
        showing = false;
        var player = Minecraft.getInstance().player;
        if (player == null || !player.isFallFlying() || !JetStream.inCubic(player)) return;
        Component text = message(player.getY(), player.getDeltaMovement().horizontalDistance() * 20, player.getDeltaMovement().y, player.getYRot());
        if (text == null) return;
        showing = true;
        Component compass = EarthCompassHud.line(player);
        Minecraft.getInstance().gui.setOverlayMessage(compass == null ? text : Component.empty().append(text).append("  ·  ").append(compass), false);
    }

    /** Whether a glider's line has the action bar, which the compass's own line then leaves alone. */
    public static boolean showing() { return showing; }

    /** The action bar line for a glider at a height, horizontal speed in blocks a second, climb in blocks a tick and yaw, or null for none. */
    static Component message(double y, double speed, double climb, float yaw) {
        if (y >= JetStream.FLOOR) {
            String heading = HEADINGS[Mth.floor(Mth.wrapDegrees(yaw) / 45.0F + 0.5F) & 7];
            return Component.translatableWithFallback("earthplatform.jet.riding", "Jet stream: %s blocks/s heading %s",
                    String.format("%,d", Math.round(speed)), Component.translatableWithFallback("earthplatform.jet.heading." + heading, heading));
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
}
