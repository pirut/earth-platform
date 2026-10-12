package io.earthplatform.minecraft.survival.gametest;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.host.*;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.earthplatform.minecraft.survival.gametest.NativeSurvivalTestFixture.require;

/** Supplied component inputs qualify real packet/cursor ownership, not earned-material progression. */
@GameTestHolder("earthplatform_menu_fixture")
@PrefixGameTestTemplate(false)
public final class NativeMenuSessionGameTests {
    private NativeMenuSessionGameTests() {}
    private static NativeFirstLoopMetadata.Limits metadataLimits() { return new NativeFirstLoopMetadata.Limits(4096,16384,8192,65536,128,128,1); }
    private static NativeCraftingRecipeAdmission.Limits recipeLimits() { return new NativeCraftingRecipeAdmission.Limits(1024,16384,16,131072,4*1024*1024,131072); }
    private static NativeInventoryMenuPreparation.Limits limits() { return new NativeInventoryMenuPreparation.Limits(1,256,256*1024,16*1024*1024); }
    private static void setup(NativeSurvivalTestFixture fixture, boolean bound) throws IOException {
        var log=fixture.nearestNaturalLog();fixture.beginNativeCarrierLoadForDryItemTicks(log);fixture.awaitNativeCarrierLoad();
        if(bound)fixture.bindNativeDeliveryForEarnedMenu();
        NativeFirstLoopMetadata.capture(fixture.player,metadataLimits());
        fixture.player.initInventoryMenu();
        if(bound) {
            boolean found=false;
            for(int dx=-4;dx<=4&&!found;dx++)for(int dz=-4;dz<=4&&!found;dz++) {
                var ground=new net.minecraft.core.BlockPos((int)log.x()+dx,(int)log.y()-1,(int)log.z()+dz);
                if(fixture.level.getBlockState(ground).is(Blocks.GRASS_BLOCK)&&fixture.level.getBlockState(ground.above()).isAir()&&fixture.level.getBlockState(ground.above(2)).isAir()) {
                    fixture.player.moveTo(ground.getX()+0.5,ground.getY()+1,ground.getZ()+0.5,0,0);
                    fixture.player.setDeltaMovement(Vec3.ZERO);fixture.player.fallDistance=0;fixture.player.setOnGround(true);found=true;
                }
            }
            require(found&&fixture.player.mainSupportingBlockPos.isPresent(),"Menu component lacks actual native dry support");
        }
        discard(fixture);
        if(bound)fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
    }
    private static ServerboundContainerClickPacket packet(NativeSurvivalTestFixture fixture,int slot,int button,boolean stale) {
        int state=fixture.player.containerMenu.getStateId();if(stale)state=(state+32766)&32767;
        // Deliberately empty/wrong prediction metadata; server must never grant it authority.
        return new ServerboundContainerClickPacket(fixture.player.containerMenu.containerId,state,slot,button,ClickType.PICKUP,
                ItemStack.EMPTY,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
    }
    private static void click(NativeSurvivalTestFixture fixture,int slot,int button,boolean stale) {
        fixture.player.connection.handleContainerClick(packet(fixture,slot,button,stale));
        requireNoPendingFailure(fixture,"click slot="+slot+" button="+button);
    }
    private static void close(NativeSurvivalTestFixture fixture) {
        fixture.player.connection.handleContainerClose(new ServerboundContainerClosePacket(fixture.player.containerMenu.containerId));
        requireNoPendingFailure(fixture,"close");
    }
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void realMenuPacketsRetainCursorAndMatchNativeReferenceComponent(GameTestHelper helper)throws IOException {
        try(var owned=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel());var reference=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            setup(owned,true);setup(reference,false);
            requireForeignReceiverRefusedBeforeScheduling(owned,reference);
            owned.player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,2));reference.player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,2));
            owned.player.inventoryMenu.broadcastChanges();reference.player.inventoryMenu.broadcastChanges();discard(owned);discard(reference);
            Object exactMenu=owned.player.inventoryMenu;long start=owned.world.commitSequence();
            int[][] clicks={{36,0},{1,1},{36,0},{0,0},{37,0},{36,0},{1,0},{0,0},{37,0},
                    {37,0},{1,1},{3,1},{37,0},{0,0},{38,0},{37,0},{1,1},{2,1},{3,1},{4,1},{37,0},{0,0},{39,0}};
            for(int i=0;i<clicks.length;i++) {
                boolean stale=i%3==1;click(owned,clicks[i][0],clicks[i][1],stale);click(reference,clicks[i][0],clicks[i][1],stale);
                require(owned.world.commitSequence()==start+i+1,"Real native click did not commit exactly one private packet action");
                require(owned.player.inventoryMenu==exactMenu&&owned.player.containerMenu==exactMenu,"Native menu was recreated between real clicks");
                require(NativeInventoryMenuSnapshot.captureLive(owned.player).equals(NativeInventoryMenuSnapshot.captureLive(reference.player)),"Native per-packet grid/cursor/cache/state differs from original handler at "+i);
                require(drain(owned).equals(drain(reference)),"Native per-packet output ordering differs at "+i);
                require(owned.player.getRecipeBook().toNbt().equals(reference.player.getRecipeBook().toNbt())&&owned.nativeAdvancementState().equals(reference.nativeAdvancementState()),"Native per-packet recipe/criterion metadata differs at "+i);
                boolean refused=false;try{owned.delivery.captureCheckpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);}catch(SurvivalRefusal expected){refused=true;}
                require(refused,"A retained native menu reported durable checkpoint success");
            }
            close(owned);close(reference);
            require(NativeInventoryMenuSnapshot.captureLive(owned.player).equals(NativeInventoryMenuSnapshot.captureLive(reference.player))&&drain(owned).equals(drain(reference)),"Genuine native close differs from original removed/cache transfer");
            require(!owned.delivery.hasRetainedMenuSession(),"Resolved native close did not release the retained menu owner");
            var checkpoint=owned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            require(checkpoint.boundary().inventory().equals(NativeInventorySnapshot.capture(owned.player)),"Resolved post-menu checkpoint lost native inventory");
            owned.saveAndVerifyNativePlayerFiles();
        } catch(Exception|Error failure) { traceNativeMenuFailure("realMenuPacketsRetainCursorAndMatchNativeReferenceComponent",failure);throw failure; }
        helper.succeed();
    }
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void retainedNativeTableOpenAndRealPickaxePacketsComponent(GameTestHelper helper)throws IOException {
        try(var owned=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel());var reference=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            setup(owned,true);setup(reference,false);
            var support=owned.player.mainSupportingBlockPos.orElseThrow();CubeCell table=null;
            for(var direction:List.of(net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.SOUTH,net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.WEST)) {
                var position=support.relative(direction).above();
                if(owned.level.getBlockState(position).isAir()&&owned.level.getBlockState(position.below()).is(Blocks.GRASS_BLOCK)){table=CubeCell.of(position.getX(),position.getY(),position.getZ());break;}
            }
            require(table!=null,"Table component lacks an actual reachable dry site");var pos=new net.minecraft.core.BlockPos((int)table.x(),(int)table.y(),(int)table.z());
            // Explicit component-only placed block/materials. The separate earned route must obtain these natively.
            try(var action=owned.beginNextDeclared()){action.set(table,Blocks.CRAFTING_TABLE.defaultBlockState());action.commit();}
            reference.level.setBlock(pos,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            reference.player.moveTo(owned.player.getX(),owned.player.getY(),owned.player.getZ(),owned.player.getYRot(),owned.player.getXRot());
            for(var fixture:List.of(owned,reference)){fixture.player.getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,3));fixture.player.getInventory().setItem(1,new ItemStack(Items.STICK,2));fixture.player.inventoryMenu.broadcastChanges();discard(fixture);}
            owned.delivery.dispatchMenuOpen(table,metadataLimits(),recipeLimits(),limits().menuSnapshotBytes());
            reference.level.getBlockState(pos).useWithoutItem(reference.level,reference.player,new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false));
            Object menu=owned.player.containerMenu;
            require(menu instanceof net.minecraft.world.inventory.CraftingMenu&&owned.delivery.hasRetainedMenuSession(),"Native open did not retain its actual CraftingMenu");
            require(NativeCraftingTableSnapshot.captureLive(owned.player).equals(NativeCraftingTableSnapshot.captureLive(reference.player))&&drain(owned).equals(drain(reference)),"Retained native table open differs from original native use");
            int[][] clicks={{37,0},{1,1},{2,1},{3,1},{37,0},{38,0},{5,1},{8,1},{38,0},{0,0},{39,0}};
            for(int i=0;i<clicks.length;i++){
                click(owned,clicks[i][0],clicks[i][1],i%2==0);click(reference,clicks[i][0],clicks[i][1],i%2==0);
                require(owned.player.containerMenu==menu,"Native CraftingMenu was recreated between packet receipts");
                require(NativeCraftingTableSnapshot.captureLive(owned.player).equals(NativeCraftingTableSnapshot.captureLive(reference.player))&&drain(owned).equals(drain(reference)),"Native retained table click diverged at "+i);
            }
            close(owned);close(reference);
            require(NativeInventoryMenuSnapshot.captureLive(owned.player).equals(NativeInventoryMenuSnapshot.captureLive(reference.player))&&drain(owned).equals(drain(reference)),"Native retained table close lost inactive inventory caches");
            require(owned.player.getInventory().getItem(2).is(Items.WOODEN_PICKAXE)&&owned.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED,Items.WOODEN_PICKAXE)==1,"Real table packets lost exact native pickaxe/statistic");
            owned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);owned.saveAndVerifyNativePlayerFiles();
        } catch(Exception|Error failure) { traceNativeMenuFailure("retainedNativeTableOpenAndRealPickaxePacketsComponent",failure);throw failure; }
        helper.succeed();
    }
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void capacityRefusalKeepsBeforeImageAndResolvingClickComponent(GameTestHelper helper)throws IOException {
        try(var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            setup(fixture,true);
            for(int i=0;i<36;i++)fixture.player.getInventory().setItem(i,new ItemStack(Items.OAK_LOG,64));
            fixture.player.getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,64));fixture.player.inventoryMenu.broadcastChanges();discard(fixture);
            click(fixture,36,0,false);click(fixture,1,1,false);click(fixture,3,1,false);click(fixture,36,0,false);discard(fixture);
            var before=NativeInventoryMenuSnapshot.captureLive(fixture.player);long commits=fixture.world.commitSequence();
            require(fixture.player.inventoryMenu.getSlot(0).getItem().is(Items.STICK),"Capacity test did not reach the genuine native stick preview");
            click(fixture,0,0,false); // Output4 sticks cannot fit; genuine native capacity proof reaches refused toss.
            require(commits==fixture.world.commitSequence()&&before.equals(NativeInventoryMenuSnapshot.captureLive(fixture.player))&&!fixture.delivery.usage().failedDelivery(),"Capacity refusal mutated/fenced accepted native ownership");
            click(fixture,1,0,true);click(fixture,36,0,false);close(fixture);
            require(fixture.player.getInventory().getItem(0).getCount()==64&&fixture.player.getInventory().getItem(0).is(Items.OAK_PLANKS)
                    &&fixture.player.inventoryMenu.getCraftSlots().isEmpty()&&fixture.player.inventoryMenu.getCarried().isEmpty(),"Resolving click/native close failed to conserve full inventory after refusal");
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
        } catch(Exception|Error failure) { traceNativeMenuFailure("capacityRefusalKeepsBeforeImageAndResolvingClickComponent",failure);throw failure; }
        helper.succeed();
    }
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void forgedObserverAndPredictionCollectionsRefuseBeforeCallbacksComponent(GameTestHelper helper)throws Exception {
        try(var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            setup(fixture,true);fixture.player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG));fixture.player.inventoryMenu.broadcastChanges();discard(fixture);
            var before=NativeInventoryMenuSnapshot.captureLive(fixture.player);long commits=fixture.world.commitSequence();
            Field field=AbstractContainerMenu.class.getDeclaredField("containerListeners");require(field.trySetAccessible(),"Native observer field is unavailable");
            Object original=field.get(fixture.player.inventoryMenu);var poisoned=new PoisonList();field.set(fixture.player.inventoryMenu,poisoned);
            boolean refused=false;try{fixture.delivery.dispatchMenuPacket(packet(fixture,36,0,false),metadataLimits(),recipeLimits(),limits());}catch(SurvivalRefusal expected){refused=true;}finally{field.set(fixture.player.inventoryMenu,original);}
            require(refused&&poisoned.calls==0&&commits==fixture.world.commitSequence()&&before.equals(NativeInventoryMenuSnapshot.captureLive(fixture.player)),"Foreign observer collection executed before admission");
            var map=new PoisonMap();var packet=new ServerboundContainerClickPacket(0,fixture.player.inventoryMenu.getStateId(),36,0,ClickType.PICKUP,ItemStack.EMPTY,map);
            refused=false;try{fixture.delivery.dispatchMenuPacket(packet,metadataLimits(),recipeLimits(),limits());}catch(SurvivalRefusal expected){refused=true;}
            require(refused&&map.calls==0&&commits==fixture.world.commitSequence()&&before.equals(NativeInventoryMenuSnapshot.captureLive(fixture.player)),"Foreign prediction map executed before admission");
            Field components=ItemStack.class.getDeclaredField("components");require(components.trySetAccessible(),"Native component owner field is unavailable");
            Object componentMap=components.get(fixture.player.getInventory().getItem(0));Field patch=net.minecraft.core.component.PatchedDataComponentMap.class.getDeclaredField("patch");require(patch.trySetAccessible(),"Native component patch field is unavailable");
            Object originalPatch=patch.get(componentMap);var poisonPatch=new PoisonPatch();patch.set(componentMap,poisonPatch);
            refused=false;try{fixture.delivery.dispatchMenuPacket(packet(fixture,36,0,false),metadataLimits(),recipeLimits(),limits());}catch(SurvivalRefusal expected){refused=true;}finally{patch.set(componentMap,originalPatch);}
            require(refused&&poisonPatch.calls==0&&commits==fixture.world.commitSequence()&&before.equals(NativeInventoryMenuSnapshot.captureLive(fixture.player)),"Foreign component collection executed before admission");
            click(fixture,36,0,false);close(fixture);fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
        } catch(Exception|Error failure) { traceNativeMenuFailure("forgedObserverAndPredictionCollectionsRefuseBeforeCallbacksComponent",failure);throw failure; }
        helper.succeed();
    }
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void unsupportedNativeMenuPacketsRefuseBeforeEffectsComponent(GameTestHelper helper)throws IOException {
        try(var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            setup(fixture,true);fixture.player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG));fixture.player.inventoryMenu.broadcastChanges();discard(fixture);
            var before=NativeInventoryMenuSnapshot.captureLive(fixture.player);long commits=fixture.world.commitSequence();
            for(var type:List.of(ClickType.QUICK_MOVE,ClickType.QUICK_CRAFT,ClickType.THROW,ClickType.SWAP,ClickType.CLONE,ClickType.PICKUP_ALL)) {
                var packet=new ServerboundContainerClickPacket(0,before.stateId(),36,0,type,ItemStack.EMPTY,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
                boolean refused=false;try{fixture.delivery.dispatchMenuPacket(packet,metadataLimits(),recipeLimits(),limits());}catch(SurvivalRefusal expected){refused=true;}
                require(refused&&commits==fixture.world.commitSequence()&&before.equals(NativeInventoryMenuSnapshot.captureLive(fixture.player)),"Unsupported native click entered the effect boundary: "+type);
            }
            for(int slot:new int[]{-999,-1,5,8,45,46}) {
                var packet=new ServerboundContainerClickPacket(0,before.stateId(),slot,0,ClickType.PICKUP,ItemStack.EMPTY,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
                boolean refused=false;try{fixture.delivery.dispatchMenuPacket(packet,metadataLimits(),recipeLimits(),limits());}catch(SurvivalRefusal expected){refused=true;}
                require(refused&&commits==fixture.world.commitSequence()&&before.equals(NativeInventoryMenuSnapshot.captureLive(fixture.player)),"Outside/armor/offhand click escaped pre-effect refusal");
            }
            click(fixture,36,0,true);close(fixture);fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
        } catch(Exception|Error failure) { traceNativeMenuFailure("unsupportedNativeMenuPacketsRefuseBeforeEffectsComponent",failure);throw failure; }
        helper.succeed();
    }
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void caughtPreActionMenuReentryPoisonsExactOpeningComponent(GameTestHelper helper)throws Exception {
        try(var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            setup(fixture,true);fixture.player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG));fixture.player.inventoryMenu.broadcastChanges();discard(fixture);
            click(fixture,36,0,false);var before=NativeInventoryMenuSnapshot.captureLive(fixture.player);long sequence=fixture.world.commitSequence();
            // Component-only access to the private pre-action scope isolates caught callback reentry.
            var enter=NativeEffectDeliveryHost.class.getDeclaredMethod("enterMenuOpening");require(enter.trySetAccessible(),"Private opening test scope unavailable");
            Object opening=enter.invoke(fixture.delivery);var check=opening.getClass().getDeclaredMethod("requireCurrent");var release=NativeEffectDeliveryHost.class.getDeclaredMethod("releaseMenuOpening",opening.getClass());
            require(check.trySetAccessible()&&release.trySetAccessible(),"Private opening test assertions unavailable");
            try {
                boolean nested=false;try{fixture.delivery.dispatchMenuClose(metadataLimits(),limits().menuSnapshotBytes());}catch(SurvivalRefusal expected){nested=true;}
                boolean nestedSave=false;try{fixture.delivery.captureCheckpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);}catch(SurvivalRefusal expected){nestedSave=true;}
                boolean poisoned=false;try{check.invoke(opening);}catch(java.lang.reflect.InvocationTargetException expected){poisoned=expected.getCause() instanceof SurvivalRefusal;}
                require(nested&&nestedSave&&poisoned&&sequence==fixture.world.commitSequence()&&before.equals(NativeInventoryMenuSnapshot.captureLive(fixture.player))&&fixture.delivery.hasRetainedMenuSession(),"Caught pre-action menu reentry changed/lost the retained boundary or escaped sticky refusal");
            }finally{release.invoke(fixture.delivery,opening);}
            click(fixture,36,0,true);close(fixture);fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
        } catch(Exception|Error failure) { traceNativeMenuFailure("caughtPreActionMenuReentryPoisonsExactOpeningComponent",failure);throw failure; }
        helper.succeed();
    }
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void retainedOpenPostcommitFailureKeepsReceiptAndRefusesReentryComponent(GameTestHelper helper)throws IOException {
        try(var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            setup(fixture,true);var support=fixture.player.mainSupportingBlockPos.orElseThrow();CubeCell table=null;
            for(var direction:List.of(net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.SOUTH,net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.WEST)) {
                var position=support.relative(direction).above();if(fixture.level.getBlockState(position).isAir()&&fixture.level.getBlockState(position.below()).is(Blocks.GRASS_BLOCK)){table=CubeCell.of(position.getX(),position.getY(),position.getZ());break;}
            }
            require(table!=null,"Failure component lacks reachable native table site");
            try(var action=fixture.beginNextDeclared()){action.set(table,Blocks.CRAFTING_TABLE.defaultBlockState());action.commit();}
            var before=NativeInventorySnapshot.capture(fixture.player);long sequence=fixture.world.commitSequence();var thread=Thread.currentThread();
            boolean[] installed={false},thrown={false},reentrantRefused={false};
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open> listener=event->{
                require(Thread.currentThread()==thread&&event.getEntity()==fixture.player&&event.getContainer()==fixture.player.containerMenu,"Retained Open fault lost exact native callback ownership");
                try{fixture.delivery.dispatchMenuClose(metadataLimits(),limits().menuSnapshotBytes());}catch(SurvivalRefusal expected){reentrantRefused[0]=true;}
                thrown[0]=true;throw new SurvivalRefusal("fixture retained-menu Open callback failure");
            };
            fixture.channel.pipeline().addLast("earth-retained-menu-open-fault",new io.netty.channel.ChannelOutboundHandlerAdapter(){
                @Override public void write(io.netty.channel.ChannelHandlerContext context,Object message,io.netty.channel.ChannelPromise promise)throws Exception {
                    if(message instanceof ClientboundOpenScreenPacket){
                        require(Thread.currentThread()==thread&&!installed[0]&&fixture.actions.fenced()&&fixture.world.commitSequence()==sequence+1,"Retained OpenScreen was not once-only postcommit");
                        installed[0]=true;net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open.class,listener);
                    }
                    context.write(message,promise);
                }
            });
            try {
                boolean refused=false;try{fixture.delivery.dispatchMenuOpen(table,metadataLimits(),recipeLimits(),limits().menuSnapshotBytes());}catch(SurvivalRefusal expected){refused=true;}
                require(refused&&installed[0]&&thrown[0]&&reentrantRefused[0]&&fixture.actions.fenced()&&!fixture.actions.hasCurrentAction(),"Retained native postcommit failure/reentry did not keep its fence");
            }finally{
                if(installed[0])net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
                fixture.channel.pipeline().remove("earth-retained-menu-open-fault");
                if(fixture.actions.fenced()&&!fixture.actions.hasCurrentAction())fixture.expectFencedPhysicalClose();
            }
            var pending=fixture.actions.pendingDelivery();
            require(pending!=null&&pending.hasRetainedPreparedBatch()&&pending.receipt().commitSequence()==sequence+1&&pending.failure()!=null,"Retained menu failure lost exact accepted receipt/private plan");
            require(fixture.player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu,"Failed actual open was fictionally rolled back");
            for(int i=0;i<41;i++)require(before.slots().get(i).matches(fixture.player.getInventory().getItem(i),fixture.level.registryAccess()),"Retained failed-open inventory changed");
            boolean retry=false,checkpoint=false;
            try{fixture.delivery.dispatchMenuClose(metadataLimits(),limits().menuSnapshotBytes());}catch(SurvivalRefusal expected){retry=true;}
            try{fixture.delivery.captureCheckpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);}catch(SurvivalRefusal expected){checkpoint=true;}
            require(retry&&checkpoint&&fixture.actions.pendingDelivery()==pending&&fixture.world.commitSequence()==sequence+1,"Retained failed close retried or checkpointed incomplete native ownership");
        } catch(Exception|Error failure) { traceNativeMenuFailure("retainedOpenPostcommitFailureKeepsReceiptAndRefusesReentryComponent",failure);throw failure; }
        helper.succeed();
    }
    private static void requireNoPendingFailure(NativeSurvivalTestFixture fixture,String stage) {
        if(fixture.actions==null)return; // Exact unbound native reference has no cubic action owner.
        var pending=fixture.actions.pendingDelivery();
        if(pending!=null&&pending.failure()!=null)
            throw new IllegalStateException("Native menu packet retained postcommit failure at "+stage,pending.failure());
    }
    private static void traceNativeMenuFailure(String stage, Throwable failure) {
        System.err.println("EARTH_NATIVE_MENU_FAILURE stage=" + stage);
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        var queue = new java.util.ArrayDeque<Throwable>(); queue.add(failure);
        int causes = 0;
        while (!queue.isEmpty() && causes++ < 8) {
            Throwable current = queue.removeFirst(); if (!seen.add(current)) continue;
            System.err.println(current.getClass().getName() + ": " + current.getMessage());
            var frames = current.getStackTrace();
            for (int frame=0; frame<frames.length && frame<128; frame++) System.err.println("  at " + frames[frame]);
            if (frames.length > 128) System.err.println("  ... bounded stack remainder omitted");
            if (current.getCause() != null) queue.add(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) if (queue.size()<8) queue.add(suppressed);
        }
    }
    private static void requireForeignReceiverRefusedBeforeScheduling(NativeSurvivalTestFixture owned,NativeSurvivalTestFixture reference) {
        var click=packet(owned,36,0,false);var close=new ServerboundContainerClosePacket(0);long sequence=owned.world.commitSequence();
        var result=new java.util.concurrent.atomic.AtomicReference<Throwable>();var refusals=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new Thread(()->{
            try{
                try{NativeSessionMenuPackets.click(reference.player.connection,owned.player,click);}catch(SurvivalRefusal expected){refusals.incrementAndGet();}
                try{NativeSessionMenuPackets.close(reference.player.connection,owned.player,close);}catch(SurvivalRefusal expected){refusals.incrementAndGet();}
            }catch(Throwable failure){result.set(failure);}
        },"earth-menu-foreign-receiver-pre-enqueue");worker.setDaemon(true);worker.start();
        try{worker.join(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("Native menu pre-enqueue test interrupted",interrupted);}
        require(!worker.isAlive()&&result.get()==null&&refusals.get()==2&&owned.world.commitSequence()==sequence,
                "Foreign native click/close receiver reached scheduling or effects: "+result.get());
    }
    private static final class PoisonList extends ArrayList<Object>{int calls;@Override public int size(){calls++;throw new AssertionError("Untrusted observer callback");}}
    private static final class PoisonMap extends it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ItemStack>{int calls;@Override public int size(){calls++;throw new AssertionError("Untrusted prediction callback");}}
    private static final class PoisonPatch extends it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap<Object,Object>{int calls;@Override public int size(){calls++;throw new AssertionError("Untrusted component map size callback");}@Override public boolean isEmpty(){calls++;throw new AssertionError("Untrusted component map empty callback");}}
    private static void discard(NativeSurvivalTestFixture fixture){fixture.channel.runPendingTasks();int count=0;for(Object packet;(packet=fixture.channel.readOutbound())!=null;){require(++count<=1024,"Menu setup output exceeded bound");io.netty.util.ReferenceCountUtil.release(packet);}}
    private static String stack(ItemStack stack,NativeSurvivalTestFixture fixture){return java.util.Base64.getEncoder().encodeToString(NativeStackSnapshot.capture(stack,fixture.level.registryAccess()).encoded());}
    private static List<String> drain(NativeSurvivalTestFixture fixture){
        fixture.channel.runPendingTasks();var result=new ArrayList<String>();
        for(Object packet;(packet=fixture.channel.readOutbound())!=null;){
            require(result.size()<1024,"Menu packet observation exceeded bound");
            if(packet instanceof ClientboundContainerSetSlotPacket slot)result.add("slot:"+slot.getContainerId()+":"+slot.getStateId()+":"+slot.getSlot()+":"+stack(slot.getItem(),fixture));
            else if(packet instanceof ClientboundContainerSetContentPacket content)result.add("content:"+content.getContainerId()+":"+content.getStateId()+":"+content.getItems().stream().map(value->stack(value,fixture)).toList()+":"+stack(content.getCarriedItem(),fixture));
            else if(packet instanceof ClientboundOpenScreenPacket open)result.add("open:"+open.getContainerId()+":"+net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(open.getType())+":"+open.getTitle().getString());
            else if(packet instanceof ClientboundRecipePacket recipes)result.add("recipes:"+recipes.getState()+":"+recipes.getRecipes()+":"+recipes.getHighlights());
            else throw new AssertionError("Unexpected per-packet native menu output "+packet.getClass().getName());
            io.netty.util.ReferenceCountUtil.release(packet);
        }
        return List.copyOf(result);
    }
}
