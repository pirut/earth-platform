package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeMiningControlJournalTest {
    @Test void nativeCracksRetainOrderAndOutOfRangeClearStage() {
        var journal=new NativeMiningControlJournal();var target=CubeCell.of(4,134,7);
        journal.crack(2,target,0);journal.crack(2,target,10);journal.crack(2,target,-1);
        var output=journal.seal();assertEquals(3,output.size());
        assertEquals(10,((NativeMiningControlJournal.Crack)output.get(1)).stage());
        assertEquals(-1,((NativeMiningControlJournal.Crack)output.get(2)).stage());
        assertThrows(UnsupportedOperationException.class,()->output.clear());
        assertThrows(SurvivalRefusal.class,()->journal.crack(2,target,3));
        assertThrows(SurvivalRefusal.class,journal::seal);
    }
    @Test void realNativeCrackCodecPreservesUnsignedByteNarrowing() {
        for(int stage:new int[]{10,-1,255,256,Integer.MIN_VALUE,Integer.MAX_VALUE})
            assertTrue(NativeMiningControlJournal.roundTripCrack(2,CubeCell.of(4,134,7),stage)>0);
    }
    @Test void noticesCannotExceedFixedReservation() {
        var journal=new NativeMiningControlJournal();
        for(int i=0;i<NativeMiningControlJournal.MAX_NOTICES;i++)journal.crack(2,CubeCell.of(i,134,7),i);
        assertThrows(SurvivalRefusal.class,()->journal.crack(2,CubeCell.of(9,134,7),9));
    }
    @Test void firstTransportExplicitlyRefusesPackedPositionAliases() {
        assertDoesNotThrow(()->NativeMiningControlJournal.requireWireCell(CubeCell.of(-33554432,-2048,33554431)));
        assertDoesNotThrow(()->NativeMiningControlJournal.requireWireCell(CubeCell.of(33554431,2047,-33554432)));
        for(var cell:java.util.List.of(CubeCell.of(0,-2049,0),CubeCell.of(0,2048,0),
                CubeCell.of(33554432,134,0),CubeCell.of(0,134,-33554433)))
            assertThrows(SurvivalRefusal.class,()->NativeMiningControlJournal.requireWireCell(cell));
    }
    @Test void nativeBlockResyncUsesExactGlobalStateCodecAndPackedPosition() {
        net.minecraft.data.registries.VanillaRegistries.createLookup();
        for(var state:java.util.List.of(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),
                net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())) {
            var journal=new NativeMiningControlJournal();var cell=CubeCell.of(-13640057,134,-4213188);
            journal.packet(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(NativeMiningControlState.pos(cell),state));
            assertEquals(java.util.List.of(new NativeMiningControlJournal.BlockResync(cell,net.minecraft.world.level.block.Block.getId(state))),journal.seal());
            assertTrue(journal.wireBytes()>0&&journal.wireBytes()<=64);
        }
        var journal=new NativeMiningControlJournal();
        assertThrows(SurvivalRefusal.class,()->journal.packet(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(
                new net.minecraft.core.BlockPos(0,2048,0),net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState())));
        assertEquals(0,journal.wireBytes());assertTrue(journal.seal().isEmpty());
    }
    @Test void unrecognizedPacketsNeverBecomeOutputEvidence() {
        var journal=new NativeMiningControlJournal();
        assertThrows(SurvivalRefusal.class,()->journal.packet(new Object()));
        assertThrows(SurvivalRefusal.class,()->journal.packet(null));
    }
}
