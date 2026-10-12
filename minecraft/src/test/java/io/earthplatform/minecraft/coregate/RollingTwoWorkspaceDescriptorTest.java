package io.earthplatform.minecraft.coregate;
import com.mojang.serialization.Lifecycle;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
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
class RollingTwoWorkspaceDescriptorTest {
 static EnginePaletteContext palette;
 @BeforeAll static void setup(){var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());biomes.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(biomes.freeze());}
 @Test void explicitTwoWorkspacePolicyBindsFlatSourceAndDisjointPairAtAllHeights(){for(var p:CoreGateProfile.values()){var d=RollingSessionDescriptor.analytic(p,palette,RollingEditPolicy.FULL_CUBE_V1,true);assertTrue(d.twoWorkspaces());assertEquals(p.center().offset(4,0,0),d.workspaces().second());assertEquals(d.world(),d.flatPolicy().providerIdentity());assertEquals(p.anchorY(),d.flatPolicy().surfaceY());assertEquals(0xff808080,d.flatPolicy().opaqueArgb());assertEquals(4096,d.fixedFarRadius());assertEquals("earthplatform-two-workspace-player-gate-v1/"+p.id(),d.saveDirectory());}}
 @Test void oldWorldSourceAndStoreAreNotReinterpreted(){var old=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1);var two=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true);assertFalse(old.twoWorkspaces());assertEquals(old.world(),two.world());assertEquals(old.sourceFingerprint(),two.sourceFingerprint());assertNotEquals(old.fingerprint(),two.fingerprint());assertNotEquals(old.saveDirectory(),two.saveDirectory());assertThrows(IllegalStateException.class,old::flatPolicy);}
 @Test void completeDescriptorRoundTripsWithoutFilesOrSourceQueries(){for(var p:CoreGateProfile.values()){var d=RollingSessionDescriptor.analytic(p,palette,RollingEditPolicy.FULL_CUBE_V1,true);var b=new FriendlyByteBuf(Unpooled.buffer());try{d.write(b);assertTrue(b.readableBytes()<RollingSessionDescriptor.MAX_WIRE_BYTES);b.writeInt(12345);var decoded=RollingSessionDescriptor.read(b,d.world(),palette);assertEquals(d,decoded);assertEquals(d.flatPolicy(),decoded.flatPolicy());assertEquals(12345,b.readInt());assertFalse(b.isReadable());}finally{b.release();}}}
 @Test void interiorPolicyCannotAcquireTwoWorkspaceCapability(){assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.INTERIOR_V1,true));}
 @Test void editAdmissionRequiresDeclaredActiveFocusAndActualCube(){var d=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true);var a=d.workspaces().original();var b=d.workspaces().second();assertTrue(d.permitsEdit(b,b.minBlockX()+15,b.minBlockY(),b.minBlockZ()+15));assertFalse(d.permitsEdit(a,b.minBlockX(),b.minBlockY(),b.minBlockZ()));assertFalse(d.permitsEdit(b.offset(4,0,0),b.minBlockX()+64,b.minBlockY(),b.minBlockZ()));}
}
