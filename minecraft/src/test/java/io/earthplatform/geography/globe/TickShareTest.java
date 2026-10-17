package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TickShareTest {
    private static long waited(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void workGivesWayToALateTickForABoundedTimeButTheTickNeverWaits() throws Exception {
        assertTrue(waited(TickShare::giveWay) < 50, "nothing is held back while the tick keeps time");

        TickShare.late();
        assertTrue(TickShare.holding());
        assertTrue(waited(TickShare::giveWay) < 50, "the tick's own thread never waits");

        long other = CompletableFuture.supplyAsync(() -> waited(TickShare::giveWay)).get();
        assertTrue(other >= 200 && other < 400, "background work waits, but at most about a quarter of a second: " + other);

        Thread.sleep(450);
        assertFalse(TickShare.holding(), "work goes on at full speed once the tick is back on time");
        assertTrue(CompletableFuture.supplyAsync(() -> waited(TickShare::giveWay)).get() < 50);
    }
}
