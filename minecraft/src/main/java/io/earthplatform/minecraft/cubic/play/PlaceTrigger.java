package io.earthplatform.minecraft.cubic.play;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The advancement criterion {@code earthplatform:reached_place}: a player is standing at a real
 * latitude and longitude, whichever Earth world they are in. Datapacks and quest mods name the
 * place in degrees, so the same advancement is right in a world that started in Yosemite and one
 * that started at the equator, where the same landmark is at different blocks.
 *
 * <p>The server looks at each player on the Earth once a second ({@link EarthRules#tick}), converts
 * the position with the world's own projection (the one {@code /earth where} uses) and asks this
 * trigger. It does nothing for a player with no such criterion to meet, so the cost of the check is
 * a map lookup. The distance is the great circle's, in real metres, so it does not depend on how
 * stretched the map is at that latitude. Height is in real metres above sea level: a block of height
 * is a metre everywhere ({@link EarthStretch}).
 */
public final class PlaceTrigger extends SimpleCriterionTrigger<PlaceTrigger.Instance> {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "reached_place");
    public static final PlaceTrigger INSTANCE = new PlaceTrigger();

    /** The radius a criterion gets when it names none, in metres. */
    public static final double DEFAULT_RADIUS_METRES = 100;
    /** The largest radius that makes sense: half the way round the Earth. */
    public static final double MAX_RADIUS_METRES = 20_100_000;
    /**
     * Metres along a meridian for each degree of latitude, on the sphere {@link EarthRules#kilometers} measures on.
     * Two points a degree of latitude apart are at least this far apart, which lets most checks skip the trigonometry.
     */
    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;

    private PlaceTrigger() {}

    @Override public Codec<Instance> codec() { return Instance.CODEC; }

    /**
     * Looks at a player standing at {@code latitude} and {@code longitude}, {@code elevation} metres above sea
     * level, and grants every {@code reached_place} criterion they have met.
     */
    public void fire(ServerPlayer player, double latitude, double longitude, double elevation) {
        trigger(player, instance -> instance.matches(latitude, longitude, elevation));
    }

    /**
     * The real metres between two places, along the great circle (haversine). Longitudes go round, so 179.9° E and
     * 179.9° W are a fifth of a degree apart, and at a pole every longitude is the same place.
     */
    public static double metresBetween(double lat1, double lon1, double lat2, double lon2) {
        return EarthRules.kilometers(lat1, lon1, lat2, lon2) * 1000;
    }

    /**
     * A criterion's conditions. {@code latitude} and {@code longitude} are degrees, {@code radiusMetres} the real
     * horizontal distance that counts as there and {@code elevation} bounds the metres above sea level (any by default).
     */
    public record Instance(Optional<ContextAwarePredicate> player, double latitude, double longitude, double radiusMetres,
                           MinMaxBounds.Doubles elevation) implements SimpleInstance {
        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(builder -> builder.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                Codec.doubleRange(-90, 90).fieldOf("latitude").forGetter(Instance::latitude),
                Codec.doubleRange(-180, 180).fieldOf("longitude").forGetter(Instance::longitude),
                Codec.doubleRange(Double.MIN_NORMAL, MAX_RADIUS_METRES).optionalFieldOf("radius_m", DEFAULT_RADIUS_METRES).forGetter(Instance::radiusMetres),
                MinMaxBounds.Doubles.CODEC.optionalFieldOf("elevation_m", MinMaxBounds.Doubles.ANY).forGetter(Instance::elevation)
        ).apply(builder, Instance::new));

        /** Whether standing at a place, {@code elevation} metres up, meets these conditions (the player predicate is checked by the trigger). */
        public boolean matches(double lat, double lon, double elevationMetres) {
            // A degree of latitude is at least 111 km, so a player a few degrees south of the place is too far without any
            // trigonometry (the margin keeps rounding from turning away a player exactly on the radius).
            if (Math.abs(lat - latitude) * METRES_PER_DEGREE > radiusMetres * (1 + 1e-9) + 1e-9) return false;
            if (!elevation.matches(elevationMetres)) return false;
            return metresBetween(latitude, longitude, lat, lon) <= radiusMetres;
        }
    }
}
