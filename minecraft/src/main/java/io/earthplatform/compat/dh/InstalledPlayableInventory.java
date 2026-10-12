package io.earthplatform.compat.dh;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.neoforged.fml.ModList;

/** Exact released playable stack, including Sodium's four official service-discovered dependencies. */
public final class InstalledPlayableInventory {
    static final Map<String,String> VERSIONS=Map.of(
        "minecraft","1.21.1","neoforge","21.1.256","earthplatform","0.1.0-engine-prototype",
        "distanthorizons",InstalledDhResolver.VERSION,"iris",InstalledIrisRuntime.IRIS_VERSION,"sodium",InstalledIrisRuntime.SODIUM_VERSION,
        "fabric_api_base","0.4.42+d1308ded19","fabric_block_view_api_v2","1.0.10+9afaaf8c19",
        "fabric_renderer_api_v1","3.4.1+9125b6dc19","fabric_rendering_data_attachment_v1","0.3.48+73761d2e19");
    @FunctionalInterface interface OriginCheck {void check(Map<String,InstalledIrisRuntime.InstalledMod> mods)throws IOException;}
    private InstalledPlayableInventory(){}

    /** Admission only. Does not initialize a mod class, change settings, load a level or allocate a renderer. */
    public static void require(){
        try{
            var live=ModList.get();if(live==null)throw new IOException("NeoForge's loaded mod inventory is unavailable");
            var catalogue=new NeoForgeIrisCatalogue();var entries=new ArrayList<InstalledIrisRuntime.InstalledMod>();
            for(var entry:live.getMods())entries.add(catalogue.installed(entry.getModId())
                .orElseThrow(()->new IllegalStateException("Loaded module disappeared: "+entry.getModId())));
            verify(entries,InstalledPlayableInventory::verifyOrigins);
        }catch(IOException failure){throw new IllegalStateException("Finite arena requires the exact supported installed module inventory: "+failure.getMessage(),failure);}
    }
    static void verify(List<InstalledIrisRuntime.InstalledMod> entries,OriginCheck origins)throws IOException{
        Objects.requireNonNull(entries);Objects.requireNonNull(origins);
        if(entries.size()!=VERSIONS.size())throw new IOException("Expected exactly ten released modules, including Sodium's four nested dependencies; found "+entries.size());
        var actual=new LinkedHashMap<String,InstalledIrisRuntime.InstalledMod>();
        for(var mod:entries){
            if(mod==null||actual.putIfAbsent(mod.id(),mod)!=null)throw new IOException("Null or duplicate loaded module entry");
            String version=VERSIONS.get(mod.id());
            if(version==null)throw new IOException("Unsupported extra loaded module: "+mod.id());
            if(!version.equals(mod.version()))throw new IOException("Unsupported "+mod.id()+" version "+mod.version()+"; require "+version);
        }
        if(!actual.keySet().equals(VERSIONS.keySet()))throw new IOException("Missing required released module");
        origins.check(Map.copyOf(actual));
    }
    static void verifyOrigins(Map<String,InstalledIrisRuntime.InstalledMod> mods)throws IOException{
        InstalledDhResolver.verifyArtifact(mods.get("distanthorizons").path());
        var iris=mods.get("iris");InstalledIrisRuntime.verifyBytes(iris.path(),2_540_723L,InstalledIrisRuntime.IRIS_SHA256,"Iris");
        var outer=InstalledSodiumOrigin.verifiedOuter(mods.get("sodium"),InstalledSodiumOrigin.SODIUM);
        for(var id:InstalledSodiumOrigin.MEMBERS.keySet())if(!id.equals("sodium"))InstalledSodiumOrigin.verifySameOuter(mods.get(id),outer);
    }
}
