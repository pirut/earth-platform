package io.earthplatform.compat.dh;

import cpw.mods.niofs.union.UnionFileSystem;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.neoforged.jarjar.nio.pathfs.PathFileSystem;

/** Reads only authoritative, already mounted NeoForge origins. Never mounts, closes or searches. */
final class InstalledSodiumOrigin {
    static final String ENTRY = "META-INF/jarjar/net.caffeinemc.sodium-neoforge-0.8.12-beta.1+mc1.21.1-mod.jar";
    static final String NESTED_SHA256 = "912a12199fe120a99b94e225dc08886671d2052386c6e92beb0146c587eab1e4";
    record Member(String id,String version,String entry,long bytes,String sha256) {}
    static final Member SODIUM = new Member("sodium",InstalledIrisRuntime.SODIUM_VERSION,ENTRY,1_225_252L,NESTED_SHA256);
    static final Map<String,Member> MEMBERS = Map.of(
        "sodium",SODIUM,
        "fabric_api_base",new Member("fabric_api_base","0.4.42+d1308ded19","META-INF/jarjar/fabric-api-base-0.4.42+d1308ded19.jar",34_805L,"bdf464c6df587b4aa87df2cbe326acbbb307727d3e27dbaf58d592c429e6eac3"),
        "fabric_block_view_api_v2",new Member("fabric_block_view_api_v2","1.0.10+9afaaf8c19","META-INF/jarjar/fabric-block-view-api-v2-1.0.10+9afaaf8c19.jar",25_631L,"87da93c4ef283e0ce66f8da67d6cba93d9884c55152014a49c2b6f4f861bec48"),
        "fabric_renderer_api_v1",new Member("fabric_renderer_api_v1","3.4.1+9125b6dc19","META-INF/jarjar/fabric-renderer-api-v1-3.4.1+9125b6dc19.jar",58_938L,"cfa513ab50123b564421e093a657e15bd28b82b0219f196e518de679187e4845"),
        "fabric_rendering_data_attachment_v1",new Member("fabric_rendering_data_attachment_v1","0.3.48+73761d2e19","META-INF/jarjar/fabric-rendering-data-attachment-v1-0.3.48+73761d2e19.jar",20_497L,"a720c7a1e73583e0fed5110556290df023d158750740bc00e4040d6cd2cb9af1")
    );

    // Preserve the prior shader resolver contract. The new playable inventory
    // uses the stricter direct-jij method below, matching the actual pinned locator.
    static void verify(InstalledIrisRuntime.InstalledMod mod) throws IOException { verifiedOuter(mod,SODIUM,true); }

    /** Returns a verified physical outer; the mounted owners remain entirely upstream-owned. */
    static Path verifiedOuter(InstalledIrisRuntime.InstalledMod mod,Member member) throws IOException {
        return verifiedOuter(mod,member,false);
    }
    private static Path verifiedOuter(InstalledIrisRuntime.InstalledMod mod,Member member,boolean legacyWrapper) throws IOException {
        if(member==null||MEMBERS.get(mod.id())!=member||!member.version().equals(mod.version()))
            throw new IOException("Unsupported Sodium nested module identity/version: "+mod.id());
        Path root = mod.path().toAbsolutePath().normalize();
        if (root.getFileSystem() instanceof UnionFileSystem union) {
            if(!legacyWrapper)throw new IOException("Playable Sodium inventory requires the direct released jij root, not a union overlay");
            requireRoot(root, "Sodium nested SecureJar union");
            root = union.getPrimaryPath().toAbsolutePath().normalize();
        }
        if (!(root.getFileSystem() instanceof PathFileSystem nested))
            throw new IOException("Installed "+mod.id()+" is not the pinned service-discovered nested mod JAR");
        requireRoot(root, "Sodium nested mod");
        Path entry = nested.getTarget().toAbsolutePath().normalize();
        if (!(entry.getFileSystem() instanceof UnionFileSystem outer)
                || !("/" + member.entry()).equals(entry.toString()))
            throw new IOException("Installed "+mod.id()+" nested origin is not the exact released service-JAR member");
        Path physical = outer.getPrimaryPath();
        if (physical.getFileSystem() != FileSystems.getDefault())
            throw new IOException("Installed Sodium service JAR is not a separately installed physical artifact");
        InstalledIrisRuntime.verifyBytes(physical, 1_291_422L, InstalledIrisRuntime.SODIUM_SHA256, "Sodium service");
        InstalledIrisRuntime.verifyBytes(entry, member.bytes(), member.sha256(), "Sodium nested "+mod.id());
        return physical.toRealPath();
    }
    static void verifySameOuter(InstalledIrisRuntime.InstalledMod mod,Path exactSodiumOuter) throws IOException {
        if(exactSodiumOuter==null||exactSodiumOuter.getFileSystem()!=FileSystems.getDefault())
            throw new IOException("Missing authoritative physical Sodium outer");
        Path found=verifiedOuter(mod,MEMBERS.get(mod.id()));
        if(!Files.isSameFile(exactSodiumOuter,found))throw new IOException("Installed "+mod.id()+" comes from a different physical Sodium service JAR");
    }
    private static void requireRoot(Path path, String label) throws IOException {
        if (!path.equals(path.getRoot())) throw new IOException(label + " must be the mounted artifact root");
    }
}
