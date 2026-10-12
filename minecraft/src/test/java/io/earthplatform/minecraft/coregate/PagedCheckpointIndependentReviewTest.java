package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure request/current-pair/acknowledgement binding adversaries for diagnostic screenshots. */
class PagedCheckpointIndependentReviewTest {
    private static final String DIGEST = "a".repeat(64), LABEL = "scenario12-14-outside_edit_refusal";
    private static RollingScenarioCheckpoint request() { return new RollingScenarioCheckpoint(1, 2, 3, 4, DIGEST, LABEL); }

    @Test void receiptMustMatchEveryRequestedAuthorityComponentAndItsExactLabel() {
        var request = request(); assertTrue(request.matches(1, 2, 3, 4, DIGEST, LABEL));
        assertFalse(request.matches(9, 2, 3, 4, DIGEST, LABEL));
        assertFalse(request.matches(1, 9, 3, 4, DIGEST, LABEL));
        assertFalse(request.matches(1, 2, 9, 4, DIGEST, LABEL));
        assertFalse(request.matches(1, 2, 3, 9, DIGEST, LABEL));
        assertFalse(request.matches(1, 2, 3, 4, "b".repeat(64), LABEL));
        assertFalse(request.matches(1, 2, 3, 4, DIGEST, "scenario12-15-next"));
        assertFalse(request.matches(1, 2, 3, 4, null, LABEL));
        assertFalse(request.matches(1, 2, 3, 4, DIGEST, null));
    }

    @Test void matchingLabelOnANewerVisiblePublicationCannotSatisfyAnOlderScreenshotRequest() {
        var pending = request();
        var newlyCurrent = new RollingScenarioCheckpoint(1, 3, 3, 5, "b".repeat(64), LABEL);
        assertFalse(pending.matches(newlyCurrent.epoch(), newlyCurrent.publication(), newlyCurrent.resources(),
                newlyCurrent.coverage(), newlyCurrent.digest(), newlyCurrent.label()));
        assertTrue(pending.matches(1, 2, 3, 4, DIGEST, LABEL), "The immutable request cannot drift to current state");
    }

    @Test void priorCheckpointAckCannotSatisfyTheNextCheckpointOnTheSamePair() {
        var prior = request(); var next = new RollingScenarioCheckpoint(1, 2, 3, 4, DIGEST, "scenario12-15-next");
        assertFalse(next.matches(prior.epoch(), prior.publication(), prior.resources(), prior.coverage(), prior.digest(), prior.label()));
    }

    @Test void nonpositiveGenerationsAndUnboundedUnsafeLabelsAreRejectedAtConstruction() {
        for (long bad : new long[]{Long.MIN_VALUE, -1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> new RollingScenarioCheckpoint(bad, 2, 3, 4, DIGEST, LABEL));
            assertThrows(IllegalArgumentException.class, () -> new RollingScenarioCheckpoint(1, bad, 3, 4, DIGEST, LABEL));
            assertThrows(IllegalArgumentException.class, () -> new RollingScenarioCheckpoint(1, 2, bad, 4, DIGEST, LABEL));
            assertThrows(IllegalArgumentException.class, () -> new RollingScenarioCheckpoint(1, 2, 3, bad, DIGEST, LABEL));
        }
        for (String bad : new String[]{null, "", "x".repeat(65), "../report", "nested/file", "white space", "UPPER", "nul\0"})
            assertThrows(IllegalArgumentException.class, () -> new RollingScenarioCheckpoint(1, 2, 3, 4, DIGEST, bad));
        assertDoesNotThrow(() -> new RollingScenarioCheckpoint(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, DIGEST, "x".repeat(64)));
    }

    @Test void digestMustBeCanonicalSha256AndTheValueRetainsOnlyBoundedScalars() {
        for (String bad : new String[]{null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64)})
            assertThrows(IllegalArgumentException.class, () -> new RollingScenarioCheckpoint(1, 2, 3, 4, bad, LABEL));
        for (var component : RollingScenarioCheckpoint.class.getRecordComponents())
            assertTrue(component.getType() == long.class || component.getType() == String.class);
        assertEquals(request(), request());
    }
}
