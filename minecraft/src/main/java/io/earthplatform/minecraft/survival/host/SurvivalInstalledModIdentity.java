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

/** Exact installed first-slice executable profile; unexpected gameplay mods refuse before entry. */
public final class SurvivalInstalledModIdentity {
    private static final Map<String,String> VERSIONS = Map.of(
            "minecraft","1.21.1", "neoforge","21.1.256", "earthplatform","0.1.0-engine-prototype",
            "distanthorizons","3.3.3", "iris","1.8.14-beta.1+mc1.21.1", "sodium","0.8.12-beta.1+mc1.21.1");
    private static final Map<String,String> DISTRIBUTIONS = Map.of(
            "distanthorizons","864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab",
            "iris","60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314",
            "sodium","dc2838d5538db39414171fe86586ab89d1a30d2fd97f59229cc44cf3777f569d");
    public record Capture(String digest, String earthArtifactSha256, Map<String,String> versions,
                          Map<String,String> rendererArtifactSha256) {
        public Capture { versions=Map.copyOf(versions);rendererArtifactSha256=Map.copyOf(rendererArtifactSha256); }
    }
    private SurvivalInstalledModIdentity() {}
    /** No development-directory fallback. Headless component tests do not grant installed admission. */
    public static Capture captureInstalled() throws IOException {
        var mods=ModList.get().getMods();
        if(mods.size()!=VERSIONS.size())throw new IOException("Survival first slice requires the exact six-mod installed profile");
        var versions=new TreeMap<String,String>();var files=new TreeMap<String,Path>();
        for(var mod:mods) {
            if(versions.put(mod.getModId(),mod.getVersion().toString())!=null)throw new IOException("Duplicate installed mod identity");
            if(DISTRIBUTIONS.containsKey(mod.getModId())||mod.getModId().equals("earthplatform"))
                files.put(mod.getModId(),mod.getOwningFile().getFile().getFilePath());
        }
        requireVersions(versions);
        var hashes=new TreeMap<String,String>();
        for(var entry:files.entrySet())hashes.put(entry.getKey(),hashInstalledJar(entry.getValue()));
        for(var entry:DISTRIBUTIONS.entrySet())if(!entry.getValue().equals(hashes.get(entry.getKey())))
            throw new IOException("Unverified installed renderer distribution: "+entry.getKey());
        String earth=hashes.remove("earthplatform");
        var digest=digest();digest.update("earth-survival-installed-profile-039-v1\n".getBytes(StandardCharsets.UTF_8));
        for(var entry:versions.entrySet())digest.update((entry.getKey()+"="+entry.getValue()+"\n").getBytes(StandardCharsets.UTF_8));
        digest.update(("earthplatform="+earth+"\n").getBytes(StandardCharsets.UTF_8));
        for(var entry:hashes.entrySet())digest.update((entry.getKey()+"="+entry.getValue()+"\n").getBytes(StandardCharsets.UTF_8));
        return new Capture(HexFormat.of().formatHex(digest.digest()),earth,versions,hashes);
    }
    static void requireVersions(Map<String,String> versions) throws IOException {
        if(!VERSIONS.equals(versions))throw new IOException("Unsupported installed gameplay/mod version inventory");
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
