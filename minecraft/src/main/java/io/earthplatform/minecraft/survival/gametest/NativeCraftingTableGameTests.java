package io.earthplatform.minecraft.survival.gametest;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.interaction.NativeMiningProgress;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.world.CubeCell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.earthplatform.minecraft.survival.gametest.NativeSurvivalTestFixture.require;

import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.host.*;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/** Internal native resolved-script gates; no client packet/UI ownership claim. */
@GameTestHolder("earthplatform")
@PrefixGameTestTemplate(false)
public final class NativeCraftingTableGameTests {
    private NativeCraftingTableGameTests() {}

    private static io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata menuMetadata(ServerPlayer player) {
        return io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata.capture(player,
                new io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata.Limits(4096, 16384, 8192, 65536, 128, 128, 1));
    }
    private static io.earthplatform.minecraft.survival.interaction.host.NativeCraftingRecipeAdmission menuRecipeAdmission(ServerPlayer player,
            io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata metadata) {
        return io.earthplatform.minecraft.survival.interaction.host.NativeCraftingRecipeAdmission.capture(player, metadata,
                new io.earthplatform.minecraft.survival.interaction.host.NativeCraftingRecipeAdmission.Limits(1024, 16384, 16, 131072, 4*1024*1024, 131072));
    }
    private static io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Limits menuPreparationLimits() {
        return new io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Limits(64, 256, 256*1024, 4*1024*1024);
    }
    private static io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Click menuClick(int slot, int button) {
        return new io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Click(slot, button, net.minecraft.world.inventory.ClickType.PICKUP);
    }
    private static List<io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Click> nativeFirstLoop2x2Clicks() {
        return List.of(menuClick(36,0), menuClick(1,1), menuClick(36,0), menuClick(0,0), menuClick(37,0),
                menuClick(36,0), menuClick(1,0), menuClick(0,0), menuClick(37,0),
                menuClick(37,0), menuClick(1,1), menuClick(3,1), menuClick(37,0), menuClick(0,0), menuClick(38,0),
                menuClick(37,0), menuClick(1,1), menuClick(2,1), menuClick(3,1), menuClick(4,1),
                menuClick(37,0), menuClick(0,0), menuClick(39,0));
    }
    private static ItemEntity commitEarnedNativeLog(NativeSurvivalTestFixture fixture, CubeCell log) {
        require(fixture.level.getBlockState(NativeVanillaPreparation.nativePos(log)).is(Blocks.OAK_LOG),
                "Earned script input must come from the next actual generated oak log");
        var inventoryBefore = io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player);
        long sequence = fixture.world.nextActionSequence(fixture.player.getUUID());
        var identity = fixture.world.actionIdentity(fixture.player.getUUID(),sequence);
        // Pinned ordinary oak-log harvest/loot/tool callbacks read only this exact target cube.
        // Any unexpected neighbor read still refuses; no wildcard or synthetic native readiness.
        var expected = Map.of(log.cube(),fixture.world.stamp(log.cube()));
        try (var action = fixture.delivery.begin(identity,expected)) {
            action.read(log);
            var mining = fixture.preparation.mining(fixture.player,log,action.random());
            require(mining.harvestable() && mining.loot().size() == 1 && !mining.tool().durabilityChanged(),
                    "Native first-loop log preparation changed its harvest/tool profile");
            require(mining.loot().getFirst().matches(new ItemStack(Items.OAK_LOG),fixture.level.registryAccess()),
                    "Native generated log loot differed from one oak log");
            // Pinned Block.playerDestroy exact inherited metadata, delivered through typed effects.
            action.stage(new NativeEffectCodec.PlayerStat(net.minecraft.resources.ResourceLocation.withDefaultNamespace("mined"),
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("oak_log"),1));
            action.stage(new NativeEffectCodec.PlayerExhaustion(0.005F));
            action.set(log,Blocks.AIR.defaultBlockState());
            var random = action.random();
            double x = log.x()+0.5+Mth.nextDouble(random,-0.25,0.25);
            double y = log.y()+0.5+Mth.nextDouble(random,-0.25,0.25)-EntityType.ITEM.getHeight()/2.0;
            double z = log.z()+0.5+Mth.nextDouble(random,-0.25,0.25);
            double vx = random.nextDouble()*0.2-0.1, vz = random.nextDouble()*0.2-0.1;
            UUID id = UUID.nameUUIDFromBytes(("native-earned-script-drop:"+identity).getBytes(StandardCharsets.UTF_8));
            action.stage(new NativeEffectCodec.ItemSpawn(id,mining.loot().getFirst(),x,y,z,vx,0.2,vz,0,10));
            action.commit();
        }
        require(inventoryBefore.equals(io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player)),
                "Native log preparation granted inventory instead of spawning its actual drop");
        var items = fixture.delivery.captureNativeState().items();
        require(items.size() == 1, "Earned log fixture must own one actual native drop at a time");
        return items.getFirst();
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
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void earnedThreeLogsPlaceTableAndNativeWoodenPickaxe(GameTestHelper helper) throws IOException {
        String stage="fixture initialization";
        try (var earned=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel());
                var reference=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var first=earned.nearestNaturalLog();
            earned.beginNativeCarrierLoadForDryItemTicks(first); earned.awaitNativeCarrierLoad(); earned.bindNativeDeliveryForEarnedTable();
            reference.beginNativeCarrierLoadForDryItemTicks(first); reference.awaitNativeCarrierLoad(); // Genuine unbound native reference.
            require(earned.player.getInventory().isEmpty(),"Earned table route must start empty-handed");
            menuMetadata(earned.player); menuMetadata(reference.player);
            earned.player.initInventoryMenu(); reference.player.initInventoryMenu();
            earned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
            earned.player.moveTo(first.x()+0.5,first.y(),first.z()+0.5,180,0);
            // Fix the actual available site before any earned action or action-local RNG draw.
            var table=findNativeTableSite(earned,first);
            for (int i=0;i<3;i++) {
                var log=CubeCell.of(first.x(),first.y()+i,first.z()); stage="earned natural log "+log;
                ItemEntity item=commitEarnedNativeLog(earned,log); int ticks=0; double y=item.getY(); boolean fell=false;
                require(item.isAddedToLevel() && earned.level.getEntity(item.getUUID())==item && earned.level.getEntity(item.getId())==item,
                        "Original natural drop is not tracked before physics");
                int initialDelay=io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(item).tagCopy().getInt("PickupDelay");
                var pickupBox=earned.player.getBoundingBox().inflate(1,0.5,1);
                boolean initiallyTouching=pickupBox.intersects(item.getBoundingBox());
                System.out.printf("EARTH_NATIVE_EARNED_DROP log=%s position=%s sourceBox=%s pickupBox=%s delay=%d initialTouch=%s%n",
                        log,item.position(),item.getBoundingBox(),pickupBox,initialDelay,initiallyTouching);
                require(initialDelay==10,"Original native default pickup delay changed");
                // The native engine only calls playerTouch for actual collision candidates. A
                // higher unchanged drop must fall into range before it can enter that callback.
                if(initiallyTouching) { item.playerTouch(earned.player);require(!item.isRemoved(),"Original native pickup delay was bypassed"); }
                while (ticks<64 && (ticks<10 || !item.onGround())) {
                    earned.level.tickNonPassenger(item);ticks++;fell|=item.getY()<y;y=item.getY();
                }
                require(ticks>=10 && ticks<64 && fell && item.onGround() && item.mainSupportingBlockPos.isPresent()
                        && earned.level.getBlockState(item.mainSupportingBlockPos.get()).is(Blocks.GRASS_BLOCK),
                        "Earned table input lost original delay/gravity/grass landing");
                require(earned.player.getBoundingBox().inflate(1,0.5,1).intersects(item.getBoundingBox()),"Original natural drop escaped pickup reach");
                item.playerTouch(earned.player);require(item.isRemoved(),"Earned native log pickup failed");
                earned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);require(earned.lastRetiredOwners==1,"Earned consumed owner was not durably retired");
            }
            require(earned.player.getInventory().getItem(0).is(Items.OAK_LOG)
                    && earned.player.getInventory().getItem(0).getCount()==3,"Earned route did not conserve three native logs");
            for (int i=1;i<41;i++) require(earned.player.getInventory().getItem(i).isEmpty(),"Earned route acquired supplied items");
            // Only the separate native semantic reference receives component setup inventory.
            reference.player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,3));
            int changes=earned.player.getInventory().getTimesChanged();require(changes>=0 && changes<=64,"Native input inventory counter exceeds fixture bound");
            for (int i=0;i<changes;i++) reference.player.getInventory().setChanged();
            require(NativeInventoryMenuSnapshot.captureLive(earned.player).equals(NativeInventoryMenuSnapshot.captureLive(reference.player)),"Native three-log reference before-image differs");
            discardPackets(earned);discardPackets(reference);stage="accepted native 2x2 three-log script";
            var two=threeLogClicks();
            for (var click:two) reference.player.inventoryMenu.clicked(click.slot(),click.button(),click.type(),reference.player);
            reference.player.inventoryMenu.broadcastChanges();
            earned.delivery.dispatchResolvedMenuScript(two,metadataLimits(),recipeLimits(),menuPreparationLimits());
            require(NativeInventoryMenuSnapshot.captureLive(earned.player).equals(NativeInventoryMenuSnapshot.captureLive(reference.player))
                    && drainPackets(earned).equals(drainPackets(reference)),"Accepted native three-log 2x2 output/cache/packets differ");
            requireMetadataEqual(earned,reference);
            require(earned.player.getInventory().getItem(1).getCount()==6 && earned.player.getInventory().getItem(2).getCount()==4
                    && earned.player.getInventory().getItem(3).is(Items.CRAFTING_TABLE),"Three logs did not craft exact table materials");
            earned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            var ground=CubeCell.of(table.x(),table.y()-1,table.z());
            require(earned.level.getBlockState(NativeVanillaPreparation.nativePos(table)).isAir()
                    && earned.level.getBlockState(NativeVanillaPreparation.nativePos(ground)).is(Blocks.GRASS_BLOCK),"Chosen natural table site is not air above grass");
            reference.player.moveTo(earned.player.getX(),earned.player.getY(),earned.player.getZ(),earned.player.getYRot(),earned.player.getXRot());
            reference.player.setOnGround(earned.player.onGround());
            reference.level.setBlock(NativeVanillaPreparation.nativePos(ground),Blocks.GRASS_BLOCK.defaultBlockState(),3);
            earned.player.getInventory().selected=3;reference.player.getInventory().selected=3;
            var hit=placementHit(ground);stage="native BlockItem.place reference";
            NativePlacementReferenceObservation.Sound nativeSound;NativePlacementReferenceObservation.Event nativeEvent;
            try (var observed=NativePlacementReferenceObservation.open(reference.level,reference.player)) {
                var held=reference.player.getMainHandItem();
                var result=((BlockItem)held.getItem()).place(new BlockPlaceContext(new UseOnContext(reference.level,reference.player,InteractionHand.MAIN_HAND,held,hit)));
                require(result==net.minecraft.world.InteractionResult.CONSUME,"Actual native BlockItem.place did not consume the action");
                require(observed.sounds().size()==1 && observed.events().size()==1 && observed.order().equals(List.of("sound","event")),"Native BlockItem.place observation lost sound/event calls");
                nativeSound=observed.sounds().getFirst();nativeEvent=observed.events().getFirst();
            }
            stage="accepted native dry table placement";NativeDryPlacementPlan.Prepared placement;
            require(earned.delivery.captureNativeState().ticks().isEmpty(),"First native placement requires a fresh scheduled-order domain");
            try (var action=earned.beginNextDeclared()) { placement=earned.delivery.dispatchDryTablePlacement(action,hit);action.commit(); }
            require(placement.result()==net.minecraft.world.InteractionResult.CONSUME && placement.rejectingPlacedBlockCriteria()==11 && placement.placement().target().equals(table)
                    && earned.level.getBlockState(NativeVanillaPreparation.nativePos(table))==reference.level.getBlockState(NativeVanillaPreparation.nativePos(table))
                    && earned.player.getMainHandItem().isEmpty() && reference.player.getMainHandItem().isEmpty(),"Native table placement state/consumption/criterion census differs");
            var sound=placement.sound();
            require(sound.sound().equals(nativeSound.sound()) && sound.category().equals(nativeSound.category()) && sound.x()==nativeSound.x()
                    && sound.y()==nativeSound.y() && sound.z()==nativeSound.z() && sound.volume()==nativeSound.volume() && sound.pitch()==nativeSound.pitch()
                    && sound.excludedActor().equals(earned.player.getUUID()) && nativeSound.excludedActor().equals(reference.player.getUUID()),
                    "Accepted placement lost exact native sound arguments/actor exclusion");
            require(nativeEvent.event().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block_place"))
                    && nativeEvent.position().equals(Vec3.atCenterOf(NativeVanillaPreparation.nativePos(table)))
                    && nativeEvent.actor().equals(reference.player.getUUID()) && nativeEvent.affectedState().is(Blocks.CRAFTING_TABLE)
                    && placement.event().affectedStateId()==earned.palette.id(nativeEvent.affectedState()),"Native placement game-event context differs");
            System.out.printf("EARTH_NATIVE_TABLE_PLACE criteria=%d nativeSoundSeed=%d acceptedActionSoundSeed=%d rngDomainsDistinct=true witnesses=%s%n",
                    placement.rejectingPlacedBlockCriteria(),nativeSound.seed(),sound.seed(),NativePlacedBlockCriterionAdmission.witnessUsage());
            requireMetadataEqual(earned,reference);earned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            drainPackets(earned);drainPackets(reference);stage="direct native placed-table open/click/close reference";
            var tableHit=new BlockHitResult(Vec3.atCenterOf(NativeVanillaPreparation.nativePos(table)),Direction.UP,NativeVanillaPreparation.nativePos(table),false);
            require(reference.level.getBlockState(NativeVanillaPreparation.nativePos(table)).useWithoutItem(reference.level,reference.player,tableHit)
                    ==net.minecraft.world.InteractionResult.CONSUME,"Native placed table use did not open its provider");
            var openBefore=NativeCraftingTableSnapshot.captureLive(reference.player);
            var steps=new ArrayList<NativeCraftingTableSnapshot.Data>();
            for (var click:woodenPickClicks()) { reference.player.containerMenu.clicked(click.slot(),click.button(),click.type(),reference.player);steps.add(NativeCraftingTableSnapshot.captureLive(reference.player)); }
            reference.player.containerMenu.broadcastChanges();reference.player.closeContainer();
            var referenceAfter=NativeInventoryMenuSnapshot.captureLive(reference.player);var referencePackets=drainPackets(reference);
            stage="accepted native placed-table 3x3 wooden-pick script";
            var receipt=earned.delivery.dispatchResolvedTableScript(table,woodenPickClicks(),metadataLimits(),recipeLimits(),menuPreparationLimits());
            var effects=receipt.effects().stream().map(effect->NativeEffectCodec.decode(effect,earned.level.registryAccess())).toList();
            var transcript=effects.stream().filter(NativeEffectCodec.ResolvedTableScript.class::isInstance).map(NativeEffectCodec.ResolvedTableScript.class::cast).findFirst().orElseThrow().transcript();
            require(transcript.before().equals(openBefore) && transcript.steps().equals(steps) && transcript.after().equals(steps.getLast()),
                    "Prepared native 3x3 constructor/grid/cursor/result/observer vectors differ per click");
            require(referenceAfter.equals(NativeInventoryMenuSnapshot.captureLive(earned.player)) && referencePackets.equals(drainPackets(earned)),
                    "Accepted native table open/result/broadcast/close state or ordered packets differ");
            requireMetadataEqual(earned,reference);
            for (var item:List.of(Items.OAK_PLANKS,Items.STICK,Items.CRAFTING_TABLE,Items.WOODEN_PICKAXE)) {
                int expected=item==Items.OAK_PLANKS?12:item==Items.STICK?4:1;
                require(earned.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED,item)==expected
                        && reference.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED,item)==expected,"Native earned crafted statistic differs");
            }
            require(earned.player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE))==1,
                    "Actual native table interaction statistic was omitted/repeated");
            stage="earned table/pick checkpoint and native file readback";
            var saved=earned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);var slots=saved.boundary().inventory().slots();
            for(int i=0;i<41;i++) require(slots.get(i).matches(i==1?new ItemStack(Items.OAK_PLANKS,3):i==2?new ItemStack(Items.STICK,2):
                    i==4?new ItemStack(Items.WOODEN_PICKAXE):ItemStack.EMPTY,earned.level.registryAccess()),"Earned table/pick checkpoint item conservation differs at slot "+i);
            require(saved.boundary().items().isEmpty() && earned.level.getBlockState(NativeVanillaPreparation.nativePos(table)).is(Blocks.CRAFTING_TABLE)
                    && saved.cubes().containsKey(table.cube()) && earned.palette.state(saved.cubes().get(table.cube()).blocks().get(table.localIndex())).is(Blocks.CRAFTING_TABLE),
                    "Decoded checkpoint lost exact placed-table cell or native item ownership");
            var files=earned.saveAndVerifyNativePlayerFiles();
            var stats=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(files.statistics())).getAsJsonObject().getAsJsonObject("stats");
            require(stats.getAsJsonObject("minecraft:crafted").get("minecraft:wooden_pickaxe").getAsInt()==1
                    && stats.getAsJsonObject("minecraft:custom").get("minecraft:interact_with_crafting_table").getAsInt()==1,
                    "Actual native statistics file lost wooden-pick/table callbacks");
        } catch(RuntimeException|Error|IOException failure) { traceNativeMenuFailure(stage,failure);throw failure; }
        helper.succeed();
    }

    private static NativeFirstLoopMetadata.Limits metadataLimits() { return new NativeFirstLoopMetadata.Limits(4096,16384,8192,65536,128,128,1); }
    private static NativeCraftingRecipeAdmission.Limits recipeLimits() { return new NativeCraftingRecipeAdmission.Limits(1024,16384,16,131072,4*1024*1024,131072); }
    private static List<NativeInventoryMenuPreparation.Click> threeLogClicks() {
        var result=new ArrayList<NativeInventoryMenuPreparation.Click>();
        result.addAll(List.of(menuClick(36,0),menuClick(1,1),menuClick(36,0),menuClick(0,0),menuClick(37,0),
                menuClick(36,0),menuClick(1,1),menuClick(36,0),menuClick(0,0),menuClick(37,0),
                menuClick(36,0),menuClick(1,0),menuClick(0,0),menuClick(37,0)));
        result.addAll(nativeFirstLoop2x2Clicks().subList(9,23));return List.copyOf(result);
    }
    private static List<NativeInventoryMenuPreparation.Click> woodenPickClicks() {
        return List.of(menuClick(38,0),menuClick(1,1),menuClick(2,1),menuClick(3,1),menuClick(38,0),
                menuClick(39,0),menuClick(5,1),menuClick(8,1),menuClick(39,0),menuClick(0,0),menuClick(41,0));
    }
    private static BlockHitResult placementHit(CubeCell ground) {
        var pos=NativeVanillaPreparation.nativePos(ground);return new BlockHitResult(new Vec3(ground.x()+0.5,ground.y()+1.0,ground.z()+0.5),Direction.UP,pos,false);
    }
    private static void requireMetadataEqual(NativeSurvivalTestFixture first,NativeSurvivalTestFixture second) {
        require(first.player.getRecipeBook().toNbt().equals(second.player.getRecipeBook().toNbt())
                && first.nativeAdvancementState().equals(second.nativeAdvancementState()),"Native recipe-book/advancement callbacks differ");
    }
    private static String stack(ItemStack stack,NativeSurvivalTestFixture fixture) {
        return java.util.Base64.getEncoder().encodeToString(NativeStackSnapshot.capture(stack,fixture.level.registryAccess()).encoded());
    }
    private static void discardPackets(NativeSurvivalTestFixture fixture) {
        fixture.channel.runPendingTasks();int count=0;
        for(Object packet;(packet=fixture.channel.readOutbound())!=null;) { require(++count<=1024,"Native setup packet discard exceeded bound");io.netty.util.ReferenceCountUtil.release(packet); }
    }
    private static List<String> drainPackets(NativeSurvivalTestFixture fixture) {
        fixture.channel.runPendingTasks();var result=new ArrayList<String>();
        for(Object packet;(packet=fixture.channel.readOutbound())!=null;) {
            require(result.size()<1024,"Native table packet observation exceeded its bound");
            if(packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket slot)
                result.add("slot:"+slot.getContainerId()+":"+slot.getStateId()+":"+slot.getSlot()+":"+stack(slot.getItem(),fixture));
            else if(packet instanceof net.minecraft.network.protocol.game.ClientboundRecipePacket recipe)
                result.add("recipes:"+recipe.getState()+":"+recipe.getRecipes()+":"+recipe.getHighlights());
            else if(packet instanceof net.minecraft.network.protocol.game.ClientboundOpenScreenPacket open)
                result.add("open:"+open.getContainerId()+":"+net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(open.getType())+":"+open.getTitle().getString());
            else if(packet instanceof net.minecraft.network.protocol.game.ClientboundContainerClosePacket close) result.add("close:"+close.getContainerId());
            else if(packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket content)
                result.add("content:"+content.getContainerId()+":"+content.getStateId()+":"+content.getItems().stream().map(value->stack(value,fixture)).toList()+":"+stack(content.getCarriedItem(),fixture));
            else throw new AssertionError("Unexpected native table packet "+packet.getClass().getName());
            io.netty.util.ReferenceCountUtil.release(packet);
        }
        return List.copyOf(result);
    }

    /** Supplied materials/placed block exercise authority only; they are not earned progression. */
    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void nativeTableForgedNestedAndTwoEventRefusalsComponent(GameTestHelper helper) throws IOException {
        String stage="component setup";
        try (var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var table=setupComponentTable(fixture);
            fixture.player.getInventory().setItem(0,new ItemStack(Items.CRAFTING_TABLE)); // Explicit component-only forge input.
            var before=NativeInventoryMenuSnapshot.captureLive(fixture.player);
            var metadata=menuMetadata(fixture.player);var recipes=menuRecipeAdmission(fixture.player,metadata);
            long sequence=fixture.world.commitSequence();var book=fixture.player.getRecipeBook().toNbt();var progress=fixture.nativeAdvancementState();
            stage="abort-only native table transcript";NativeCraftingTablePreparation.Result transcript;
            var clicks=List.of(menuClick(10,0));
            try(var action=fixture.beginNextDeclared()) {
                transcript=NativeCraftingTablePreparation.prepareScript(action,fixture.player,table,metadata,recipes,clicks,menuPreparationLimits());
                require(action.state()==NativeWholeAction.State.ABORTED,"Abort-only table comparison retained commit authority");
            }
            stage="forged native table transcript";
            try(var action=fixture.beginNextDeclared()) {
                var id=action.identity();action.read(table);
                action.stage(new NativeEffectCodec.ResolvedTableScript(fixture.world.world(),id.ownerEpoch(),id.actor(),id.sequence(),table,
                        fixture.palette.id(Blocks.CRAFTING_TABLE.defaultBlockState()),0,clicks,transcript));
                boolean rejected=false;
                try { action.commit(); } catch(SurvivalRefusal expected) {
                    rejected=expected.getMessage().equals("Earth survival: Native table script lacks its exact effect-only single-use captured plan");
                }
                require(rejected && action.state()==NativeWholeAction.State.ABORTED,"Copied table transcript obtained private delivery authority");
            }
            stage="caught native cross-shape scope refusal";
            for(int direction=0;direction<2;direction++) {
                final boolean tableOuter=direction==0;final boolean[] attempted={false},caught={false};
                try(var action=fixture.beginNextDeclared()) {
                    // Deliberately hostile component list calls back only while the genuine outer
                    // detached scope exists. Production host copies external lists before begin.
                    var hostile=new java.util.AbstractList<NativeInventoryMenuPreparation.Click>() {
                        public int size() { return 1; }
                        public NativeInventoryMenuPreparation.Click get(int index) {
                            if (!attempted[0]) {
                                attempted[0]=true;
                                try {
                                    if(tableOuter) NativeInventoryMenuPreparation.prepareScript(action,fixture.player,metadata,recipes,List.of(menuClick(9,0)),menuPreparationLimits());
                                    else NativeCraftingTablePreparation.prepareScript(action,fixture.player,table,metadata,recipes,List.of(menuClick(10,0)),menuPreparationLimits());
                                } catch(SurvivalRefusal expected) { caught[0]=expected.getMessage().contains("cannot reenter"); }
                            }
                            return menuClick(tableOuter?10:9,0);
                        }
                    };
                    boolean outerRefused=false;
                    try {
                        if(tableOuter) NativeCraftingTablePreparation.prepareScript(action,fixture.player,table,metadata,recipes,hostile,menuPreparationLimits());
                        else NativeInventoryMenuPreparation.prepareScript(action,fixture.player,metadata,recipes,hostile,menuPreparationLimits());
                    } catch(SurvivalRefusal expected) { outerRefused=true; }
                    require(attempted[0] && caught[0] && outerRefused && action.state()==NativeWholeAction.State.ABORTED,
                            "Caught nested/cross-shape native preparation retained the outer action");
                    boolean commitRefused=false;try { action.commit(); } catch(SurvivalRefusal expected) { commitRefused=true; }
                    require(commitRefused,"Caught cross-shape refusal still allowed commit");
                }
            }
            stage="caught nested host dispatch refusal";
            try(var action=fixture.beginNextDeclared()) {
                boolean caught=false;
                try { fixture.delivery.dispatchResolvedTableScript(table,clicks,metadataLimits(),recipeLimits(),menuPreparationLimits()); }
                catch(SurvivalRefusal expected) { caught=expected.getMessage().equals("Earth survival: Native table script is nested, busy or fenced"); }
                require(caught && action.state()==NativeWholeAction.State.ABORTED,"Nested host dispatch did not poison its owned action");
            }
            stage="two native game events precommit refusal";
            try(var action=fixture.beginNextDeclared()) {
                action.stage(new NativeEffectCodec.GameEvent(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block_place"),table,null));
                action.stage(new NativeEffectCodec.GameEvent(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block_place"),table,null));
                boolean rejected=false;try { action.commit(); } catch(SurvivalRefusal expected) {
                    System.out.println("EARTH_NATIVE_EXPECTED_TWO_EVENT_REFUSAL "+expected.getMessage());
                    rejected=expected.getMessage().equals("Earth survival: Multiple native game events require a shared section-metadata reservation");
                }
                require(rejected && action.state()==NativeWholeAction.State.ABORTED,"Two native events bypassed the sole section reservation");
            }
            stage="forged exact native placement data without private plan";
            var forgedTarget=findNativeTableSite(fixture,table);
            require(fixture.level.getBlockState(NativeVanillaPreparation.nativePos(forgedTarget)).is(Blocks.AIR),"Forged placement fixture requires natural AIR");
            try(var action=fixture.beginNextDeclared()) {
                int air=fixture.palette.id(Blocks.AIR.defaultBlockState()),placed=fixture.palette.id(Blocks.CRAFTING_TABLE.defaultBlockState());
                action.set(forgedTarget,Blocks.CRAFTING_TABLE.defaultBlockState());
                action.stage(new NativeEffectCodec.BlockNotification(fixture.world.world(),forgedTarget,air,placed,11,512));
                action.stage(new NativeEffectCodec.PlacementSound(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getKey(net.minecraft.sounds.SoundEvents.WOOD_PLACE),
                        "blocks",forgedTarget.x()+0.5,forgedTarget.y()+0.5,forgedTarget.z()+0.5,1.0F,0.8F,action.random().nextLong(),fixture.player.getUUID()));
                action.stage(new NativeEffectCodec.BlockPlaceEvent(fixture.world.world(),forgedTarget,fixture.player.getUUID(),placed));
                action.replaceStack(0,ItemStack.EMPTY);
                boolean rejected=false;try { action.commit(); } catch(SurvivalRefusal expected) {
                    System.out.println("EARTH_NATIVE_EXPECTED_PLACEMENT_AUTHORITY_REFUSAL "+expected.getMessage());
                    rejected=expected.getMessage().equals("Earth survival: Native placement effects lack their exact private preparation plan");
                }
                require(rejected && action.state()==NativeWholeAction.State.ABORTED,"Manually staged placement data minted native consumption/counter authority");
            }
            require(fixture.level.getBlockState(NativeVanillaPreparation.nativePos(forgedTarget)).is(Blocks.AIR),"Forged native placement committed its block prefix");
            require(sequence==fixture.world.commitSequence() && !fixture.actions.fenced() && before.equals(NativeInventoryMenuSnapshot.captureLive(fixture.player))
                    && book.equals(fixture.player.getRecipeBook().toNbt()) && progress.equals(fixture.nativeAdvancementState()) && drainPackets(fixture).isEmpty(),
                    "Refused native table actions changed live inventory/menu/metadata/packets or committed a prefix");
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
        } catch(RuntimeException|Error|IOException failure) { traceNativeMenuFailure(stage,failure);throw failure; }
        helper.succeed();
    }

    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void nativeTablePostcommitOpenFailureRetainsExactFenceComponent(GameTestHelper helper) throws IOException {
        String stage="fenced component setup";
        try(var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var table=setupComponentTable(fixture);var before=io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player);
            long sequence=fixture.world.commitSequence();var owner=Thread.currentThread();boolean[] installed={false},thrown={false};
            java.util.function.Consumer<PlayerContainerEvent.Open> listener=event->{
                require(Thread.currentThread()==owner && event.getEntity()==fixture.player && event.getContainer()==fixture.player.containerMenu,
                        "Injected native Open callback lost its exact owner/player/menu");
                thrown[0]=true;throw new SurvivalRefusal("fixture native Open callback failure after accepted receipt");
            };
            fixture.channel.pipeline().addLast("earth-table-open-fault",new io.netty.channel.ChannelOutboundHandlerAdapter() {
                @Override public void write(io.netty.channel.ChannelHandlerContext context,Object message,io.netty.channel.ChannelPromise promise) throws Exception {
                    if(message instanceof net.minecraft.network.protocol.game.ClientboundOpenScreenPacket) {
                        require(Thread.currentThread()==owner && !installed[0] && fixture.actions.fenced()
                                && fixture.world.commitSequence()==sequence+1,"Native OpenScreen did not run once synchronously after receipt acceptance");
                        installed[0]=true;NeoForge.EVENT_BUS.addListener(PlayerContainerEvent.Open.class,listener);
                    }
                    context.write(message,promise);
                }
            });
            stage="actual native postcommit Open callback failure";
            try {
                boolean refused=false;
                try { fixture.delivery.dispatchResolvedTableScript(table,List.of(menuClick(10,0)),metadataLimits(),recipeLimits(),menuPreparationLimits()); }
                catch(SurvivalRefusal expected) { refused=expected.getMessage().equals("Earth survival: cube commit succeeded but native effect delivery failed; session fenced, receipt retained"); }
                require(refused && installed[0] && thrown[0] && fixture.actions.fenced() && !fixture.actions.hasCurrentAction(),"Native Open fault did not leave the exact postcommit delivery fence");
            } finally {
                if(installed[0]) NeoForge.EVENT_BUS.unregister(listener);
                fixture.channel.pipeline().remove("earth-table-open-fault");
                if(fixture.actions.fenced() && !fixture.actions.hasCurrentAction()) fixture.expectFencedPhysicalClose();
            }
            var pending=fixture.actions.pendingDelivery();
            require(pending!=null && pending.hasRetainedPreparedBatch() && pending.receipt().commitSequence()==sequence+1
                    && pending.failure()!=null && pending.failure().getMessage().equals("Earth survival: fixture native Open callback failure after accepted receipt"),
                    "Accepted native table failure lost its receipt/private plan/cause");
            require(fixture.player.containerMenu.getClass()==CraftingMenu.class && fixture.player.containerMenu.containerId==1
                    && fixture.player.getInventory().selected==before.selected(),"Native table failure rolled back its actual open prefix");
            for(int i=0;i<41;i++) require(before.slots().get(i).matches(fixture.player.getInventory().getItem(i),fixture.level.registryAccess()),"Native table failed before-click inventory changed");
            require(fixture.player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE))==0,
                    "Native table interaction stat ran after its Open callback threw");
            var packets=drainPackets(fixture);require(packets.stream().filter(value->value.startsWith("open:1:")).count()==1
                    && packets.stream().noneMatch(value->value.startsWith("close:")),"Failed native open packet prefix was lost or fictionally closed");
            boolean retry=false,checkpoint=false;
            try { fixture.delivery.dispatchResolvedTableScript(table,List.of(menuClick(10,0)),metadataLimits(),recipeLimits(),menuPreparationLimits()); } catch(SurvivalRefusal expected) { retry=true; }
            try { fixture.delivery.captureCheckpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE); } catch(SurvivalRefusal expected) { checkpoint=true; }
            require(retry && checkpoint && fixture.actions.pendingDelivery()==pending && fixture.world.commitSequence()==sequence+1,"Failed native table action retried or checkpointed unresolved ownership");
        } catch(RuntimeException|Error|IOException failure) { traceNativeMenuFailure(stage,failure);throw failure; }
        helper.succeed();
    }

    /** Finite native-state site selection, never terrain preparation or an assumed flat offset. */
    private static CubeCell findNativeTableSite(NativeSurvivalTestFixture fixture,CubeCell center) {
        int examined=0;
        for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++) {
            examined++;long x=Math.addExact(center.x(),dx),z=Math.addExact(center.z(),dz);
            long groundY=fixture.definition.surfaceY(x,z);
            var ground=CubeCell.of(x,groundY,z);var table=CubeCell.of(x,Math.addExact(groundY,1),z);
            if(fixture.world.presence(ground.cube())!=NativeSurvivalWorld.Presence.RESIDENT
                    || fixture.world.presence(table.cube())!=NativeSurvivalWorld.Presence.RESIDENT) continue;
            var groundPos=NativeVanillaPreparation.nativePos(ground);var tablePos=NativeVanillaPreparation.nativePos(table);
            if(!fixture.level.getBlockState(groundPos).is(Blocks.GRASS_BLOCK)
                    || !fixture.level.getBlockState(tablePos).is(Blocks.AIR)
                    || !fixture.player.canInteractWithBlock(groundPos,1.0) || !fixture.player.canInteractWithBlock(tablePos,1.0)
                    || fixture.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(tablePos))) continue;
            System.out.printf("EARTH_NATIVE_TABLE_SITE examined=%d maximum=49 sourceGroundY=%d table=%s player=%s%n",
                    examined,groundY,table,fixture.player.position());
            return table;
        }
        throw new AssertionError("No actual AIR-above-GRASS site in the fixed 49-column resident native reach domain");
    }

    private static CubeCell setupComponentTable(NativeSurvivalTestFixture fixture) throws IOException {
        var log=fixture.nearestNaturalLog();fixture.beginNativeCarrierLoadForDryItemTicks(log);fixture.awaitNativeCarrierLoad();fixture.bindNativeDeliveryForEarnedMenu();
        menuMetadata(fixture.player);fixture.player.initInventoryMenu();fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
        var table=findNativeTableSite(fixture,log);fixture.standNear(table);
        // Explicit component state only. This is never used by the earned-route test.
        try(var action=fixture.beginNextDeclared()) { action.set(table,Blocks.CRAFTING_TABLE.defaultBlockState());action.commit(); }
        fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);discardPackets(fixture);return table;
    }

    @GameTest(template="native_survival_empty",timeoutTicks=400)
    public static void nativePlacementReferenceObservationOwnershipComponent(GameTestHelper helper) throws IOException {
        try(var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var witness=NativePlacedBlockCriterionAdmission.witnessUsage();
            require(witness.enabled() && witness.owners()>0 && witness.owners()<=8192 && witness.edges()<=65536
                    && witness.bytes()<=4*1024*1024 && !witness.overflow(),"Actual native constructor provenance is absent or exceeded fixed admission");
            try(var scope=NativePlacementReferenceObservation.open(fixture.level,fixture.player)) {
                referenceSound(fixture,fixture.level,17L);
                fixture.level.gameEvent(GameEvent.BLOCK_PLACE,fixture.player.position(),GameEvent.Context.of(fixture.player,Blocks.CRAFTING_TABLE.defaultBlockState()));
                require(scope.sounds().size()==1 && scope.sounds().getFirst().seed()==17L
                        && scope.events().size()==1 && scope.order().equals(List.of("sound","event")),"Native transformed reference hooks did not observe exact unchanged calls");
            }
            boolean nested=false,sticky=false;
            var scope=NativePlacementReferenceObservation.open(fixture.level,fixture.player);
            try { NativePlacementReferenceObservation.open(fixture.level,fixture.player); } catch(SurvivalRefusal expected) { nested=true; }
            try { scope.close(); } catch(SurvivalRefusal expected) { sticky=true; }
            require(nested && sticky,"Nested reference observation did not retain its refusal");
            boolean wrongWorld=false;
            scope=NativePlacementReferenceObservation.open(fixture.level,fixture.player);
            referenceSound(fixture,helper.getLevel(),18L);
            try { scope.close(); } catch(SurvivalRefusal expected) { wrongWorld=expected.getMessage().contains("owner/world changed"); }
            require(wrongWorld,"Foreign native reference receiver escaped exact scope identity");
            boolean overflow=false;
            scope=NativePlacementReferenceObservation.open(fixture.level,fixture.player);
            for(int i=0;i<3;i++) referenceSound(fixture,fixture.level,i);
            try { scope.close(); } catch(SurvivalRefusal expected) { overflow=expected.getMessage().contains("bound/actor changed"); }
            require(overflow,"Native reference observation silently truncated overflow");
            var ownerScope=NativePlacementReferenceObservation.open(fixture.level,fixture.player);
            var offThreadFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var other=new Thread(()->{try { ownerScope.close(); } catch(Throwable failure) { offThreadFailure.set(failure); }},"native-reference-owner-adversary");
            other.start();try { other.join(1000); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
            require(!other.isAlive() && offThreadFailure.get() instanceof SurvivalRefusal,"Off-owner reference close did not visibly refuse");
            boolean ownerSticky=false;try { ownerScope.close(); } catch(SurvivalRefusal expected) { ownerSticky=true; }
            require(ownerSticky,"Off-owner reference close did not retain failure on its real owner");
            try { try(var ignored=NativePlacementReferenceObservation.open(fixture.level,fixture.player)) { throw new IllegalArgumentException("fixture body failure"); } }
            catch(IllegalArgumentException expected) { require(expected.getMessage().equals("fixture body failure"),"Unexpected reference body failure"); }
            try(var clean=NativePlacementReferenceObservation.open(fixture.level,fixture.player)) {
                require(clean.sounds().isEmpty() && clean.events().isEmpty(),"Exception cleanup leaked reference ownership or observations");
            }
        }
        helper.succeed();
    }
    private static void referenceSound(NativeSurvivalTestFixture fixture,ServerLevel receiver,long seed) {
        receiver.playSeededSound(fixture.player,fixture.player.getX(),fixture.player.getY(),fixture.player.getZ(),
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.WOOD_PLACE),
                net.minecraft.sounds.SoundSource.BLOCKS,1.0F,0.8F,seed);
    }

}
