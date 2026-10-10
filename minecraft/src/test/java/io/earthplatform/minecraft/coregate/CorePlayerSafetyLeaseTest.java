package io.earthplatform.minecraft.coregate;
import java.util.UUID;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CorePlayerSafetyLeaseTest {
    final UUID id=UUID.randomUUID();
    @Test void bothNativeEndpointsFitExactPlayerLease(){var lease=new CorePlayerSafetyLease(id,8);for(var p:new CoreGateProfile[]{CoreGateProfile.ABYSS,CoreGateProfile.SUMMIT})assertTrue(lease.contains(id,p.walkEntryBody()));assertEquals(8,lease.epoch());}
    @Test void differentPlayerCannotInheritLease(){assertFalse(new CorePlayerSafetyLease(id,1).contains(UUID.randomUUID(),CoreGateProfile.ABYSS.walkEntryBody()));}
    @Test void closedLeaseNeverReopens(){var lease=new CorePlayerSafetyLease(id,1);lease.close();lease.close();assertFalse(lease.contains(id,CoreGateProfile.ABYSS.walkEntryBody()));}
    @Test void nativeSafetyBoundsDoNotIncludeRenderPadding(){var lease=new CorePlayerSafetyLease(id,1);assertFalse(lease.contains(id,new AABB(0,-11009,0,.6,-11007.2,.6)));assertFalse(lease.contains(id,new AABB(0,8863,0,.6,8864.8,.6)));assertFalse(lease.contains(id,new AABB(512,128,0,512.6,129.8,.6)));assertFalse(lease.contains(id,new AABB(0,0,0,8,8,8)));}
    @Test void invalidPoseAndIdentityFailClosed(){var lease=new CorePlayerSafetyLease(id,1);assertFalse(lease.contains(id,null));assertFalse(lease.contains(id,new AABB(Double.NaN,0,0,1,1,1)));assertThrows(IllegalArgumentException.class,()->new CorePlayerSafetyLease(id,0));assertThrows(NullPointerException.class,()->new CorePlayerSafetyLease(null,1));}
}
