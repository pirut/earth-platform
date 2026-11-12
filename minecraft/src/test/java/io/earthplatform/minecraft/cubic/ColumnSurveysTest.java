package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;

class ColumnSurveysTest {
    /** Workers that run only when told to, and surveys that fail while {@code failing} says so. */
    private static final class Rig {
        final List<Runnable> queued = new ArrayList<>();
        int asked;
        LongPredicate failing = column -> false;
        final ColumnSurveys surveys;

        Rig(boolean surfaces) {
            surveys = new ColumnSurveys(surfaces, false, queued::add, (column, x, z) -> {
                asked++;
                boolean failed = failing.test(column);
                long low = failed ? ColumnSurvey.FAILED : 40;
                return surfaces ? new long[] {column, low, failed ? ColumnSurvey.FAILED : 63, failed ? ColumnSurvey.FAILED : 90} : new long[] {column, low};
            });
        }

        /** The workers finish what they were given, and the server takes it in on {@code tick}. */
        void finish(int tick) {
            List<Runnable> now = new ArrayList<>(queued);
            queued.clear();
            now.forEach(Runnable::run);
            surveys.drain(tick);
        }
    }

    @Test void offEveryColumnIsAskedForOnceAndAFailureStaysAsBefore() {
        Rig rig = new Rig(false);
        for (int x = 0; x < 500; x++) assertEquals(ColumnSurveys.UNKNOWN_LOW, rig.surveys.lowest(x, 0, ColumnSurveys.ANY));
        assertEquals(500, rig.surveys.inFlight(), "no limit, as before");
        rig.surveys.lowest(3, 0, ColumnSurveys.ANY);
        assertEquals(500, rig.queued.size(), "a column in flight is not asked for again");
        rig.failing = column -> column == CubeKey.column(7, 0);
        rig.finish(0);
        assertEquals(40, rig.surveys.lowest(3, 0, ColumnSurveys.ANY));
        assertEquals(SurfaceFlight.UNKNOWN, rig.surveys.surfaceBounds(3, 0), "off, no surface bounds are kept");
        for (int tick : new int[] {100, 10_000, 1_000_000}) {
            rig.surveys.drain(tick);
            assertEquals(Integer.MIN_VALUE, rig.surveys.lowest(7, 0, ColumnSurveys.ANY), "the failed ground, as on main");
        }
        assertEquals(500, rig.asked, "off, a failed survey is never asked for again");
    }

    /**
     * Review: the survey limit held only while a flyer was served. Adopting the cubes that came in at the start of
     * the next tick ({@code openBehind} from {@code adoptReady}) queued surveys without limit ahead of its next cubes.
     */
    @Test void aFlyersSurveysStayFewOnEveryPathIncludingAdoption() {
        Rig rig = new Rig(true);
        int send = SurfaceFlight.surveyCap(true, false), ask = SurfaceFlight.surveyCap(true, true);
        // Tick 1: its band is sent, so its columns are surveyed, nearest first, a few at a time.
        for (int x = 0; x < 40; x++) rig.surveys.buried(x, 0, 0, send);
        assertEquals(SurfaceFlight.SURVEYS, rig.surveys.inFlight());
        // Tick 2, before the workers finish: 40 cubes of its surface are adopted, each with open faces and columns
        // around it not surveyed yet, as adoptReady passes them with someone flying.
        for (int x = 0; x < 40; x++) rig.surveys.openBehind(100 + 2 * x, 4, 50, 63, SurfaceFlight.surveyCap(true, false));
        assertEquals(SurfaceFlight.SURVEYS, rig.surveys.inFlight(), "adoption asks for no more than a flyer may have");
        // Then its cubes are asked for: no survey at all.
        for (int x = 0; x < 100; x++) rig.surveys.buried(x, 0, 200, ask);
        assertEquals(SurfaceFlight.SURVEYS, rig.surveys.inFlight());
        // Storing or unloading cubes with someone flying is held to the same.
        for (int x = 0; x < 100; x++) rig.surveys.buried(x, 0, 300, SurfaceFlight.surveyCap(true, false));
        assertEquals(SurfaceFlight.SURVEYS, rig.surveys.inFlight());
        rig.finish(1);
        assertEquals(0, rig.surveys.inFlight());
        for (int x = 0; x < 40; x++) rig.surveys.openBehind(100 + 2 * x, 4, 50, 63, SurfaceFlight.surveyCap(true, false));
        assertEquals(SurfaceFlight.SURVEYS, rig.surveys.inFlight());

        // With nobody flying, adoption asks for every column it reads, as on main.
        Rig foot = new Rig(true);
        for (int x = 0; x < 40; x++) foot.surveys.openBehind(100 + 2 * x, 4, 50, 63, SurfaceFlight.surveyCap(false, false));
        // Each cube's column and the four beside it: 81 along the row, shared, and 40 on each side of it.
        assertEquals(81 + 40 + 40, foot.surveys.inFlight());
    }

    /** Review: one failed survey of a flyer's own column turned surface streaming off for it for good. */
    @Test void aFailedSurfaceSurveyIsAskedForAgainLaterAndLaterThenLeft() {
        Rig rig = new Rig(true);
        long column = CubeKey.column(5, -2);
        rig.failing = c -> true;
        rig.surveys.lowest(5, -2, ColumnSurveys.ANY);
        rig.finish(0);
        assertEquals(SurfaceFlight.UNKNOWN, rig.surveys.surfaceBounds(5, -2), "failed bounds read as not known, not as ground");
        assertEquals(Integer.MIN_VALUE, rig.surveys.lowest(5, -2, ColumnSurveys.ANY), "until then, the failed ground, as on main");
        int at = 0;
        for (int retry = 1; retry <= ColumnSurveys.RETRIES; retry++) {
            int wait = ColumnSurveys.RETRY_TICKS << (retry - 1);
            rig.surveys.drain(at + wait - 1);
            rig.surveys.lowest(5, -2, ColumnSurveys.ANY);
            assertTrue(rig.queued.isEmpty(), "not before its wait, retry " + retry);
            rig.surveys.drain(at + wait);
            rig.surveys.lowest(5, -2, 0);
            assertTrue(rig.queued.isEmpty(), "a retry keeps to the caller's limit");
            rig.surveys.lowest(5, -2, ColumnSurveys.ANY);
            assertEquals(1, rig.queued.size(), "asked for again, retry " + retry);
            rig.surveys.lowest(5, -2, ColumnSurveys.ANY);
            assertEquals(1, rig.queued.size(), "once");
            at += wait;
            rig.finish(at);
        }
        assertEquals(1 + ColumnSurveys.RETRIES, rig.asked);
        rig.surveys.drain(Integer.MAX_VALUE - 1);
        rig.surveys.lowest(5, -2, ColumnSurveys.ANY);
        assertTrue(rig.queued.isEmpty(), "left after the last retry");

        // Once the column is forgotten, as when the player has gone, it is tried afresh the next time.
        rig.surveys.prune(c -> c != column);
        rig.failing = c -> false;
        rig.surveys.lowest(5, -2, ColumnSurveys.ANY);
        rig.finish(Integer.MAX_VALUE - 1);
        assertEquals(SurfaceFlight.bounds(63, 90), rig.surveys.surfaceBounds(5, -2));
    }

    @Test void aRetryThatWorksGivesTheFlyerItsGround() {
        Rig rig = new Rig(true);
        rig.failing = c -> true;
        rig.surveys.lowest(1, 1, ColumnSurveys.ANY);
        rig.finish(0);
        assertEquals(Integer.MIN_VALUE, SurfaceFlight.ground(rig.surveys.surfaceBounds(1, 1), Integer.MIN_VALUE));
        assertFalse(SurfaceFlight.flying(1000, SurfaceFlight.ground(rig.surveys.surfaceBounds(1, 1), Integer.MIN_VALUE), false));
        rig.failing = c -> false;
        rig.surveys.drain(ColumnSurveys.RETRY_TICKS);
        rig.surveys.lowest(1, 1, ColumnSurveys.ANY);
        rig.finish(ColumnSurveys.RETRY_TICKS);
        assertEquals(40, rig.surveys.lowest(1, 1, ColumnSurveys.ANY));
        int ground = SurfaceFlight.ground(rig.surveys.surfaceBounds(1, 1), Integer.MIN_VALUE);
        assertEquals(90, ground);
        assertTrue(SurfaceFlight.flying(90 + SurfaceFlight.START_HEIGHT, ground, false));
        rig.surveys.drain(1_000_000);
        rig.surveys.lowest(1, 1, ColumnSurveys.ANY);
        assertTrue(rig.queued.isEmpty(), "a column that worked is not asked for again");
    }

    /** A failed survey means ground that can't be had: nothing is buried there, as {@link ColumnSurvey#of} says. */
    @Test void aFailedSurveyBuriesNothingInItsColumnOrNextToIt() {
        for (boolean surfaces : new boolean[] {false, true}) {
            Rig rig = new Rig(surfaces);
            rig.failing = column -> column == CubeKey.column(0, 0);
            for (int x = -1; x <= 1; x++) rig.surveys.lowest(x, 0, ColumnSurveys.ANY);
            rig.surveys.lowest(0, 1, ColumnSurveys.ANY);
            rig.surveys.lowest(0, -1, ColumnSurveys.ANY);
            rig.finish(0);
            assertEquals(Integer.MIN_VALUE, rig.surveys.lowest(0, 0, ColumnSurveys.ANY));
            for (int y : new int[] {-1000, -100, -5, 0, 2, 50})
                assertEquals(0, rig.surveys.buried(0, y, 0, ColumnSurveys.ANY), "failed column, cube y=" + y);
            // The same cubes over a column that worked (ground at 40) are buried when deep.
            Rig worked = new Rig(surfaces);
            for (int x = -1; x <= 1; x++) worked.surveys.lowest(x, 0, ColumnSurveys.ANY);
            worked.surveys.lowest(0, 1, ColumnSurveys.ANY);
            worked.surveys.lowest(0, -1, ColumnSurveys.ANY);
            worked.finish(0);
            assertEquals(1, worked.surveys.buried(0, -100, 0, ColumnSurveys.ANY));
            assertEquals(0, worked.surveys.buried(0, 2, 0, ColumnSurveys.ANY));
            // A neighbour's failure buries nothing in the centre column, which worked.
            worked.failing = column -> column == CubeKey.column(1, 0);
            worked.surveys.prune(column -> column != CubeKey.column(1, 0));
            worked.surveys.lowest(1, 0, ColumnSurveys.ANY);
            worked.finish(1);
            assertEquals(40, worked.surveys.lowest(0, 0, ColumnSurveys.ANY));
            for (int y : new int[] {-100, -5, 2, 50})
                assertEquals(0, worked.surveys.buried(0, y, 0, ColumnSurveys.ANY), "failed neighbour, cube y=" + y);
        }
    }
}
