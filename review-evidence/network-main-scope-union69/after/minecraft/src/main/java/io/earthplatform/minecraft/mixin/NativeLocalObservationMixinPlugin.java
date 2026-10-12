package io.earthplatform.minecraft.mixin;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
/** Explicit ordinary installed-client observation only; no Host or gameplay gate is enabled. */
public final class NativeLocalObservationMixinPlugin implements IMixinConfigPlugin {
    public static boolean enabled(){return Boolean.getBoolean("earthplatform.nativeLocalFrontierObservation")
            &&Boolean.getBoolean("earthplatform.installedSessionProbe")
            &&!Boolean.getBoolean("earthplatform.installedMarinSession")
            &&!Boolean.getBoolean("earthplatform.nativeSessionBridge")
            &&!Boolean.getBoolean("neoforge.gameTestServer");}
    public void onLoad(String name){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        if(mixin.endsWith(".NativeLocalMainPayloadObservationMixin"))
            return enabled()&&Boolean.getBoolean("earthplatform.nativeLocalPayloadObservation");
        if(mixin.endsWith(".NativeLocalConnectionRootsMixin")||mixin.endsWith(".NativeLocalInboundConfigurationRootsMixin")||mixin.endsWith(".NativeLocalOutboundConfigurationRootsMixin"))
            return enabled()&&Boolean.getBoolean("earthplatform.nativeLocalRootObservation");
        return enabled();
    }
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
