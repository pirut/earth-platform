package io.earthplatform.minecraft.survival.dhfixture;

import java.util.Objects;

/** Fixture-only equivalents for the migrated assertions. No JUnit runtime or production capability. */
public final class DhProducerFixtureAssertions {
    private DhProducerFixtureAssertions() {}
    public static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("Expected true");
    }
    public static void assertFalse(boolean value) {
        if (value) throw new AssertionError("Expected false");
    }
    public static void assertEquals(long expected, long actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void assertEquals(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    @FunctionalInterface public interface CheckedOperation { void run() throws Throwable; }
    public static <T extends Throwable> T assertThrows(Class<T> type, CheckedOperation operation) {
        try { operation.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError("Expected " + type.getName() + ", got " + failure.getClass().getName(), failure);
        }
        throw new AssertionError("Expected " + type.getName() + " but nothing was thrown");
    }
}
