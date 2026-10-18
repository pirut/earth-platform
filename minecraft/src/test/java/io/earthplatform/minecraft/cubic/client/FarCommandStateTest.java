package io.earthplatform.minecraft.cubic.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarCommandStateTest {
    @Test void startsInvalidEvenForAnAllZeroKey() {
        FarCommandState state = new FarCommandState();
        assertFalse(state.matches(new Object(), 0, 0, 0.0F));
        assertFalse(state.matches(null, 0, 0, 0.0F));
    }

    @Test void repeatedExactKeyMatchesWithoutChangingRememberedState() {
        FarCommandState state = new FarCommandState();
        Object owner = new Object();
        state.remember(owner, 12, 34, 192.0F);
        for (int i = 0; i < 3; i++) assertTrue(state.matches(owner, 12, 34, 192.0F));
        assertFalse(state.matches(owner, 13, 34, 192.0F));
        assertTrue(state.matches(owner, 12, 34, 192.0F));
    }

    @Test void allocationChangesAndEverySelectionEpochInvalidateIncludingFadeRebuilds() {
        FarCommandState state = new FarCommandState();
        Object owner = new Object();
        long selection = 1L << 40, allocations = (1L << 40) + 5;
        state.remember(owner, selection, allocations, 192.0F);
        assertFalse(state.matches(owner, selection, allocations + 1, 192.0F));
        assertFalse(state.matches(owner, selection + 1, allocations, 192.0F));
        // A fade can rebuild a pick without any allocation or camera movement. Its epoch alone
        // must invalidate all prior fade groups, then allow the newly assembled commands to match.
        for (int fadeFrame = 0; fadeFrame < 3; fadeFrame++) {
            selection++;
            assertFalse(state.matches(owner, selection, allocations, 192.0F));
            state.remember(owner, selection, allocations, 192.0F);
            assertTrue(state.matches(owner, selection, allocations, 192.0F));
        }
    }

    @Test void ownerUsesIdentityRatherThanEquality() {
        FarCommandState state = new FarCommandState();
        Object owner = new String("arena-owner"), equalOtherOwner = new String("arena-owner");
        assertEquals(owner, equalOtherOwner);
        assertNotSame(owner, equalOtherOwner);
        state.remember(owner, 1, 2, 192.0F);
        assertFalse(state.matches(equalOtherOwner, 1, 2, 192.0F));
        assertTrue(state.matches(owner, 1, 2, 192.0F));
    }

    @Test void nearReachUsesExactFloatBitsIncludingSignedZeroAndNanPayloads() {
        FarCommandState state = new FarCommandState();
        Object owner = new Object();
        state.remember(owner, 1, 2, 192.0F);
        assertFalse(state.matches(owner, 1, 2, Math.nextUp(192.0F)));
        assertFalse(state.matches(owner, 1, 2, Math.nextDown(192.0F)));
        assertTrue(state.matches(owner, 1, 2, Float.intBitsToFloat(Float.floatToRawIntBits(192.0F))));

        state.remember(owner, 1, 2, +0.0F);
        assertTrue(state.matches(owner, 1, 2, +0.0F));
        assertFalse(state.matches(owner, 1, 2, -0.0F));
        state.remember(owner, 1, 2, -0.0F);
        assertTrue(state.matches(owner, 1, 2, -0.0F));
        assertFalse(state.matches(owner, 1, 2, +0.0F));

        float nan = Float.intBitsToFloat(0x7FC00001), anotherNan = Float.intBitsToFloat(0x7FC00002);
        state.remember(owner, 1, 2, nan);
        assertTrue(state.matches(owner, 1, 2, nan));
        assertFalse(state.matches(owner, 1, 2, anotherNan));
    }

    @Test void invalidateForcesRebuildUntilAnotherKeyIsRemembered() {
        FarCommandState state = new FarCommandState();
        Object owner = new Object();
        state.remember(owner, 1, 2, 192.0F);
        state.invalidate();
        assertFalse(state.matches(owner, 1, 2, 192.0F));
        assertFalse(state.matches(null, 1, 2, 192.0F));
        state.invalidate();
        assertFalse(state.matches(owner, 1, 2, 192.0F));
        state.remember(owner, 1, 2, 192.0F);
        assertTrue(state.matches(owner, 1, 2, 192.0F));
    }
}
