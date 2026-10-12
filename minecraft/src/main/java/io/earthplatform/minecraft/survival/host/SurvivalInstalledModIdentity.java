package io.earthplatform.minecraft.survival.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Exact installed first-slice executable profile; unexpected gameplay mods refuse before entry. */
public final class SurvivalInstalledModIdentity {
    private static final Map<String,String> VERSIONS = Map.of(
            "minecraft","1.21.1", "neoforge","21.1.256", "earthplatform","0.1.0-engine-prototype",
            "distanthorizons","3.3.3", "iris","1.8.14-beta.1+mc1.21.1", "sodium","0.8.12-beta.1+mc1.21.1");
    private static final Map<String,String> DISTRIBUTIONS = Map.of(
            "distanthorizons","864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab",
            "iris","60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314",
            "sodium","dc2838d5538db39414171fe86586ab89d1a30d2fd97f59229cc44cf3777f569d");
    /**
     * Sodium 0.8.12 ships its NeoForge mod and four Fabric API shims as jar-in-jar entries. They are
     * part of the verified Sodium distribution, so they are admitted only with their exact versions.
     */
    private static final Map<String,String> SODIUM_EMBEDDED = Map.of(
            "fabric_api_base","0.4.42+d1308ded19", "fabric_rendering_data_attachment_v1","0.3.48+73761d2e19",
            "fabric_block_view_api_v2","1.0.10+9afaaf8c19", "fabric_renderer_api_v1","3.4.1+9125b6dc19");
    public record Capture(String digest, String earthArtifactSha256, Map<String,String> versions,
                          Map<String,String> rendererArtifactSha256) {
        public Capture { versions=Map.copyOf(versions);rendererArtifactSha256=Map.copyOf(rendererArtifactSha256); }
    }
    private SurvivalInstalledModIdentity() {}
    /** No development-directory fallback. Headless component tests do not grant installed admission. */
    public static Capture captureInstalled() throws IOException {
        var mods=ModList.get().getMods();
        var versions=new TreeMap<String,String>();Path earthFile=null;
        for(var mod:mods) {
            if(versions.put(mod.getModId(),mod.getVersion().toString())!=null)throw new IOException("Duplicate installed mod identity");
            if(mod.getModId().equals("earthplatform"))earthFile=mod.getOwningFile().getFile().getFilePath();
        }
        requireVersions(versions);
        // Nested mods report jar-in-jar paths, so renderer distributions are proven from the mods folder itself.
        var hashes=hashModsFolder(FMLPaths.MODSDIR.get());
        String earth=hashInstalledJar(earthFile);
        if(!hashes.containsValue(earth))throw new IOException("Earth platform JAR is not the installed mods-folder artifact");
        hashes.values().remove(earth);
        var distributions=new TreeMap<String,String>();
        for(var entry:DISTRIBUTIONS.entrySet()) {
            if(!hashes.containsValue(entry.getValue()))throw new IOException("Unverified installed renderer distribution: "+entry.getKey());
            distributions.put(entry.getKey(),entry.getValue());
        }
        if(hashes.size()!=DISTRIBUTIONS.size())throw new IOException("Installed mods folder holds an unexpected JAR");
        hashes=distributions;
        var digest=digest();digest.update("earth-survival-installed-profile-039-v1\n".getBytes(StandardCharsets.UTF_8));
        for(var entry:versions.entrySet())digest.update((entry.getKey()+"="+entry.getValue()+"\n").getBytes(StandardCharsets.UTF_8));
        digest.update(("earthplatform="+earth+"\n").getBytes(StandardCharsets.UTF_8));
        for(var entry:hashes.entrySet())digest.update((entry.getKey()+"="+entry.getValue()+"\n").getBytes(StandardCharsets.UTF_8));
        return new Capture(HexFormat.of().formatHex(digest.digest()),earth,versions,hashes);
    }
    static void requireVersions(Map<String,String> versions) throws IOException {
        var expected=new TreeMap<>(VERSIONS);expected.putAll(SODIUM_EMBEDDED);
        if(!expected.equals(versions))throw new IOException("Unsupported installed gameplay/mod version inventory: "+new TreeMap<>(versions));
    }
    /** Every regular JAR directly in the mods folder, keyed by file name. */
    static TreeMap<String,String> hashModsFolder(Path folder) throws IOException {
        var hashes=new TreeMap<String,String>();
        try(var entries=Files.list(folder)) {
            for(Path file:(Iterable<Path>)entries::iterator)
                if(file.getFileName().toString().endsWith(".jar"))hashes.put(file.getFileName().toString(),hashInstalledJar(file));
        }
        if(hashes.size()!=DISTRIBUTIONS.size()+1)throw new IOException("Installed mods folder must hold exactly Earth platform and its renderer JARs");
        return hashes;
    }
    static String hashInstalledJar(Path file) throws IOException {
        if(file==null||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.getFileName().toString().endsWith(".jar"))
            throw new IOException("Installed profile requires a plain distribution JAR");
        MessageDigest digest=digest();long total=0;byte[] buffer=new byte[8192];
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            for(int n;(n=in.read(buffer))!=-1;) {
                total=Math.addExact(total,n);if(total>128L*1024*1024)throw new IOException("Installed distribution exceeds bounded proof input");digest.update(buffer,0,n);
            }
        }
        if(total==0)throw new IOException("Empty installed distribution");
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
