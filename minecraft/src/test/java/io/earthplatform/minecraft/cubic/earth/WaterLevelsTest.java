package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Water that was levelled by its shores, brought back to the bank the ground actually has. */
class WaterLevelsTest {
    /** How far water may stand above the bank it meets, and how far a cliff rises above the water in front of it. */
    private static final int BANK = 4, FALL = 20;

    @Test void canyonWaterComesDownToTheChannel() {
        // The coarse shore is the wall. The bank in front of it is the river.
        int bank = WaterLevels.bankReading(652, 1100, 660, FALL);
        assertEquals(660, bank);
        assertEquals(664, WaterLevels.capToBanks(860, 652, bank, BANK));
    }

    @Test void aGentleBankIsTheDryGroundAtTheEdge() {
        int bank = WaterLevels.bankReading(652, 670, 658, FALL);
        assertEquals(670, bank);
        assertEquals(674, WaterLevels.capToBanks(860, 652, bank, BANK));
    }

    @Test void aReservoirShoreStaysUpAtItsLevel() {
        int bank = WaterLevels.bankReading(80, 172, 168, FALL);
        assertEquals(172, bank);
        assertEquals(175, WaterLevels.capToBanks(175, 80, bank, BANK));
    }

    @Test void aReservoirCliffThatHasClimbedDoesNotLowerTheWater() {
        // The block before the cliff is already well above this column: it is the valley side, not the bed.
        int bank = WaterLevels.bankReading(80, 400, 150, FALL);
        assertEquals(400, bank);
        assertEquals(175, WaterLevels.capToBanks(175, 80, bank, BANK));
    }

    @Test void openWaterWithNoBankKeepsTheReservoirLevel() {
        assertEquals(175, WaterLevels.capToBanks(175, 60, WaterLevels.MISSING, BANK));
    }

    @Test void niagaraStepsFromTheLipToThePool() {
        int lip = WaterLevels.capToBanks(170, 168, 171, BANK);
        int poolBank = WaterLevels.closestBank(99, 168, 112, 70);
        int pool = WaterLevels.capToBanks(170, 99, poolBank, BANK);
        assertEquals(170, lip, "the lip is already at its bank");
        assertEquals(112, poolBank, "the gorge's own bank, not the cliff and not a terrace below the pool");
        assertEquals(116, pool);
        assertTrue(lip - pool >= 40, "a falls is a step, not one flat sheet");
    }

    @Test void theChannelBankWinsOverTheWallAndTheNextReachDown() {
        assertEquals(668, WaterLevels.closestBank(652, 1400, 668, 640, 900));
        assertEquals(WaterLevels.MISSING, WaterLevels.closestBank(652));
    }

    @Test void waterAlreadyAtItsGroundIsLeftThere() {
        assertEquals(170, WaterLevels.capToBanks(170, 168, 112, BANK));
    }
}
