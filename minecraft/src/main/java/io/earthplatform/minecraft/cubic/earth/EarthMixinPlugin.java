package io.earthplatform.minecraft.cubic.earth;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Keeps the engine's developer flags working under their Earth Platform names: the engine, now the
 * Chunks3 library, reads {@code -Dchunks3.far.fade} and the like, so each {@code -Dearthplatform.*}
 * flag given is copied to its {@code chunks3.*} name unless that is set too. Runs as Mixin starts,
 * before any engine class reads a flag. Must not touch Minecraft classes: it is loaded before them.
 */
public final class EarthMixinPlugin implements IMixinConfigPlugin {
    private static final String EARTH = "earthplatform.", ENGINE = "chunks3.";

    @Override public void onLoad(String mixinPackage) {
        for (String name : System.getProperties().stringPropertyNames()) {
            if (!name.startsWith(EARTH)) continue;
            String engine = ENGINE + name.substring(EARTH.length());
            if (System.getProperty(engine) == null) System.setProperty(engine, System.getProperty(name));
        }
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
