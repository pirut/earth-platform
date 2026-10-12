package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.NativeDistanceProgressWatchdog.Lane.*;
import static org.junit.jupiter.api.Assertions.*;

/** The new lane is completed immutable value facts, never model callbacks or polling. */
class NativeDistanceBodyProgressTest {
    private static final long SECOND=1_000_000_000L;
    private static NativeDistanceProgressWatchdog guard(){
        var g=new NativeDistanceProgressWatchdog(1,12,0,false,false);
        assertTrue(g.bindAttempt(1,1,12,2,1,1,"a".repeat(64),"b".repeat(64)));return g;
    }
    @Test void exact125CapIsSeparateFromLegacyModelCallbacks(){
        var g=guard();assertEquals(512_000,BODY_EQUALITY_COMPARISONS.cap());assertEquals(114_688,NATIVE_MESH_CALLBACKS.cap());
        assertTrue(g.creditLane(BODY_EQUALITY_COMPARISONS,512_000));
        assertEquals(0,g.rawVector()[NATIVE_MESH_CALLBACKS.ordinal()]);
        assertFalse(g.creditLane(BODY_EQUALITY_COMPARISONS,512_001));assertTrue(g.failed());
    }
    @Test void repeatedPollsAndProofReuseEarnNoNewUsefulCredit(){
        var g=guard();g.observe(60*SECOND,false);assertFalse(g.creditLane(BODY_EQUALITY_COMPARISONS,0));
        assertEquals(60*SECOND,g.usefulIdleNanos());assertTrue(g.creditLane(BODY_EQUALITY_COMPARISONS,4096));
        g.observe(119*SECOND,false);assertFalse(g.creditLane(BODY_EQUALITY_COMPARISONS,4096));
        assertEquals(59*SECOND,g.usefulIdleNanos());g.observe(180*SECOND,false);assertTrue(g.failed());
    }
    @Test void sameSelectionRetryAndAbaCannotRefreshAlreadyCreditedEquality(){
        var g=guard();assertTrue(g.creditLane(BODY_EQUALITY_COMPARISONS,512_000));g.observe(40*SECOND,false);
        assertTrue(g.bindAttempt(1,2,24,3,1,1,"c".repeat(64),"d".repeat(64)));
        assertTrue(g.creditLane(BODY_EQUALITY_COMPARISONS,4096));g.observe(80*SECOND,false);
        assertTrue(g.bindAttempt(1,3,12,4,1,1,"e".repeat(64),"f".repeat(64)));
        assertFalse(g.creditLane(BODY_EQUALITY_COMPARISONS,512_000));assertEquals(40*SECOND,g.usefulIdleNanos());
        assertTrue(g.bindAttempt(1,4,12,5,1,1,"1".repeat(64),"2".repeat(64)));
        assertFalse(g.creditLane(BODY_EQUALITY_COMPARISONS,4096));assertEquals(40*SECOND,g.usefulIdleNanos());
    }
    @Test void pauseAndEpisodeCeilingsRemainUnchanged(){
        assertEquals(120*SECOND,NativeDistanceProgressWatchdog.USEFUL_IDLE_NANOS);
        assertEquals(120*SECOND,NativeDistanceProgressWatchdog.PEER_IDLE_NANOS);
        assertEquals(1800*SECOND,NativeDistanceProgressWatchdog.TOTAL_ACTIVE_NANOS);
        var g=guard();g.observe(60*SECOND,true);g.observe(3600*SECOND,false);
        assertEquals(60*SECOND,g.activeNanos());assertTrue(g.creditLane(BODY_EQUALITY_COMPARISONS,4096));
    }
    @Test void wireChannelSeparatesTheSeventeenLaneSchema(){
        assertEquals(2,NativeDistanceProgressPayload.WIRE_VERSION);assertEquals(17,NativeDistanceProgressWatchdog.LANE_COUNT);
        assertEquals("native_distance_progress_v2",NativeDistanceProgressPayload.TYPE.id().getPath());
    }
}
