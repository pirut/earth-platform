package io.earthplatform.minecraft.mixin;

import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Only observes constructor-final native fields; never invokes or returns the payload. */
@Mixin(TickTask.class)
public abstract class NativeTickTaskPayloadMixin implements NativeTickTaskPayloadAccess {
    @Shadow @Final private Runnable runnable;
    @Shadow @Final private int tick;

    @Override public final boolean earthplatform$matchesPayload(Object expected) {
        return runnable == expected;
    }

    @Override public final int earthplatform$nativeTick() {
        return tick;
    }
}
