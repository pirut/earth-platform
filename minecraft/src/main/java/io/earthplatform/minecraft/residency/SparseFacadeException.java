package io.earthplatform.minecraft.residency;

/** A scoped unsupported/unknown access: callers must not reinterpret it as air or regenerate data. */
public final class SparseFacadeException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    public SparseFacadeException(String message) { super("Earth Platform sparse Gate 0: " + message); }
}
