package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingWindowAdmissionTest {
    private static final CubePos CENTER=new CubePos(0,8,0);
    @Test void fullBodyAndAllSixFacesAreChecked() {
        assertTrue(RollingWindowAdmission.contains(new AABB(-32,96,-32,-31,98,-31),CENTER));
        assertTrue(RollingWindowAdmission.contains(new AABB(47,174,47,48,176,48),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(-32.01,128,0,-31.01,130,1),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(47.01,128,0,48.01,130,1),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,95.99,0,1,97.99,1),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,174.01,0,1,176.01,1),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,128,-32.01,1,130,-31.01),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,128,47.01,1,130,48.01),CENTER));
    }
    @Test void oldWindowOrPinCannotAuthorizeCandidateCollision() {
        var body=new AABB(-31,128,0,-30.4,129.8,.6);
        assertTrue(RollingWindowAdmission.contains(body,CENTER));
        assertFalse(RollingWindowAdmission.contains(body,CENTER.offset(1,0,0)));
        assertFalse(RollingWindowAdmission.contains(new AABB(4,130,4,4.6,131.8,4.6),CENTER.offset(8,0,0)));
    }
    @Test void negativeYAndVerticalTransitionUseActualCandidate() {
        var abyss=new CubePos(0,-688,0);
        assertTrue(RollingWindowAdmission.contains(new AABB(0,-11000,0,.6,-10998.2,.6),abyss));
        var edge=new AABB(0,abyss.minBlockY()-32,0,.6,abyss.minBlockY()-30.2,.6);
        assertTrue(RollingWindowAdmission.contains(edge,abyss));
        assertFalse(RollingWindowAdmission.contains(edge,abyss.offset(0,1,0)));
    }
    @Test void invalidAndOversizedBodiesFailClosed() {
        assertFalse(RollingWindowAdmission.contains(null,CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,128,0,1,130,1),null));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,128,0,1.01,130,1),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,128,0,1,130.01,1),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,128,0,1,130,1.01),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,128,0,0,130,1),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(Double.NaN,128,0,1,130,1),CENTER));
        assertFalse(RollingWindowAdmission.contains(new AABB(0,128,0,Double.POSITIVE_INFINITY,130,1),CENTER));
    }
}
