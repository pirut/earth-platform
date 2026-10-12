package io.earthplatform.minecraft.mixin;

import io.earthplatform.compat.neoforge.PinnedTempScanContract;
import io.earthplatform.compat.neoforge.TempFileShimActivation;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Loading metadata only; does not initialize Minecraft, DH or the target class. */
public final class EarthTempFileMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String packageName){}
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String target,String mixin) {
        boolean requested=Boolean.getBoolean(TempFileShimActivation.PROPERTY)
                ||io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled();
        if(!requested)return false;
        if(!PinnedTempScanContract.TARGET.replace('/','.').equals(target))throw new IllegalStateException("Unexpected save-path shim target");
        LoadingModList mods=LoadingModList.get();
        if(mods==null)throw new IllegalStateException("Installed mod metadata is unavailable for requested save-path shim");
        String neo=version(mods,"neoforge"),dh=version(mods,"distanthorizons");
        if(!TempFileShimActivation.select(true,neo,dh))return false;
        TempFileShimActivation.verifyInstalledDh(mods.getModFileById("distanthorizons").getFile().getFilePath());
        return true;
    }
    private static String version(LoadingModList mods,String id) {
        var matches=mods.getMods().stream().filter(m->m.getModId().equals(id)).toList();
        return matches.size()==1?matches.getFirst().getVersion().toString():null;
    }
    @Override public void acceptTargets(Set<String> mine,Set<String> others){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){PinnedTempScanContract.verify(node);}
    @Override public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
