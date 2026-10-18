package io.earthplatform.minecraft.cubic.client;

/** Exact selection and allocation identity for reusable opaque draw commands. */
final class FarCommandState {
    private Object owner;
    private long selection, allocations;
    private int reach;

    boolean matches(Object owner, long selection, long allocations, float reach) {
        return owner != null && this.owner == owner && this.selection == selection && this.allocations == allocations
                && this.reach == Float.floatToRawIntBits(reach);
    }

    void remember(Object owner, long selection, long allocations, float reach) {
        this.owner = owner;
        this.selection = selection;
        this.allocations = allocations;
        this.reach = Float.floatToRawIntBits(reach);
    }

    void invalidate() { owner = null; }
}
