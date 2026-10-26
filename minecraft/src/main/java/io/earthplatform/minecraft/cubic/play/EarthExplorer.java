package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The Earth's own tab of advancements, for going places on it: high ground and low, the deep sea,
 * the equator, the date line and the polar circles, countries, the far side of the world from your
 * spawn and the whole way round it. The tab's files are in {@code data/earthplatform/advancement/earth};
 * all but its root are awarded from here, as the server looks at its players each second. Heights
 * are the generated ground's, so a tower or a mine shaft doesn't count. Nothing is awarded during a
 * Where on Earth round, where a toast for the Arctic Circle would give the answer away.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class EarthExplorer {
    /** Farther than anyone travels in a second (a glider in the jet stream goes under 1,700 blocks): a teleport. */
    private static final double STEP = 4000;
    /** Latitude of the Arctic and Antarctic Circles. */
    private static final double POLAR = 66.563;
    private static final double FAR_KM = 5000, ANTIPODE_KM = 100;
    /** Countries Globetrotter and World Traveler count to, a criterion each. */
    static final int GLOBETROTTER = 10, WORLD_TRAVELER = 50;
    /** Around the World has a criterion for each ten degrees of longitude a trip goes round. */
    static final int ROUND_STEPS = 36;
    /** Where each player on the Earth was at the last look. */
    private static final Map<UUID, Seen> LAST = new HashMap<>();
    private static final Map<String, ResourceLocation> IDS = new HashMap<>();

    private record Seen(ResourceKey<Level> level, double x, double z, GeoPoint point) {}

    private EarthExplorer() {}

    /** Looks at a player on the Earth, at {@code here} in {@code country} (null at sea), and awards what they've done since a second ago. */
    static void step(ServerPlayer player, GeoPoint here, @Nullable EarthCountries.Country country) {
        EarthGenerator earth = EarthRules.earth(player);
        if (earth == null) return;
        // A spectator goes through walls, and a round drops everyone somewhere at random: neither is a trip.
        if (player.isSpectator() || EarthGuess.playing(player)) {
            away(player);
            return;
        }
        Journeys journeys = Journeys.of(player.server);
        UUID id = player.getUUID();
        Seen last = LAST.put(id, new Seen(player.level().dimension(), player.getX(), player.getZ(), here));
        if (last != null) {
            // The Earth repeats east and west, so a hop to the same spot in the next copy of it is no move at all.
            double dx = Math.IEEEremainder(player.getX() - last.x, earth.grid().worldWidthBlocks()), dz = player.getZ() - last.z;
            if (last.level == player.level().dimension() && dx * dx + dz * dz <= STEP * STEP) travelled(player, journeys, last.point, here);
            else journeys.endTrip(id);
        }
        if (here.latitude() >= POLAR) award(player, "arctic_circle");
        if (here.latitude() <= -POLAR) award(player, "antarctic_circle");
        if (country != null) journeys.visit(player, country.name());
        GeoPoint spawn = EarthCompass.spawnOf(player, earth);
        if (EarthRules.kilometers(spawn.latitude(), spawn.longitude(), here.latitude(), here.longitude()) >= FAR_KM) award(player, "far_from_home");
        if (EarthRules.kilometers(-spawn.latitude(), spawn.longitude() + 180, here.latitude(), here.longitude()) <= ANTIPODE_KM) award(player, "antipodes");
        heights(player, earth);
    }

    /** A player left the Earth or stopped playing on it for now: whatever they do next is a fresh start. */
    static void away(ServerPlayer player) {
        if (LAST.remove(player.getUUID()) != null) Journeys.of(player.server).endTrip(player.getUUID());
    }

    /** A second's travel between two points, by any means but a teleport. */
    private static void travelled(ServerPlayer player, Journeys journeys, GeoPoint from, GeoPoint to) {
        if ((from.latitude() < 0) != (to.latitude() < 0)) award(player, "equator");
        // Longitudes jump from 180 to -180 at the date line, and nowhere else in a second's travel.
        if (Math.abs(to.longitude() - from.longitude()) > 180) award(player, "date_line");
        double east = east(from.longitude(), to.longitude());
        if (east != 0) journeys.travelled(player, east);
    }

    /** Degrees east from one longitude to another (west when negative) the short way, so 179.9° E to 179.9° W is a fifth of a degree east. */
    static double east(double from, double to) { return Math.IEEEremainder(to - from, 360); }

    /** High ground and low, and the deep sea, by the generated ground under the player: they have to be on it or above it. */
    private static void heights(ServerPlayer player, EarthGenerator earth) {
        int sea = earth.seaLevel(), feet = player.getBlockY();
        boolean high = feet >= sea + 2000 && player.onGround(), low = feet <= sea - 49, deep = feet <= sea - 100 && player.isUnderWater();
        if (!high && !low && !deep) return;
        int x = player.getBlockX(), z = player.getBlockZ(), ground = earth.surfaceY(x, z), water = earth.waterY(x, z);
        if (feet < ground) return;
        int metres = ground - sea;
        if (high) {
            if (metres >= 2000) award(player, "high_country");
            if (metres >= 4000) award(player, "four_thousander");
            if (metres >= 8000) award(player, "death_zone");
        }
        // Ground below the sea, and any water on it as low: the Dead Sea's shore counts, the sea floor doesn't.
        if (low && Math.max(ground, water) <= sea - 50) award(player, "below_sea_level");
        if (deep && Math.abs(water - sea) <= 1) {
            award(player, "deep_diver");
            if (feet <= sea - 1000) award(player, "abyss");
        }
    }

    /** Gives a player the one criterion of an Earth advancement that has one, named after the advancement. */
    static void award(ServerPlayer player, String name) { award(player, name, name); }

    /** Gives a player a criterion of one of the Earth advancements, if they haven't got it. */
    static void award(ServerPlayer player, String name, String criterion) {
        ResourceLocation id = IDS.computeIfAbsent(name, path -> ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth/" + path));
        AdvancementHolder advancement = player.server.getAdvancements().get(id);
        if (advancement != null) player.getAdvancements().award(advancement, criterion);
    }

    /** Gives every criterion of a counting advancement up to a count: "3/10" countries, and so on. */
    private static void count(ServerPlayer player, String name, int count, int of) {
        for (int i = 1; i <= Math.min(count, of); i++) award(player, name, Integer.toString(i));
    }

    @SubscribeEvent public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { LAST.remove(event.getEntity().getUUID()); }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LAST.clear(); }

    /** One player's journeys: the countries they've been to, how far east this trip has gone (west is negative) and the farthest any trip went round, in degrees. */
    private static final class Journey {
        final Set<String> countries = new LinkedHashSet<>();
        double east, farthest;
    }

    /** What the explorer keeps with the world: each player's journeys, so trips and countries last from one session to the next. */
    static final class Journeys extends SavedData {
        private static final String NAME = "earthplatform_journeys";
        private final Map<UUID, Journey> players = new HashMap<>();

        static Journeys of(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Journeys::new, Journeys::load, null), NAME);
        }

        private Journey journey(UUID player) { return players.computeIfAbsent(player, id -> new Journey()); }

        /** Notes a country a player is in, and counts the ones they've been to. */
        void visit(ServerPlayer player, String country) {
            Journey journey = journey(player.getUUID());
            if (!journey.countries.add(country)) return;
            setDirty();
            int count = journey.countries.size();
            if (count >= 2) award(player, "border_crosser");
            count(player, "globetrotter", count, GLOBETROTTER);
            count(player, "world_traveler", count, WORLD_TRAVELER);
        }

        /** Adds a second's travel east (west when negative) to a player's trip round the world. */
        void travelled(ServerPlayer player, double east) {
            Journey journey = journey(player.getUUID());
            journey.east += east;
            setDirty();
            int before = (int) (journey.farthest / 10);
            journey.farthest = Math.max(journey.farthest, Math.abs(journey.east));
            if ((int) (journey.farthest / 10) > before) count(player, "around_the_world", (int) (journey.farthest / 10), ROUND_STEPS);
        }

        /** A teleport ends a player's trip round the world: the next one starts from nothing. */
        void endTrip(UUID player) {
            Journey journey = players.get(player);
            if (journey == null || journey.east == 0) return;
            journey.east = 0;
            setDirty();
        }

        private static Journeys load(CompoundTag tag, HolderLookup.Provider registries) {
            Journeys journeys = new Journeys();
            for (String key : tag.getAllKeys()) {
                UUID id;
                try { id = UUID.fromString(key); } catch (IllegalArgumentException ignored) { continue; }
                CompoundTag saved = tag.getCompound(key);
                Journey journey = journeys.journey(id);
                for (Tag country : saved.getList("countries", Tag.TAG_STRING)) journey.countries.add(country.getAsString());
                journey.east = saved.getDouble("east");
                journey.farthest = saved.getDouble("farthest");
            }
            return journeys;
        }

        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            players.forEach((id, journey) -> {
                CompoundTag saved = new CompoundTag();
                ListTag countries = new ListTag();
                for (String country : journey.countries) countries.add(StringTag.valueOf(country));
                saved.put("countries", countries);
                saved.putDouble("east", journey.east);
                saved.putDouble("farthest", journey.farthest);
                tag.put(id.toString(), saved);
            });
            return tag;
        }
    }
}
