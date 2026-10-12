package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.NativeAlpineCubeProvider;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent pure policy checks. These tests neither prepare Earth data nor launch a client. */
class EarthEntryIndependentReviewTest {
    private static EnginePaletteContext palette;
    private static WorldIdentity world;

    @BeforeAll static void setup() {
        var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        biomes.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(biomes.freeze());
        world=new WorldIdentity(EarthAlpineDefinition.SEED,NativeAlpineCubeProvider.GENERATOR_ID,
                NativeAlpineCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION,
                EarthAlpineDefinition.DEFINITION_IDENTITY,NativeCubeCodec.paletteIdentity(palette));
    }

    private static RollingSessionDescriptor descriptor(WorldIdentity w) {
        return RollingSessionDescriptor.alpine(w,palette,EarthAlpineDefinition.ANCHOR_X,
                Math.toIntExact(EarthAlpineDefinition.GROUND_Y),EarthAlpineDefinition.ANCHOR_Z);
    }

    @Test void exactAlpineDescriptorRoundTripsWithoutSourcePreparation() {
        var expected=descriptor(world);
        var wire=new FriendlyByteBuf(Unpooled.buffer());
        try {
            expected.write(wire);
            assertEquals(expected,RollingSessionDescriptor.read(wire,world,palette));
            assertFalse(wire.isReadable());
        } finally { wire.release(); }
        assertTrue(expected.alpineMode());assertTrue(expected.playerFacing());
        assertFalse(expected.arenaMode());assertTrue(expected.naturalRelief());
        assertEquals(RollingEditPolicy.INTERIOR_V1,expected.editPolicy());
        assertFalse(expected.twoWorkspaces());assertFalse(expected.adjacentWorkspaces());assertFalse(expected.indexedWorkspaces());
    }

    @Test void sourceContractRejectsIndependentlyChangedSeedGeneratorVersionDatasetAndPalette() {
        for(var invalid:List.of(
                new WorldIdentity(world.seed()+1,world.generatorId(),world.generatorVersion(),world.datasetVersion(),world.blockPaletteVersion()),
                new WorldIdentity(world.seed(),"earthplatform:other",world.generatorVersion(),world.datasetVersion(),world.blockPaletteVersion()),
                new WorldIdentity(world.seed(),world.generatorId(),NativeAlpineCubeProvider.GENERATOR_VERSION,world.datasetVersion(),world.blockPaletteVersion()),
                new WorldIdentity(world.seed(),world.generatorId(),world.generatorVersion(),"0".repeat(64),world.blockPaletteVersion()),
                new WorldIdentity(world.seed(),world.generatorId(),world.generatorVersion(),world.datasetVersion(),"foreign-palette")))
            assertThrows(IllegalArgumentException.class,()->descriptor(invalid),invalid.toString());
    }

    @Test void exactAnchorAndMetreHeightAreRequiredRatherThanSilentlyAdjusted() {
        long x=EarthAlpineDefinition.ANCHOR_X,z=EarthAlpineDefinition.ANCHOR_Z;
        int y=Math.toIntExact(EarthAlpineDefinition.GROUND_Y);
        for(long[] anchor:new long[][]{{x+1,y,z},{x,y+1,z},{x,y,z-1}})
            assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.alpine(world,palette,
                    anchor[0],Math.toIntExact(anchor[1]),anchor[2]));
        var d=descriptor(world);
        assertEquals(y,d.anchorY());assertEquals(y+1.01,d.entry().y,1e-10);
        assertEquals(EarthAlpineDefinition.ENTRY_YAW,RollingGateCommands.entryYaw(d));
        assertEquals(EarthAlpineDefinition.ENTRY_PITCH,RollingGateCommands.entryPitch(d));
    }

    @Test void finiteTravelIncludesWholeBodyAndEveryCornerWindow() {
        var d=descriptor(world);var b=d.bodyEnvelope();var w=d.windowEnvelope();
        assertEquals(512,b.maxX()-b.minX());assertEquals(512,b.maxZ()-b.minZ());
        assertTrue(d.containsBody(d.walkEntryBody()));
        for(int x:new int[]{w.minX(),w.maxX()})for(int y:new int[]{w.minY(),w.maxY()})for(int z:new int[]{w.minZ(),w.maxZ()})
            assertDoesNotThrow(()->d.requireAllowedWindow(new CubePos(x,y,z)));
        for(var bad:List.of(new CubePos(w.minX()-1,w.minY(),w.minZ()),new CubePos(w.maxX()+1,w.minY(),w.minZ()),
                new CubePos(w.minX(),w.minY()-1,w.minZ()),new CubePos(w.minX(),w.maxY()+1,w.minZ()),
                new CubePos(w.minX(),w.minY(),w.minZ()-1),new CubePos(w.minX(),w.minY(),w.maxZ()+1)))
            assertThrows(IllegalArgumentException.class,()->d.requireAllowedWindow(bad));
        double y=d.entry().y,z=d.entry().z;
        assertTrue(d.containsBody(new AABB(b.minX(),y,z,b.minX()+.6,y+1.8,z+.6)));
        assertFalse(d.containsBody(new AABB(b.minX()-.01,y,z,b.minX()+.59,y+1.8,z+.6)));
        assertFalse(d.containsBody(new AABB(b.maxX()-.59,y,z,b.maxX()+.01,y+1.8,z+.6)));
    }

    @Test void onlyOneOriginalFourteenCubedInteriorIsEditable() {
        var d=descriptor(world);var pin=d.pinCenter();int admitted=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            boolean interior=x>0&&x<15&&y>0&&y<15&&z>0&&z<15;
            assertEquals(interior,d.permitsEdit(pin.minBlockX()+x,pin.minBlockY()+y,pin.minBlockZ()+z));
            if(interior)admitted++;
        }
        assertEquals(14*14*14,admitted);
        assertFalse(d.permitsEdit(pin.offset(1,0,0),pin.minBlockX()+4,pin.minBlockY()+4,pin.minBlockZ()+4));
        assertFalse(d.permitsEdit(pin.minBlockX()+20,pin.minBlockY()+4,pin.minBlockZ()+4));
    }

    @Test void newEarthStoreNeverAliasesTheAcceptedArenaOrDiagnosticNamespace() {
        var d=descriptor(world);
        assertTrue(d.saveDirectory().matches("earthplatform-everest-v1/[0-9a-f]{64}"));
        assertNotEquals(RollingSessionDescriptor.arena(palette).saveDirectory(),d.saveDirectory());
        assertNotEquals(RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette).saveDirectory(),d.saveDirectory());
    }
}
