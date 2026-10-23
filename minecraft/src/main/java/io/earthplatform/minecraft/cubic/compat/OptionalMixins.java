package io.earthplatform.minecraft.cubic.compat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Leaves out the mixins into other mods when those mods are not installed. Mixin skips them anyway,
 * but only after logging an "Error loading class" warning for every missing target, dozens on
 * every start, which read like a fault in players' logs. Targets in Minecraft, Mojang's libraries
 * and NeoForge are always there and are not looked up. Must not touch Minecraft classes: it is
 * loaded before them.
 */
public final class OptionalMixins implements IMixinConfigPlugin {
    private static final List<String> ALWAYS_THERE = List.of("net.minecraft.", "com.mojang.", "net.neoforged.");
    private final Map<String, Boolean> present = new ConcurrentHashMap<>();

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        for (String prefix : ALWAYS_THERE) if (targetClassName.startsWith(prefix)) return true;
        return present.computeIfAbsent(targetClassName, OptionalMixins::installed);
    }

    /** Whether a class is in an installed mod's jar or, failing that, on the class path. */
    private static boolean installed(String className) {
        String path = className.replace('.', '/') + ".class";
        try {
            for (ModFileInfo file : FMLLoader.getLoadingModList().getModFiles()) {
                Path found = file.getFile().findResource(path);
                if (found != null && Files.exists(found)) return true;
            }
        } catch (RuntimeException failure) {
            // No mod list to ask: leave the mixin to Mixin, which skips it if the class is missing.
            return true;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null && loader.getResource(path) != null;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
