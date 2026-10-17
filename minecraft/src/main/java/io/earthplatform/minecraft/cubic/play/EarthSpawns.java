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

    public static EarthSpawns of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(EarthSpawns::new, EarthSpawns::load, null), NAME);
    }

    public Optional<Spot> spawn() { return Optional.ofNullable(spawn); }
    public Optional<Spot> spawn(UUID player) { return Optional.ofNullable(spawns.get(player)); }
    public Optional<Spot> home(UUID player) { return Optional.ofNullable(homes.get(player)); }
    public boolean started(UUID player) { return started.contains(player); }

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
        return tag;
    }
}
