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

/** Pure projection/capacity checks. Actual native delivery is qualified only by transformed GameTests. */
class NativeResolvedInventoryMenuPlanTest {
    private static HolderLookup.Provider registries;
    private static NativeStackSnapshot empty, log;
    @BeforeAll static void setup() {
        registries = VanillaRegistries.createLookup(); empty = NativeStackSnapshot.capture(ItemStack.EMPTY,registries);
        log = NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG),registries);
    }
    private static NativeInventoryMenuSnapshot.Data data(List<NativeStackSnapshot> inventory, List<NativeStackSnapshot> grid,
            NativeStackSnapshot result, NativeStackSnapshot carried, int state, List<NativeStackSnapshot> last,
            List<NativeStackSnapshot> remote, NativeStackSnapshot remoteCarried, boolean suppress) {
        return new NativeInventoryMenuSnapshot.Data(inventory,0,7,grid,result,carried,null,0,state,last,remote,remoteCarried,suppress,-1);
    }
    private static NativeInventoryMenuSnapshot.Data emptyData() {
        return data(Collections.nCopies(41,empty),Collections.nCopies(4,empty),empty,empty,0,
                Collections.nCopies(46,empty),Collections.nCopies(46,empty),empty,false);
    }
    @Test void exactNativeMenuToInventoryIndicesIncludeArmorHotbarAndOffhand() {
        for (int i=5;i<=8;i++) assertEquals(44-i,NativeInventoryMenuBroadcastAdmission.inventoryIndex(i));
        for (int i=9;i<=35;i++) assertEquals(i,NativeInventoryMenuBroadcastAdmission.inventoryIndex(i));
        for (int i=36;i<=44;i++) assertEquals(i-36,NativeInventoryMenuBroadcastAdmission.inventoryIndex(i));
        assertEquals(40,NativeInventoryMenuBroadcastAdmission.inventoryIndex(45));
        for (int i:new int[]{-1,0,1,2,3,4,46,Integer.MAX_VALUE}) assertThrows(SurvivalRefusal.class,
                () -> NativeInventoryMenuBroadcastAdmission.inventoryIndex(i));
    }
    @Test void unchangedResolvedBroadcastDoesNotInventPacketsOrStateTransitions() {
        var before = emptyData(); assertEquals(before,NativeResolvedInventoryMenuPlan.expectedBroadcast(before));
        assertDoesNotThrow(() -> NativeResolvedInventoryMenuPlan.requireResolved(before));
    }
    @Test void finalBroadcastUsesOldRemoteCachesAndWrapsNativeStateId() {
        var inventory = new ArrayList<>(Collections.nCopies(41,empty)); inventory.set(0,log); inventory.set(40,log);
        var after = data(inventory,Collections.nCopies(4,empty),empty,empty,32767,
                Collections.nCopies(46,empty),Collections.nCopies(46,empty),empty,false);
        var broadcast = NativeResolvedInventoryMenuPlan.expectedBroadcast(after);
        assertEquals(1,broadcast.stateId()); assertEquals(log,broadcast.lastSlots().get(36));
        assertEquals(log,broadcast.remoteSlots().get(45)); assertEquals(broadcast.lastSlots(),broadcast.remoteSlots());
        assertEquals(after.inventory(),broadcast.inventory()); assertEquals(7,broadcast.inventoryChanges());
    }
    @Test void changedLastSlotsAloneDoNotSendRemotePackets() {
        var inventory = new ArrayList<>(Collections.nCopies(41,empty)); inventory.set(0,log);
        var remote = new ArrayList<>(Collections.nCopies(46,empty)); remote.set(36,log);
        var after = data(inventory,Collections.nCopies(4,empty),empty,empty,17,
                Collections.nCopies(46,empty),remote,empty,false);
        var broadcast = NativeResolvedInventoryMenuPlan.expectedBroadcast(after);
        assertEquals(17,broadcast.stateId()); assertEquals(remote,broadcast.lastSlots());
    }
    @Test void unresolvedGridResultCursorOrSuppressedRemoteAlwaysRefuses() {
        var d=emptyData();
        var grid=new ArrayList<>(d.grid());grid.set(2,log);
        for (var invalid:List.of(
                data(d.inventory(),grid,empty,empty,0,d.lastSlots(),d.remoteSlots(),empty,false),
                data(d.inventory(),d.grid(),log,empty,0,d.lastSlots(),d.remoteSlots(),empty,false),
                data(d.inventory(),d.grid(),empty,log,0,d.lastSlots(),d.remoteSlots(),empty,false),
                data(d.inventory(),d.grid(),empty,empty,0,d.lastSlots(),d.remoteSlots(),empty,true)))
            assertThrows(SurvivalRefusal.class,() -> NativeResolvedInventoryMenuPlan.requireResolved(invalid));
    }
    @Test void deliveryPayloadChargeRefusesOverflowAndNeverUndercharges() {
        assertEquals(10,NativeInventoryMenuBroadcastAdmission.checkedCharge(7,3,10));
        assertThrows(SurvivalRefusal.class,() -> NativeInventoryMenuBroadcastAdmission.checkedCharge(7,4,10));
        assertThrows(SurvivalRefusal.class,() -> NativeInventoryMenuBroadcastAdmission.checkedCharge(Integer.MAX_VALUE,1,Integer.MAX_VALUE));
        assertThrows(SurvivalRefusal.class,() -> NativeInventoryMenuBroadcastAdmission.checkedCharge(0,-1,10));
    }
}
