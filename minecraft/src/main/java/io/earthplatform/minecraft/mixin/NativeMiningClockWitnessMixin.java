package io.earthplatform.minecraft.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.Util;
import net.minecraft.util.TimeSource;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
/** Capture only the original <clinit> supplier assignment; there is no later witness setter. */
@Mixin(Util.class)
abstract class NativeMiningClockWitnessMixin {
    @Unique private static TimeSource.NanoTimeSource earthplatform$initialNativeMiningClock;
    @WrapOperation(method="<clinit>()V",at=@At(value="FIELD",target="Lnet/minecraft/Util;timeSource:Lnet/minecraft/util/TimeSource$NanoTimeSource;",opcode=Opcodes.PUTSTATIC),require=1,allow=1)
    private static void earthplatform$witnessOriginalMiningClock(TimeSource.NanoTimeSource source,Operation<Void> original) {
        if(source==null||earthplatform$initialNativeMiningClock!=null)
            throw new IllegalStateException("Original native mining clock assignment repeated or missing");
        original.call(source); // Preserve the exact original PUTSTATIC once; never replace the clock.
        earthplatform$initialNativeMiningClock=source;
    }
}
