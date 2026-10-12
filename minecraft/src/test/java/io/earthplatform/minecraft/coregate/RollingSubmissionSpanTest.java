package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class RollingSubmissionSpanTest {
    @Test void trueAndFalseResultsArePreservedAndFinishRunsLast() {
        for (boolean expected : new boolean[]{true,false}) {
            var calls=new ArrayList<String>();
            assertEquals(expected,RollingSubmissionSpan.run(()->{calls.add("work");return expected;},()->calls.add("finish")));
            assertEquals(java.util.List.of("work","finish"),calls);
        }
    }
    @Test void workFailureIsNotReplacedByFinishRuntimeException() {
        var work=new IllegalStateException("draw");var finish=new IllegalArgumentException("timing");
        assertSame(work,assertThrows(IllegalStateException.class,()->RollingSubmissionSpan.run(()->{throw work;},()->{throw finish;})));
        assertArrayEquals(new Throwable[]{finish},work.getSuppressed());
    }
    @Test void workFailureIsNotReplacedByFinishAllocationError() {
        var work=new IllegalStateException("draw");var finish=new OutOfMemoryError("simulated timing allocation");
        assertSame(work,assertThrows(IllegalStateException.class,()->RollingSubmissionSpan.run(()->{throw work;},()->{throw finish;})));
        assertArrayEquals(new Throwable[]{finish},work.getSuppressed());
    }
    @Test void workErrorRetainsItsIdentityAndFinishRuns() {
        var work=new AssertionError("draw");var finish=new IllegalStateException("timing");
        assertSame(work,assertThrows(AssertionError.class,()->RollingSubmissionSpan.run(()->{throw work;},()->{throw finish;})));
        assertArrayEquals(new Throwable[]{finish},work.getSuppressed());
    }
    @Test void finishFailureAfterSuccessfulWorkRemainsVisible() {
        var finish=new AssertionError("timing");
        assertSame(finish,assertThrows(AssertionError.class,()->RollingSubmissionSpan.run(()->true,()->{throw finish;})));
    }
    @Test void repeatedSameFailureDoesNotSelfSuppress() {
        var same=new IllegalStateException("same");
        assertSame(same,assertThrows(IllegalStateException.class,()->RollingSubmissionSpan.run(()->{throw same;},()->{throw same;})));
        assertEquals(0,same.getSuppressed().length);
    }
    @Test void finishRunsAfterFailedWorkEvenWhenItSucceeds() {
        var work=new IllegalStateException("draw");var calls=new ArrayList<String>();
        assertSame(work,assertThrows(IllegalStateException.class,()->RollingSubmissionSpan.run(()->{calls.add("work");throw work;},()->calls.add("finish"))));
        assertEquals(java.util.List.of("work","finish"),calls);
    }
}
