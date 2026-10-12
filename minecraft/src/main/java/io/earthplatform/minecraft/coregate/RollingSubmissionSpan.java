package io.earthplatform.minecraft.coregate;

import java.util.function.BooleanSupplier;

/** Diagnostic finalization must not replace a rendering failure after the renderer restored its owners. */
final class RollingSubmissionSpan {
    private RollingSubmissionSpan() {}
    static boolean run(BooleanSupplier work, Runnable finish) {
        Throwable primary = null;
        try { return work.getAsBoolean(); }
        catch (RuntimeException | Error failure) { primary = failure; throw failure; }
        finally { finish(primary, finish); }
    }
    static void finish(Throwable primary, Runnable finish) {
        try { finish.run(); }
        catch (RuntimeException | Error failure) {
            if (primary == null) throw failure;
            if (primary != failure) primary.addSuppressed(failure);
        }
    }
}
