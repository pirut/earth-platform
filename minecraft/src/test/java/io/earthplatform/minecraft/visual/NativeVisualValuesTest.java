package io.earthplatform.minecraft.visual;

import io.earthplatform.core.WorldIdentity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeVisualValuesTest {
    private static final String H="a".repeat(64);
    private static NativeVisualStamp stamp(int selected,int effective,int server,long revision,long generation) {
        return new NativeVisualStamp(new WorldIdentity(1,"earth","v1",H,"palette"),new UUID(1,2),1,1,generation,revision,
                selected,effective,server,H,H,H,H,H);
    }
    @Test void signedPagesUseFloorOwnershipAtAllEdges() {
        var p=NativePageKey.containing(-1,-65,9678074);
        assertEquals(new NativePageKey(-1,-2,151219),p);
        assertEquals(-64,p.minX()); assertEquals(-128,p.minY());
        assertEquals(63,p.localX(-1)); assertEquals(63,p.localY(-65));
        assertTrue(p.contains(-1,-65,9678074)); assertFalse(p.contains(0,-65,9678074));
        assertThrows(IllegalArgumentException.class,()->p.localX(0));
        assertThrows(ArithmeticException.class,()->new NativePageKey(Long.MAX_VALUE,0,0));
        assertThrows(ArithmeticException.class,()->NativePageKey.containing(Long.MAX_VALUE,0,0));
    }
    @Test void arbitraryPinnedSettingsBindExactActualValues() {
        for(int radius=2;radius<=32;radius++) {
            var s=stamp(radius,radius,0,0,1);s.requireSelectedDistanceEffective();
            assertEquals(radius*16,s.radiusBlocks());assertEquals(64,s.identity().length());
            assertNotEquals(s.identity(),stamp(radius,radius,0,1,1).identity());
            assertNotEquals(s.identity(),stamp(radius,radius,0,0,2).identity());
        }
        assertNotEquals(stamp(12,12,0,0,1).identity(),stamp(24,24,0,0,1).identity());
    }
    @Test void serverClampingCannotMasqueradeAsChosenReadiness() {
        var s=stamp(24,12,12,0,1);
        assertFalse(s.selectedDistanceEffective());
        assertThrows(IllegalStateException.class,s::requireSelectedDistanceEffective);
        assertThrows(IllegalArgumentException.class,()->stamp(24,24,12,0,1));
        assertThrows(IllegalArgumentException.class,()->stamp(33,33,0,0,1));
        assertThrows(IllegalArgumentException.class,()->stamp(1,1,0,0,1));
    }
}
