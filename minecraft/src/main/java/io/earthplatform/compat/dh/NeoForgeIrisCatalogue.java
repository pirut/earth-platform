package io.earthplatform.compat.dh;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** Only the running NeoForge catalogue grants an origin. Never scans or loads the mods directory. */
final class NeoForgeIrisCatalogue implements InstalledIrisRuntime.Catalogue {
    @Override public Optional<InstalledIrisRuntime.InstalledMod> installed(String id) {
        var mods = ModList.get();
        if (mods == null || !mods.isLoaded(id)) return Optional.empty();
        var container = mods.getModContainerById(id).orElseThrow(() -> InstalledIrisRuntime.unsupported("Loaded " + id + " has no container"));
        var file = mods.getModFileById(id);
        if (file == null) throw InstalledIrisRuntime.unsupported("Loaded " + id + " has no authoritative mod-file entry");
        var module = file.getFile().getSecureJar().moduleDataProvider();
        return Optional.of(new InstalledIrisRuntime.InstalledMod(id, container.getModInfo().getVersion().toString(),
                file.getFile().getFilePath(), NeoForgeIrisCatalogue.class.getClassLoader(), module.name(), module.uri()));
    }
    @Override public int effectiveRenderDistance() { return Minecraft.getInstance().options.getEffectiveRenderDistance(); }
    @Override public int selectedRenderDistance() { return Minecraft.getInstance().options.renderDistance().get(); }
    @Override public boolean singleplayer() { return Minecraft.getInstance().hasSingleplayerServer(); }
    @Override public int serverRenderDistanceLimit() {
        try { return ServerDistanceField.VALUE.getInt(Minecraft.getInstance().options); }
        catch (IllegalAccessException failure) {
            throw InstalledIrisRuntime.unsupported("Cannot read the actual Options server render distance limit", failure);
        }
    }
    /** Pinned Options has no getter. Read its exact field without changing or inferring its value. */
    private static final class ServerDistanceField {
        static final Field VALUE = resolve();
        private static Field resolve() {
            try {
                Field field = net.minecraft.client.Options.class.getDeclaredField("serverRenderDistance");
                if (field.getType() != int.class || !field.trySetAccessible())
                    throw new IllegalAccessException("Pinned Options.serverRenderDistance is not a readable int");
                return field;
            } catch (ReflectiveOperationException failure) {
                throw InstalledIrisRuntime.unsupported("Cannot read the pinned Options server render distance field", failure);
            }
        }
    }
    @Override public void requireRenderThread() {
        if (!RenderSystem.isOnRenderThread()) throw InstalledIrisRuntime.unsupported("Installed shader leases may only be read on the render thread");
    }
    static void verifyArtifact(InstalledIrisRuntime.InstalledMod mod) throws Exception {
        if ("iris".equals(mod.id())) {
            InstalledIrisRuntime.verifyBytes(mod.path(), 2_540_723L, InstalledIrisRuntime.IRIS_SHA256, "Iris");
        } else if ("sodium".equals(mod.id())) {
            InstalledSodiumOrigin.verify(mod);
        } else throw new IOException("Unexpected artifact requested for shader attestation: " + mod.id());
    }
}
