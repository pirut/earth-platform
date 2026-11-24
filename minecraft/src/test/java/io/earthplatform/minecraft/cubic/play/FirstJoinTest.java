package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.cubic.ResidentCubes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A first-join player's spot: a failed elevation sample keeps them waiting, and leaving lets go of their cubes. */
class FirstJoinTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void aScatterWhoseElevationCannotBeHadKeepsThePlayerWaiting() {
        assertNull(EarthWorlds.scatterOrWait(() -> { throw new MissingTerrainException("elevation tile not downloaded"); }),
                "a failed elevation sample threw out of the server tick");
        int[] spot = {10, 20};
        assertEquals(spot, EarthWorlds.scatterOrWait(() -> spot));
    }

    @Test void aPlayerWhoLeavesWhileWaitingLeavesNoHold() {
        ResidentCubes resident = new ResidentCubes();
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        Map<UUID, Object> spots = new HashMap<>();
        spots.put(player, "their spot");
        spots.put(other, "another's spot");
        long[] there = ResidentCubes.around(new BlockPos(1000, 300, -2000));
        resident.keep("join:" + player, there);
        resident.keep("join:" + other, ResidentCubes.around(new BlockPos(5000, 300, -2000)));
        // They disconnect while their landing cubes are held.
        EarthWorlds.forgetSpot(spots, player, resident::release);
        assertFalse(spots.containsKey(player));
        assertFalse(resident.holds(there[0]), "their landing cubes stayed resident");
        assertEquals(1, resident.holders(), "another player's hold went too");
        assertTrue(spots.containsKey(other));
        // Forgetting a player with no spot (left before one was scattered) is harmless.
        EarthWorlds.forgetSpot(spots, UUID.randomUUID(), resident::release);
        assertEquals(1, resident.holders());
    }
}
