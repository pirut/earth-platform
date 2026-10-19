package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.play.EarthMap;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The creative travel map and the flight that goes with a trip: the camera pulls up off the
 * player until the world gives way to the globe, the globe swings out and round to the
 * destination and closes in on it while the terrain there loads, and once the player has landed
 * the camera drops back down onto them. All of it is camera work on this client; the trip itself
 * is the server's /earth goto.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class EarthFlight {
    public static final KeyMapping KEY = new KeyMapping("key.earthplatform.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.earthplatform");

    /** How high the camera climbs above the player's eyes before the globe takes over, in blocks. */
    static final double HEIGHT = 4500;
    /** The globe's zoom (see {@link EarthGlobeScreen}) that roughly matches the view from that height. */
    static final double NEAR_ZOOM = 9;
    private static final long RISE = 1700, FADE = 450, GLOBE = 3600, LANDED = 800, DESCEND = 1900, PATIENCE = 90_000;
    /** Slows the whole flight down by this factor, for looking at it frame by frame on a slow machine. */
    private static final double PACE = Math.max(0.1, Double.parseDouble(System.getProperty("earthplatform.map.pace", "1")));
    /** Below this height the camera stays in first person, so it never shows the inside of the player's head. */
    private static final double DETACH = 2;

    @Nullable private static EarthFlight current;

    private final double fromLat, fromLon, toLat, toLon, farZoom;
    private final long start = clock();
    private final float startYaw, startPitch;
    private final boolean hidGui;
    private long landedAt = -1, descendAt = -1;
    /** Where the climb began: the camera keeps rising from there even if the trip lands early. */
    @Nullable private Vec3 origin;

    @EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(KEY); }
    }

    private EarthFlight(LocalPlayer player, double fromLat, double fromLon, double toLat, double toLon) {
        this.fromLat = fromLat;
        this.fromLon = fromLon;
        this.toLat = toLat;
        this.toLon = toLon;
        farZoom = FlightPath.farZoom(fromLat, fromLon, toLat, toLon, NEAR_ZOOM);
        startYaw = player.getYRot();
        startPitch = player.getXRot();
        Minecraft minecraft = Minecraft.getInstance();
        hidGui = !minecraft.options.hideGui;
        minecraft.options.hideGui = true;
    }

    // Starting and ending ---------------------------------------------------------------------------

    /** Where the other players are, sent along with a map. */
    public static void others(EarthMap.Players payload) {
        Minecraft.getInstance().execute(() -> EarthGlobeScreen.others(payload.players()));
    }

    /** The server's word on the map or the trip. */
    public static void received(EarthMap.Payload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            switch (payload.kind()) {
                case EarthMap.OPEN -> {
                    if (current == null && minecraft.player != null && minecraft.screen == null)
                        minecraft.setScreen(EarthGlobeScreen.travel(payload.latitude(), payload.longitude()));
                }
                case EarthMap.PICK_SPAWN, EarthMap.PICK_START, EarthMap.PICK_GUESS -> {
                    if (current == null && minecraft.player != null && minecraft.screen == null) {
                        String keys = switch (payload.kind()) {
                            case EarthMap.PICK_SPAWN -> "spawn";
                            case EarthMap.PICK_START -> "start";
                            default -> "guess";
                        };
                        String command = switch (payload.kind()) {
                            case EarthMap.PICK_SPAWN -> "earth spawn set to %.6f %.6f";
                            case EarthMap.PICK_START -> "earth start %.6f %.6f";
                            default -> "earth guess %.6f %.6f";
                        };
                        minecraft.setScreen(EarthGlobeScreen.pick("earthplatform.map." + keys,
                                payload.latitude(), payload.longitude(),
                                (lat, lon) -> minecraft.player.connection.sendCommand(String.format(Locale.ROOT, command, lat, lon))));
                    }
                }
                case EarthMap.ARRIVED -> { if (current != null && current.landedAt < 0) current.landedAt = clock(); }
                case EarthMap.FAILED -> { if (current != null) current.skip(); }
                default -> {}
            }
        });
    }

    /** Takes off for a spot chosen on the map; the server is asked for the trip at once so its downloads overlap the flight. */
    static void start(double fromLat, double fromLon, double toLat, double toLon) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) return;
        player.connection.sendCommand(String.format(Locale.ROOT, "earth goto %.6f %.6f", toLat, toLon));
        if (current != null) current.end();
        current = new EarthFlight(player, fromLat, fromLon, toLat, toLon);
        minecraft.setScreen(EarthGlobeScreen.flight(current));
    }

    /** Comes straight down where the player is now, as when the trip failed or the player pressed Escape. */
    void skip() {
        long now = clock();
        if (descendAt < 0) descendAt = Math.max(now, start + RISE);
    }

    /** The flight is over, finished or cut short; the screen and the HUD go back to normal. */
    void end() {
        closed();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof EarthGlobeScreen screen && screen.flying() == this) minecraft.setScreen(null);
    }

    /** Its screen has gone, by the flight's end or because something else took the screen over. */
    void closed() {
        if (current != this) return;
        current = null;
        if (hidGui) Minecraft.getInstance().options.hideGui = false;
    }

    /** Moves the flight on: called each frame by its screen. */
    void tick() {
        long now = clock();
        if (descendAt < 0) {
            boolean shown = now - globeStart() >= GLOBE;
            if (landedAt >= 0 && shown && now - landedAt >= LANDED) descendAt = now;
            else if (now - start > PATIENCE) descendAt = now;
        }
        if (descendAt >= 0 && now - descendAt >= DESCEND) end();
    }

    // What the screen and camera show ---------------------------------------------------------------

    /** The flight's own clock, in milliseconds. */
    private static long clock() { return Cinema.active() ? Cinema.clock() : (long) (Util.getMillis() / PACE); }

    private long globeStart() { return start + RISE - FADE; }

    /** How opaque the globe is over the world. */
    float globeAlpha() {
        long now = clock();
        if (descending(now)) return (float) (1 - Mth.clamp((now - descendAt) / (double) FADE, 0, 1));
        return (float) Mth.clamp((now - globeStart()) / (double) FADE, 0, 1);
    }

    private boolean descending(long now) { return descendAt >= 0 && now >= descendAt; }

    /** True once the globe has reached the destination and the trip is still waiting on the server. */
    boolean waiting() { return landedAt < 0 && descendAt < 0 && clock() - globeStart() >= GLOBE; }

    double fromLatitude() { return fromLat; }
    double fromLongitude() { return fromLon; }
    double toLatitude() { return toLat; }
    double toLongitude() { return toLon; }

    /** The globe's view now: centre latitude, longitude and zoom. */
    double[] view() {
        double g = clock() - globeStart();
        double zoomOut = 1400, panFrom = 600, panTo = 2700, zoomInFrom = 2000;
        double zoom;
        if (g < zoomOut) zoom = Mth.lerp(FlightPath.smooth(g / zoomOut), NEAR_ZOOM, farZoom);
        else if (g < zoomInFrom) zoom = farZoom;
        else if (g < GLOBE) zoom = Mth.lerp(FlightPath.smooth((g - zoomInFrom) / (GLOBE - zoomInFrom)), farZoom, NEAR_ZOOM);
        // While the terrain loads, keep creeping in.
        else zoom = NEAR_ZOOM + 0.7 * (1 - Math.exp(-(g - GLOBE) / 5000));
        double[] centre = FlightPath.along(fromLat, fromLon, toLat, toLon, FlightPath.smooth((g - panFrom) / (panTo - panFrom)));
        return new double[]{centre[0], centre[1], zoom};
    }

    /**
     * Where the camera is and which way it looks, overriding the player's view: x, y, z, yaw, pitch,
     * and 1 when it has left the player (0 while it is still at their eyes). Null when no flight is on.
     */
    @Nullable public static double[] camera(Entity entity, float partialTick) {
        EarthFlight flight = current;
        if (flight == null || entity != Minecraft.getInstance().player) return null;
        Vec3 eye = entity.getEyePosition(partialTick);
        long now = clock();
        double height, yaw, pitch;
        if (!flight.descending(now)) {
            if (flight.origin == null) flight.origin = eye;
            eye = flight.origin;
            double p = Mth.clamp((now - flight.start) / (double) RISE, 0, 1), s = FlightPath.smooth(p);
            // Slow off the ground, then away fast.
            height = HEIGHT * s * s;
            yaw = flight.startYaw;
            pitch = Mth.lerp(FlightPath.smooth(p / 0.55), flight.startPitch, 90);
        } else {
            double q = Mth.clamp((now - flight.descendAt) / (double) DESCEND, 0, 1), s = FlightPath.smooth(q);
            // Fast from up high, settling gently onto the player.
            height = HEIGHT * (1 - s) * (1 - s);
            yaw = entity.getViewYRot(partialTick);
            pitch = Mth.lerp(FlightPath.smooth((q - 0.45) / 0.55), 90, entity.getViewXRot(partialTick));
        }
        return new double[]{eye.x, eye.y + height, eye.z, yaw, pitch, height >= DETACH ? 1 : 0};
    }

    public static boolean flying() { return current != null; }

    // The key ---------------------------------------------------------------------------------------

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (KEY.consumeClick()) {
            LocalPlayer player = minecraft.player;
            if (player == null || minecraft.screen != null || current != null) continue;
            if (!player.isCreative() && !player.hasPermissions(2)) {
                player.displayClientMessage(Component.translatable("earthplatform.map.creativeOnly"), true);
                continue;
            }
            player.connection.sendCommand("earth map");
        }
    }
}
