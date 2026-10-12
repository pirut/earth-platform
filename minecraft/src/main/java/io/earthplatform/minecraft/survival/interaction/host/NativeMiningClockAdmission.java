package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.mixin.NativeMiningClockWitnessAccess;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.Util;
import net.minecraft.util.TimeSource;
/** Exact original System::nanoTime supplier lineage; observing a later public supplier is insufficient. */
final class NativeMiningClockAdmission {
    private final TimeSource.NanoTimeSource original;
    private NativeMiningClockAdmission(TimeSource.NanoTimeSource original){this.original=original;}
    static NativeMiningClockAdmission capture() {
        NativeMiningControlPins.require(Util.class);
        NativeMiningControlPins.require(TimeSource.class);
        NativeMiningControlPins.require(TimeSource.NanoTimeSource.class);
        TimeSource.NanoTimeSource witnessed;
        try{witnessed=NativeMiningClockWitnessAccess.earthplatform$originalMiningClock();}
        catch(RuntimeException|LinkageError failure){
            var refused=new SurvivalRefusal("Original Util clock witness is unavailable; verify default-off mixin load order before mining");
            refused.initCause(failure);throw refused;
        }
        if(witnessed==null||Util.timeSource!=witnessed)
            throw new SurvivalRefusal("Native mining clock is not the exact original witnessed Util supplier");
        return new NativeMiningClockAdmission(witnessed);
    }
    void require() {
        if(Util.timeSource!=original||NativeMiningClockWitnessAccess.earthplatform$originalMiningClock()!=original)
            throw new SurvivalRefusal("Original native clock supplier changed during mining preparation");
    }
}
