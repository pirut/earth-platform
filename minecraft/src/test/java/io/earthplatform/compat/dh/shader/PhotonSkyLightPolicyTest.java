package io.earthplatform.compat.dh.shader;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Read-only proof from the exact unchanged external ZIP, not generated shader code or a GL claim. */
class PhotonSkyLightPolicyTest {
    private static Path pack()throws Exception{
        String input=System.getProperty("earthplatform.installedShaderTestPack");assertNotNull(input,"Exact external Photon test input is required");
        Path path=Path.of(input);assertEquals(InstalledIrisRuntime.PHOTON_SHA256,hash(Files.readAllBytes(path)));return path;
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String text(ZipFile zip,String entry)throws Exception{var e=zip.getEntry(entry);assertNotNull(e,entry);try(var in=zip.getInputStream(e)){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
    @Test void existingUserOptionDisablesOnlyItsComputeStageAndLeavesNativeShadowsIndependent()throws Exception{
        Path path=pack();String before=hash(Files.readAllBytes(path));
        try(var zip=new ZipFile(path.toFile())){
            String properties=text(zip,"shaders/shaders.properties"),settings=text(zip,"shaders/settings.glsl");
            assertTrue(Pattern.compile("(?m)^screen\\.lighting\\s*=.*\\bSH_SKYLIGHT\\b.*$").matcher(properties).find());
            assertTrue(Pattern.compile("(?m)^program\\.world0/deferred4_a\\.enabled\\s*=\\s*SH_SKYLIGHT\\s*$").matcher(properties).find());
            assertTrue(Pattern.compile("(?m)^program\\.world0/shadow\\.enabled\\s*=\\s*SHADOW\\s*$").matcher(properties).find());
            assertTrue(Pattern.compile("(?m)^program\\.world1/shadow\\.enabled\\s*=\\s*SHADOW\\s*$").matcher(properties).find());
            assertTrue(Pattern.compile("(?m)^dhShadow\\.enabled\\s*=\\s*false\\s*$").matcher(properties).find());
            assertTrue(Pattern.compile("(?m)^\\s*#define\\s+SH_SKYLIGHT\\s*$").matcher(settings).find(),"The selected override changes an existing declared option only");
            assertTrue(text(zip,"shaders/world0/deferred4_a.csh").contains("/program/d4a_generate_sky_sh.csh"));
        }
        assertEquals(before,hash(Files.readAllBytes(path)));
    }
    @Test void allShaderEntriesHaveOnlyTheObservedGatedSharedStorageDeclaration()throws Exception{
        var declarations=new ArrayList<String>();int entries=0;
        try(var zip=new ZipFile(pack().toFile())){
            var names=zip.stream().filter(e->!e.isDirectory()&&(e.getName().endsWith(".glsl")||e.getName().endsWith(".csh"))).toList();
            assertTrue(names.size()>100);
            for(var entry:names){entries++;String source=text(zip,entry.getName());
                var matcher=Pattern.compile("(?m)^\\s*shared\\s+[^;]+;").matcher(source);
                while(matcher.find()){
                    declarations.add(entry.getName());assertEquals("shared vec3 shared_memory[256][9];",matcher.group().strip());
                }
            }
            assertEquals(List.of("shaders/program/d4a_generate_sky_sh.csh"),declarations);
        }
        assertTrue(entries>100);
    }
}
