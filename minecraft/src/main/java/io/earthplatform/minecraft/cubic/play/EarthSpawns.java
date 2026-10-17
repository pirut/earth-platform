package io.earthplatform.minecraft.cubic.play;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Where people land on an Earth world, as its operators set it: the server spawn (in place of the
 * world's home), and each player's own spawn and home. Saved with the world; spots are latitude and
 * longitude, so they stay put whatever the world's start.
 */
public final class EarthSpawns extends SavedData {
    private static final String NAME = "earthplatform_spawns";

    /** A spot on the Earth, facing a sight (NaN for none), with a name to tell people (may be empty). */
    public record Spot(double latitude, double longitude, double viewLatitude, double viewLongitude, String name) {
        public static Spot at(double latitude, double longitude) { return new Spot(latitude, longitude, Double.NaN, Double.NaN, ""); }

        public static Spot of(EarthPlaces.Place place) {
            return new Spot(place.latitude(), place.longitude(), place.viewLatitude(), place.viewLongitude(), place.label());
        }

        /** The name, or the latitude and longitude. */
        public String label() {
            return name.isEmpty() ? String.format(java.util.Locale.ROOT, "%.4f, %.4f", latitude, longitude) : name;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("lat", latitude);
            tag.putDouble("lon", longitude);
            if (!Double.isNaN(viewLatitude)) { tag.putDouble("viewLat", viewLatitude); tag.putDouble("viewLon", viewLongitude); }
            if (!name.isEmpty()) tag.putString("name", name);
            return tag;
        }

        static Spot load(CompoundTag tag) {
            return new Spot(tag.getDouble("lat"), tag.getDouble("lon"),
                    tag.contains("viewLat") ? tag.getDouble("viewLat") : Double.NaN, tag.contains("viewLon") ? tag.getDouble("viewLon") : Double.NaN,
                    tag.getString("name"));
        }
    }

    @Nullable private Spot spawn;
    private final Map<UUID, Spot> spawns = new HashMap<>(), homes = new HashMap<>();
    /** Players who have used their one choice of start. */
    private final Set<UUID> started = new HashSet<>();
    /** Named spots anyone may go to, in the order they were made. */
    private final Map<String, Spot> warps = new java.util.TreeMap<>();
    /** Spawns of scoreboard teams, by team name. */
    private final Map<String, Spot> teams = new HashMap<>();
    /** Where players may go, or null for the whole Earth. */
    @Nullable private Border border;
    /** The day (since 1970, in UTC) the last daily race started. */
    private long raceDay = -1;

    /**
     * A limit on where players may go: a latitude and longitude box, or a country's borders when
     * {@code country} is set (then the box is its extent).
     */
    public record Border(double south, double west, double north, double east, String country) {
        public boolean contains(double latitude, double longitude) {
            if (latitude < south || latitude > north || longitude < west || longitude > east) return false;
            if (country.isEmpty()) return true;
            EarthCountries.Country found = EarthCountries.find(country).orElse(null);
            return found == null || found.contains(latitude, longitude);
        }

        public String label() {
            return country.isEmpty() ? String.format(java.util.Locale.ROOT, "%.3f to %.3f latitude, %.3f to %.3f longitude", south, north, west, east) : country;
        }
    }

    public static EarthSpawns of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(EarthSpawns::new, EarthSpawns::load, null), NAME);
    }

    public Optional<Spot> spawn() { return Optional.ofNullable(spawn); }
    public Optional<Spot> spawn(UUID player) { return Optional.ofNullable(spawns.get(player)); }
    public Optional<Spot> home(UUID player) { return Optional.ofNullable(homes.get(player)); }
    public boolean started(UUID player) { return started.contains(player); }
    public Map<String, Spot> warps() { return java.util.Collections.unmodifiableMap(warps); }
    public Optional<Spot> team(String team) { return Optional.ofNullable(teams.get(team)); }
    public Optional<Border> border() { return Optional.ofNullable(border); }
    public long raceDay() { return raceDay; }

    /** A player's own spawn, else their team's; empty when they spawn at the server spawn. */
    public Optional<Spot> personal(net.minecraft.world.entity.player.Player player) {
        Spot own = spawns.get(player.getUUID());
        if (own != null) return Optional.of(own);
        var team = player.getTeam();
        return team == null ? Optional.empty() : team(team.getName());
    }

    public void setWarp(String name, @Nullable Spot spot) {
        if (spot == null) warps.remove(name); else warps.put(name, spot);
        setDirty();
    }

    public void setTeam(String team, @Nullable Spot spot) {
        if (spot == null) teams.remove(team); else teams.put(team, spot);
        setDirty();
    }

    public void setBorder(@Nullable Border border) { this.border = border; setDirty(); }
    public void setRaceDay(long day) { raceDay = day; setDirty(); }

    public void setSpawn(@Nullable Spot spot) { spawn = spot; setDirty(); }

    public void setSpawn(UUID player, @Nullable Spot spot) {
        if (spot == null) { spawns.remove(player); started.remove(player); } else spawns.put(player, spot);
        setDirty();
    }

    public void setHome(UUID player, @Nullable Spot spot) {
        if (spot == null) homes.remove(player); else homes.put(player, spot);
        setDirty();
    }

    public void start(UUID player, Spot spot) {
        started.add(player);
        spawns.put(player, spot);
        setDirty();
    }

    private static EarthSpawns load(CompoundTag tag, HolderLookup.Provider registries) {
        EarthSpawns spawns = new EarthSpawns();
        if (tag.contains("spawn")) spawns.spawn = Spot.load(tag.getCompound("spawn"));
        read(tag.getCompound("spawns"), spawns.spawns);
        read(tag.getCompound("homes"), spawns.homes);
        CompoundTag warps = tag.getCompound("warps");
        for (String name : warps.getAllKeys()) spawns.warps.put(name, Spot.load(warps.getCompound(name)));
        CompoundTag teams = tag.getCompound("teams");
        for (String name : teams.getAllKeys()) spawns.teams.put(name, Spot.load(teams.getCompound(name)));
        if (tag.contains("border")) {
            CompoundTag b = tag.getCompound("border");
            spawns.border = new Border(b.getDouble("south"), b.getDouble("west"), b.getDouble("north"), b.getDouble("east"), b.getString("country"));
        }
        if (tag.contains("raceDay")) spawns.raceDay = tag.getLong("raceDay");
        for (Tag id : tag.getList("started", Tag.TAG_STRING)) {
            try { spawns.started.add(UUID.fromString(id.getAsString())); } catch (IllegalArgumentException ignored) {}
        }
        return spawns;
    }

    private static void read(CompoundTag tag, Map<UUID, Spot> into) {
        for (String key : tag.getAllKeys()) {
            try { into.put(UUID.fromString(key), Spot.load(tag.getCompound(key))); } catch (IllegalArgumentException ignored) {}
        }
    }

    private static CompoundTag write(Map<UUID, Spot> spots) {
        CompoundTag tag = new CompoundTag();
        spots.forEach((id, spot) -> tag.put(id.toString(), spot.save()));
        return tag;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (spawn != null) tag.put("spawn", spawn.save());
        tag.put("spawns", write(spawns));
        tag.put("homes", write(homes));
        var list = new net.minecraft.nbt.ListTag();
        for (UUID id : started) list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        tag.put("started", list);
        CompoundTag warpTag = new CompoundTag();
        warps.forEach((name, spot) -> warpTag.put(name, spot.save()));
        tag.put("warps", warpTag);
        CompoundTag teamTag = new CompoundTag();
        teams.forEach((name, spot) -> teamTag.put(name, spot.save()));
        tag.put("teams", teamTag);
        if (border != null) {
            CompoundTag b = new CompoundTag();
            b.putDouble("south", border.south); b.putDouble("west", border.west); b.putDouble("north", border.north); b.putDouble("east", border.east);
            b.putString("country", border.country);
            tag.put("border", b);
        }
        tag.putLong("raceDay", raceDay);
        return tag;
    }
}
