package io.earthplatform.minecraft.cubic.client;

/**
 * A frame's share of time for mesh uploads, which come in more than one call a frame (the shadow map's, then the
 * view's). It counts only the time the uploads themselves take, never what is drawn between the calls, so a shadow pass
 * that found nothing ready leaves the view's call its whole share. The first upload of the frame always goes, however
 * long it takes; a call that finds nothing to upload uses none of it.
 */
final class CubeUploadBudget {
    private final long nanos;
    private long spent;
    private boolean uploaded;

    CubeUploadBudget(long nanos) { this.nanos = nanos; }

    /** Whether another upload may start. */
    boolean mayStart() { return !uploaded || spent < nanos; }

    /** One finished mesh taken off the queue took {@code nanos}; {@code uploaded} false if it was thrown away instead. */
    void spend(long nanos, boolean uploaded) {
        spent += nanos;
        this.uploaded |= uploaded;
    }
}
