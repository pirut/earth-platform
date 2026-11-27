package io.earthplatform.minecraft.cubic.client;

/** Pending notice state, independent of Minecraft so early failures can wait for a player. */
final class FarTerrainNotice {
    enum Reason {
        UNSUPPORTED_GL("earthplatform.far.disabled.opengl"),
        SHADER_BUILD("earthplatform.far.disabled.shader");

        final String translationKey;
        Reason(String translationKey) { this.translationKey = translationKey; }
    }

    private Reason pending;
    private boolean delivered;

    synchronized void request(Reason reason) {
        if (!delivered && pending == null) pending = reason;
    }

    /** Only consumes the first failure once chat and a player are available. */
    synchronized Reason take(boolean ready) {
        if (!ready || delivered || pending == null) return null;
        delivered = true;
        return pending;
    }
}
