package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.geography.GeoPoint;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The server's rules for getting about the Earth, and what it watches each second: the region
 * border (trips past it are refused, and players who walk out are put back), what travel costs in
 * survival, and the country each player is in, named on screen as they cross into it. It hands
 * each player's whereabouts on to the explorer advancements too ({@link EarthExplorer}).
 */
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
        return earth == null ? null : earth.grid().tryGeographic(player.getX(), player.getZ());
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
     * What a trip will cost when it lands: experience levels by distance, and the cooldown between
     * trips it starts. Nothing is taken when it sets off, so a trip that does not land costs nothing
     * and there is nothing to give back.
     */
    public record Fare(int levels, boolean charged) {
        /** Operators, creative players and trips sent by others: nothing, and no cooldown. */
        public static final Fare FREE = new Fare(0, false);
    }

    /** What a fare is charged to: a player's experience levels. */
    interface Wallet {
        UUID id();
        int levels();
        void spend(int levels);
    }

    private static Wallet wallet(ServerPlayer player) {
        return new Wallet() {
            @Override public UUID id() { return player.getUUID(); }
            @Override public int levels() { return player.experienceLevel; }
            @Override public void spend(int levels) { player.giveExperienceLevels(-levels); }
        };
    }

    /**
     * Whether a player may set off on a trip they started themselves, in survival or adventure, as
     * the server's settings say, and what it will cost when it lands: null, telling them why, when
     * they must wait or can't pay. Operators, creative players and trips sent by others go free.
     * Charges nothing.
     */
    @Nullable static Fare quote(CommandSourceStack source, ServerPlayer player, double latitude, double longitude) {
        if (source.getPlayer() != player || source.hasPermission(2) || player.isCreative() || player.isSpectator()) return Fare.FREE;
        int levels = cost(player, latitude, longitude);
        String refused = refusal(wallet(player), levels, net.minecraft.Util.getMillis());
        if (refused != null) {
            source.sendFailure(Component.literal(refused));
            return null;
        }
        return new Fare(levels, true);
    }

    /**
     * Lands a trip: makes {@code move} (the teleport, true once the player is confirmed at the
     * destination), then charges the fare (null or free: nothing) and starts the cooldown. False,
     * telling the player why, when they can no longer pay or another trip's cooldown now runs (then
     * no move is made), or when something stopped the move (a mod vetoing the teleport): then they
     * stay where they are, pay nothing and start no cooldown.
     */
    public static boolean land(CommandSourceStack source, ServerPlayer player, @Nullable Fare fare, java.util.function.BooleanSupplier move) {
        String refused = landTrip(wallet(player), fare, net.minecraft.Util.getMillis(), EarthConfig.TRAVEL_COOLDOWN.get(), move);
        if (refused != null) {
            source.sendFailure(Component.literal(refused));
            return false;
        }
        if (fare != null && fare.charged() && fare.levels() > 0)
            source.sendSuccess(() -> Component.literal("Paid " + fare.levels() + " experience levels for the trip.").withStyle(ChatFormatting.GRAY), false);
        return true;
    }

    /**
     * {@link #land} for a fare and a wallet: why the trip did not land, or null once it landed and
     * was paid for. The fare is checked before the move (nothing taken), and taken only after it.
     */
    @Nullable static String landTrip(Wallet wallet, @Nullable Fare fare, long now, int cooldownSeconds, java.util.function.BooleanSupplier move) {
        if (fare != null && fare.charged()) {
            String refused = refusal(wallet, fare.levels(), now);
            if (refused != null) return refused.startsWith("This trip costs")
                    ? "This trip costs " + fare.levels() + " experience levels and you now have " + wallet.levels() + "; it did not go." : refused;
        }
        if (!move.getAsBoolean()) return "The trip did not go: something stopped the move.";
        return charge(wallet, fare, now, cooldownSeconds);
    }

    /** Why a trip costing {@code levels} may not set off now, or null when it may. Takes nothing. */
    @Nullable static String refusal(Wallet wallet, int levels, long now) {
        long next = NEXT_TRIP.getOrDefault(wallet.id(), 0L);
        if (now < next) return "You can travel again in " + ((next - now + 999) / 1000) + " s.";
        if (levels > wallet.levels()) return "This trip costs " + levels + " experience levels; you have " + wallet.levels() + ".";
        return null;
    }

    /**
     * Charges a trip's fare as it lands and starts the cooldown, or says why it can't land: it still
     * may not set off (another trip's cooldown runs) or the levels are no longer there.
     */
    @Nullable static String charge(Wallet wallet, @Nullable Fare fare, long now, int cooldownSeconds) {
        if (fare == null || !fare.charged()) return null;
        String refused = refusal(wallet, fare.levels(), now);
        if (refused != null) return refused.startsWith("This trip costs")
                ? "This trip costs " + fare.levels() + " experience levels and you now have " + wallet.levels() + "; it did not go." : refused;
        if (fare.levels() > 0) wallet.spend(fare.levels());
        if (cooldownSeconds > 0) NEXT_TRIP.put(wallet.id(), now + cooldownSeconds * 1000L);
        return null;
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

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        EarthSpawns spawns = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GeoPoint point = where(player);
            if (point == null) { EarthExplorer.away(player); continue; }
            if (spawns == null) spawns = EarthSpawns.of(server);
            border(player, point, spawns.border().orElse(null));
            EarthCountries.Country country = EarthCountries.at(point.latitude(), point.longitude());
            country(player, country);
            EarthExplorer.step(player, point, country);
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
    private static void country(ServerPlayer player, @Nullable EarthCountries.Country country) {
        if (!EarthConfig.COUNTRY_NAMES.get() || EarthGuess.playing(player)) return;
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

    public static void loggedOut(Player entity) {
        UUID id = entity.getUUID();
        INSIDE.remove(id);
        COUNTRY.remove(id);
    }

    public static void stopped() {
        INSIDE.clear();
        COUNTRY.clear();
        NEXT_TRIP.clear();
    }
}
