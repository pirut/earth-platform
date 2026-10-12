package io.earthplatform.minecraft.scene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic schema arithmetic only; never logged as loaded-model or actual GL parity. */
class SurvivalNativePageParitySchemaTest {
    private static SurvivalNativePageParityProbe.Schema schema(boolean iris) {
        var fields=new ArrayList<>(List.of(new SurvivalNativePageParityProbe.Element("Position",0,12),
                new SurvivalNativePageParityProbe.Element("Color",12,4),new SurvivalNativePageParityProbe.Element("UV0",16,8),
                new SurvivalNativePageParityProbe.Element("UV2",24,4),new SurvivalNativePageParityProbe.Element("Normal",28,3)));
        if(iris)fields.addAll(List.of(new SurvivalNativePageParityProbe.Element("mc_Entity",32,4),
                new SurvivalNativePageParityProbe.Element("mc_midTexCoord",36,8),new SurvivalNativePageParityProbe.Element("at_tangent",44,4),
                new SurvivalNativePageParityProbe.Element("at_midBlock",48,3)));
        return new SurvivalNativePageParityProbe.Schema(iris?52:32,fields,iris?List.of(31,51):List.of(31));
    }
    @Test void paddingIsExactlyNativeElementComplementAndCannotHideOneSemanticByte() {
        for(boolean iris:new boolean[]{false,true}) {
            var schema=schema(iris);
            var wrong=new ArrayList<>(schema.paddingOffsets());wrong.add(12);
            assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityProbe.Schema(schema.stride(),schema.elements(),wrong));
            assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityProbe.Schema(schema.stride(),schema.elements(),List.of()));
        }
    }
    @Test void normalizationPreservesEveryDeclaredAttributeExceptAuthorizedPositionTranslation() {
        for(boolean iris:new boolean[]{false,true}) {
            var schema=schema(iris);var bytes=ByteBuffer.allocate(schema.stride()).order(ByteOrder.nativeOrder());
            for(int i=0;i<bytes.capacity();i++)bytes.put(i,(byte)(i*47+29));
            bytes.putFloat(0,.25f);bytes.putFloat(4,1.5f);bytes.putFloat(8,2.75f);byte[] original=bytes.array().clone();
            SurvivalNativePageParityProbe.canonicalizeVertex(bytes,schema,63,true);
            assertEquals(48.25f,bytes.getFloat(0));assertEquals(49.5f,bytes.getFloat(4));assertEquals(50.75f,bytes.getFloat(8));
            for(int i=12;i<bytes.capacity();i++)assertEquals(schema.paddingOffsets().contains(i)?0:original[i],bytes.get(i),"byte "+i);
        }
    }
    @Test void groupedBytesAreNeverTranslatedAgainAndOnlyActualPaddingIsNormalized() {
        var schema=schema(true);var bytes=ByteBuffer.allocate(52).order(ByteOrder.nativeOrder());
        for(int i=0;i<52;i++)bytes.put(i,(byte)(i+1));
        byte[] before=bytes.array().clone();SurvivalNativePageParityProbe.canonicalizeVertex(bytes,schema,63,false);
        for(int i=0;i<52;i++)assertEquals(schema.paddingOffsets().contains(i)?0:before[i],bytes.get(i));
        assertThrows(IllegalArgumentException.class,()->SurvivalNativePageParityProbe.canonicalizeVertex(bytes.asReadOnlyBuffer(),schema,0,false));
        assertThrows(IllegalArgumentException.class,()->SurvivalNativePageParityProbe.canonicalizeVertex(bytes,schema,64,false));
    }
    @Test void malformedAndOverlappingSchemasRefuseWithoutUnboundedLoops() {
        var position=new SurvivalNativePageParityProbe.Element("Position",0,12);
        assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityProbe.Schema(32,
                List.of(position,new SurvivalNativePageParityProbe.Element("invalid",8,20)),List.of(28,29,30,31)));
        assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityProbe.Schema(32,
                List.of(position,new SurvivalNativePageParityProbe.Element("huge",12,Integer.MAX_VALUE-12)),List.of()));
        assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityProbe.Schema(32,
                List.of(new SurvivalNativePageParityProbe.Element("wrong-position",0,32)),List.of()));
    }
    /** Arithmetic-only gate tests; actual transformed allocator counts come from the installed probe. */
    @Test void defaultParityCannotPassWithoutObservedWholeQuadReservationsInBothCompilers() {
        var good=new SurvivalNativePageParityProbe.ReservationEvidence(1,1,1,1,4,128,4,128);
        assertDoesNotThrow(()->good.requireParity(32,false));
        for(var missing:List.of(
                new SurvivalNativePageParityProbe.ReservationEvidence(4,0,1,1,4,128,4,128),
                new SurvivalNativePageParityProbe.ReservationEvidence(1,1,4,0,4,128,4,128),
                new SurvivalNativePageParityProbe.ReservationEvidence(4,0,4,0,4,128,4,128)))
            assertThrows(IllegalStateException.class,()->missing.requireParity(32,false));
    }
    @Test void irisGenericAndKnownEmptyReservationsRemainValidWithoutInventingBulkCalls() {
        assertDoesNotThrow(()->new SurvivalNativePageParityProbe.ReservationEvidence(4,0,4,0,4,208,4,208).requireParity(52,true));
        assertDoesNotThrow(()->new SurvivalNativePageParityProbe.ReservationEvidence(0,0,0,0,0,0,0,0).requireParity(32,false));
        assertDoesNotThrow(()->new SurvivalNativePageParityProbe.ReservationEvidence(0,0,0,0,0,0,0,0).requireParity(52,true));
    }
    @Test void reservationEvidenceCannotHideWrongBytesPartialQuadsOrFabricatedCounters() {
        for(var bad:List.of(
                new SurvivalNativePageParityProbe.ReservationEvidence(1,1,1,1,4,128,4,127),
                new SurvivalNativePageParityProbe.ReservationEvidence(1,1,1,1,3,96,3,96),
                new SurvivalNativePageParityProbe.ReservationEvidence(1,2,1,1,4,128,4,128),
                new SurvivalNativePageParityProbe.ReservationEvidence(5,1,1,1,4,128,4,128),
                new SurvivalNativePageParityProbe.ReservationEvidence(4,1,1,1,4,128,4,128),
                new SurvivalNativePageParityProbe.ReservationEvidence(0,0,1,1,4,128,4,128)))
            assertThrows(IllegalStateException.class,()->bad.requireParity(32,false));
    }

}
