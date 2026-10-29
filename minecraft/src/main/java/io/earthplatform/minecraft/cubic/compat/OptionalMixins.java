package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.platform.Loader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Leaves out the mixins into other mods when those mods are not installed. Mixin skips them anyway,
 * but only after logging an "Error loading class" warning for every missing target, dozens on
 * every start, which read like a fault in players' logs. Targets in Minecraft, Mojang's libraries
 * and the mod loader are always there and are not looked up. Must not touch Minecraft classes: it
 * is loaded before them.
 */
public final class OptionalMixins implements IMixinConfigPlugin {
    private static final List<String> ALWAYS_THERE = List.of("net.minecraft.", "com.mojang.", "net.neoforged.", "net.fabricmc.");
    private final Map<String, Boolean> present = new ConcurrentHashMap<>();

    /**
     * On Fabric, mixins into other mods stay out. They name their targets as text in NeoForge's
     * (Mojang's) names, which Fabric's build cannot translate for mods it was not compiled against,
     * and those mods' NeoForge and Fabric builds differ inside. Each comes back as it is tested there.
     */
    private static final boolean FABRIC = "fabric".equals(Loader.INSTANCE.name());

    /**
     * Mixins into other mods tested on Fabric too. Their targets are that mod's own code, named the
     * same in its NeoForge and Fabric builds, and the Minecraft methods they look for are remapped
     * like the rest of ours.
     */
    private static final Set<String> ON_FABRIC = Set.of("ComputerCraftTurtleMoveMixin", "ComputerCraftModemRangeMixin");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (FABRIC && mixinClassName.contains(".mixin.compat.")
                && !ON_FABRIC.contains(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1))) return false;
        for (String prefix : ALWAYS_THERE) if (targetClassName.startsWith(prefix)) return true;
        return present.computeIfAbsent(targetClassName, Loader.INSTANCE::hasClass);
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
