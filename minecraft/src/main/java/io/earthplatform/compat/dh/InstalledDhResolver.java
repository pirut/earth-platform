package io.earthplatform.compat.dh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Resolves only an already installed mod. Never loads a supplied path or creates a classloader. */
public final class InstalledDhResolver {
    public static final String MOD_ID="distanthorizons",VERSION="3.3.3",API="7.2.0";
    public static final String SHA256="864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab";
    public static final long ARTIFACT_BYTES=28_289_212;
    public enum Status { ABSENT, UNSUPPORTED, WAITING_FOR_INIT, READY }
    public record InstalledMod(String version,Path path,ClassLoader gameLoader,String moduleName,java.net.URI moduleLocation) {
        /** Plain classpath form is for developer tests, not the NeoForge catalogue. */
        public InstalledMod(String version,Path path,ClassLoader loader){this(version,path,loader,"",path.toAbsolutePath().normalize().toUri());}
        public InstalledMod { Objects.requireNonNull(version);Objects.requireNonNull(path);Objects.requireNonNull(gameLoader);Objects.requireNonNull(moduleName);Objects.requireNonNull(moduleLocation);path=path.toAbsolutePath().normalize(); }
    }
    @FunctionalInterface public interface Catalogue { Optional<InstalledMod> installedDh(); }
    public record Capabilities(Class<?> apiClass,String version,String apiVersion,boolean initialized,
                               String renderingApi,String renderingEngine,String rendererMode,boolean enabled) {
        public Capabilities { Objects.requireNonNull(apiClass);Objects.requireNonNull(version);Objects.requireNonNull(apiVersion); }
    }
    @FunctionalInterface interface Probe { Capabilities inspect(ClassLoader installedLoader)throws ReflectiveOperationException; }
    @FunctionalInterface interface OriginCheck {void check(InstalledMod mod,Class<?> api)throws IOException;}
    @FunctionalInterface interface ArtifactCheck { void check(Path installedArtifact)throws IOException; }
    /** Borrowed process runtime. It has no close method and owns no upstream pool/classloader. */
    public record Runtime(InstalledMod installation,Class<?> apiClass) {
        public Runtime {Objects.requireNonNull(installation);Objects.requireNonNull(apiClass);}
        public ClassLoader loader(){return installation.gameLoader();}
    }
    public record Result(Status status,String explanation,Runtime runtime) {
        public Result {if((status==Status.READY)!=(runtime!=null))throw new IllegalArgumentException("Exact readiness runtime");}
    }
    private final Catalogue catalogue;private final Probe probe;private final ArtifactCheck artifactCheck;private final OriginCheck originCheck;private final boolean verifyBeforeInitialization;
    private InstalledMod verified;private Class<?> resolvedApi;private Result permanentFailure;
    public InstalledDhResolver(Catalogue catalogue){this(catalogue,InstalledDhApiProbe::inspect,InstalledDhResolver::verifyArtifact,InstalledDhResolver::verifyOrigin,true);}
    InstalledDhResolver(Catalogue catalogue,Probe probe,ArtifactCheck artifactCheck){this(catalogue,probe,artifactCheck,(m,a)->{},false);}
    InstalledDhResolver(Catalogue catalogue,Probe probe,ArtifactCheck artifactCheck,OriginCheck originCheck){this(catalogue,probe,artifactCheck,originCheck,false);}
    private InstalledDhResolver(Catalogue catalogue,Probe probe,ArtifactCheck artifactCheck,OriginCheck originCheck,boolean precheck){this.catalogue=Objects.requireNonNull(catalogue);this.probe=Objects.requireNonNull(probe);this.artifactCheck=Objects.requireNonNull(artifactCheck);this.originCheck=Objects.requireNonNull(originCheck);this.verifyBeforeInitialization=precheck;}
    public synchronized Result resolve(){
        if(permanentFailure!=null)return permanentFailure;
        try{
            var installed=catalogue.installedDh();
            if(installed.isEmpty())return new Result(Status.ABSENT,"Install Distant Horizons 3.3.3 for Minecraft 1.21.1 / NeoForge separately. The cubic DH adapter is unavailable.",null);
            var mod=installed.get();
            if(!VERSION.equals(mod.version()))return reject("Unsupported installed Distant Horizons version "+mod.version()+"; this adapter supports only 3.3.3 / API 7.2.0.");
            if(verified==null){artifactCheck.check(mod.path());verified=mod;}
            else if(!verified.equals(mod))return reject("Installed DH artifact or game classloader changed during this process; restart required.");
            if(resolvedApi==null&&verifyBeforeInitialization){
                Class<?> api=Class.forName("com.seibel.distanthorizons.api.DhApi",false,mod.gameLoader());
                originCheck.check(mod,api);resolvedApi=api;
            }
            var c=probe.inspect(mod.gameLoader());
            if(!VERSION.equals(c.version())||!API.equals(c.apiVersion()))return reject("Installed DH API/mod version does not match the supported pinned runtime.");
            if(resolvedApi==null){originCheck.check(mod,c.apiClass());resolvedApi=c.apiClass();}else if(resolvedApi!=c.apiClass())return reject("Installed DH class identity changed; no fallback classloader is permitted.");
            if(!c.initialized())return new Result(Status.WAITING_FOR_INIT,"Waiting for the installed DH initialization event.",null);
            if(!"OPEN_GL".equals(c.renderingApi())||!"OPEN_GL".equals(c.renderingEngine())||!"DEFAULT".equals(c.rendererMode())||!c.enabled())
                return new Result(Status.UNSUPPORTED,"The cubic DH adapter currently requires enabled DH, the OpenGL engine and DEFAULT rendering mode. Existing DH settings were not changed.",null);
            return new Result(Status.READY,"Pinned installed DH runtime resolved; cubic rendering still requires a scoped carrier lease.",new Runtime(mod,c.apiClass()));
        }catch(IOException|ReflectiveOperationException|RuntimeException|LinkageError failure){return reject("Installed DH adapter validation failed: "+failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));}
    }
    private Result reject(String reason){permanentFailure=new Result(Status.UNSUPPORTED,reason.substring(0,Math.min(reason.length(),768)),null);return permanentFailure;}
    static void verifyOrigin(InstalledMod mod,Class<?> api)throws IOException{
        if(!api.getName().equals("com.seibel.distanthorizons.api.DhApi"))throw new IOException("Foreign installed API class");
        verifyInstalledClassOrigin(mod,api);
    }
    static void verifyInstalledClassOrigin(InstalledMod mod,Class<?> api)throws IOException{
        var module=api.getModule();
        if(!mod.moduleName().isEmpty()){
            if(!module.isNamed()||!mod.moduleName().equals(module.getName())||module.getLayer()==null)throw new IOException("DH API is not in the installed SecureJar module");
            var resolved=module.getLayer().configuration().findModule(module.getName()).orElseThrow(()->new IOException("Missing installed module reference"));
            if(!resolved.reference().location().orElseThrow(()->new IOException("Missing installed module location")).equals(mod.moduleLocation()))
                throw new IOException("DH API module origin differs from the installed SecureJar");
        }else{
            try{
                var source=api.getProtectionDomain().getCodeSource();
                if(source==null||source.getLocation()==null||!Path.of(source.getLocation().toURI()).toRealPath().equals(mod.path().toRealPath()))throw new IOException("DH API CodeSource differs from the supplied test artifact");
            }catch(java.net.URISyntaxException|IllegalArgumentException invalid){throw new IOException("Unverifiable DH class origin",invalid);}
        }
    }
    static void verifyArtifact(Path path)throws IOException{
        if(!Files.isRegularFile(path)||Files.size(path)!=ARTIFACT_BYTES)throw new IOException("Expected the unmodified supported official DH artifact");
        try(var in=Files.newInputStream(path)){
            var hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int count;
            while((count=in.read(buffer))!=-1)hash.update(buffer,0,count);
            if(!SHA256.equals(HexFormat.of().formatHex(hash.digest())))throw new IOException("Unsupported DH artifact hash");
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
