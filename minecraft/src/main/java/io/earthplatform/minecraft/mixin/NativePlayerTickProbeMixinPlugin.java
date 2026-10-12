package io.earthplatform.minecraft.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** No game classes loaded before transformation. Both explicit diagnostic flags are required. */
public final class NativePlayerTickProbeMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String targetClassName,String mixinClassName) {
        return Boolean.getBoolean("earthplatform.nativePlayerTickDiagnostic") && Boolean.getBoolean("neoforge.gameTestServer");
    }
    public void acceptTargets(Set<String> myTargets,Set<String> otherTargets) {}
    public List<String> getMixins() { return null; }
    public void preApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo) {}
    public void postApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo) {}
}
