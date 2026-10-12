package io.earthplatform.minecraft.scene;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The config package itself is reserved by Mixin even when shouldApplyMixin is false. */
class NativeShaderMixinConfigTest {
    private static final String NATIVE="earthplatform.shaders.mixins.json";
    private static final String FAR="earthplatform.far-shaders.mixins.json";
    private static final String PLUGIN="io.earthplatform.minecraft.mixin.EarthShaderMixinPlugin";
    private static JsonObject config(String name)throws Exception{
        try(var input=NativeShaderMixinConfigTest.class.getClassLoader().getResourceAsStream(name)){
            assertNotNull(input,name);return JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static Set<String> entries(JsonObject config){
        var result=new HashSet<String>();String prefix=config.get("package").getAsString();
        for(var item:config.getAsJsonArray("client")){
            String simple=item.getAsString();assertFalse(simple.contains("."),"Only direct members of the reserved mixin package are allowed");
            assertTrue(result.add(prefix+"."+simple),"Duplicate mixin entry "+simple);
        }
        return result;
    }
    @Test void twoOptionalConfigsHaveExactNarrowPackagesAndTheSameExplicitOptInPlugin()throws Exception{
        JsonObject nativeConfig=config(NATIVE),farConfig=config(FAR);
        assertEquals("io.earthplatform.minecraft.mixin",nativeConfig.get("package").getAsString());
        assertEquals("io.earthplatform.compat.dh.shader.mixin",farConfig.get("package").getAsString());
        for(var value:List.of(nativeConfig,farConfig)){
            assertEquals(PLUGIN,value.get("plugin").getAsString());assertTrue(value.get("required").getAsBoolean());
            assertEquals(1,value.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        }
    }
    @Test void splitPreservesExactlySixteenNativeAndTenFarMixinsWithoutDuplication()throws Exception{
        Set<String> nativeEntries=entries(config(NATIVE)),farEntries=entries(config(FAR));
        assertEquals(Set.of("NativeIrisTerrainLayerMixin","NativeShaderReloadScopeMixin","NativeShaderTextureDeletionMixin","NativeShaderRenderSystemAccessor","NativeIrisMaterialLifecycleMixin","NativeIrisProgramSamplersAccessor",
                "NativeIrisProgramUniformsAccessor","NativeIrisPipelineStateAccessor","NativeIrisTextureTrackerAccessor",
                "NativeIrisBlendStateAccessor","NativeIrisExtendedShaderAccessor","NativeIrisSamplerBindingAccessor",
                "NativeIrisTextureStateAccessor","NativeIrisShaderInstanceAccessor","NativeIrisUniformAccessor","SceneWeightedBakedModelAccessor"),
                nativeEntries.stream().map(n->n.substring(n.lastIndexOf('.')+1)).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("DhIrisTerrainPassMixin","DhSurvivalTerrainRendererAccess","DhCubicNearPlaneMixin","IrisShaderLifecycleMixin","IrisPipelineLifecycleMixin",
                "IrisTargetResizeMixin","ShaderMainTargetResizeMixin","IrisDhTargetResizeMixin","SurvivalDhConfigBaseMixin","SurvivalDhConfigEntryMixin"),
                farEntries.stream().map(n->n.substring(n.lastIndexOf('.')+1)).collect(java.util.stream.Collectors.toSet()));
        var union=new HashSet<>(nativeEntries);assertTrue(java.util.Collections.disjoint(nativeEntries,farEntries));union.addAll(farEntries);assertEquals(26,union.size());
    }
    @Test void weightedModelAdmissionAccessorTargetsOnlyThePinnedGraphFields()throws Exception{
        var node=new org.objectweb.asm.tree.ClassNode();
        try(var input=getClass().getClassLoader().getResourceAsStream("io/earthplatform/minecraft/mixin/SceneWeightedBakedModelAccessor.class")){
            assertNotNull(input);new org.objectweb.asm.ClassReader(input).accept(node,0);
        }
        assertTrue((node.access&org.objectweb.asm.Opcodes.ACC_INTERFACE)!=0);
        var annotations=new ArrayList<org.objectweb.asm.tree.AnnotationNode>();
        if(node.visibleAnnotations!=null)annotations.addAll(node.visibleAnnotations);
        if(node.invisibleAnnotations!=null)annotations.addAll(node.invisibleAnnotations);
        var target=annotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).toList();
        assertEquals(1,target.size());assertEquals(List.of("value",List.of(org.objectweb.asm.Type.getObjectType("net/minecraft/client/resources/model/WeightedBakedModel"))),target.getFirst().values);
        assertEquals(2,node.methods.size());
        for(var method:node.methods){
            assertTrue((method.access&org.objectweb.asm.Opcodes.ACC_ABSTRACT)!=0);
            String field=switch(method.name){case "earthplatform$children" -> "list";case "earthplatform$totalWeight" -> "totalWeight";default -> throw new AssertionError(method.name);};
            assertEquals(field.equals("list")?"()Ljava/util/List;":"()I",method.desc);
            var entries=new ArrayList<org.objectweb.asm.tree.AnnotationNode>();
            if(method.visibleAnnotations!=null)entries.addAll(method.visibleAnnotations);
            if(method.invisibleAnnotations!=null)entries.addAll(method.invisibleAnnotations);
            var accessors=entries.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")).toList();
            assertEquals(1,accessors.size());assertEquals(List.of("value",field),accessors.getFirst().values);
            assertEquals(0,method.instructions.size(),"No allocation, mutation or model callback is added");
        }
    }
    @Test void noConfigReservesApplicationPackagesEvenWhenOptionalMixinsAreDisabled()throws Exception{
        var applicationClasses=List.of("io.earthplatform.minecraft.fixture.NativeSparseLevelAccess",
                "io.earthplatform.minecraft.RollingGateClient","io.earthplatform.minecraft.scene.NativeIrisState",
                "io.earthplatform.minecraft.scene.SceneMesh","io.earthplatform.compat.dh.shader.InstalledShaderCarrier",
                "io.earthplatform.compat.dh.shader.IrisShaderFarBackend","io.earthplatform.compat.dh.InstalledIrisRuntime");
        for(String name:List.of("earthplatform.mixins.json",NATIVE,FAR,"earthplatform.temp-files.mixins.json",
                "earthplatform.native-player-tick-diagnostic.mixins.json","earthplatform.native-session-bridge.mixins.json",
                "earthplatform.installed-callback-observation.mixins.json","earthplatform.native-local-observation.mixins.json")){
            String prefix=config(name).get("package").getAsString()+".";
            assertTrue(prefix.equals("io.earthplatform.minecraft.mixin.")||prefix.equals("io.earthplatform.compat.dh.shader.mixin."));
            for(String application:applicationClasses)assertFalse(application.startsWith(prefix),name+" reserves application class "+application);
        }
    }
    @Test void metadataTemplateRegistersAllSevenExactConfigsOnceAndKeepsSeparateOptionalGuards()throws Exception{
        String project=System.getProperty("earthplatform.test.projectDirectory");assertNotNull(project,"Explicit test project directory is required");
        String template=Files.readString(Path.of(project,"src/main/templates/META-INF/neoforge.mods.toml"));
        var matcher=Pattern.compile("(?m)^config\\s*=\\s*\"([^\"]+)\"\\s*$").matcher(template);
        var values=new ArrayList<String>();while(matcher.find())values.add(matcher.group(1).replace("${mod_id}","earthplatform"));
        assertEquals(9,values.size());assertEquals(Set.of("earthplatform.mixins.json",NATIVE,FAR,"earthplatform.temp-files.mixins.json",
                "earthplatform.native-player-tick-diagnostic.mixins.json","earthplatform.native-session-bridge.mixins.json",
                "earthplatform.installed-callback-observation.mixins.json","earthplatform.native-local-observation.mixins.json",
                "earthplatform.cubic.mixins.json"),new HashSet<>(values));
    }
}
