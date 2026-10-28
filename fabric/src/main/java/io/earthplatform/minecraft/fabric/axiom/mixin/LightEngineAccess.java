package io.earthplatform.minecraft.fabric.axiom.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelLightEngine.class)
public interface LightEngineAccess {
    @Accessor("levelHeightAccessor") LevelHeightAccessor earthplatform$heightAccessor();
}
