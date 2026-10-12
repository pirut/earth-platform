package io.earthplatform.minecraft.survival.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InstalledProbeMetadataBudgetTest {
    @Test void exactCapIsAllowedAndOverspendPreservesCharge() {
        var budget=new InstalledProbeMetadataBudget(1024);
        budget.reserve(1000);budget.reserve(24);assertEquals(1024,budget.charged());
        assertThrows(IllegalStateException.class,()->budget.reserve(1));
        assertEquals(1024,budget.charged());
    }
    @Test void negativeAndOverflowDoNotRecycleCredit() {
        var budget=new InstalledProbeMetadataBudget(Long.MAX_VALUE);
        budget.reserve(Long.MAX_VALUE-3);
        assertThrows(IllegalStateException.class,()->budget.reserve(-1));
        assertThrows(IllegalStateException.class,()->budget.reserve(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE-3,budget.charged());
        budget.reserve(3);assertEquals(Long.MAX_VALUE,budget.charged());
    }
    @Test void noNegativeLimitAndZeroReservationIsHarmless() {
        assertThrows(IllegalArgumentException.class,()->new InstalledProbeMetadataBudget(-1));
        var budget=new InstalledProbeMetadataBudget(0);budget.reserve(0);
        assertThrows(IllegalStateException.class,()->budget.reserve(1));
        assertEquals(0,budget.charged());
    }
}
