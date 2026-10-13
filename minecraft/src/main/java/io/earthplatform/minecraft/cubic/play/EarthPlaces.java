package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Famous wild places to start in or travel to. No cities: the Earth here is wild land. */
public final class EarthPlaces {
    /**
     * A place to start: where to land, and what to face on arrival (the peak or sight the place is
     * known for). Without a view, arrivals face the named point when the landing is away from it.
     */
    public record Place(String id, String name, String region, double latitude, double longitude, double viewLatitude, double viewLongitude) {
        public Place(String id, String name, String region, double latitude, double longitude) {
            this(id, name, region, latitude, longitude, latitude, longitude);
        }
        public String label() { return name + ", " + region; }
    }

    public static final Place YOSEMITE = new Place("yosemite", "Yosemite Valley", "California",
            EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);

    private static final List<Place> ALL = List.of(
            YOSEMITE,
            new Place("grand_canyon", "Grand Canyon", "Arizona", 36.0619, -112.1076),
            new Place("lake_tahoe", "Lake Tahoe", "California", 38.9520, -120.1080),
            new Place("mount_rainier", "Mount Rainier", "Washington", 46.7860, -121.7350, 46.8523, -121.7603),
            new Place("grand_teton", "Grand Teton", "Wyoming", 43.7904, -110.6818, 43.7410, -110.8024),
            new Place("kilauea", "Kilauea", "Hawaii", 19.4069, -155.2834),
            new Place("lake_louise", "Lake Louise", "Canadian Rockies", 51.4166, -116.2150),
            new Place("exumas", "The Exumas", "Bahamas", 23.7800, -76.0900),
            new Place("amazon", "Amazon Rainforest", "Brazil", -3.4650, -62.2150),
            new Place("torres_del_paine", "Torres del Paine", "Chile", -50.9423, -73.4068),
            new Place("fitz_roy", "Fitz Roy", "Patagonia", -49.2710, -73.0430),
            new Place("antarctic_peninsula", "Antarctic Peninsula", "Antarctica", -64.8250, -62.8570),
            new Place("skogafoss", "Skogafoss", "Iceland", 63.5321, -19.5114),
            new Place("glencoe", "Glencoe", "Scottish Highlands", 56.6826, -5.1023),
            new Place("geirangerfjord", "Geirangerfjord", "Norway", 62.1010, 7.2050),
            new Place("lauterbrunnen", "Lauterbrunnen Valley", "Switzerland", 46.5935, 7.9090),
            new Place("matterhorn", "Matterhorn", "Switzerland", 45.9850, 7.7500, 45.9763, 7.6586),
            new Place("dolomites", "Tre Cime", "Dolomites, Italy", 46.6187, 12.3022),
            new Place("sahara", "Erg Chebbi Dunes", "Sahara, Morocco", 31.1450, -3.9800),
            new Place("serengeti", "Serengeti", "Tanzania", -2.3333, 34.8333),
            new Place("kilimanjaro", "Kilimanjaro", "Tanzania", -3.0300, 37.2550, -3.0758, 37.3533),
            new Place("victoria_falls", "Victoria Falls", "Zambia and Zimbabwe", -17.9243, 25.8572),
            new Place("everest", "Mount Everest", "Nepal", 28.0026, 86.8528, 27.9881, 86.9250),
            new Place("sundarbans", "Sundarbans", "Bangladesh", 21.9497, 89.1833),
            new Place("zhangjiajie", "Zhangjiajie", "China", 29.3167, 110.4333),
            new Place("ha_long_bay", "Ha Long Bay", "Vietnam", 20.9101, 107.1839),
            new Place("mount_fuji", "Mount Fuji", "Japan", 35.4990, 138.7600, 35.3606, 138.7274),
            new Place("uluru", "Uluru", "Australia", -25.3444, 131.0369),
            new Place("whitsundays", "Whitehaven Beach", "Great Barrier Reef, Australia", -20.2820, 149.0390));

    private EarthPlaces() {}

    public static List<Place> all() { return ALL; }

    public static Optional<Place> byId(String id) {
        return ALL.stream().filter(place -> place.id.equals(id)).findFirst();
    }

    /** The place whose id or name best matches what a player typed, ignoring case, spaces and underscores. */
    public static Optional<Place> find(String query) {
        String wanted = simple(query);
        if (wanted.isEmpty()) return Optional.empty();
        for (Place place : ALL) if (simple(place.id).equals(wanted) || simple(place.name).equals(wanted)) return Optional.of(place);
        for (Place place : ALL) if (simple(place.label()).contains(wanted)) return Optional.of(place);
        return Optional.empty();
    }

    /** The famous place within about a kilometre of a point, if any. */
    public static Optional<Place> near(double latitude, double longitude) {
        return ALL.stream().filter(place -> Math.abs(place.latitude - latitude) < 0.01 && Math.abs(place.longitude - longitude) < 0.01).findFirst();
    }

    private static String simple(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
}
