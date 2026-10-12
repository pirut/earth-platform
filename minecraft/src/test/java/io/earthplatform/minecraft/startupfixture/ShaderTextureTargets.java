package io.earthplatform.minecraft.startupfixture;

/** Original no-game test targets; never packaged with the shim. */
public final class ShaderTextureTargets {
    private ShaderTextureTargets() {}
    public static final class RenderAliases {
        private static final int[] slots = new int[12];
        public static int[] raw() { return slots; }
    }
    public static final class BindingAliases {
        private static final int[] slots = new int[12];
        public static int[] raw() { return slots; }
    }
    public static final class Unregistered {
        private static final int[] slots = new int[12];
        public static int[] raw() { return slots; }
    }
}
