package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The server's rules for getting about the Earth, and what it watches each second: the region
 * border (trips past it are refused, and players who walk out are put back), what travel costs in
 * survival, and the country each player is in, named on screen as they cross into it.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class EarthRules {
    /** Where each player last stood inside the border, to put them back when they walk out. */
    private static final Map<UUID, Vec3> INSIDE = new HashMap<>();
    /** The country each player was last in ("" at sea). */
    private static final Map<UUID, String> COUNTRY = new HashMap<>();
    /** When each player may next travel, for the cooldown. */
    private static final Map<UUID, Long> NEXT_TRIP = new HashMap<>();

    private EarthRules() {}

    @Nullable static EarthGenerator earth(ServerPlayer player) {
        return CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes && cubes.generator() instanceof EarthGenerator earth ? earth : null;
    }

    /** Where a player stands on the Earth, or null off it. */
    @Nullable static GeoPoint where(ServerPlayer player) {
        EarthGenerator earth = earth(player);
        return earth == null ? null : earth.grid().geographic(player.getX(), player.getZ());
    }

    /**
     * Why a trip there may not happen, or null when it may: it would leave the server's border.
     * Every trip asks, whoever sent it; a race also notes who travelled.
     */
    @Nullable public static String refuse(ServerPlayer player, double latitude, double longitude) {
        Optional<EarthSpawns.Border> border = EarthSpawns.of(player.server).border();
        if (border.isPresent() && !border.get().contains(latitude, longitude))
            return "That's outside this server's border (" + border.get().label() + ").";
        EarthRace.travelled(player);
        return null;
    }

    /**
     * Charges a player for a trip they started themselves, in survival or adventure, as the server's
     * settings say: experience levels by distance, and a cooldown between trips. Operators and creative
     * players go free. Tells the player and returns false when they can't pay or must wait.
     */
    static boolean pay(CommandSourceStack source, ServerPlayer player, double latitude, double longitude) {
        if (source.getPlayer() != player || source.hasPermission(2) || player.isCreative() || player.isSpectator()) return true;
        long now = net.minecraft.Util.getMillis();
        long next = NEXT_TRIP.getOrDefault(player.getUUID(), 0L);
        if (now < next) {
            source.sendFailure(Component.literal("You can travel again in " + ((next - now + 999) / 1000) + " s."));
            return false;
        }
        int levels = cost(player, latitude, longitude);
        if (levels > player.experienceLevel) {
            source.sendFailure(Component.literal("This trip costs " + levels + " experience levels; you have " + player.experienceLevel + "."));
            return false;
        }
        if (levels > 0) {
            player.giveExperienceLevels(-levels);
            source.sendSuccess(() -> Component.literal("Paid " + levels + " experience levels for the trip.").withStyle(ChatFormatting.GRAY), false);
        }
        int cooldown = EarthConfig.TRAVEL_COOLDOWN.get();
        if (cooldown > 0) NEXT_TRIP.put(player.getUUID(), now + cooldown * 1000L);
        return true;
    }

    /** Levels a trip costs: the server's rate per 1000 km, at least one level when there is a rate; NaN for an unknown destination costs 1000 km. */
    static int cost(ServerPlayer player, double latitude, double longitude) {
        int rate = EarthConfig.TRAVEL_LEVELS_PER_1000_KM.get();
        if (rate <= 0) return 0;
        GeoPoint here = where(player);
        double km = here == null || Double.isNaN(latitude) ? 1000 : kilometers(here.latitude(), here.longitude(), latitude, longitude);
        return Math.max(1, (int) Math.round(km / 1000 * rate));
    }

    /** Great-circle distance in kilometers. */
    public static double kilometers(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371.0088 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Compass direction from one point toward another, as N, NE, E... */
    public static String heading(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double bearing = Math.toDegrees(Math.atan2(Math.sin(dl) * Math.cos(p2), Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)));
        String[] names = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        return names[(int) Math.floorMod(Math.round((bearing + 360) / 45), 8)];
    }

    // Each second -------------------------------------------------------------------------------

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        EarthSpawns spawns = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GeoPoint point = where(player);
            if (point == null) continue;
            if (spawns == null) spawns = EarthSpawns.of(server);
            border(player, point, spawns.border().orElse(null));
            country(player, point);
        }
        EarthRace.second(server);
        EarthGuess.second(server);
        EarthCompass.second(server);
        EarthTeleports.second(server);
        EarthAdmin.second(server);
    }

    private static void border(ServerPlayer player, GeoPoint point, @Nullable EarthSpawns.Border border) {
        if (border == null || border.contains(point.latitude(), point.longitude())) {
            INSIDE.put(player.getUUID(), player.position());
            return;
        }
        if (player.hasPermissions(2) && player.isCreative()) return;
        Vec3 back = INSIDE.get(player.getUUID());
        player.displayClientMessage(Component.literal("You've reached the edge of this server's map.").withStyle(ChatFormatting.RED), true);
        // Someone already outside when the border was drawn (or who arrived there by an operator's command) stays put.
        if (back == null) return;
        player.teleportTo(player.serverLevel(), back.x, back.y, back.z, player.getYRot() + 180, player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    /** Names the country a player has just crossed into, on screen. */
    private static void country(ServerPlayer player, GeoPoint point) {
        if (!EarthConfig.COUNTRY_NAMES.get() || EarthGuess.playing(player)) return;
        EarthCountries.Country country = EarthCountries.at(point.latitude(), point.longitude());
        String name = country == null ? "" : country.name();
        String was = COUNTRY.put(player.getUUID(), name);
        if (name.isEmpty() || name.equals(was)) return;
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Now entering " + name).withStyle(ChatFormatting.WHITE)));
    }

    /** The country a player is in, or empty. */
    public static String country(ServerPlayer player) {
        GeoPoint point = where(player);
        if (point == null) return "";
        EarthCountries.Country country = EarthCountries.at(point.latitude(), point.longitude());
        return country == null ? "" : country.name();
    }

    @SubscribeEvent public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        INSIDE.remove(id);
        COUNTRY.remove(id);
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        INSIDE.clear();
        COUNTRY.clear();
        NEXT_TRIP.clear();
    }
}
