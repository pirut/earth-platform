package io.earthplatform.minecraft.survival.world;

/** Explicit bounded-world refusal. Unknown/unsupported space must never be interpreted as air. */
public final class SurvivalRefusal extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    public SurvivalRefusal(String message) { super("Earth survival: " + message); }
}
