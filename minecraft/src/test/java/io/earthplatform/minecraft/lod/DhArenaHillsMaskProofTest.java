package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;

/** Finite modular proof for all integral native Y masks of the exhaustively checked narrow source faces. */
class DhArenaHillsMaskProofTest {
    @Test void everyYPhaseAndRelevantNativeMaskOnlyShortensOrDeletesANarrowSide() {
        int cases = 0, shortened = 0;
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST))
            for (int yBase : new int[] {-2080, -32, 0, 128, 2032}) for (int phase = 0; phase < 16; phase++)
                for (int height = 1; height <= 2; height++) for (int coordinate : new int[] {-17, -16, -1, 0, 15, 16}) {
                    int x = coordinate, z = coordinate, y = yBase + phase;
                    var face = new Face(direction, x + (direction == Direction.EAST ? 1 : 0), y,
                            z + (direction == Direction.SOUTH ? 1 : 0), 1, height, 0xff808080);
                    var first = CubePos.fromBlock(x, y, z); var last = CubePos.fromBlock(x, y + height - 1, z);
                    var relevant = first.equals(last) ? List.of(first) : List.of(first, last);
                    // No other native cube's solid-side projection intersects the face. These are
                    // all subsets of all relevant Y cubes, stronger than contiguous moving27 masks.
                    for (int mask = 0; mask < (1 << relevant.size()); mask++) {
                        var ready = new ArrayList<CubePos>();
                        for (int i = 0; i < relevant.size(); i++) if ((mask & (1 << i)) != 0) ready.add(relevant.get(i));
                        var clipped = clip(face, ready, 16); assertTrue(clipped.size() <= 1);
                        int expectedUnits = 0;
                        for (int dy = 0; dy < height; dy++) if (!ready.contains(CubePos.fromBlock(x, y + dy, z))) expectedUnits++;
                        assertEquals(expectedUnits, clipped.stream().mapToInt(Face::height).sum());
                        for (var piece : clipped) {
                            assertEquals(face.direction(), piece.direction()); assertEquals(face.x(), piece.x());
                            assertEquals(face.z(), piece.z()); assertEquals(face.width(), piece.width());
                            assertEquals(face.argb(), piece.argb());
                            assertTrue(piece.y() >= face.y() && piece.y() + piece.height() <= face.y() + face.height());
                            if (!piece.equals(face)) shortened++;
                        }
                        cases++;
                    }
                }
        assertEquals(7920, cases); assertTrue(shortened > 0);
        System.out.println("ARENA_HILLS_MASK_PROOF cases=" + cases + " shortened=" + shortened
                + " all16YPhases=true allIntersectingCubeSubsets=true maxFragments=1 colorAndAreaExact=true");
    }
}
