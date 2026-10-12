package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaCaptureTest {
    static ArenaCapture filled(int material){var d=ArenaDefinition.FIXED;var b=ArenaCapture.builder(d);for(long y=d.readMinY();y<d.readMaxYExclusive();y++)for(long z=d.readMinZ();z<d.readMaxZExclusive();z++)for(long x=d.readMinX();x<d.readMaxXExclusive();x++)b.set(x,y,z,material);return b.seal();}
    @Test void fixedEightAndSixtyFourCanonicalGeometry(){var d=ArenaDefinition.FIXED;assertEquals(8,d.positions().size());assertEquals(64,d.dependencies().size());assertEquals(d.positions().stream().sorted().toList(),d.positions());assertEquals(d.dependencies().stream().sorted().toList(),d.dependencies());for(var p:d.positions())assertTrue(d.insideWindow(p));assertFalse(d.insideWindow(new CubePos(-1,8,0)));assertTrue(d.editable(31,159,31));assertFalse(d.editable(32,159,31));assertEquals(36,d.readMaxXExclusive()-d.readMinX());assertTrue(d.permitsCenter(new CubePos(-16,6,15)));assertTrue(d.permitsCenter(new CubePos(15,15,-16)));assertFalse(d.permitsCenter(new CubePos(16,8,0)));assertFalse(d.permitsCenter(new CubePos(0,16,0)));}
    @Test void exactPackingRoundTripAndImmutableOwnershipTransfer()throws Exception{
        var d=ArenaDefinition.FIXED;var b=ArenaCapture.builder(d);int count=0;
        for(long y=d.readMinY();y<d.readMaxYExclusive();y++)for(long z=d.readMinZ();z<d.readMaxZExclusive();z++)for(long x=d.readMinX();x<d.readMaxXExclusive();x++)b.set(x,y,z,count++%3);
        var c=b.seal();assertThrows(IllegalStateException.class,()->b.set(0,128,0,1));assertThrows(IllegalStateException.class,b::seal);
        assertEquals(46656,c.cellCount());assertEquals(11664,c.declaredBytes());assertTrue(2*c.declaredBytes()<24000);
        var bytes=new ByteArrayOutputStream();c.write(new DataOutputStream(bytes));assertEquals(11664,bytes.size());assertEquals(0x24,bytes.toByteArray()[0]&255);
        var copy=ArenaCapture.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),d);assertEquals(c.contentIdentity(),copy.contentIdentity());count=0;
        for(long y=d.readMinY();y<d.readMaxYExclusive();y++)for(long z=d.readMinZ();z<d.readMaxZExclusive();z++)for(long x=d.readMinX();x<d.readMaxXExclusive();x++)assertEquals(count++%3,copy.materialAt(x,y,z));
    }
    @Test void invalidMissingCellsTruncationAndThirdMaterialRefuse()throws Exception{
        assertThrows(IllegalArgumentException.class,()->ArenaCapture.builder(ArenaDefinition.FIXED).seal());
        assertThrows(IllegalArgumentException.class,()->ArenaCapture.builder(ArenaDefinition.FIXED).set(0,128,0,3));
        byte[] invalid=new byte[ArenaCapture.PACKED_BYTES];invalid[100]=(byte)0xff;
        assertThrows(IOException.class,()->ArenaCapture.read(new DataInputStream(new ByteArrayInputStream(invalid)),ArenaDefinition.FIXED));
        assertThrows(EOFException.class,()->ArenaCapture.read(new DataInputStream(new ByteArrayInputStream(new byte[10])),ArenaDefinition.FIXED));
    }
    @Test void prospectiveOverlayChangesOnlyOneCellAndCompactsWithoutDefensiveCopy(){var base=filled(0);var overlay=base.withMaterial(15,143,15,2);assertEquals(0,base.materialAt(15,143,15));assertEquals(2,overlay.materialAt(15,143,15));assertThrows(IllegalStateException.class,()->overlay.withMaterial(16,144,16,1));var compact=overlay.compact();assertEquals(overlay.contentIdentity(),compact.contentIdentity());assertSame(compact,compact.compact());assertSame(base,base.withMaterial(1,129,1,0));assertThrows(IllegalArgumentException.class,()->base.withMaterial(-1,128,0,1));assertThrows(IndexOutOfBoundsException.class,()->base.materialAt(-3,128,0));}
}
