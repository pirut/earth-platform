package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Every town of 1,000 people or more, from GeoNames (CC BY 4.0), bundled in the jar by
 * {@code scripts/towns.py}, so a player can go to a place by its name: where it is on the Earth,
 * not the town itself, since the Earth here is wild land. Nothing is looked up over the network.
 * The towns are kept packed as they come out of the jar (about 4 MB) and searched in place,
 * most people first, so "Paris" finds the one in France before the one in Texas.
 */
public final class EarthTowns {
    /** A town found by name: its name, its state or province and country, and where it is. */
    public record Town(String name, String region, double latitude, double longitude) {
        public String label() { return name + ", " + region; }
    }

    private record Packed(byte[] data, String[] regions, int[] starts) {}

    private static volatile Packed packed;

    private EarthTowns() {}

    /**
     * The towns that match what a player typed, best first, at most {@code limit}: names that are
     * the query, then names that start with it, then names that hold it, each most people first.
     * Case, spaces, accents and punctuation don't count. Words after a comma narrow the state or
     * country: "Springfield, Illinois".
     */
    public static List<Town> search(String query, int limit) {
        int comma = query.indexOf(',');
        String wanted = simple(comma < 0 ? query : query.substring(0, comma));
        String where = comma < 0 ? "" : simple(query.substring(comma + 1));
        if (wanted.isEmpty() || limit <= 0) return List.of();
        Packed towns = packed();
        byte[] key = wanted.getBytes(StandardCharsets.US_ASCII);
        List<Town> same = new ArrayList<>(), starts = new ArrayList<>(), holds = new ArrayList<>();
        int[] at = new int[1];
        for (int start : towns.starts) {
            if (same.size() >= limit) break;
            at[0] = start;
            int nameLength = (int) varint(towns.data, at), nameAt = at[0];
            at[0] += nameLength;
            int keyLength = (int) varint(towns.data, at), keyAt = at[0];
            int found = keyLength == 0 ? find(towns.data, nameAt, nameLength, key, true) : find(towns.data, keyAt, keyLength, key, false);
            if (found == NONE) continue;
            List<Town> into = found == SAME ? same : found == STARTS ? starts : holds;
            if (into != same && starts.size() + holds.size() >= 4 * limit) continue;
            at[0] = keyAt + keyLength;
            String region = towns.regions[(int) varint(towns.data, at)];
            if (!where.isEmpty() && !simple(region).contains(where)) continue;
            double latitude = unzig(varint(towns.data, at)) / 1000.0, longitude = unzig(varint(towns.data, at)) / 1000.0;
            into.add(new Town(new String(towns.data, nameAt, nameLength, StandardCharsets.UTF_8), region, latitude, longitude));
        }
        List<Town> all = new ArrayList<>(same);
        all.addAll(starts);
        all.addAll(holds);
        return List.copyOf(all.subList(0, Math.min(limit, all.size())));
    }

    /** The town nearest a point, within {@code km} of it, or null. A town counts by its center, as the list gives it. */
    @javax.annotation.Nullable public static Town nearest(double latitude, double longitude, double km) {
        Packed towns = packed();
        double shrink = Math.max(0.01, Math.cos(Math.toRadians(latitude))), reach = km / 111.2, best = reach * reach;
        int bestAt = -1;
        int[] at = new int[1];
        for (int start : towns.starts) {
            at[0] = start;
            skip(towns.data, at);
            skip(towns.data, at);
            varint(towns.data, at);
            double dLat = unzig(varint(towns.data, at)) / 1000.0 - latitude;
            if (Math.abs(dLat) > reach) continue;
            double dLon = unzig(varint(towns.data, at)) / 1000.0 - longitude;
            dLon = (dLon - 360 * Math.rint(dLon / 360)) * shrink;
            double d = dLat * dLat + dLon * dLon;
            if (d < best) { best = d; bestAt = start; }
        }
        if (bestAt < 0) return null;
        at[0] = bestAt;
        int nameLength = (int) varint(towns.data, at), nameAt = at[0];
        at[0] += nameLength;
        skip(towns.data, at);
        String region = towns.regions[(int) varint(towns.data, at)];
        return new Town(new String(towns.data, nameAt, nameLength, StandardCharsets.UTF_8), region,
                unzig(varint(towns.data, at)) / 1000.0, unzig(varint(towns.data, at)) / 1000.0);
    }

    /** How many towns are bundled; 0 when the list could not be read. */
    public static int count() { return packed().starts.length; }

    private static final int NONE = 0, HOLDS = 1, STARTS = 2, SAME = 3;

    /**
     * How {@code key} sits in a town's name: the same, at its start, inside it, or not at all. With
     * {@code fold} the bytes are the name itself, plain ASCII, read as its key would be: letters in
     * lower case and everything but letters and digits skipped.
     */
    private static int find(byte[] data, int from, int length, byte[] key, boolean fold) {
        if (!fold) {
            if (length < key.length) return NONE;
            for (int start = 0; start + key.length <= length; start++) {
                int k = 0;
                while (k < key.length && data[from + start + k] == key[k]) k++;
                if (k == key.length) return start > 0 ? HOLDS : length == key.length ? SAME : STARTS;
            }
            return NONE;
        }
        boolean first = true;
        for (int start = 0; start < length; start++) {
            if (fold(data[from + start]) == 0) continue;
            int k = 0, i = start;
            for (; k < key.length && i < length; i++) {
                int c = fold(data[from + i]);
                if (c == 0) continue;
                if (c != key[k]) break;
                k++;
            }
            if (k == key.length) {
                if (!first) return HOLDS;
                for (; i < length; i++) if (fold(data[from + i]) != 0) return STARTS;
                return SAME;
            }
            first = false;
        }
        return NONE;
    }

    private static int fold(byte b) {
        if (b >= 'a' && b <= 'z' || b >= '0' && b <= '9') return b;
        return b >= 'A' && b <= 'Z' ? b + 32 : 0;
    }

    /** As the packer makes a key: ASCII letters in lower case and digits; accents come off first. */
    static String simple(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Packed packed() {
        Packed loaded = packed;
        if (loaded == null) {
            synchronized (EarthTowns.class) {
                if (packed == null) packed = load();
                loaded = packed;
            }
        }
        return loaded;
    }

    private static Packed load() {
        try (InputStream in = EarthTowns.class.getResourceAsStream("/earthplatform/towns.bin")) {
            if (in == null) throw new IOException("towns.bin is missing from the jar");
            byte[] data = new GZIPInputStream(in).readAllBytes();
            if (data.length < 4 || data[0] != 'E' || data[1] != 'P' || data[2] != 'T' || data[3] != '1') throw new IOException("not a town file");
            int[] at = {4};
            String[] regions = new String[(int) varint(data, at)];
            for (int r = 0; r < regions.length; r++) {
                int length = (int) varint(data, at);
                regions[r] = new String(data, at[0], length, StandardCharsets.UTF_8);
                at[0] += length;
            }
            int[] starts = new int[(int) varint(data, at)];
            for (int t = 0; t < starts.length; t++) {
                starts[t] = at[0];
                skip(data, at);
                skip(data, at);
                varint(data, at);
                varint(data, at);
                varint(data, at);
            }
            return new Packed(data, regions, starts);
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("The town list could not be read: {}", failure.toString());
            return new Packed(new byte[0], new String[0], new int[0]);
        }
    }

    /** Steps over a length and the bytes it counts. */
    private static void skip(byte[] data, int[] at) {
        int length = (int) varint(data, at);
        at[0] += length;
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
