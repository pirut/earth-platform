package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarthReplacementCaptureTest {
    private static final CubePos POS=new CubePos(-3,8,7);
    private static EarthReplacementCapture blank(){try(var b=new EarthReplacementCapture.Builder(POS)){for(int i=0;i<8000;i++)b.setNext(0);return b.finish();}}
    static long arrayBytes(Object... roots)throws Exception{
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());long total=0;var work=new ArrayDeque<Object>();for(Object root:roots)if(root!=null)work.add(root);
        while(!work.isEmpty()){Object value=work.remove();if(!seen.add(value))continue;if(value instanceof byte[] array){total+=array.length;continue;}
            for(Field field:value.getClass().getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())&&(field.getType()==byte[].class||field.getType()==EarthReplacementCapture.class)){
                field.setAccessible(true);Object child=field.get(value);if(child!=null)work.add(child);
            }
        }return total;
    }
    @Test void exactSixPaletteIdsColorsAndBoundsMatchPinnedEarthDefinition(){
        var capture=blank();assertEquals(8000,capture.cellCount());assertEquals(8000,capture.declaredBytes());assertEquals(POS.minBlockX()-2,capture.minX());assertEquals(POS.minBlockY()+18,capture.maxYExclusive());
        int[] colors={0,EarthAlpineDefinition.Material.STONE.argb(),0xffdedede,EarthAlpineDefinition.Material.ANDESITE.argb(),EarthAlpineDefinition.Material.TUFF.argb(),EarthAlpineDefinition.Material.SNOW_BLOCK.argb()};
        for(int material=0;material<6;material++){var changed=capture.withMaterial(POS.minBlockX()+4,133,POS.minBlockZ()+4,material);assertEquals(material,changed.materialAt(POS.minBlockX()+4,133,POS.minBlockZ()+4));assertEquals(colors[material],changed.argbAt(POS.minBlockX()+4,133,POS.minBlockZ()+4));}
        assertThrows(IndexOutOfBoundsException.class,()->capture.materialAt(capture.minX()-1,128,capture.minZ()));
        assertThrows(IndexOutOfBoundsException.class,()->capture.materialAt(capture.maxXExclusive(),128,capture.minZ()));
    }
    @Test void builderTransfersOneArrayAndRevokesEveryMutableRoute()throws Exception{
        var b=new EarthReplacementCapture.Builder(POS);assertEquals(8000,arrayBytes(b));for(int i=0;i<8000;i++)b.setNext(i%6);
        assertEquals(8000,b.count());var capture=b.finish();assertEquals(0,b.declaredBytes());assertEquals(8000,arrayBytes(b,capture));
        assertThrows(IllegalStateException.class,()->b.setNext(0));assertThrows(IllegalStateException.class,b::finish);b.close();assertEquals(8000,arrayBytes(capture));
        var partial=new EarthReplacementCapture.Builder(POS);partial.setNext(1);assertThrows(IllegalStateException.class,partial::finish);partial.close();assertEquals(0,arrayBytes(partial));assertThrows(IllegalStateException.class,partial::finish);
    }
    @Test void prospectiveOverlayAndSerialCompactionNeverAllocateFourthCopy()throws Exception{
        var old=blank();String oldId=old.contentIdentity();var candidate=old.withMaterial(POS.minBlockX()+4,133,POS.minBlockZ()+4,5);
        assertEquals(8000,arrayBytes(old,candidate));assertEquals(0,old.materialAt(POS.minBlockX()+4,133,POS.minBlockZ()+4));assertNotEquals(oldId,candidate.contentIdentity());
        var compact=candidate.compact();assertEquals(candidate.contentIdentity(),compact.contentIdentity());assertEquals(16000,arrayBytes(old,candidate,compact));
        var staging=new EarthReplacementCapture.Builder(POS);assertEquals(24000,arrayBytes(old,candidate,compact,staging));staging.close();
        assertSame(compact,compact.compact());assertThrows(IllegalStateException.class,()->candidate.withMaterial(POS.minBlockX()+5,133,POS.minBlockZ()+4,1));
        for(Field f:EarthReplacementCapture.class.getDeclaredFields())assertNotEquals(int[].class,f.getType());
    }
    @Test void externalConstructorIsDefensiveAndOldPaletteIdentityRemainsUnchanged(){
        byte[] bytes=new byte[8000];var earth=new EarthReplacementCapture(POS,bytes);var old=new NativeReplacementCapture(POS,bytes);bytes[0]=5;
        assertEquals(0,earth.materialAt(earth.minX(),earth.minY(),earth.minZ()));assertNotEquals(old.contentIdentity(),earth.contentIdentity());
        assertThrows(IllegalArgumentException.class,()->new EarthReplacementCapture(POS,new byte[7999]));
        byte[] foreign=new byte[8000];foreign[50]=6;assertThrows(IllegalArgumentException.class,()->new EarthReplacementCapture(POS,foreign));foreign[50]=5;assertThrows(IllegalArgumentException.class,()->new NativeReplacementCapture(POS,foreign));
        assertThrows(IllegalArgumentException.class,()->earth.withMaterial(POS.minBlockX()-1,130,POS.minBlockZ()+1,1));
    }
    @Test void exactOwned18FacesUseReadHaloAndSixMaterialOcclusion(){
        byte[] solid=new byte[8000];Arrays.fill(solid,(byte)5);var full=new EarthReplacementCapture(POS,solid);assertEquals(0,EarthReplacementAdmission.faces(full));
        var air=blank();assertEquals(0,EarthReplacementAdmission.faces(air));var one=air.withMaterial(POS.minBlockX()+4,133,POS.minBlockZ()+4,1);assertEquals(6,EarthReplacementAdmission.faces(one));
        var two=one.compact().withMaterial(POS.minBlockX()+5,133,POS.minBlockZ()+4,4);assertEquals(10,EarthReplacementAdmission.faces(two));
        assertEquals(8192,EarthReplacementAdmission.MAX_FACES);
    }
}
