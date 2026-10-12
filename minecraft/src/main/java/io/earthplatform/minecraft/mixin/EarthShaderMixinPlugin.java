package io.earthplatform.minecraft.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Optional transform selection. Never initializes Minecraft, Iris or DH target classes.
 * Exact artifact/origin/policy admission still occurs in the runtime resolver. */
public final class EarthShaderMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String targetClassName,String mixinClassName){
        if(!Boolean.getBoolean("earthplatform.experimental.installedShaderBackend")
                &&!io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled())return false;
        var mods=net.neoforged.fml.loading.LoadingModList.get();
        return mods!=null&&mods.getModFileById("distanthorizons")!=null
                &&mods.getModFileById("iris")!=null&&mods.getModFileById("sodium")!=null;
    }
    @Override public void acceptTargets(Set<String> mine,Set<String> other){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String name,ClassNode target,String mixin,IMixinInfo info){}
    @Override public void postApply(String name,ClassNode target,String mixin,IMixinInfo info){}
}
