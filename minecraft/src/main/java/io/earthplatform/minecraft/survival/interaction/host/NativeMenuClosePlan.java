package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/** Genuine native fitting-close reconciliation followed by a private once-only committed close. */
final class NativeMenuClosePlan {
    private final NativeSessionMenuOwner owner;
    private final NativeMenuBudget budget;
    private final Object lineage;
    private final NativeWholeAction action;
    private final NativeSessionMenuBoundary before;
    private final NativeFirstLoopMetadata metadata;
    private final PinnedNativeEventAdmission events;
    private final NativeEffectCodec.SessionMenuClose effect;
    private final List<ClientboundContainerSetSlotPacket> packets;
    private final ServerPlayer player;
    private final AbstractContainerMenu menu;
    private final int reservedBytes;
    private NativeSurvivalWorld.CommitReceipt authorizedReceipt;
    private boolean attempted;
    static NativeMenuClosePlan prepare(NativeSessionMenuOwner owner,Object lineage,NativeWholeAction action,
            NativeSessionMenuBoundary before,NativeFirstLoopMetadata metadata,NativeMenuBudget budget) {
        try{return new NativeMenuClosePlan(owner,lineage,action,before,metadata,budget);}
        catch(RuntimeException|Error failure){if(action.state()==NativeWholeAction.State.PREPARING)action.poisonPreparation();throw failure;}
    }
    private NativeMenuClosePlan(NativeSessionMenuOwner owner,Object lineage,NativeWholeAction action,
            NativeSessionMenuBoundary before,NativeFirstLoopMetadata metadata,NativeMenuBudget budget) {
        this.budget=budget;this.owner=owner;this.lineage=lineage;this.action=action;this.before=before;this.metadata=metadata;
        player=owner.host().player();menu=before.activeMenu();events=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        owner.requirePreparing(action);events.requireEmpty(PlayerContainerEvent.Close.class);
        var result=NativeMenuClosePreparation.prepare(owner,action,before,metadata,budget);
        var journal=new NativeMenuCloseJournal(owner.host().session(),owner.revision(),before.data(),result.after(),result.packets());
        effect=new NativeEffectCodec.SessionMenuClose(action.world().world(),action.identity().ownerEpoch(),action.identity().actor(),action.identity().sequence(),journal);
        budget.reserve(2*io.earthplatform.minecraft.survival.world.NativeActionEffect.MAX_PAYLOAD_BYTES);
        if(!effect.equals(NativeEffectCodec.decode(NativeEffectCodec.encode(effect),player.registryAccess())))throw refused("Native close evidence changed during codec round trip");
        var output=new ArrayList<ClientboundContainerSetSlotPacket>(result.packets().size());
        for(var packet:result.packets()){
            budget.reserve(32768+128+packet.stack().encodedBytes()*2);
            var nativePacket=new ClientboundContainerSetSlotPacket(-2,0,packet.slot(),packet.stack().materialize(player.registryAccess()));
            NativeInventoryMenuPreparation.requireNativePacketRoundTrip(nativePacket,packet.stack(),player);output.add(nativePacket);
        }
        packets=List.copyOf(output);int retainedBytes=1024;
        for(var packet:result.packets())retainedBytes=Math.addExact(retainedBytes,256+packet.stack().encodedBytes()*2);
        reservedBytes=retainedBytes;before.requireUnchanged(metadata, budget);
    }
    NativeEffectCodec.SessionMenuClose effect(){return effect;}
    NativeSessionMenuBoundary.Data after(){return effect.journal().after();}
    List<NativeStackSnapshot> inventoryAfter(){return after().inventory();}
    int reservedDeliveryBytes(){return reservedBytes;}
    int reservedDeliveryOperations(){return packets.size()+1;}
    void requireBeforeCommit(NativeWholeAction exact,NativeEffectCodec.SessionMenuClose supplied){
        owner.requirePlan(this,lineage,exact,NativeWholeAction.State.PREVALIDATING,effect.journal().revision());
        if(exact!=action||attempted||authorizedReceipt!=null||!effect.equals(supplied))throw refused("Foreign native close plan");requireBefore();
    }
    void requireBeforeApply(NativeWholeAction exact,NativeSurvivalWorld.CommitReceipt receipt){
        owner.requirePlan(this,lineage,exact,NativeWholeAction.State.APPLYING,effect.journal().revision());
        if(exact!=action||attempted||authorizedReceipt!=null||!receipt.action().equals(action.identity())
                ||!receipt.world().equals(action.world().world())||!receipt.ownerEpoch().equals(action.world().ownerEpoch())
                ||!receipt.changedCubes().isEmpty()||receipt.commitSequence()!=action.world().commitSequence()
                ||!receipt.effects().contains(NativeEffectCodec.encode(effect)))throw refused("Native close lacks its exact committed receipt");
        requireBefore();authorizedReceipt=receipt;
    }
    private void requireBefore(){before.requireUnchanged(metadata, budget);before.requireConservedPlayer();metadata.revalidate();events.requireEmpty(PlayerContainerEvent.Close.class);}
    void apply(NativeSurvivalWorld.CommitReceipt receipt){
        owner.requirePlan(this,lineage,action,NativeWholeAction.State.APPLYING,effect.journal().revision());
        if(attempted||receipt!=authorizedReceipt)throw refused("Native close cannot replay or substitute a receipt");attempted=true;
        try{
            requireInventory();before.requireConservedPlayer();events.requireEmpty(PlayerContainerEvent.Close.class);
            // The genuine native detached removed call already assigned every owned cursor/grid
            // stack to these exact host41 slot effects. No item is discarded: only those consumed
            // source slots are now installed empty before the native terminal lifecycle call.
            menu.setCarried(ItemStack.EMPTY);
            var grid=NativeSessionMenuBoundary.read(net.minecraft.world.inventory.TransientCraftingContainer.class,NativeSessionMenuPreparation.grid(menu),"items",net.minecraft.core.NonNullList.class);for(int i=0;i<grid.size();i++)grid.set(i,ItemStack.EMPTY);
            if(menu==player.inventoryMenu)NativeSessionMenuPreparation.result(menu).setItem(0,ItemStack.EMPTY);
            NativeSessionMenuBoundary.write(Inventory.class,player.getInventory(),"timesChanged",int.class,after().inventoryChanges());
            for(var packet:packets){player.connection.send(packet);requireInventory();before.requireConservedPlayer();}
            player.doCloseContainer(); // Empty removed + exact native transferState + admitted empty Close event.
            NativeInventorySnapshot.requireResolvedMenu(player);requireInventory();before.requireConservedPlayer();metadata.revalidate();events.requireEmpty(PlayerContainerEvent.Close.class);
            var observed=NativeSessionMenuBoundary.capture(player,action.world(),null,metadata,budget.scratch());
            before.requireInventoryTopology(observed);
            if(!observed.data().equals(after()))throw refused("Native close reconciliation/cache result differs from complete prepared boundary");
            owner.closed(this,lineage,action,receipt,observed);
        }catch(RuntimeException|Error failure){owner.failed(this,lineage);throw failure;}
    }
    private void requireInventory(){
        if(player.getInventory().selected!=after().selected())throw refused("Native close selection changed");
        for(int i=0;i<41;i++)if(!after().inventory().get(i).matches(player.getInventory().getItem(i),player.registryAccess()))throw refused("Native close changed conserved inventory");
    }
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
