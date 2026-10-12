package io.earthplatform.compat.dh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Reads the exact separately installed artifact. No class loading, transform execution or GL proof. */
class InstalledSurvivalConfigMutationCensusTest {
    @Test void everyPinnedValueWriteMatchesTheThreeWrappedRootsOrInitialization()throws Exception{
        String supplied=System.getProperty("earthplatform.installedDhTestJar");assertNotNull(supplied);
        Path path=Path.of(supplied);assertEquals("864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        String prefix="com/seibel/distanthorizons/core/config/";
        Set<String> writes=new TreeSet<>();int classes=0;
        try(var jar=new ZipFile(path.toFile())){
            for(var entry:jar.stream().filter(e->e.getName().startsWith("com/seibel/distanthorizons/")&&e.getName().endsWith(".class")).toList()){
                var node=new ClassNode();try(var input=jar.getInputStream(entry)){new ClassReader(input).accept(node,0);}classes++;
                for(var method:node.methods)for(var instruction:method.instructions)if(instruction instanceof FieldInsnNode field
                        &&(field.getOpcode()==Opcodes.PUTFIELD||field.getOpcode()==Opcodes.PUTSTATIC)
                        &&field.owner.startsWith(prefix)&&Set.of("value","apiValue","mcVersionOverrideValue","grassSideRendering","debugRenderingColors").contains(field.name))
                    writes.add(node.name.substring(prefix.length())+"::"+method.name+method.desc+" -> "+field.owner.substring(prefix.length())+"."+field.name);
            }
        }
        assertEquals(1145,classes);
        assertEquals(Set.of(
                "Config$Client$Advanced$Debugging::<clinit>()V -> Config$Client$Advanced$Debugging.debugRenderingColors",
                "Config$Client$Advanced$Graphics$Quality::<clinit>()V -> Config$Client$Advanced$Graphics$Quality.grassSideRendering",
                "types/AbstractConfigBase::<init>(Lcom/seibel/distanthorizons/core/config/types/enums/EConfigEntryAppearance;Ljava/lang/Object;)V -> types/AbstractConfigBase.value",
                "types/AbstractConfigBase::set(Ljava/lang/Object;)V -> types/AbstractConfigBase.value",
                "types/ConfigEntry::setApiValue(Ljava/lang/Object;Ljava/lang/String;)V -> types/ConfigEntry.apiValue",
                "types/ConfigEntry::setMcVersionOverrideValue(Ljava/lang/Object;)V -> types/ConfigEntry.mcVersionOverrideValue"),writes);
    }
}
