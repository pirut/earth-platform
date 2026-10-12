package io.earthplatform.compat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.neoforged.fml.loading.FMLPaths;
/** Optional restart-required QA switch, independent of playable terrain and never shipped enabled. */
public final class PlayableDiagnostics {
    private PlayableDiagnostics(){}
    private static final class Loaded {static final boolean ENABLED=read();}
    public static boolean enabled(){return Loaded.ENABLED;}
    private static boolean read(){
        try{var path=FMLPaths.CONFIGDIR.get().resolve("earthplatform-diagnostics.properties");if(!Files.exists(path))return false;
            try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(257);return bytes.length<=256&&new String(bytes,StandardCharsets.UTF_8).matches("[ \t\r\n]*enabled=true[ \t\r\n]*");}
        }catch(java.io.IOException|RuntimeException unavailable){return false;}
    }
}
