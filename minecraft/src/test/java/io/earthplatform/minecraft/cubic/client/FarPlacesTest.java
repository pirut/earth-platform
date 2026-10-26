package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.far.Far;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarPlacesTest {
    /**
     * A held column, with its key, whether its mesh is up and its own last-wanted tick; a column
     * sent again is a new one with the same key.
     */
    private static final class Held {
        final long key;
        boolean meshed;
        int wanted;

        Held(long key) { this.key = key; }
    }

    /**
     * Whatever comes, goes, comes back, gets meshed, is wanted or crowds a place, the places answer
     * as the map does, and know when each column was last wanted.
     */
    @Test void answersAsTheMapDoes() {
        Random random = new Random(6541);
        for (int round = 0; round < 20; round++) {
            Long2ObjectOpenHashMap<Held> map = new Long2ObjectOpenHashMap<>();
            FarPlaces<Held> places = new FarPlaces<>(held -> held.key, map::get, held -> held.meshed, held -> held.wanted, (held, tick) -> held.wanted = tick);
            // When each held column was last wanted, kept apart from the places.
            Map<Held, Integer> wanted = new IdentityHashMap<>();
            int tick = 0;
            // Each level's columns around four spots: two 256 and 512 columns apart, crowding each
            // other's places, one nearby, and one so far off that only a tag's highest bits tell it apart.
            int x = random.nextInt(2_000_000) - 1_000_000, z = random.nextInt(2_000_000) - 1_000_000;
            int[][] spots = {{x, z}, {x + 256, z - 512}, {x + 40, z + 7}, {x + (1 << 22), z - (1 << 22)}};
            List<Long> keys = new ArrayList<>();
            for (int[] spot : spots) for (int i = 0; i < 300; i++) {
                int level = Far.MIN_LEVEL + random.nextInt(Far.MAX_LEVEL - Far.MIN_LEVEL + 1), spread = random.nextBoolean() ? 6 : 48;
                keys.add(Far.key(level, spot[0] + random.nextInt(spread) - spread / 2, spot[1] + random.nextInt(spread) - spread / 2));
            }
            for (int step = 0; step < 4000; step++) {
                long key = keys.get(random.nextInt(keys.size()));
                int action = random.nextInt(6);
                tick++;
                if (action == 0) {
                    Held gone = map.remove(key);
                    if (gone != null) {
                        places.remove(gone);
                        wanted.remove(gone);
                    }
                } else if (action >= 4) {
                    // Wanted: a run of a level's row, as the plan goes over them.
                    int level = Far.level(key), wx = Far.x(key), wz = Far.z(key);
                    for (int dx = 0; dx < 5; dx++) {
                        Held column = map.get(Far.key(level, wx + dx, wz));
                        assertEquals(column != null, places.want(level, wx + dx, wz, tick), "round " + round + " step " + step + " want");
                        if (column != null) wanted.put(column, tick);
                    }
                } else if (action == 1) {
                    Held column = map.get(key);
                    if (column != null && !column.meshed) {
                        column.meshed = true;
                        places.meshed(column);
                    }
                } else {
                    // Sent again: a new column, wanted as it comes, which may take over the old one's mesh.
                    Held column = new Held(key), old = map.put(key, column);
                    column.wanted = tick;
                    wanted.put(column, tick);
                    if (old != null) wanted.remove(old);
                    places.add(column);
                    if (old != null && old.meshed && random.nextBoolean()) {
                        column.meshed = true;
                        places.meshed(column);
                    }
                }
                if (places.crowdedLeft() && random.nextInt(10) == 0) places.placeAll(map.values());
                check(map, places, wanted, keys.get(random.nextInt(keys.size())), "round " + round + " step " + step);
            }
            for (long key : keys) check(map, places, wanted, key, "round " + round);
            places.placeAll(map.values());
            for (long key : keys) check(map, places, wanted, key, "round " + round + " placed afresh");
            assertFalse(places.crowdedLeft());
        }
    }

    private static void check(Long2ObjectOpenHashMap<Held> map, FarPlaces<Held> places, Map<Held, Integer> wanted, long key, String where) {
        Held column = map.get(key);
        int level = Far.level(key), x = Far.x(key), z = Far.z(key);
        assertSame(column, places.get(key), where);
        assertEquals(column != null, places.has(level, x, z), where + " has");
        assertEquals(column != null && column.meshed, places.meshed(level, x, z), where + " meshed");
        if (column != null) assertEquals(wanted.get(column).intValue(), places.lastWanted(column), where + " last wanted");
    }
}
