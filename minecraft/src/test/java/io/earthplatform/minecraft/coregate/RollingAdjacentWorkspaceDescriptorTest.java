package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingAdjacentWorkspaceDescriptorTest {
    static EnginePaletteContext palette;
    @BeforeAll static void setup() {
        var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        biomes.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(biomes.freeze());
    }
    static RollingSessionDescriptor adjacent(CoreGateProfile p) { return RollingSessionDescriptor.analytic(p,palette,RollingEditPolicy.FULL_CUBE_V1,true,true); }
    @Test void adjacentTopologyIsExplicitAtAllThreeHeights() {
        for(var p:CoreGateProfile.values()) {
            var d=adjacent(p);assertTrue(d.twoWorkspaces());assertTrue(d.adjacentWorkspaces());assertTrue(d.workspaces().isAdjacent());
            assertEquals(p.center().offset(1,0,0),d.workspaces().second());
            assertEquals("earthplatform-adjacent-workspace-player-gate-v1/"+p.id(),d.saveDirectory());
            assertEquals(4096,d.fixedFarRadius());assertEquals(p.anchorY(),d.flatPolicy().surfaceY());
        }
    }
    @Test void oldFactoriesKeepTheirExactWorkspaceSelectionAndStores() {
        var old=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1);
        var disjoint=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true);
        var adjacent=adjacent(CoreGateProfile.BASE);
        assertFalse(old.twoWorkspaces());assertFalse(disjoint.adjacentWorkspaces());assertEquals(new CubePos(4,8,0),disjoint.workspaces().second());
        assertEquals("earthplatform-two-workspace-player-gate-v1/base",disjoint.saveDirectory());
        assertEquals(old.world(),adjacent.world());assertEquals(old.sourceFingerprint(),adjacent.sourceFingerprint());
        assertEquals(disjoint.flatPolicy(),adjacent.flatPolicy());assertNotEquals(disjoint.fingerprint(),adjacent.fingerprint());
        assertNotEquals(disjoint.workspaces().identity(),adjacent.workspaces().identity());assertNotEquals(disjoint.saveDirectory(),adjacent.saveDirectory());
    }
    @Test void adjacentRequiresCompleteTwoWorkspaceCapability() {
        assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,false,true));
    }
    @Test void adjacentRequiresFullCubePolicy() {
        assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.INTERIOR_V1,true,true));
    }
    @Test void descriptorRoundTripPreservesModeAndLeavesNextPacketUntouched() {
        for(var p:CoreGateProfile.values()) {
            var d=adjacent(p);var b=new FriendlyByteBuf(Unpooled.buffer());
            try { d.write(b);assertTrue(b.readableBytes()<RollingSessionDescriptor.MAX_WIRE_BYTES);b.writeLong(123456789);var x=RollingSessionDescriptor.read(b,d.world(),palette);assertEquals(d,x);assertTrue(x.adjacentWorkspaces());assertEquals(123456789,b.readLong());assertFalse(b.isReadable()); }
            finally { b.release(); }
        }
    }
    @Test void disjointWorkspaceIdentityCannotBeSubstitutedIntoAdjacentDescriptor() {
        var d=adjacent(CoreGateProfile.BASE);var old=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true);
        var b=new FriendlyByteBuf(Unpooled.buffer());try {
            d.write(b);byte[] bytes=new byte[b.readableBytes()];b.getBytes(0,bytes);
            byte[] from=d.workspaces().identity().getBytes(StandardCharsets.US_ASCII),to=old.workspaces().identity().getBytes(StandardCharsets.US_ASCII);
            int found=-1;for(int i=0;i<=bytes.length-from.length;i++){boolean same=true;for(int j=0;j<from.length;j++)if(bytes[i+j]!=from[j]){same=false;break;}if(same){found=i;break;}}
            assertTrue(found>=0);b.setBytes(found,to);assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.read(b,d.world(),palette));
        } finally { b.release(); }
    }
    @Test void seamBothSidesRequireCorrectCurrentFocus() {
        var d=adjacent(CoreGateProfile.BASE);var a=d.workspaces().original();var b=d.workspaces().second();
        for(int y:new int[]{128,143})for(int z:new int[]{0,15}) {
            assertTrue(d.permitsEdit(a,15,y,z));assertFalse(d.permitsEdit(b,15,y,z));
            assertTrue(d.permitsEdit(b,16,y,z));assertFalse(d.permitsEdit(a,16,y,z));
        }
        assertFalse(d.permitsEdit(b,32,128,0));assertFalse(d.permitsEdit(a,-1,128,0));
    }
    @Test void sharedHaloDoesNotExpandBodyOrWindowEnvelope() {
        var a=adjacent(CoreGateProfile.BASE);var old=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true);
        assertEquals(old.bodyEnvelope(),a.bodyEnvelope());assertEquals(old.windowEnvelope(),a.windowEnvelope());
        assertThrows(IllegalArgumentException.class,()->a.requireAllowedWindow(new CubePos(32,8,0)));
        assertThrows(IllegalArgumentException.class,()->a.requireAllowedWindow(new CubePos(-33,8,0)));
    }
}
