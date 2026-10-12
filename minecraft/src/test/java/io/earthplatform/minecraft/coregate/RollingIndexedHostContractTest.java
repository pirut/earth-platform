package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
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

class RollingIndexedHostContractTest {
    static EnginePaletteContext palette;
    @BeforeAll static void setup(){var b=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());b.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(b.freeze());}
    static RollingSessionDescriptor descriptor(CoreGateProfile p,boolean indexed){return RollingSessionDescriptor.analytic(p,palette,RollingEditPolicy.FULL_CUBE_V1,true,true,indexed);}
    static ReplacementSetCertificate certificate(RollingSessionDescriptor d,long generation,long indexGeneration,String root){
        var regions=new ArrayList<ReplacementSetCertificate.Region>();long lease=1;
        for(var p:d.workspaces().positions()){
            var versions=new ArrayList<RollingCubeActor.CubeVersion>();for(var x:CubeTickets.box(p,1,1,27).stream().sorted().toList())versions.add(new RollingCubeActor.CubeVersion(x,lease++,0));
            regions.add(new ReplacementSetCertificate.Region(p,versions,regions.isEmpty()?"a".repeat(64):"b".repeat(64)));
        }
        String store="efb413e5-d191-4854-a78d-7e90d136fefc",index="c".repeat(64),policy=RollingIndexedQueryGuard.policy(d);
        String result=PagedReplacementIndex.resultIdentity(regions.stream().map(x->new PagedReplacementIndex.Entry(x.position(),x.revision(),policy,x.captureIdentity())).toList());
        var q=new ReplacementQueryAttestation(store,index,indexGeneration,root,ReplacementQueryAttestation.DOMAIN_MIN,ReplacementQueryAttestation.DOMAIN_MIN,ReplacementQueryAttestation.DOMAIN_MIN,ReplacementQueryAttestation.DOMAIN_MAX,ReplacementQueryAttestation.DOMAIN_MAX,ReplacementQueryAttestation.DOMAIN_MAX,d.lodLayout().identity(),policy,result,2);
        return new ReplacementSetCertificate(d.world(),1,1,store,index,d.sourceFingerprint(),palette.fingerprint(),io.earthplatform.minecraft.lod.DhReplacementInput.ANALYTIC_MATERIAL_POLICY,RollingGateFixture.REPLACEMENT_DETAIL_POLICY,generation,regions,q);
    }
    @Test void indexModeChangesOnlyExplicitAuthorityAndStoreNotSourceOrCaps(){var d=descriptor(CoreGateProfile.BASE,true);var old=descriptor(CoreGateProfile.BASE,false);assertEquals(old.world(),d.world());assertEquals(old.sourceFingerprint(),d.sourceFingerprint());assertEquals(old.bodyEnvelope(),d.bodyEnvelope());assertEquals(old.windowEnvelope(),d.windowEnvelope());assertEquals(old.workspaces(),d.workspaces());assertNotEquals(old.fingerprint(),d.fingerprint());assertEquals("earthplatform-indexed-workspace-player-gate-v1/base",d.saveDirectory());}
    @Test void indexedModeRequiresAcceptedAdjacentCapability(){assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true,false,true));}
    @Test void oldVersionFiveAndIndexedVersionSixRoundTripAllHeights(){for(var p:CoreGateProfile.values())for(boolean indexed:new boolean[]{false,true}){var d=descriptor(p,indexed);var b=new FriendlyByteBuf(Unpooled.buffer());try{d.write(b);assertEquals(indexed?6:5,b.getUnsignedByte(0));b.writeInt(123);assertEquals(d,RollingSessionDescriptor.read(b,d.world(),palette));assertEquals(123,b.readInt());assertFalse(b.isReadable());}finally{b.release();}}}
    @Test void completeCatalogueCoversEveryAllowedCornerAndEndpoint(){for(var p:CoreGateProfile.values()){var d=descriptor(p,true);var c=certificate(d,1,1,"d".repeat(64));var w=d.windowEnvelope();for(int x:new int[]{w.minX(),w.maxX()})for(int y:new int[]{w.minY(),w.maxY()})for(int z:new int[]{w.minZ(),w.maxZ()})assertDoesNotThrow(()->RollingIndexedQueryGuard.require(d,c,new io.earthplatform.core.CubePos(x,y,z)));}}
    @Test void missingQueryCannotEnterIndexedHost(){var d=descriptor(CoreGateProfile.BASE,true);var c=certificate(d,1,1,"d".repeat(64));var old=new ReplacementSetCertificate(c.world(),c.epoch(),c.resourceGeneration(),c.storeIdentity(),c.indexIdentity(),c.providerIdentity(),c.paletteIdentity(),c.materialPolicy(),c.detailPolicy(),c.generation(),c.regions());assertThrows(IllegalArgumentException.class,()->RollingIndexedQueryGuard.require(d,old,d.center()));}
    @Test void indexedQueryCannotEnterLegacySession(){var d=descriptor(CoreGateProfile.BASE,true);var c=certificate(d,1,1,"d".repeat(64));assertThrows(IllegalArgumentException.class,()->RollingIndexedQueryGuard.require(descriptor(CoreGateProfile.BASE,false),c,d.center()));}
    @Test void attestationWireRoundTripIsLengthBoundedAndNoPartialRead(){var d=descriptor(CoreGateProfile.BASE,true);var q=certificate(d,1,1,"d".repeat(64)).queryAttestation();var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeQueryAttestation(b,q);assertTrue(b.readableBytes()<2048);b.writeInt(456);assertEquals(q,RollingGateWire.readQueryAttestation(b));assertEquals(456,b.readInt());}finally{b.release();}}
    @Test void mirrorRejectsIndexRootAbaAndUnannouncedRootAdvance(){var d=descriptor(CoreGateProfile.BASE,true);var c=certificate(d,1,1,"d".repeat(64));var m=new RollingReplacementSetAuthority(d.world(),1,1,d.workspaces(),d.sourceFingerprint(),palette.fingerprint(),c.materialPolicy(),c.detailPolicy(),RollingIndexedQueryGuard.policy(d),d.lodLayout().identity());m.propose(c);m.commit(c.identity());assertThrows(IllegalArgumentException.class,()->m.propose(certificate(d,2,1,"e".repeat(64))));assertThrows(IllegalArgumentException.class,()->m.propose(certificate(d,2,2,"e".repeat(64))));assertTrue(m.isCurrent(c.identity()));}
    @Test void diagnosticReceiptCannotCarryMalformedAuthorityOrPath(){assertThrows(IllegalArgumentException.class,()->new RollingScenarioReceiptPayload(1,1,1,0,0,"a".repeat(64),""));assertThrows(IllegalArgumentException.class,()->new RollingScenarioReceiptPayload(1,1,1,1,0,"a".repeat(64),"../escape"));}
}
