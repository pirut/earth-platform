package io.earthplatform.minecraft.fixture;

import io.earthplatform.minecraft.residency.SparseCubeStorage;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.Objects;
import java.util.OptionalLong;
import net.minecraft.world.level.dimension.DimensionType;

/** Ephemeral constructor-only fixture. Does not widen dimension codecs or register a saved world type. */
public final class SparseFixtureDimensions {
    public static final String OPT_IN = "earthplatform.experimental.nativeSparseFixture";
    public static final int MIN_Y = -11008, HEIGHT = 19872, MAX_Y = 8863;
    private static final ThreadLocal<Boolean> CONSTRUCTING = new ThreadLocal<>();
    private SparseFixtureDimensions() {}

    public static DimensionType create(DimensionType ordinaryTemplate) {
        Objects.requireNonNull(ordinaryTemplate);
        if (!Boolean.getBoolean(OPT_IN) || !Boolean.getBoolean(SparseCubeStorage.OPT_IN)) {
            throw new SparseFacadeException("both nativeSparseFixture and sparseFacade opt-ins are required");
        }
        if (CONSTRUCTING.get() != null) throw new SparseFacadeException("nested fixture dimension construction rejected");
        CONSTRUCTING.set(Boolean.TRUE);
        try {
            return new DimensionType(OptionalLong.of(6000), false, false, false, false, 1.0,
                    false, false, MIN_Y, HEIGHT, HEIGHT, ordinaryTemplate.infiniburn(),
                    ordinaryTemplate.effectsLocation(), 1.0F, ordinaryTemplate.monsterSettings());
        } finally { CONSTRUCTING.remove(); }
    }

    /** Called only at the verified constructor bound comparison; default path returns vanilla MAX_Y. */
    public static int maximumY(int minY, int height, int logicalHeight) {
        if (CONSTRUCTING.get() == null) return DimensionType.MAX_Y;
        if (minY != MIN_Y || height != HEIGHT || logicalHeight != HEIGHT) {
            throw new SparseFacadeException("fixture dimension envelope must match the exact reviewed bounds");
        }
        return MAX_Y;
    }
}
