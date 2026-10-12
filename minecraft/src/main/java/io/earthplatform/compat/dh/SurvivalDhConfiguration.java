package io.earthplatform.compat.dh;

import java.util.Objects;

/** Read-only view of the three exact pinned config mutation roots. No public writer or proof factory. */
public final class SurvivalDhConfiguration {
    private SurvivalDhConfiguration() {}
    /** Implemented only by our actual AbstractConfigBase transform. The getter is synchronized. */
    public interface BaseAccess { long earthplatform$survivalBaseGeneration(); }
    /** Implemented only by our actual ConfigEntry override-root transform. */
    public interface OverrideAccess { long earthplatform$survivalOverrideGeneration(); }

    private record Entry(Object object, long base, long override) {
        static Entry capture(Object object) {
            synchronized (object) {
                return new Entry(object, ((BaseAccess)object).earthplatform$survivalBaseGeneration(),
                        ((OverrideAccess)object).earthplatform$survivalOverrideGeneration());
            }
        }
        void requireCurrent() {
            synchronized (object) {
                if (((BaseAccess)object).earthplatform$survivalBaseGeneration() != base
                        || ((OverrideAccess)object).earthplatform$survivalOverrideGeneration() != override)
                    throw new IllegalStateException("Installed DH configuration generation was retired");
            }
        }
        boolean same(Entry other) { return object == other.object && base == other.base && override == other.override; }
    }
    /** Internal immutable receipt. Public scalar observations cannot construct or attach one. */
    static final class Witness {
        private final Entry grass, debug;
        private Witness(Entry grass, Entry debug) { this.grass=grass; this.debug=debug; }
        void requireCurrent(Object exactGrass, Object exactDebug) {
            if (grass.object != exactGrass || debug.object != exactDebug)
                throw new IllegalStateException("Installed DH configuration entry identity changed");
            grass.requireCurrent(); debug.requireCurrent(); grass.requireCurrent();
        }
        boolean same(Witness other) { return other != null && grass.same(other.grass) && debug.same(other.debug); }
    }
    /** Untransformed helper tests remain historical packing only; no current receipt is created. */
    static Witness capture(Class<?> exactEntryClass, Object grass, Object debug) {
        Objects.requireNonNull(exactEntryClass); Objects.requireNonNull(grass); Objects.requireNonNull(debug);
        if (grass.getClass()!=exactEntryClass || debug.getClass()!=exactEntryClass)
            throw new IllegalStateException("Foreign installed DH configuration entry");
        if (!(grass instanceof BaseAccess) || !(grass instanceof OverrideAccess)
                || !(debug instanceof BaseAccess) || !(debug instanceof OverrideAccess)) return null;
        var result=new Witness(Entry.capture(grass), Entry.capture(debug));
        result.requireCurrent(grass,debug); return result;
    }
}
