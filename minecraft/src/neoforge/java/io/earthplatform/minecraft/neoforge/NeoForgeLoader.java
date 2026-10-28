package io.earthplatform.minecraft.neoforge;

import io.earthplatform.minecraft.platform.Loader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

/** {@link Loader} on NeoForge. Reads the loading mod list, which is there before mods construct. */
public final class NeoForgeLoader implements Loader {
    @Override public String name() { return "neoforge"; }

    @Override public boolean isModLoaded(String modId) {
        ModList mods = ModList.get();
        if (mods != null) return mods.isLoaded(modId);
        LoadingModList loading = LoadingModList.get();
        return loading != null && loading.getModFileById(modId) != null;
    }

    @Override public boolean hasClass(String className) {
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

    @Override public Path gameDir() { return FMLPaths.GAMEDIR.get(); }

    @Override public boolean isClient() { return FMLEnvironment.dist.isClient(); }

    /** Mods naming a handler class under {@code frex:flawless_frames_handler}, whose static acceptController takes the switch. */
    @Override public List<FlawlessRequester> flawlessFrameRequesters() {
        List<FlawlessRequester> found = new ArrayList<>();
        for (var mod : ModList.get().getMods()) {
            if (!(mod.getModProperties().get("frex:flawless_frames_handler") instanceof String handler)) continue;
            found.add(new FlawlessRequester(mod.getModId(), provider -> {
                try {
                    MethodHandle accept = MethodHandles.publicLookup().findStatic(Class.forName(handler), "acceptController",
                            MethodType.methodType(void.class, Function.class));
                    accept.invoke(provider);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        return found;
    }
}
