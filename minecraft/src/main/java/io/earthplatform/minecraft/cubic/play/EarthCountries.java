package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;

/**
 * Country borders, from Natural Earth's 1:50,000,000 admin-0 countries (public domain), bundled
 * in the jar by {@code scripts/countries.py}: which country a point is in, and each country's
 * extent. The borders are drawn to a few kilometers, enough to name the country a player is in.
 */
public final class EarthCountries {
    /** One country: its name, extent in degrees and border rings in 1/10000 degree, longitude then latitude. */
    public record Country(String name, double south, double west, double north, double east, List<int[]> rings) {
        boolean contains(double latitude, double longitude) {
            if (latitude < south || latitude > north || longitude < west || longitude > east) return false;
            double x = longitude * 10000, y = latitude * 10000;
            boolean inside = false;
            // Even-odd over every ring, so holes such as Lesotho inside South Africa stay out.
            for (int[] ring : rings) {
                for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
                    double xi = ring[i], yi = ring[i + 1], xj = ring[j], yj = ring[j + 1];
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
                }
            }
            return inside;
        }
    }

    private static volatile List<Country> countries;

    private EarthCountries() {}

    public static List<Country> all() {
        List<Country> loaded = countries;
        if (loaded == null) {
            synchronized (EarthCountries.class) {
                if (countries == null) countries = load();
                loaded = countries;
            }
        }
        return loaded;
    }

    /** The country at a point, or null at sea or where the borders leave it out. */
    @Nullable public static Country at(double latitude, double longitude) {
        for (Country country : all()) if (country.contains(latitude, longitude)) return country;
        return null;
    }

    /** A country by name, ignoring case, spaces and punctuation; a name that starts it will do when only one does. */
    public static Optional<Country> find(String query) {
        String wanted = simple(query);
        if (wanted.isEmpty()) return Optional.empty();
        Country start = null;
        int starts = 0;
        for (Country country : all()) {
            String name = simple(country.name());
            if (name.equals(wanted)) return Optional.of(country);
            if (name.startsWith(wanted)) { start = country; starts++; }
        }
        return starts == 1 ? Optional.of(start) : Optional.empty();
    }

    private static String simple(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}0-9]", ""); }

    private static List<Country> load() {
        try (InputStream in = EarthCountries.class.getResourceAsStream("/earthplatform/countries.bin")) {
            if (in == null) throw new IOException("countries.bin is missing from the jar");
            byte[] data = new GZIPInputStream(in).readAllBytes();
            int[] at = {4};
            if (data.length < 4 || data[0] != 'E' || data[1] != 'P' || data[2] != 'C' || data[3] != '1') throw new IOException("not a country file");
            int count = (int) varint(data, at);
            List<Country> list = new ArrayList<>(count);
            for (int c = 0; c < count; c++) {
                int length = (int) varint(data, at);
                String name = new String(data, at[0], length, StandardCharsets.UTF_8);
                at[0] += length;
                int ringCount = (int) varint(data, at);
                List<int[]> rings = new ArrayList<>(ringCount);
                int west = Integer.MAX_VALUE, east = Integer.MIN_VALUE, south = Integer.MAX_VALUE, north = Integer.MIN_VALUE;
                for (int r = 0; r < ringCount; r++) {
                    int points = (int) varint(data, at);
                    int[] ring = new int[points * 2];
                    long x = 0, y = 0;
                    for (int p = 0; p < points; p++) {
                        x += unzig(varint(data, at));
                        y += unzig(varint(data, at));
                        ring[2 * p] = (int) x;
                        ring[2 * p + 1] = (int) y;
                        west = Math.min(west, (int) x); east = Math.max(east, (int) x);
                        south = Math.min(south, (int) y); north = Math.max(north, (int) y);
                    }
                    rings.add(ring);
                }
                list.add(new Country(name, south / 10000.0, west / 10000.0, north / 10000.0, east / 10000.0, List.copyOf(rings)));
            }
            return List.copyOf(list);
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Country borders could not be read: {}", failure.toString());
            return List.of();
        }
    }

    private static long varint(byte[] data, int[] at) {
        long value = 0;
        for (int shift = 0; ; shift += 7) {
            int b = data[at[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
    }

    private static long unzig(long n) { return (n >>> 1) ^ -(n & 1); }
}
