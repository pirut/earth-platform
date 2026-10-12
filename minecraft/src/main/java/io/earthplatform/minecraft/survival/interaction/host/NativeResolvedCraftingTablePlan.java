package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.CraftingMenu;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Private host-retained, receipt-scoped delivery for a resolved-to-resolved native click script. */
final class NativeResolvedCraftingTablePlan {
    private sealed interface PreparedNotice permits PreparedStat, PreparedCrafted, PreparedUnlock, PreparedPacket {}
    private record PreparedStat(Stat<?> stat, int amount) implements PreparedNotice {}
    private record PreparedCrafted(RecipeHolder<?> recipe, List<ItemStack> input) implements PreparedNotice {}
    private record PreparedUnlock(List<RecipeHolder<?>> recipes) implements PreparedNotice {}
    private record PreparedPacket(ClientboundContainerSetSlotPacket packet, ItemStack remote) implements PreparedNotice {}
    private final NativeEffectDeliveryHost host;
    private final NativeWholeAction action;
    private final ServerPlayer player;
    private final InventoryMenu inventoryMenu;
    private CraftingMenu menu;
    private final CubeCell table;
    private final net.minecraft.world.level.block.state.BlockState tableState;
    private final NativeTableMenuLifecycleAdmission lifecycle;
    
    private final ServerGamePacketListenerImpl connection;
    private final NativeFirstLoopMetadata metadata;
    private final NativeCraftingRecipeAdmission recipes;
    private final NativeInventoryMenuSnapshot before;
    private NativeCraftingTableBroadcastAdmission broadcast;
    private final NativeMenuMetadataDeliveryAdmission metadataDelivery;
    private final NativeEffectCodec.ResolvedTableScript effect;
    private final NativeFoodSnapshot food;
    private final NativePlayerPoseSnapshot pose;
    private final int xpBits, xpTotal, xpLevel;
    private final List<PreparedNotice> notices;
    private final Map<Stat<?>, Integer> statsBefore, statsAfter;
    private final RecipeHolder<?> finalRecipe;
    private final NativeCraftingTableSnapshot.Data expectedAfterBroadcast;
    private final NativeInventoryMenuSnapshot.Data expectedAfterClose;
    private final int reservedBytes;
    private NativeSurvivalWorld.CommitReceipt authorizedReceipt;
    private boolean attempted, openAttempted;

    static NativeResolvedCraftingTablePlan prepare(NativeEffectDeliveryHost host, NativeWholeAction action, CubeCell table,
            NativeFirstLoopMetadata metadata, NativeCraftingRecipeAdmission recipes,
            List<NativeInventoryMenuPreparation.Click> clicks, NativeInventoryMenuPreparation.Limits limits) {
        try { return new NativeResolvedCraftingTablePlan(host, action, table, metadata, recipes, clicks, limits); }
        catch (RuntimeException | Error failure) {
            if (action.state() == NativeWholeAction.State.PREPARING) action.poisonPreparation();
            throw failure;
        }
    }
    private NativeResolvedCraftingTablePlan(NativeEffectDeliveryHost host, NativeWholeAction action, CubeCell table,
            NativeFirstLoopMetadata metadata, NativeCraftingRecipeAdmission recipes,
            List<NativeInventoryMenuPreparation.Click> clicks, NativeInventoryMenuPreparation.Limits limits) {
        this.host = host; this.action = action; this.table=table; player = host.player(); inventoryMenu = player.inventoryMenu; connection = player.connection;
        tableState=action.read(table);
        if (!tableState.is(Blocks.CRAFTING_TABLE)) throw refused("Resolved table action requires the actual placed crafting table");
        this.metadata = metadata; this.recipes = recipes; lifecycle=new NativeTableMenuLifecycleAdmission(player,table);
        if (!host.actions().owns(action) || NativeWholeAction.current() != action || action.state() != NativeWholeAction.State.PREPARING)
            throw refused("Resolved native menu plan requires its host's exact preparing action");
        metadata.requirePlayerIdentity(player); recipes.requirePlayerIdentity(player);
        NativeInventorySnapshot.requireResolvedMenu(player);
        var sharedSnapshots = new NativeInventoryMenuSnapshot.Budget(limits.menuSnapshotBytes());
        before = NativeInventoryMenuSnapshot.capture(player, inventoryMenu, player.getInventory(), sharedSnapshots);
        food = NativeFoodSnapshot.capture(player.getFoodData()); pose = NativePlayerPoseSnapshot.capture(player);
        xpBits = Float.floatToRawIntBits(player.experienceProgress); xpTotal = player.totalExperience; xpLevel = player.experienceLevel;
        var transcript = NativeCraftingTablePreparation.prepareResolvedScript(action, player, table, metadata, recipes, clicks, limits, sharedSnapshots);
        if (!before.data().equals(transcript.inventoryMenuBefore())) throw refused("Native resolved script before-image differs from its retained owner");
        requireResolved(transcript.after());
        effect = new NativeEffectCodec.ResolvedTableScript(action.world().world(), action.identity().ownerEpoch(),
                action.identity().actor(), action.identity().sequence(), table, action.world().palette().id(tableState), lifecycle.counterBefore(), clicks, transcript);
        // Preserve exact encoded cap before retaining delivery buffers or staging any live write.
        NativeEffectCodec.encode(effect);
        var prepared = new ArrayList<PreparedNotice>();
        var beforeStats = new LinkedHashMap<Stat<?>, Integer>(); var afterStats = new LinkedHashMap<Stat<?>, Integer>();
        int bytes = NativeCraftingTableBroadcastAdmission.reservePayload(player,transcript.after(),limits.noticeBytes());
        bytes=charge(bytes,NativeTableMenuLifecycleAdmission.reserveOpen(player,transcript.before(),limits.noticeBytes()-bytes),limits);
        Stat<?> interact=net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE);
        int interactionBefore=player.getStats().getValue(interact); beforeStats.put(interact,interactionBefore);
        afterStats.put(interact,NativeEffectDeliveryHost.checkedAdd(interactionBefore,1,"Native table interaction statistic"));
        int state = transcript.before().stateId();
        for (var notice : transcript.notices()) {
            if (notice instanceof NativeInventoryMenuPreparation.Statistic statistic) {
                Stat<?> stat = resolve(statistic.stat()); int initial = player.getStats().getValue(stat);
                beforeStats.putIfAbsent(stat, initial);
                int expected = NativeEffectDeliveryHost.checkedAdd(afterStats.getOrDefault(stat, initial), statistic.stat().amount(), "Native crafted statistic");
                afterStats.put(stat, expected); prepared.add(new PreparedStat(stat, statistic.stat().amount()));
                bytes = charge(bytes, 128, limits);
            } else if (notice instanceof NativeInventoryMenuPreparation.Crafted crafted) {
                RecipeHolder<?> recipe = recipe(crafted.recipe()); metadata.requireCraftedRecipe(recipe); recipes.requireTake(recipe);
                var input = new ArrayList<ItemStack>(crafted.input().size());
                if (crafted.input().size() > 9) throw refused("Native3x3 crafted input exceeds its shape");
                for (var image : crafted.input()) { input.add(image.materialize(player.registryAccess())); bytes = charge(bytes, 64 + image.encodedBytes(), limits); }
                metadata.requireItems(input); prepared.add(new PreparedCrafted(recipe, List.copyOf(input)));
            } else if (notice instanceof NativeInventoryMenuPreparation.Unlocked unlocked) {
                if (unlocked.recipes().size() != 1) throw refused("Unexpected resolved native recipe unlock count");
                var values = new ArrayList<RecipeHolder<?>>();
                for (var id : unlocked.recipes()) { var recipe = recipe(id); metadata.requireUnlockRecipe(recipe); values.add(recipe); }
                prepared.add(new PreparedUnlock(List.copyOf(values))); bytes = charge(bytes, 128, limits);
            } else if (notice instanceof NativeInventoryMenuPreparation.SlotPacket packet) {
                state = (state + 1) & 32767;
                if (packet.container() != transcript.before().containerId() || packet.slot() != 0 || packet.state() != state)
                    throw refused("Native result packet state progression differs from its actual script");
                ItemStack stack = packet.stack().materialize(player.registryAccess()); recipes.requirePreviewStack(stack);
                var nativePacket = new ClientboundContainerSetSlotPacket(transcript.before().containerId(), state, 0, stack);
                NativeInventoryMenuPreparation.requireNativePacketRoundTrip(nativePacket, packet.stack(), player);
                prepared.add(new PreparedPacket(nativePacket, stack.copy())); bytes = charge(bytes, 64 + packet.stack().encodedBytes() * 2, limits);
            } else throw refused("Unknown resolved native script notice");
        }
        if (state != transcript.after().stateId()) throw refused("Native script state ID has an uncaptured transition");
        if (!transcript.before().lastSlots().equals(transcript.after().lastSlots())
                || !transcript.before().remoteSlots().subList(1, 46).equals(transcript.after().remoteSlots().subList(1, 46))
                || !transcript.before().remoteCarried().equals(transcript.after().remoteCarried()))
            throw refused("Native detached script changed uncaptured observer caches");
        notices = List.copyOf(prepared); statsBefore = Map.copyOf(beforeStats); statsAfter = Map.copyOf(afterStats);
        finalRecipe = transcript.after().recipeUsed() == null ? null : recipe(transcript.after().recipeUsed());
        metadataDelivery = NativeMenuMetadataDeliveryAdmission.captureTable(player, metadata, recipes, transcript, limits.noticeBytes() - bytes);
        reservedBytes = charge(bytes, metadataDelivery.reservedBytes(), limits);
        expectedAfterBroadcast = expectedBroadcast(transcript.after());
        expectedAfterClose=expectedClose(before.data(),expectedAfterBroadcast);
        before.requireUnchanged(player); requireConserved(false); lifecycle.requireBefore(); metadata.revalidate(); recipes.revalidate();
    }
    NativeEffectCodec.ResolvedTableScript effect() { return effect; }
    List<NativeStackSnapshot> inventoryAfter() { return effect.transcript().after().inventory(); }
    int reservedDeliveryBytes() { return reservedBytes; }
    int reservedDeliveryOperations() {
        // Initial native cache copies, open/content/close packets, interaction statistic and
        // open/close event invocations are separately charged from captured click notices.
        int operations = Math.addExact(metadataDelivery.reservedOperations(),52);
        for (int i=0;i<46;i++) if (!NativeCraftingTableBroadcastAdmission.slotImage(effect.transcript().before(),i).isEmpty())
            operations=Math.addExact(operations,2);
        var after = effect.transcript().after();
        for (int i = 0; i < 46; i++) {
            var finalStack = NativeCraftingTableBroadcastAdmission.slotImage(after, i);
            if (!finalStack.equals(after.lastSlots().get(i))) operations = Math.addExact(operations, 1);
            if (!finalStack.equals(after.remoteSlots().get(i))) operations = Math.addExact(operations, 1);
        }
        if (!after.carried().equals(after.remoteCarried())) operations = Math.addExact(operations, 1);
        return operations;
    }
    void requireBeforeCommit(NativeWholeAction exactAction, NativeEffectCodec.ResolvedTableScript supplied) {
        if (exactAction != action || !effect.equals(supplied) || action.state() != NativeWholeAction.State.PREVALIDATING
                || NativeWholeAction.current() != action || !host.actions().owns(action) || attempted || openAttempted || authorizedReceipt != null)
            throw refused("Resolved native menu precommit capability is foreign or stale");
        requireBefore();
    }
    void requireBeforeApply(NativeWholeAction exactAction, NativeSurvivalWorld.CommitReceipt receipt) {
        if (exactAction != action || action.state() != NativeWholeAction.State.APPLYING || NativeWholeAction.current() != action
                || !host.actions().owns(action) || attempted || openAttempted || authorizedReceipt != null || !receipt.action().equals(action.identity())
                || !receipt.world().equals(action.world().world()) || !receipt.ownerEpoch().equals(action.world().ownerEpoch())
                || !receipt.changedCubes().isEmpty() || receipt.commitSequence() != action.world().commitSequence()
                || !receipt.effects().contains(NativeEffectCodec.encode(effect)))
            throw refused("Resolved native menu delivery lacks its exact committed receipt");
        requireBefore(); authorizedReceipt = receipt;
    }
    private void requireBefore() {
        if (player.connection != connection || player.inventoryMenu != inventoryMenu || host.player() != player)
            throw refused("Resolved native menu owner/connection changed");
        before.requireUnchanged(player); requireConserved(false); lifecycle.requireBefore(); metadataDelivery.require();
        metadata.revalidate(); recipes.revalidate();
        for (var entry : statsBefore.entrySet()) if (player.getStats().getValue(entry.getKey()) != entry.getValue())
            throw refused("Native crafted statistic changed before committed delivery");
    }
    /** Exact native placed-block use/open runs after commit and before the host's inventory diffs. */
    void open(NativeSurvivalWorld.CommitReceipt receipt) {
        if (openAttempted || attempted || receipt!=authorizedReceipt || action.state()!=NativeWholeAction.State.APPLYING
                || NativeWholeAction.current()!=action || !host.actions().owns(action))
            throw refused("Native table open lacks its single-use committed plan");
        openAttempted=true; lifecycle.requireBefore(); requireConserved(false); metadataDelivery.require();
        var pos=NativeVanillaPreparation.nativePos(table);
        var provider=tableState.getMenuProvider(player.level(),pos);
        NativeTableMenuLifecycleAdmission.requireProvider(provider,player,table);
        var result=tableState.useWithoutItem(player.level(),player,new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false));
        if (result!=net.minecraft.world.InteractionResult.CONSUME || !(player.containerMenu instanceof CraftingMenu opened))
            throw refused("Actual native placed-table use did not open CraftingMenu");
        menu=opened; lifecycle.requireOpened(menu); broadcast=NativeCraftingTableBroadcastAdmission.capture(player,menu);
        requireConserved(false); metadataDelivery.require(); metadata.revalidate(); recipes.revalidate();
        if (!NativeCraftingTableSnapshot.captureLive(player).equals(effect.transcript().before()))
            throw refused("Actual native table initialization differs from its prepared before image");
        var interact=net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE);
        if (player.getStats().getValue(interact)!=statsAfter.get(interact))
            throw refused("Native table interaction statistic was not delivered exactly once");
    }
    /** Called after only the host's exact41 InventorySlot effects; failed attempts remain permanently retained. */
    void apply(NativeSurvivalWorld.CommitReceipt receipt) {
        if (!openAttempted || menu==null || attempted || receipt != authorizedReceipt || action.state() != NativeWholeAction.State.APPLYING
                || NativeWholeAction.current() != action || !host.actions().owns(action))
            throw refused("Resolved native menu transcript cannot be replayed or supplied independently");
        attempted = true;
        requireConserved(true); broadcast.require(); metadataDelivery.require();
        lifecycle.installLiveCounter(effect.transcript().after().inventoryChanges());
        NativeCraftingTableSnapshot.result(menu).setRecipeUsed(finalRecipe);
        // Last-slot and inventory remote caches intentionally remain the original live before image.
        for (PreparedNotice notice : notices) {
            if (notice instanceof PreparedStat statistic) player.awardStat(statistic.stat(), statistic.amount());
            else if (notice instanceof PreparedCrafted crafted) player.triggerRecipeCrafted(crafted.recipe(), crafted.input());
            else if (notice instanceof PreparedUnlock unlock) player.awardRecipes(unlock.recipes());
            else if (notice instanceof PreparedPacket packet) {
                menu.setRemoteSlotNoCopy(0, packet.remote());
                if (menu.incrementStateId() != packet.packet().getStateId()) throw refused("Native result state ID changed during delivery");
                connection.send(packet.packet());
            }
            requireConserved(true); metadataDelivery.require(); metadata.revalidate();
        }
        var current = NativeCraftingTableSnapshot.captureLive(player);
        if (!current.equals(effect.transcript().after())) throw refused("Resolved live menu differs from native script before final broadcast");
        broadcast.require();
        menu.broadcastChanges(); // Genuine native inventory criteria and synchronization, exactly once.
        requireConserved(true); broadcast.require(); metadataDelivery.require(); metadata.revalidate(); recipes.revalidate();
        if (!NativeCraftingTableSnapshot.captureLive(player).equals(expectedAfterBroadcast))
            throw refused("Native final broadcast differs from its bounded observer/cache projection");
        lifecycle.requireEvents();
        player.closeContainer(); // Native resolved clearContainer + cache transfer + close event.
        if (player.containerMenu!=inventoryMenu || !NativeInventoryMenuSnapshot.captureLive(player).equals(expectedAfterClose))
            throw refused("Native table close differs from its exact inventory-menu transfer projection");
        NativeInventorySnapshot.requireResolvedMenu(player); requireConserved(true);
        metadataDelivery.require(); metadata.revalidate(); recipes.revalidate();
        for (var entry : statsAfter.entrySet()) if (player.getStats().getValue(entry.getKey()) != entry.getValue())
            throw refused("Native crafted statistic delivery differs from its exact prepared result");
    }
    private void requireConserved(boolean deliveredInventory) {
        var expectedInventory=deliveredInventory ? effect.transcript().after().inventory() : before.data().inventory();
        int selected=deliveredInventory ? effect.transcript().after().selected() : before.data().selected();
        if (player.inventoryMenu!=inventoryMenu || player.connection!=connection || player.getInventory().selected!=selected
                || (menu==null ? player.containerMenu!=inventoryMenu : player.containerMenu!=menu && player.containerMenu!=inventoryMenu))
            throw refused("Native table lifecycle owner or selection changed");
        for (int i=0;i<41;i++) if (!expectedInventory.get(i).matches(player.getInventory().getItem(i),player.registryAccess()))
            throw refused("Native table metadata changed conserved inventory");
        if (menu!=null && player.containerMenu==menu) {
            if (!menu.getCarried().isEmpty() || !menu.getSlot(0).getItem().isEmpty()
                    || NativeCraftingTableSnapshot.grid(menu).getItems().stream().anyMatch(stack->!stack.isEmpty()))
                throw refused("Native live composite table retained unresolved ownership");
        } else NativeInventorySnapshot.requireResolvedMenu(player);
        if (!food.equals(NativeFoodSnapshot.capture(player.getFoodData())) || !pose.equals(observeOwnedPose())
                || xpBits!=Float.floatToRawIntBits(player.experienceProgress) || xpTotal!=player.totalExperience || xpLevel!=player.experienceLevel)
            throw refused("Native table metadata changed conserved food/pose/XP");
    }
    /** Read-only equality probe inside this exact already-committed resolved table ownership. */
    private NativePlayerPoseSnapshot observeOwnedPose() {
        if (player.containerMenu==inventoryMenu) return NativePlayerPoseSnapshot.capture(player);
        if (menu==null || player.containerMenu!=menu || !openAttempted || authorizedReceipt==null
                || NativeWholeAction.current()!=action || action.state()!=NativeWholeAction.State.APPLYING
                || !host.actions().owns(action) || player.getClass()!=ServerPlayer.class || !player.server.isSameThread())
            throw refused("Native table pose observation lacks its exact committed menu owner");
        if (!player.isAlive() || player.isRemoved() || player.isPassenger() || player.isVehicle() || player.isSleeping()
                || player.isChangingDimension() || player.portalProcess!=null || player.isUsingItem()
                || !player.getActiveEffects().isEmpty() || player.getCamera()!=player || player.isFallFlying()
                || player.isAutoSpinAttack() || player.isSwimming() || player.getPose()!=net.minecraft.world.entity.Pose.STANDING)
            throw refused("Native table callback changed an unadmitted player transition state");
        var motion=player.getDeltaMovement();
        return new NativePlayerPoseSnapshot(player.level().dimension().location(),player.getX(),player.getY(),player.getZ(),
                player.getYRot(),player.getXRot(),motion.x,motion.y,motion.z,player.fallDistance,player.onGround(),
                player.gameMode.getGameModeForPlayer(),player.gameMode.getPreviousGameModeForPlayer(),
                NativePlayerPoseSnapshot.AbilityState.capture(player.getAbilities()));
    }
    static void requireResolved(NativeCraftingTableSnapshot.Data data) {
        if (!data.result().isEmpty() || !data.carried().isEmpty() || data.grid().stream().anyMatch(stack -> !stack.isEmpty())
                || data.removeCount() != 0 || data.suppressRemoteUpdates())
            throw refused("Native committed script must resolve grid/result/cursor and remote state");
    }
    static NativeCraftingTableSnapshot.Data expectedBroadcast(NativeCraftingTableSnapshot.Data after) {
        var slots = new ArrayList<NativeStackSnapshot>(46); int state = after.stateId();
        for (int i = 0; i < 46; i++) {
            var stack = NativeCraftingTableBroadcastAdmission.slotImage(after, i); slots.add(stack);
            if (!stack.equals(after.remoteSlots().get(i))) state = (state + 1) & 32767;
        }
        if (!after.carried().equals(after.remoteCarried())) state = (state + 1) & 32767;
        return new NativeCraftingTableSnapshot.Data(after.containerId(), after.inventory(), after.selected(), after.inventoryChanges(), after.grid(), after.result(),
                after.carried(), after.recipeUsed(), 0, state, slots, slots, after.carried(), false, after.quickcraftType());
    }
    static NativeInventoryMenuSnapshot.Data expectedClose(NativeInventoryMenuSnapshot.Data before,NativeCraftingTableSnapshot.Data table) {
        requireResolved(table);
        var last=new ArrayList<>(before.lastSlots()); var remote=new ArrayList<>(before.remoteSlots());
        for (int tableSlot=10;tableSlot<46;tableSlot++) {
            int inventory=NativeCraftingTableBroadcastAdmission.inventoryIndex(tableSlot);
            int menuSlot=inventory<9 ? inventory+36 : inventory;
            last.set(menuSlot,table.lastSlots().get(tableSlot)); remote.set(menuSlot,table.remoteSlots().get(tableSlot));
        }
        return new NativeInventoryMenuSnapshot.Data(table.inventory(),table.selected(),table.inventoryChanges(),before.grid(),before.result(),
                before.carried(),before.recipeUsed(),before.removeCount(),before.stateId(),last,remote,before.remoteCarried(),
                before.suppressRemoteUpdates(),before.quickcraftType());
    }
    private RecipeHolder<?> recipe(net.minecraft.resources.ResourceLocation id) {
        return player.server.getRecipeManager().byKey(id).orElseThrow(() -> refused("Prepared native recipe disappeared"));
    }
    private static int charge(int used, int amount, NativeInventoryMenuPreparation.Limits limits) {
        return NativeInventoryMenuBroadcastAdmission.checkedCharge(used, amount, limits.noticeBytes());
    }
    private static Stat<?> resolve(NativeEffectCodec.PlayerStat effect) {
        var type = BuiltInRegistries.STAT_TYPE.getOptional(effect.category()).orElseThrow(() -> refused("Unknown native crafted statistic category"));
        return resolve(type, effect);
    }
    private static <T> Stat<T> resolve(StatType<T> type, NativeEffectCodec.PlayerStat effect) {
        return type.get(type.getRegistry().getOptional(effect.value()).orElseThrow(() -> refused("Unknown native crafted statistic value")));
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
