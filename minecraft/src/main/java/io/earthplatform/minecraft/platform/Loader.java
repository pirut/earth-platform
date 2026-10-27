package io.earthplatform.minecraft.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What the game code asks of the mod loader, NeoForge or Fabric, before and outside the game: which
 * mods are installed and where the game folder is. Safe from mixin plugins, which run before any
 * Minecraft class may load, so nothing here names one. The game-side calls are in {@link Platform}.
 */
public interface Loader {
    Loader INSTANCE = load(Loader.class);

    /** "neoforge" or "fabric". */
    String name();

    boolean isModLoaded(String modId);

    /** Whether a class file is in an installed mod's jar or on the class path, without loading it. */
    boolean hasClass(String className);

    /** The game folder: config, saves and the Earth data cache live under it. */
    Path gameDir();

    /** Whether this is the game client (true in singleplayer too), not a dedicated server. */
    boolean isClient();

    /** A mod that asks renderers for flawless frames, and where to hand it a renderer's switch. */
    record FlawlessRequester(String modId, Consumer<Function<String, Consumer<Boolean>>> accept) {}

    /** The installed mods that ask for flawless frames (FREX's convention, which each loader declares its own way). */
    List<FlawlessRequester> flawlessFrameRequesters();

    /** The one implementation the loader's half of the mod ships, in META-INF/services. */
    static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, type.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + type.getSimpleName() + " for this mod loader"));
    }
}
