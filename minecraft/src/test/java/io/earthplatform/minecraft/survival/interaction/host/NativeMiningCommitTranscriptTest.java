package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.CubeCell;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Codec/data boundaries only. Original branch/runtime equivalence requires the registered probes. */
class NativeMiningCommitTranscriptTest {
    private static final CubeCell TARGET=CubeCell.of(-17,134,65), DELAYED=CubeCell.of(-18,134,65);
    private static NativeMiningControlState.Mode mode(boolean held,boolean delayed,int ticks,int stage) {
        return new NativeMiningControlState.Mode(held,7,TARGET,ticks,delayed,DELAYED,5,stage);
    }
    private static NativeMiningControlTranscript data(NativeMiningControlTranscript.Input input,
            NativeMiningControlState.Mode before,NativeMiningControlState.Mode after,
            List<NativeMiningControlJournal.Notice> notices,int destroyIndex,CubeCell destroyed) {
        var registries=VanillaRegistries.createLookup();
        return new NativeMiningControlTranscript(UUID.randomUUID(),0,input,before,after,
                new NativeMiningControlState.Prefix(17,123),new NativeMiningControlState.Prefix(input.kind()==3?-1:input.sequence(),input.kind()==3?123:456),
                0,NativeStackSnapshot.capture(ItemStack.EMPTY,registries),
                List.of(new NativeMiningControlTranscript.Target(TARGET,1,2,1),new NativeMiningControlTranscript.Target(DELAYED,1,2,1)),
                notices,64,destroyIndex,destroyed);
    }
    @Test void acceptedEvidenceRoundTripsFullNativeStateAndOrderedDestroyBoundary() {
        var registries=VanillaRegistries.createLookup();
        var notices=List.<NativeMiningControlJournal.Notice>of(new NativeMiningControlJournal.Crack(7,TARGET,-1),new NativeMiningControlJournal.BlockResync(DELAYED,1));
        var value=data(new NativeMiningControlTranscript.Input(1,TARGET,1,18,0),mode(true,true,9,12),mode(false,true,9,12),notices,1,TARGET);
        var effect=new NativeEffectCodec.MiningControl(new WorldIdentity(39,"proof","v1","source","palette"),UUID.randomUUID(),UUID.randomUUID(),1,value);
        assertEquals(effect,NativeEffectCodec.decode(NativeEffectCodec.encode(effect),registries));
        assertTrue(value.after().delayed());assertEquals(1,value.destroyIndex());assertEquals(12,value.after().lastSentStage());
    }
    @Test void repeatedZeroSequenceAbortRemainsRepresentableAndDoesNotClearDelayedState() {
        var input=new NativeMiningControlTranscript.Input(2,TARGET,1,0,0);
        var first=data(input,mode(true,true,9,3),mode(false,true,9,3),List.of(new NativeMiningControlJournal.Crack(7,TARGET,-1)),-1,null);
        var again=data(input,first.after(),first.after(),first.notices(),-1,null);
        assertEquals(0,again.prefixAfter().acknowledgement());assertTrue(again.after().delayed());assertFalse(again.after().destroying());
    }
    @Test void genuinePulseEvidenceRetainsNativeIntegerWrapAndRawStage() {
        var value=data(new NativeMiningControlTranscript.Input(3,null,-1,-1,Integer.MIN_VALUE),
                mode(true,true,Integer.MAX_VALUE,256),mode(true,true,Integer.MIN_VALUE,257),
                List.of(new NativeMiningControlJournal.Crack(7,DELAYED,257),new NativeMiningControlJournal.Ack(17)),-1,null);
        assertEquals(Integer.MIN_VALUE,value.after().ticks());assertEquals(257,((NativeMiningControlJournal.Crack)value.notices().getFirst()).stage());
        assertEquals(value.prefixBefore().lastActionTime(),value.prefixAfter().lastActionTime());
    }
    @Test void evidenceCannotInventCatchupOrPacketTicks() {
        assertThrows(IllegalArgumentException.class,()->data(new NativeMiningControlTranscript.Input(3,null,-1,-1,7),mode(true,false,9,2),mode(true,false,11,3),List.of(),-1,null));
        assertThrows(IllegalArgumentException.class,()->data(new NativeMiningControlTranscript.Input(0,TARGET,1,4,0),mode(false,false,9,2),mode(true,false,10,3),List.of(),-1,null));
    }
    @Test void orderedOutputCannotHideAnOutOfRangeOrUnpairedDestroyIndex() {
        var input=new NativeMiningControlTranscript.Input(1,TARGET,1,4,0);
        assertThrows(IllegalArgumentException.class,()->data(input,mode(true,false,9,2),mode(false,false,9,2),List.of(),1,TARGET));
        assertThrows(IllegalArgumentException.class,()->data(input,mode(true,false,9,2),mode(false,false,9,2),List.of(),0,null));
        assertThrows(IllegalArgumentException.class,()->data(input,mode(true,false,9,2),mode(false,false,9,2),List.of(),-1,TARGET));
    }
    @Test void inputSeparatesOriginalPacketFromSchedulerAndPreservesPackedRange() {
        assertThrows(IllegalArgumentException.class,()->new NativeMiningControlTranscript.Input(3,TARGET,1,0,9));
        assertThrows(IllegalArgumentException.class,()->new NativeMiningControlTranscript.Input(0,TARGET,1,-1,0));
        assertThrows(IllegalArgumentException.class,()->new NativeMiningControlTranscript.Input(0,TARGET,1,0,1));
        assertThrows(RuntimeException.class,()->new NativeMiningControlTranscript.Input(0,CubeCell.of(0,2048,0),1,0,0));
    }
    @Test void journalCannotRetainCallerMutableNoticeListOrUnboundedNoticeCount() {
        var input=new NativeMiningControlTranscript.Input(2,TARGET,1,0,0);
        var notices=new ArrayList<NativeMiningControlJournal.Notice>();notices.add(new NativeMiningControlJournal.Crack(7,TARGET,-1));
        var value=data(input,mode(true,false,9,3),mode(false,false,9,3),notices,-1,null);notices.clear();
        assertEquals(1,value.notices().size());assertThrows(UnsupportedOperationException.class,()->value.notices().clear());
        assertThrows(IllegalArgumentException.class,()->data(input,mode(true,false,9,3),mode(false,false,9,3),java.util.Collections.nCopies(9,new NativeMiningControlJournal.Ack(0)),-1,null));
    }
    @Test void checkpointReadinessPreservesNativeDelayedAbortAndPendingAck() {
        var cleared=new NativeMiningControlState.Prefix(-1,123);
        assertFalse(NativeMiningControlState.settledForCheckpoint(mode(true,false,9,3),cleared));
        assertFalse(NativeMiningControlState.settledForCheckpoint(mode(false,true,9,3),cleared));
        assertFalse(NativeMiningControlState.settledForCheckpoint(mode(false,false,9,3),new NativeMiningControlState.Prefix(0,123)));
        // Vanilla ABORT leaves lastSentState untouched. Inactive flags plus drained ack are sufficient.
        assertTrue(NativeMiningControlState.settledForCheckpoint(mode(false,false,9,3),cleared));
    }

    @Test void enumeratedMaximumTranscriptFitsRetainedLineageCharge() throws Exception {
        var registries=VanillaRegistries.createLookup();var third=CubeCell.of(-19,134,65);
        var value=new NativeMiningControlTranscript(UUID.randomUUID(),0,new NativeMiningControlTranscript.Input(1,TARGET,1,18,0),
                mode(true,true,9,12),mode(false,true,9,12),new NativeMiningControlState.Prefix(17,123),new NativeMiningControlState.Prefix(18,456),
                0,NativeStackSnapshot.capture(ItemStack.EMPTY,registries),
                List.of(new NativeMiningControlTranscript.Target(TARGET,1,2,1),new NativeMiningControlTranscript.Target(DELAYED,1,2,1),new NativeMiningControlTranscript.Target(third,1,2,1)),
                java.util.Collections.nCopies(8,new NativeMiningControlJournal.Crack(7,TARGET,Integer.MAX_VALUE)),4096,8,TARGET);
        var bytes=new java.io.ByteArrayOutputStream();value.write(new java.io.DataOutputStream(bytes));
        assertEquals(NativeMiningControlTranscript.MAX_TRANSCRIPT_BYTES,bytes.size());assertEquals(670,bytes.size());
        var effect=new NativeEffectCodec.MiningControl(new WorldIdentity(39,"proof","v1","source","palette"),UUID.randomUUID(),UUID.randomUUID(),1,value);
        assertTrue(NativeEffectCodec.encode(effect).payloadBytes()<=NativeMiningControlTranscript.MAX_EFFECT_BYTES);
        assertTrue(NativeMiningControlTranscript.MAX_TRANSCRIPT_BYTES+2*NativeMiningControlTranscript.MAX_EFFECT_BYTES+512<=4096);
    }

}
