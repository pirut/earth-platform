package io.earthplatform.minecraft.startupfixture;

import io.earthplatform.compat.neoforge.PinnedTempScanContract;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Test source only: executes the real production redirect on real pinned IOUtilities.
 * Only optional-mod admission is bypassed; the exact target contract still runs. */
public final class SavedDataScanTestPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String value){}
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String target,String mixin){
        if(!target.equals(PinnedTempScanContract.TARGET.replace('/','.')))
            throw new IllegalStateException("Unexpected test-only save-path target");
        return true;
    }
    @Override public void acceptTargets(Set<String> own,Set<String> other){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){PinnedTempScanContract.verify(node);}
    @Override public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
