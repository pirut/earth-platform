package io.earthplatform.compat.dh;

import java.util.Optional;
import net.neoforged.fml.ModList;

/** Reads the loaded NeoForge catalogue; no mods-directory scan, installation, or downloader. */
public final class NeoForgeDhCatalogue implements InstalledDhResolver.Catalogue {
    private final InstalledIrisRuntime shaderRuntime;
    public NeoForgeDhCatalogue(){shaderRuntime=null;}
    /** Shader admission requires a separately validated installed runtime, never a boolean bypass. */
    public NeoForgeDhCatalogue(InstalledIrisRuntime shaderRuntime){this.shaderRuntime=java.util.Objects.requireNonNull(shaderRuntime);if(!shaderRuntime.installationVerified())throw new IllegalArgumentException("Unverified shader runtime");}
    @Override public Optional<InstalledDhResolver.InstalledMod> installedDh(){
        var mods=ModList.get();if(mods==null||!mods.isLoaded(InstalledDhResolver.MOD_ID))return Optional.empty();
        if(mods.isLoaded("oculus")||(mods.isLoaded("iris")&&(shaderRuntime==null||!shaderRuntime.installationVerified())))throw new IllegalStateException("Installed shader mode requires the exact supported Iris/Sodium/Photon runtime; settings were not changed");
        if(shaderRuntime!=null&&!mods.isLoaded("iris"))throw new IllegalStateException("Validated Iris installation disappeared");
        var container=mods.getModContainerById(InstalledDhResolver.MOD_ID).orElseThrow();
        var file=mods.getModFileById(InstalledDhResolver.MOD_ID);
        if(file==null)throw new IllegalStateException("Loaded DH has no authoritative mod-file entry");
        return Optional.of(new InstalledDhResolver.InstalledMod(container.getModInfo().getVersion().toString(),
                file.getFile().getFilePath(),NeoForgeDhCatalogue.class.getClassLoader(),file.getFile().getSecureJar().moduleDataProvider().name(),file.getFile().getSecureJar().moduleDataProvider().uri()));
    }
}
