package io.earthplatform.compat.dh.shader.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.earthplatform.compat.dh.SurvivalDhConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** All normal, file-load, reset and no-event config writes reach this pinned root. */
@Mixin(targets="com.seibel.distanthorizons.core.config.types.AbstractConfigBase",remap=false)
public abstract class SurvivalDhConfigBaseMixin implements SurvivalDhConfiguration.BaseAccess {
    @Unique private long earthplatform$survivalBaseGeneration;
    @Unique private int earthplatform$survivalBaseWriters;
    @Unique private boolean earthplatform$survivalBasePoison;
    @WrapMethod(method="set(Ljava/lang/Object;)V")
    private void earthplatform$survivalBaseMutation(Object value, Operation<Void> original) {
        synchronized (this) {
            if (earthplatform$survivalBaseWriters != 0 || earthplatform$survivalBaseGeneration == Long.MAX_VALUE)
                earthplatform$survivalBasePoison=true;
            else earthplatform$survivalBaseGeneration++;
            if (earthplatform$survivalBaseWriters == Integer.MAX_VALUE) earthplatform$survivalBasePoison=true;
            else earthplatform$survivalBaseWriters++;
        }
        boolean returned=false;
        try { original.call(value); returned=true; }
        finally { synchronized (this) {
            if (!returned) earthplatform$survivalBasePoison=true;
            if (earthplatform$survivalBaseWriters>0) earthplatform$survivalBaseWriters--;
            else earthplatform$survivalBasePoison=true;
        } }
    }
    @Override public synchronized long earthplatform$survivalBaseGeneration() {
        if (earthplatform$survivalBasePoison || earthplatform$survivalBaseWriters!=0)
            throw new IllegalStateException("Installed DH base configuration mutation is active or poisoned");
        return earthplatform$survivalBaseGeneration;
    }
}
