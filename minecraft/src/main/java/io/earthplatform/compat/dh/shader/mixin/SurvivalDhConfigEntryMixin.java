package io.earthplatform.compat.dh.shader.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.earthplatform.compat.dh.SurvivalDhConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** API set/clear and Minecraft-version override are the only other pinned value writers. */
@Mixin(targets="com.seibel.distanthorizons.core.config.types.ConfigEntry",remap=false)
public abstract class SurvivalDhConfigEntryMixin implements SurvivalDhConfiguration.OverrideAccess {
    @Unique private long earthplatform$survivalOverrideGeneration;
    @Unique private int earthplatform$survivalOverrideWriters;
    @Unique private boolean earthplatform$survivalOverridePoison;
    @Unique private void earthplatform$survivalBeginOverride() {
        synchronized (this) {
            if (earthplatform$survivalOverrideWriters!=0 || earthplatform$survivalOverrideGeneration==Long.MAX_VALUE)
                earthplatform$survivalOverridePoison=true;
            else earthplatform$survivalOverrideGeneration++;
            if (earthplatform$survivalOverrideWriters==Integer.MAX_VALUE) earthplatform$survivalOverridePoison=true;
            else earthplatform$survivalOverrideWriters++;
        }
    }
    @Unique private void earthplatform$survivalEndOverride(boolean returned) {
        synchronized (this) {
            if (!returned) earthplatform$survivalOverridePoison=true;
            if (earthplatform$survivalOverrideWriters>0) earthplatform$survivalOverrideWriters--;
            else earthplatform$survivalOverridePoison=true;
        }
    }
    @WrapMethod(method="setApiValue(Ljava/lang/Object;Ljava/lang/String;)V")
    private void earthplatform$survivalApiMutation(Object value, String user, Operation<Void> original) {
        earthplatform$survivalBeginOverride(); boolean returned=false;
        try { original.call(value,user); returned=true; }
        finally { earthplatform$survivalEndOverride(returned); }
    }
    @WrapMethod(method="setMcVersionOverrideValue(Ljava/lang/Object;)V")
    private void earthplatform$survivalVersionMutation(Object value, Operation<Void> original) {
        earthplatform$survivalBeginOverride(); boolean returned=false;
        try { original.call(value); returned=true; }
        finally { earthplatform$survivalEndOverride(returned); }
    }
    @Override public synchronized long earthplatform$survivalOverrideGeneration() {
        if (earthplatform$survivalOverridePoison || earthplatform$survivalOverrideWriters!=0)
            throw new IllegalStateException("Installed DH override mutation is active or poisoned");
        return earthplatform$survivalOverrideGeneration;
    }
}
