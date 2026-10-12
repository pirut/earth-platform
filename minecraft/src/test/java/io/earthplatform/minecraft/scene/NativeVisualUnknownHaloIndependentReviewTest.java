package io.earthplatform.minecraft.scene;

import java.lang.reflect.Modifier;
import java.time.Duration;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent refusal-boundary tests. These do not establish renderer parity or installed-client readiness. */
class NativeVisualUnknownHaloIndependentReviewTest {
    private static final String STAMP = "abcd0123".repeat(8);

    private static NativeVisualCpuBuilder.Input input(int x, int y, int z) {
        byte[] states = new byte[NativeVisualCpuBuilder.HALO_BYTES];
        states[NativeVisualCpuBuilder.Input.haloIndex(0, 0, 0)] = NativeVisualCapsule.STONE;
        states[NativeVisualCpuBuilder.Input.haloIndex(x, y, z)] = (byte) NativeVisualCapsule.UNKNOWN;
        return new NativeVisualCpuBuilder.Input(0, 0, 0, states, new int[]{0}, STAMP);
    }

    private static NativeVisualCpuBuilder.UnknownDependency token(NativeVisualCpuBuilder.Input input, int x, int y, int z) {
        return assertThrows(NativeVisualCpuBuilder.UnknownDependency.class, () -> input.state(x, y, z));
    }

    private static NativeVisualCpuBuilder.UnknownDependency token(NativeVisualCpuBuilder.Input input) {
        return token(input, -1, 0, 0);
    }

    private static ReportedException report(Throwable cause) {
        return new ReportedException(new CrashReport("Independent refusal-boundary fixture", cause));
    }

    private static boolean accepts(Throwable failure, NativeVisualCpuBuilder.UnknownDependency recorded,
                                   NativeVisualCpuBuilder.Input input) {
        return NativeVisualPageOracle.isExpectedUnknownHaloFailure(failure, recorded, input);
    }

    @Test void exactRecordedTokenAllowsOnlyZeroOneOrTwoPinnedWrapperNodes() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        assertTrue(accepts(recorded, recorded, input));
        assertTrue(accepts(report(recorded), recorded, input));
        assertTrue(accepts(report(report(recorded)), recorded, input));
        assertFalse(accepts(report(report(report(recorded))), recorded, input));
    }

    @Test void anotherThrowAtTheSameInputAndCoordinatesCannotReplaceTheRecordedToken() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        var other = token(input);
        assertNotSame(recorded, other);
        assertEquals(recorded.getMessage(), other.getMessage());
        assertFalse(accepts(other, recorded, input));
        assertFalse(accepts(report(other), recorded, input));
        assertFalse(accepts(report(recorded), other, input));
    }

    @Test void identicalBytesAndRequestStampDoNotSubstituteForInputObjectIdentity() {
        var original = input(-1, 0, 0);
        var copy = input(-1, 0, 0);
        assertEquals(original.requestStamp(), copy.requestStamp());
        var recorded = token(original);
        assertFalse(accepts(recorded, recorded, copy));
        assertFalse(accepts(report(recorded), recorded, copy));
    }

    @Test void everyOtherNeighborCoordinateRemainsAnUnexpectedFailure() {
        int rejected = 0;
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            if (x == -1 && y == 0 && z == 0) continue;
            var input = input(x, y, z);
            var recorded = token(input, x, y, z);
            assertFalse(accepts(recorded, recorded, input), x + "," + y + "," + z);
            assertFalse(accepts(report(recorded), recorded, input), x + "," + y + "," + z);
            rejected++;
        }
        assertEquals(26, rejected);
    }

    @Test void messagesAndGenericCausalWrappersCannotAuthorizeARefusal() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        var spoof = new IllegalStateException(recorded.getMessage());
        for (Throwable failure : new Throwable[]{spoof, report(spoof), new RuntimeException(recorded),
                new IllegalStateException(recorded.getMessage(), recorded), new AssertionError(recorded),
                report(new RuntimeException(recorded)), report(new AssertionError(recorded))}) {
            assertFalse(accepts(failure, recorded, input), failure.getClass().getName());
        }
    }

    @Test void suppressedCleanupFaultAtEveryAllowedNodeIsFatal() {
        for (int depth = 0; depth <= 2; depth++) for (int faultyNode = 0; faultyNode <= depth; faultyNode++) {
            var input = input(-1, 0, 0);
            var recorded = token(input);
            Throwable[] nodes = new Throwable[depth + 1];
            nodes[0] = recorded;
            for (int i = 1; i <= depth; i++) nodes[i] = report(nodes[i - 1]);
            nodes[faultyNode].addSuppressed(new AssertionError("Independent cleanup failure"));
            assertFalse(accepts(nodes[depth], recorded, input), "depth=" + depth + ", node=" + faultyNode);
        }
    }

    @Test void evenAnExpectedLookingSuppressedTokenIsFatal() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        var wrapped = report(recorded);
        wrapped.addSuppressed(token(input));
        assertFalse(accepts(wrapped, recorded, input));
        var unrelated = new IllegalStateException("Unrelated failure");
        unrelated.addSuppressed(recorded);
        assertFalse(accepts(unrelated, recorded, input));
    }

    @Test void callersCannotAttachAHiddenCauseToTheSentinel() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        assertThrows(IllegalStateException.class, () -> recorded.initCause(new IllegalStateException("Hidden failure")));
        assertNull(recorded.getCause());
        assertTrue(accepts(recorded, recorded, input));
    }

    @Test void wrapperAndReportSubclassesCannotExpandTheTrustedCauseGraph() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        var reportedSubclass = new ReportedException(new CrashReport("Subclass", recorded)) {};
        assertFalse(accepts(reportedSubclass, recorded, input));
        var reportSubclass = new CrashReport("Subclass", recorded) {};
        assertFalse(accepts(new ReportedException(reportSubclass), recorded, input));
    }

    @Test void missingProvenanceAndMalformedPinnedWrappersReject() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        assertFalse(accepts(null, recorded, input));
        assertFalse(accepts(recorded, null, input));
        assertFalse(accepts(recorded, recorded, null));
        assertFalse(accepts(new ReportedException(null), recorded, input));
        assertFalse(accepts(report(null), recorded, input));
    }

    @Test void untrustedCauseAccessorsAreNeverTraversed() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        var generic = new RuntimeException() {
            @Override public synchronized Throwable getCause() { throw new AssertionError("Untrusted getCause called"); }
        };
        var reportSubclass = new CrashReport("Untrusted accessor", recorded) {
            @Override public Throwable getException() { throw new AssertionError("Untrusted report getter called"); }
        };
        assertFalse(accepts(generic, recorded, input));
        assertFalse(accepts(new ReportedException(reportSubclass), recorded, input));
    }

    @Test void deeplyNestedReportsHaveABoundedClassificationCost() {
        var input = input(-1, 0, 0);
        var recorded = token(input);
        Throwable nested = recorded;
        for (int i = 0; i < 64; i++) nested = report(nested);
        Throwable deep = nested;
        assertTimeout(Duration.ofSeconds(1), () -> assertFalse(accepts(deep, recorded, input)));
    }

    @Test void typedUnknownCannotBeSubclassedOrConstructedByCallers() {
        Class<?> type = NativeVisualCpuBuilder.UnknownDependency.class;
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertTrue(IllegalStateException.class.isAssignableFrom(type));
        for (var constructor : type.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
}
