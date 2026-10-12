package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Packing/schema arithmetic only. Synthetic bytes are never evidence of native model parity. */
class SurvivalPageGeometryTest {
    @Test void defaultAndIrisPiecesEndOnWholeQuadsWithinUnchangedOneMibPayload() {
        assertEquals(32768, SurvivalPageGeometry.capacityVertices(32));
        assertEquals(20164, SurvivalPageGeometry.capacityVertices(52));
        for (int stride : new int[]{32,52}) {
            int vertices = SurvivalPageGeometry.capacityVertices(stride);
            assertEquals(0, vertices % 4);
            assertTrue((long) vertices * stride <= 1 << 20);
            assertTrue((long) (vertices + 4) * stride > 1 << 20);
            assertTrue((long) vertices / 4 * 6 * 4 <= 1 << 20);
        }
        for (int invalid : new int[]{Integer.MIN_VALUE,-1,0,11,(1 << 18)+1,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> SurvivalPageGeometry.capacityVertices(invalid));
    }
    @Test void signedPageMembershipUsesXThenZThenYAndNeverWraps() {
        var page = new SurvivalVisualPage(new CubePos(-4,-688,-8));
        assertEquals(0, SurvivalPageGeometry.memberIndex(page,page.minimum()));
        assertEquals(1, SurvivalPageGeometry.memberIndex(page,page.minimum().offset(1,0,0)));
        assertEquals(4, SurvivalPageGeometry.memberIndex(page,page.minimum().offset(0,0,1)));
        assertEquals(16, SurvivalPageGeometry.memberIndex(page,page.minimum().offset(0,1,0)));
        assertEquals(63, SurvivalPageGeometry.memberIndex(page,page.minimum().offset(3,3,3)));
        assertThrows(IllegalArgumentException.class, () -> SurvivalPageGeometry.memberIndex(page,new CubePos(Integer.MAX_VALUE,0,0)));
        assertThrows(IllegalArgumentException.class, () -> SurvivalPageGeometry.memberIndex(page,page.minimum().offset(-1,0,0)));
    }
    @Test void pageRangesHaveExactIndexOffsetsAndAllowAMemberToContinueInAnotherPiece() {
        var first = new SurvivalPageGeometry.MemberRange(7,0,8);
        var second = new SurvivalPageGeometry.MemberRange(10,8,4);
        SurvivalPageGeometry.requireRanges(List.of(first,second),12);
        assertEquals(0, first.firstIndex()); assertEquals(12, first.indexCount());
        assertEquals(12, second.firstIndex()); assertEquals(6, second.indexCount());
        SurvivalPageGeometry.requireRanges(List.of(new SurvivalPageGeometry.MemberRange(10,0,4)),4);
        SurvivalPageGeometry.requireRanges(List.of(),0);
    }
    @Test void gapsOverlapDuplicateOrReorderedMembersAndPartialQuadsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SurvivalPageGeometry.MemberRange(1,0,3));
        assertThrows(IllegalArgumentException.class, () -> new SurvivalPageGeometry.MemberRange(1,1,4));
        assertThrows(IllegalArgumentException.class, () -> new SurvivalPageGeometry.MemberRange(64,0,4));
        assertThrows(IllegalArgumentException.class, () -> new SurvivalPageGeometry.MemberRange(0,0,0));
        var first = new SurvivalPageGeometry.MemberRange(3,0,4);
        for (var second : List.of(new SurvivalPageGeometry.MemberRange(4,8,4),
                new SurvivalPageGeometry.MemberRange(4,0,4),new SurvivalPageGeometry.MemberRange(3,4,4),
                new SurvivalPageGeometry.MemberRange(2,4,4)))
            assertThrows(IllegalArgumentException.class, () -> SurvivalPageGeometry.requireRanges(List.of(first,second),8));
        assertThrows(IllegalArgumentException.class, () -> SurvivalPageGeometry.requireRanges(List.of(first),8));
    }
    @Test void relocatingIrisBytesPreservesEveryMaterialTangentLightUvAndPaddingByte() {
        for (int stride : new int[]{32,52}) {
            byte[] payload = new byte[8 * stride];
            for (int i=0;i<payload.length;i++) payload[i]=(byte)(i*31+13);
            var bytes = ByteBuffer.wrap(payload).order(ByteOrder.nativeOrder());
            for (int i=0;i<8;i++) {
                bytes.putFloat(i*stride, i+.25f); bytes.putFloat(i*stride+4,i+.5f); bytes.putFloat(i*stride+8,i+.75f);
            }
            byte[] before = payload.clone();
            var ranges = List.of(new SurvivalPageGeometry.MemberRange(0,0,4),new SurvivalPageGeometry.MemberRange(63,4,4));
            SurvivalPageGeometry.relocatePositions(bytes,stride,ranges);
            for (int vertex=0;vertex<8;vertex++) {
                int shift = vertex<4 ? 0 : 48;
                assertEquals(vertex+.25f+shift,bytes.getFloat(vertex*stride));
                assertEquals(vertex+.5f+shift,bytes.getFloat(vertex*stride+4));
                assertEquals(vertex+.75f+shift,bytes.getFloat(vertex*stride+8));
                for (int offset=12;offset<stride;offset++)
                    assertEquals(before[vertex*stride+offset],payload[vertex*stride+offset],"non-position byte "+offset);
            }
        }
    }
    @Test void relocationHonorsBufferSliceAndLeavesCallerPositionLimitAndOutsideBytesAlone() {
        var bytes = ByteBuffer.allocate(160).order(ByteOrder.nativeOrder());
        for(int i=0;i<160;i++) bytes.put(i,(byte)0x51);
        for(int i=0;i<4;i++) for(int axis=0;axis<3;axis++) bytes.putFloat(16+i*32+axis*4,axis+0.5f);
        bytes.position(16);bytes.limit(144);
        SurvivalPageGeometry.relocatePositions(bytes,32,List.of(new SurvivalPageGeometry.MemberRange(21,0,4)));
        assertEquals(16,bytes.position());assertEquals(144,bytes.limit());
        assertEquals(16.5f,bytes.getFloat(16));assertEquals(17.5f,bytes.getFloat(20));assertEquals(18.5f,bytes.getFloat(24));
        bytes.clear();for(int i=0;i<16;i++)assertEquals((byte)0x51,bytes.get(i));for(int i=144;i<160;i++)assertEquals((byte)0x51,bytes.get(i));
    }
    @Test void malformedRelocationFailsBeforeMutatingAnyPosition() {
        var bytes=ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
        bytes.putFloat(0,1f);bytes.putFloat(3*32+8,Float.NaN);
        byte[] before=bytes.array().clone();
        var range=List.of(new SurvivalPageGeometry.MemberRange(63,0,4));
        assertThrows(IllegalArgumentException.class,()->SurvivalPageGeometry.relocatePositions(bytes,32,range));
        assertArrayEquals(before,bytes.array());
        assertThrows(IllegalArgumentException.class,()->SurvivalPageGeometry.relocatePositions(bytes.asReadOnlyBuffer(),32,range));
        assertThrows(IllegalArgumentException.class,()->SurvivalPageGeometry.relocatePositions(ByteBuffer.allocate(129),32,range));
    }
}
