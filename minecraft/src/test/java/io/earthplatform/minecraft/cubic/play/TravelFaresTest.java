package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Travel is charged only as a trip lands: nothing to give back, so nothing to exploit. */
class TravelFaresTest {
    private static final int COOLDOWN = 60;

    /** A player's levels; experience earned meanwhile is levels added. */
    private static final class Levels implements EarthRules.Wallet {
        final UUID id = UUID.randomUUID();
        int levels;
        Levels(int levels) { this.levels = levels; }
        @Override public UUID id() { return id; }
        @Override public int levels() { return levels; }
        @Override public void spend(int levels) { this.levels -= levels; }
    }

    @Test void settingOffTakesNothingAndLandingCharges() {
        Levels player = new Levels(30);
        assertNull(EarthRules.refusal(player, 30, 0), "may set off");
        assertEquals(30, player.levels, "charged when setting off");
        assertNull(EarthRules.refusal(player, 30, 1), "a cooldown started when setting off");
        // It lands: charged, and the cooldown starts then.
        assertNull(EarthRules.charge(player, new EarthRules.Fare(30, true), 1_000, COOLDOWN));
        assertEquals(0, player.levels);
        assertNotNull(EarthRules.refusal(player, 0, 2_000), "no cooldown after landing");
    }

    @Test void aTripThatNeverLandsCostsNothing() {
        // Quoted, then it timed out, failed, or was superseded: nothing taken, no cooldown, nothing to refund.
        Levels player = new Levels(30);
        assertNull(EarthRules.refusal(player, 30, 0));
        assertEquals(30, player.levels);
        assertNull(EarthRules.refusal(player, 30, 1), "another trip set off at once is not held back");
    }

    @Test void levelsSpentWhileWaitingMeanTheTripDoesNotGoAndNothingIsAmplified() {
        // Sol's case: a level-30 player quoted 30 levels spends them meanwhile, then earns 7 experience at level 0.
        Levels player = new Levels(30);
        EarthRules.Fare fare = new EarthRules.Fare(30, true);
        assertNull(EarthRules.refusal(player, fare.levels(), 0));
        player.levels = 0; // spent on an anvil
        // ...then earns 7 experience points, still level 0: nothing was taken, so nothing comes back to amplify.
        String refused = EarthRules.charge(player, fare, 1_000, COOLDOWN);
        assertNotNull(refused, "landed without paying");
        assertTrue(refused.contains("did not go"), refused);
        assertEquals(0, player.levels, "ended with other than what they had");
        assertNull(EarthRules.refusal(player, 0, 2_000), "a failed landing started the cooldown");
    }

    @Test void oneTripsCooldownNeverErasesAnothers() {
        // Trips A and B quoted together; B lands and starts the cooldown; A, landing later, is held to it.
        Levels player = new Levels(10);
        EarthRules.Fare a = new EarthRules.Fare(2, true), b = new EarthRules.Fare(3, true);
        assertNull(EarthRules.charge(player, b, 1_000, COOLDOWN));
        assertEquals(7, player.levels);
        assertNotNull(EarthRules.charge(player, a, 2_000, COOLDOWN), "a second trip landed inside the cooldown");
        assertEquals(7, player.levels);
        assertNotNull(EarthRules.refusal(player, 0, 1_000 + COOLDOWN * 1000L - 1), "B's cooldown was cut short");
        assertNull(EarthRules.refusal(player, 0, 1_000 + COOLDOWN * 1000L));
    }

    @Test void aVetoedMoveChargesNothingAndStartsNoCooldown() {
        // A mod cancels the teleport (a Nether-to-Earth /earth goto): the player stays put.
        Levels player = new Levels(10);
        int[] moves = {0};
        String refused = EarthRules.landTrip(player, new EarthRules.Fare(4, true), 1_000, COOLDOWN, () -> { moves[0]++; return false; });
        assertNotNull(refused, "a vetoed move landed");
        assertEquals(1, moves[0]);
        assertEquals(10, player.levels, "charged for a move that did not happen");
        assertNull(EarthRules.refusal(player, 0, 1_001), "a vetoed move started the cooldown");
    }

    @Test void aConfirmedMoveIsChargedOnlyAfterItHappens() {
        Levels player = new Levels(10);
        int[] levelsAtMove = {-1};
        assertNull(EarthRules.landTrip(player, new EarthRules.Fare(4, true), 1_000, COOLDOWN, () -> { levelsAtMove[0] = player.levels; return true; }));
        assertEquals(10, levelsAtMove[0], "charged before the move");
        assertEquals(6, player.levels);
        assertNotNull(EarthRules.refusal(player, 0, 1_001), "no cooldown after the trip landed");
    }

    @Test void aFareThatCannotBePaidNeverMoves() {
        Levels player = new Levels(1);
        int[] moves = {0};
        assertNotNull(EarthRules.landTrip(player, new EarthRules.Fare(4, true), 1_000, COOLDOWN, () -> { moves[0]++; return true; }));
        assertEquals(0, moves[0], "moved a player who could not pay");
        assertEquals(1, player.levels);
        // Free trips move and charge nothing.
        assertNull(EarthRules.landTrip(player, EarthRules.Fare.FREE, 1_000, COOLDOWN, () -> { moves[0]++; return true; }));
        assertEquals(1, moves[0]);
        assertEquals(1, player.levels);
    }

    @Test void freeTripsChargeNothingAndStartNoCooldown() {
        Levels player = new Levels(5);
        assertNull(EarthRules.charge(player, EarthRules.Fare.FREE, 0, COOLDOWN));
        assertNull(EarthRules.charge(player, null, 0, COOLDOWN));
        assertEquals(5, player.levels);
        assertNull(EarthRules.refusal(player, 0, 1));
    }
}
