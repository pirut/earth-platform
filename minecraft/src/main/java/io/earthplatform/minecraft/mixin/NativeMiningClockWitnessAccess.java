package io.earthplatform.minecraft.mixin;
import net.minecraft.Util;
import net.minecraft.util.TimeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Read-only transformed witness. Missing/late transformation fails closed at admission. */
@Mixin(Util.class)
public interface NativeMiningClockWitnessAccess {
    @Accessor("earthplatform$initialNativeMiningClock")
    static TimeSource.NanoTimeSource earthplatform$originalMiningClock() {
        throw new UnsupportedOperationException("Original Util clock witness transformation was not installed");
    }
}
