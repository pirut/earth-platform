package io.earthplatform.minecraft;

import com.mojang.serialization.JsonOps;
import io.earthplatform.minecraft.fixture.SparseFixtureDimensions;
import io.earthplatform.minecraft.residency.SparseCubeStorage;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import io.earthplatform.minecraft.transform.SparseDimensionTransformer;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.dimension.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.jupiter.api.Assertions.*;

class SparseDimensionTest {
    private static DimensionType template;
    private String oldFixture,oldFacade;
    @BeforeAll static void prepare() { template=VanillaRegistries.createLookup().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value(); }
    @BeforeEach void enable() {
        oldFixture=System.getProperty(SparseFixtureDimensions.OPT_IN);oldFacade=System.getProperty(SparseCubeStorage.OPT_IN);
        System.setProperty(SparseFixtureDimensions.OPT_IN,"true");System.setProperty(SparseCubeStorage.OPT_IN,"true");
    }
    @AfterEach void restore() { restore(SparseFixtureDimensions.OPT_IN,oldFixture);restore(SparseCubeStorage.OPT_IN,oldFacade); }
    private static void restore(String key,String value) { if(value==null)System.clearProperty(key);else System.setProperty(key,value); }
    private static DimensionType directWide() {
        return new DimensionType(OptionalLong.of(6000),false,false,false,false,1,false,false,-11008,19872,19872,
                template.infiniburn(),template.effectsLocation(),1,template.monsterSettings());
    }
    @Test void actualDimensionObjectHasTrueFullBoundsOnlyThroughOptInFactory() {
        assertThrows(IllegalStateException.class,SparseDimensionTest::directWide);
        var wide=SparseFixtureDimensions.create(template);
        assertEquals(-11008,wide.minY());assertEquals(19872,wide.height());assertEquals(8864,wide.minY()+wide.height());
        assertEquals(19872,wide.logicalHeight());assertEquals(1.0,wide.coordinateScale());assertFalse(wide.hasSkyLight());
        assertThrows(IllegalStateException.class,SparseDimensionTest::directWide,"Factory capability must be cleared after construction");
        assertEquals(2031,DimensionType.MAX_Y);assertEquals(-2032,DimensionType.MIN_Y);
    }
    @Test void existingDimensionCodecStillRejectsWideValuesEvenWithBothFlags() {
        var encoded=DimensionType.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,template).getOrThrow().getAsJsonObject();
        assertTrue(DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE,encoded).result().isPresent());
        encoded.addProperty("min_y",-11008);encoded.addProperty("height",19872);encoded.addProperty("logical_height",19872);
        assertTrue(DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE,encoded).error().isPresent());
        SparseFixtureDimensions.create(template);
        assertTrue(DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE,encoded).error().isPresent());
    }
    @Test void bothOptInsAreRequiredAndUnrelatedThreadsKeepVanillaBounds() throws Exception {
        System.clearProperty(SparseFixtureDimensions.OPT_IN);assertThrows(SparseFacadeException.class,()->SparseFixtureDimensions.create(template));
        System.setProperty(SparseFixtureDimensions.OPT_IN,"true");System.clearProperty(SparseCubeStorage.OPT_IN);assertThrows(SparseFacadeException.class,()->SparseFixtureDimensions.create(template));
        System.setProperty(SparseCubeStorage.OPT_IN,"true");SparseFixtureDimensions.create(template);
        AtomicInteger bound=new AtomicInteger();Thread thread=new Thread(()->bound.set(SparseFixtureDimensions.maximumY(-11008,19872,19872)));thread.start();thread.join();assertEquals(2031,bound.get());
    }
    private static ClassNode original() throws Exception {
        var node=new ClassNode();try(var zip=new ZipFile(System.getProperty("earthplatform.test.minecraftJar"));var in=zip.getInputStream(zip.getEntry("net/minecraft/world/level/dimension/DimensionType.class"))) { new ClassReader(in).accept(node,0); }return node;
    }
    @Test void exactConstructorPatchIsStackVerifiedAndDoesNotModifyCodecMethods() throws Exception {
        var node=original();SparseDimensionTransformer.apply(node);int hooks=0;
        for(var method:node.methods) {
            if(method.name.equals("<init>"))new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
            for(var insn:method.instructions)if(insn instanceof MethodInsnNode call && call.owner.equals("io/earthplatform/minecraft/fixture/SparseFixtureDimensions")) {
                hooks++;assertEquals("<init>",method.name);assertEquals("(III)I",call.desc);assertEquals(Opcodes.INVOKESTATIC,call.getOpcode());
            }
        }
        assertEquals(1,hooks);assertThrows(IllegalStateException.class,()->SparseDimensionTransformer.apply(node));
        var bad=original();for(var method:bad.methods)if(method.name.equals("<init>"))for(var insn:method.instructions)if(insn instanceof FieldInsnNode field && field.name.equals("MAX_Y"))field.name="unexpectedBound";
        assertThrows(IllegalStateException.class,()->SparseDimensionTransformer.apply(bad));
    }
}
