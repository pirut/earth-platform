package io.earthplatform.minecraft.fabric;

import io.earthplatform.minecraft.platform.Loader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** {@link Loader} on Fabric. */
public final class FabricModLoader implements Loader {
    @Override public String name() { return "fabric"; }

    @Override public boolean isModLoaded(String modId) { return FabricLoader.getInstance().isModLoaded(modId); }

    @Override public boolean hasClass(String className) {
        String path = className.replace('.', '/') + ".class";
        for (ModContainer mod : FabricLoader.getInstance().getAllMods())
            if (mod.findPath(path).isPresent()) return true;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null && loader.getResource(path) != null;
    }

    @Override public Path gameDir() { return FabricLoader.getInstance().getGameDir(); }

    @Override public boolean isClient() { return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT; }

    /** Mods declaring a {@code frex_flawless_frames} entrypoint, which takes the switch. */
    @Override @SuppressWarnings({"unchecked", "rawtypes"})
    public List<FlawlessRequester> flawlessFrameRequesters() {
        List<FlawlessRequester> found = new ArrayList<>();
        for (var entry : FabricLoader.getInstance().getEntrypointContainers("frex_flawless_frames", Consumer.class)) {
            Consumer<Function<String, Consumer<Boolean>>> accept = entry.getEntrypoint();
            found.add(new FlawlessRequester(entry.getProvider().getMetadata().getId(), accept));
        }
        return found;
    }
}
