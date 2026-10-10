package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreGateProfileTest {
    private static EnginePaletteContext palette;
    @BeforeAll static void registry(){var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());biomes.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(biomes.freeze());}
    @Test void endpointInterestsRemainExact125And27With18InDomainMasks(){for(var p:CoreGateProfile.values()){assertEquals(125,p.owners().size());assertEquals(27,p.meshTargets().size());assertEquals(p==CoreGateProfile.BASE?27:18,p.distantMaskTargets().size());assertTrue(p.owners().containsAll(p.meshTargets()));assertTrue(p.meshTargets().contains(p.target()));for(var cube:p.owners()){assertTrue(cube.minBlockY()>=p.renderMinY());assertTrue(cube.minBlockY()+16<=p.renderMaxYExclusive());}for(var cube:p.distantMaskTargets())assertTrue(p.distantMaskEligible(cube));}}
    @Test void anchorsAreRealSignedCoordinatesNotDisplayOffsets(){var low=CoreGateProfile.ABYSS;var high=CoreGateProfile.SUMMIT;assertEquals(-688,low.center().y());assertEquals(553,high.center().y());assertEquals(-10999,low.commandEdit().getY());assertEquals(8850,high.commandEdit().getY());assertEquals(-10997,low.entry().y);assertEquals(8852,high.entry().y);assertEquals(-11040,low.owners().getFirst().minBlockY());assertEquals(8896,high.owners().stream().mapToLong(p->p.minBlockY()+16).max().orElseThrow());}
    @Test void signedTopPagesMatchUnchangedDhPageConvention(){for(var p:new CoreGateProfile[]{CoreGateProfile.ABYSS,CoreGateProfile.SUMMIT}){long page=Math.floorDiv(p.anchorY(),2048),local=p.anchorY()+1-page*2048;assertEquals(p==CoreGateProfile.ABYSS?-6:4,page);assertEquals(p==CoreGateProfile.ABYSS?1289:658,local);}}
    @Test void realPaddingDoesNotWidenGameplayOrEditableCube(){for(var p:CoreGateProfile.values()){assertFalse(p.gameplayContains(-11009));assertTrue(p.gameplayContains(-11008));assertTrue(p.gameplayContains(8863));assertFalse(p.gameplayContains(8864));assertEquals(p.target(),CubePos.fromBlock(p.commandEdit().getX(),p.commandEdit().getY(),p.commandEdit().getZ()));assertTrue(CubePos.local(p.anchorY())+4<16);}}
    @Test void eachProfileHasDistinctProviderIdentityDescriptorAndStore(){var worlds=new HashSet<WorldIdentity>();var fingerprints=new HashSet<String>();var directories=new HashSet<String>();for(var p:CoreGateProfile.values()){worlds.add(p.world(palette));fingerprints.add(p.fingerprint(palette));directories.add(p.saveDirectory());assertEquals(64,p.fingerprint(palette).length());}assertEquals(3,worlds.size());assertEquals(3,fingerprints.size());assertEquals(3,directories.size());assertFalse(directories.contains("earthplatform-core-collision-gate"));}
    @Test void boundedWireRoundTripsAndLeavesEnclosingFieldsUntouched(){for(var p:CoreGateProfile.values()){var b=new FriendlyByteBuf(Unpooled.buffer());try{p.write(b,palette);int length=b.readableBytes();assertTrue(length<=90);b.writeLong(87654321);assertEquals(p,CoreGateProfile.read(b,palette));assertEquals(87654321,b.readLong());assertFalse(b.isReadable());}finally{b.release();}}}
    @Test void rejectsUnknownVersionProfileAnchorAndForgedPolicyFingerprint(){assertThrows(IllegalArgumentException.class,()->CoreGateProfile.byId("SUMMIT"));assertThrows(IllegalArgumentException.class,()->CoreGateProfile.atAnchor(8850));for(int variant=0;variant<3;variant++){var b=new FriendlyByteBuf(Unpooled.buffer());try{b.writeVarInt(variant==0?2:1);b.writeUtf(variant==1?"unknown":"base");b.writeUtf("0".repeat(64));assertThrows(IllegalArgumentException.class,()->CoreGateProfile.read(b,palette));}finally{b.release();}}}
    @Test void all65NonflatCellsStayInsideCentralCubeAtEveryAnchor(){for(var p:CoreGateProfile.values()){var provider=p.provider(palette);int extra=0;for(var pos:p.meshTargets()){var blocks=provider.load(pos,new CancellationToken()).join();for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(pos.minBlockY()+y>p.anchorY()&&blocks.get(CubePos.index(x,y,z))==provider.config().solidState()){assertEquals(p.target(),pos);extra++;}}assertEquals(65,extra);}}
}
