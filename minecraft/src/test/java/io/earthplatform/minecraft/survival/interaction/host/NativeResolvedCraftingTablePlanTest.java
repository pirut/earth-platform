package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure 3×3 shape/cache projections; no claim of interactive client menu ownership. */
class NativeResolvedCraftingTablePlanTest {
    private static HolderLookup.Provider registries;
    private static NativeStackSnapshot empty,log;
    @BeforeAll static void setup() {
        registries=VanillaRegistries.createLookup(); empty=NativeStackSnapshot.capture(ItemStack.EMPTY,registries);
        log=NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG),registries);
    }
    private static NativeCraftingTableSnapshot.Data data(List<NativeStackSnapshot> inventory,List<NativeStackSnapshot> grid,
            NativeStackSnapshot result,NativeStackSnapshot carried,int state,List<NativeStackSnapshot> last,
            List<NativeStackSnapshot> remote,NativeStackSnapshot remoteCarried,boolean suppressed) {
        return new NativeCraftingTableSnapshot.Data(1,inventory,0,7,grid,result,carried,null,0,state,last,remote,remoteCarried,suppressed,-1);
    }
    private static NativeCraftingTableSnapshot.Data emptyData() {
        return data(Collections.nCopies(41,empty),Collections.nCopies(9,empty),empty,empty,1,
                Collections.nCopies(46,empty),Collections.nCopies(46,empty),empty,false);
    }
    private static NativeInventoryMenuSnapshot.Data inventoryData(List<NativeStackSnapshot> last,List<NativeStackSnapshot> remote) {
        return new NativeInventoryMenuSnapshot.Data(Collections.nCopies(41,empty),0,3,Collections.nCopies(4,empty),empty,empty,null,0,
                99,last,remote,empty,false,-1);
    }
    @Test void tableIndicesNeverAliasInventoryMenuArmorOrOffhand() {
        var indices=new java.util.HashSet<Integer>();
        for (int i=10;i<=36;i++) { assertEquals(i-1,NativeCraftingTableBroadcastAdmission.inventoryIndex(i)); indices.add(i-1); }
        for (int i=37;i<=45;i++) { assertEquals(i-37,NativeCraftingTableBroadcastAdmission.inventoryIndex(i)); indices.add(i-37); }
        assertEquals(36,indices.size()); for (int i=0;i<36;i++) assertTrue(indices.contains(i));
        for (int i:new int[]{-1,0,1,9,46,Integer.MAX_VALUE}) assertThrows(SurvivalRefusal.class,()->NativeCraftingTableBroadcastAdmission.inventoryIndex(i));
    }
    @Test void nineGridSlotsAndPositiveContainerAreDistinctFromInventoryMenuImage() {
        var d=emptyData(); assertEquals(9,d.grid().size()); assertEquals(1,d.containerId());
        assertThrows(IllegalArgumentException.class,()->new NativeCraftingTableSnapshot.Data(0,d.inventory(),0,7,d.grid(),empty,empty,null,0,1,d.lastSlots(),d.remoteSlots(),empty,false,-1));
        assertThrows(IllegalArgumentException.class,()->new NativeCraftingTableSnapshot.Data(1,d.inventory(),0,7,Collections.nCopies(4,empty),empty,empty,null,0,1,d.lastSlots(),d.remoteSlots(),empty,false,-1));
    }
    @Test void nativeContainerCounterWrapIsBounded() {
        assertEquals(1,NativeTableMenuLifecycleAdmission.nextCounter(0)); assertEquals(100,NativeTableMenuLifecycleAdmission.nextCounter(99));
        assertEquals(1,NativeTableMenuLifecycleAdmission.nextCounter(100));
        assertThrows(SurvivalRefusal.class,()->NativeTableMenuLifecycleAdmission.nextCounter(-1));
        assertThrows(SurvivalRefusal.class,()->NativeTableMenuLifecycleAdmission.nextCounter(101));
    }
    @Test void unchangedTableBroadcastPreservesInitializedStateId() {
        var d=emptyData(); assertEquals(d,NativeResolvedCraftingTablePlan.expectedBroadcast(d));
        assertDoesNotThrow(()->NativeResolvedCraftingTablePlan.requireResolved(d));
    }
    @Test void broadcastWrapsOnlyNativeChangedTableRemoteSlots() {
        var inv=new ArrayList<>(Collections.nCopies(41,empty)); inv.set(0,log); inv.set(9,log); inv.set(40,log);
        var d=data(inv,Collections.nCopies(9,empty),empty,empty,32767,Collections.nCopies(46,empty),Collections.nCopies(46,empty),empty,false);
        var output=NativeResolvedCraftingTablePlan.expectedBroadcast(d);
        assertEquals(1,output.stateId()); assertEquals(log,output.remoteSlots().get(37)); assertEquals(log,output.remoteSlots().get(10));
        assertEquals(empty,output.remoteSlots().get(45)); assertEquals(log,output.inventory().get(40));
    }
    @Test void closeTransfersOnlyMatchingNativeInventoryContainerSlots() {
        var old=new ArrayList<>(Collections.nCopies(46,empty)); old.set(5,log); old.set(45,log);
        var inventory=inventoryData(old,old);
        var inv=new ArrayList<>(Collections.nCopies(41,empty));inv.set(0,log);inv.set(35,log);
        var table=NativeResolvedCraftingTablePlan.expectedBroadcast(data(inv,Collections.nCopies(9,empty),empty,empty,1,
                Collections.nCopies(46,empty),Collections.nCopies(46,empty),empty,false));
        var output=NativeResolvedCraftingTablePlan.expectedClose(inventory,table);
        assertEquals(99,output.stateId());assertEquals(7,output.inventoryChanges());
        assertEquals(log,output.lastSlots().get(36));assertEquals(log,output.lastSlots().get(35));
        assertEquals(log,output.lastSlots().get(5));assertEquals(log,output.lastSlots().get(45));
        assertEquals(output.lastSlots(),output.remoteSlots()); assertEquals(inventory.grid(),output.grid());
    }
    @Test void unresolvedTableOwnershipCannotCrossCompositeBoundary() {
        var d=emptyData();var grid=new ArrayList<>(d.grid());grid.set(8,log);
        for (var invalid:List.of(data(d.inventory(),grid,empty,empty,1,d.lastSlots(),d.remoteSlots(),empty,false),
                data(d.inventory(),d.grid(),log,empty,1,d.lastSlots(),d.remoteSlots(),empty,false),
                data(d.inventory(),d.grid(),empty,log,1,d.lastSlots(),d.remoteSlots(),empty,false),
                data(d.inventory(),d.grid(),empty,empty,1,d.lastSlots(),d.remoteSlots(),empty,true)))
            assertThrows(SurvivalRefusal.class,()->NativeResolvedCraftingTablePlan.requireResolved(invalid));
    }
    @Test void allTableGridAndPlayerSlotsAllowOnlyInitialPickupMode() {
        for (int i=0;i<46;i++) NativeCraftingTablePreparation.requireClick(new NativeInventoryMenuPreparation.Click(i,0,net.minecraft.world.inventory.ClickType.PICKUP));
        for (var click:List.of(new NativeInventoryMenuPreparation.Click(46,0,net.minecraft.world.inventory.ClickType.PICKUP),
                new NativeInventoryMenuPreparation.Click(0,2,net.minecraft.world.inventory.ClickType.PICKUP),
                new NativeInventoryMenuPreparation.Click(0,0,net.minecraft.world.inventory.ClickType.QUICK_MOVE)))
            assertThrows(SurvivalRefusal.class,()->NativeCraftingTablePreparation.requireClick(click));
    }
}
