package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.far.Far;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarPlacesTest {
    /** A held column, with its key and whether its mesh is up; a column sent again is a new one with the same key. */
    private static final class Held {
        final long key;
        boolean meshed;

        Held(long key) { this.key = key; }
    }

    /** Whatever comes, goes, comes back, gets meshed or crowds a place, the places answer as the map does. */
    @Test void answersAsTheMapDoes() {
        Random random = new Random(6541);
        for (int round = 0; round < 20; round++) {
            Long2ObjectOpenHashMap<Held> map = new Long2ObjectOpenHashMap<>();
            FarPlaces<Held> places = new FarPlaces<>(held -> held.key, map::get, held -> held.meshed);
            // Each level's columns around three spots: two 256 and 512 columns apart, crowding each
            // other's places, and one nearby.
            int x = random.nextInt(2_000_000) - 1_000_000, z = random.nextInt(2_000_000) - 1_000_000;
            int[][] spots = {{x, z}, {x + 256, z - 512}, {x + 40, z + 7}};
            List<Long> keys = new ArrayList<>();
            for (int[] spot : spots) for (int i = 0; i < 300; i++) {
                int level = Far.MIN_LEVEL + random.nextInt(Far.MAX_LEVEL - Far.MIN_LEVEL + 1), spread = random.nextBoolean() ? 6 : 48;
                keys.add(Far.key(level, spot[0] + random.nextInt(spread) - spread / 2, spot[1] + random.nextInt(spread) - spread / 2));
            }
            for (int step = 0; step < 4000; step++) {
                long key = keys.get(random.nextInt(keys.size()));
                int action = random.nextInt(4);
                if (action == 0) {
                    Held gone = map.remove(key);
                    if (gone != null) places.remove(gone);
                } else if (action == 1) {
                    Held column = map.get(key);
                    if (column != null && !column.meshed) {
                        column.meshed = true;
                        places.meshed(column);
                    }
                } else {
                    // Sent again: a new column, which may take over the old one's mesh.
                    Held column = new Held(key), old = map.put(key, column);
                    places.add(column);
                    if (old != null && old.meshed && random.nextBoolean()) {
                        column.meshed = true;
                        places.meshed(column);
                    }
                }
                if (places.crowdedLeft() && random.nextInt(10) == 0) places.placeAll(map.values());
                check(map, places, keys.get(random.nextInt(keys.size())), "round " + round + " step " + step);
            }
            for (long key : keys) check(map, places, key, "round " + round);
            places.placeAll(map.values());
            for (long key : keys) check(map, places, key, "round " + round + " placed afresh");
            assertFalse(places.crowdedLeft());
        }
    }

    private static void check(Long2ObjectOpenHashMap<Held> map, FarPlaces<Held> places, long key, String where) {
        Held column = map.get(key);
        assertSame(column, places.get(key), where);
        assertEquals(column != null && column.meshed, places.meshed(Far.level(key), Far.x(key), Far.z(key)), where + " meshed");
    }
}
