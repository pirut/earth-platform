package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NamedThreadsTest {
    private static final Runnable NOTHING = () -> {};

    @Test
    void namedThreadsAreDaemonsWithTheGivenNameAndPriority() {
        Thread plain = NamedThreads.daemon("Earth cube writer").newThread(NOTHING);
        assertEquals("Earth cube writer", plain.getName());
        assertTrue(plain.isDaemon());
        assertEquals(Thread.currentThread().getPriority(), plain.getPriority(), "an unset priority is inherited");

        Thread low = NamedThreads.daemon("Earth far builds", Thread.MIN_PRIORITY).newThread(NOTHING);
        assertEquals("Earth far builds", low.getName());
        assertTrue(low.isDaemon());
        assertEquals(Thread.MIN_PRIORITY, low.getPriority());
    }

    @Test
    void numberedThreadsCountFromOneAndASharedCounterCarriesOn() {
        ThreadFactory meshers = NamedThreads.numbered("Earth far mesher", Thread.NORM_PRIORITY - 1);
        Thread first = meshers.newThread(NOTHING), second = meshers.newThread(NOTHING);
        assertEquals("Earth far mesher 1", first.getName());
        assertEquals("Earth far mesher 2", second.getName());
        assertTrue(first.isDaemon());
        assertEquals(Thread.NORM_PRIORITY - 1, first.getPriority());

        AtomicInteger shared = new AtomicInteger();
        NamedThreads.numbered("Earth cube worker", shared, Thread.NORM_PRIORITY - 1).newThread(NOTHING);
        Thread next = NamedThreads.numbered("Earth cube worker", shared, Thread.NORM_PRIORITY - 1).newThread(NOTHING);
        assertEquals("Earth cube worker 2", next.getName());
    }
}
