package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Privately retained one-packet preparation/delivery. No public transcript-to-apply constructor. */
final class NativeMenuPacketPlan {
    private sealed interface PreparedNotice permits PreparedStat, PreparedCrafted, PreparedUnlock, PreparedPacket {}
    private record PreparedStat(Stat<?> stat, int amount) implements PreparedNotice {}
    private record PreparedCrafted(RecipeHolder<?> recipe, List<ItemStack> input) implements PreparedNotice {}
    private record PreparedUnlock(List<RecipeHolder<?>> recipes) implements PreparedNotice {}
    private record PreparedPacket(ClientboundContainerSetSlotPacket packet, ItemStack remote) implements PreparedNotice {}
    private final NativeSessionMenuOwner owner;
    private final NativeMenuBudget budget;
    private final NativeMenuBudget finalBoundaryBudget;
    private final Object lineage;
    private final NativeWholeAction action;
    private final long revision;
    private final ServerPlayer player;
    private final AbstractContainerMenu menu;
    private final NativeSessionMenuBoundary before;
    private final NativeFirstLoopMetadata metadata;
    private final NativeCraftingRecipeAdmission recipes;
    private final NativeMenuMetadataDeliveryAdmission delivery;
    private final NativeEffectCodec.SessionMenuPacket effect;
    private final List<PreparedNotice> notices;
    private final Map<Stat<?>, Integer> statsBefore, statsAfter;
    private final List<ItemStack> grid, remoteBefore, lastBefore, predictions;
    private final ItemStack result, carried, predictedCarried, remoteCarriedBefore;
    private final RecipeHolder<?> recipe;
    private final int reservedBytes;
    private int materializedBytes;
    private NativeSurvivalWorld.CommitReceipt authorizedReceipt;
    private boolean attempted;
    static NativeMenuPacketPlan prepare(NativeSessionMenuOwner owner, Object lineage, NativeWholeAction action,
            NativeSessionMenuBoundary before, NativeMenuPacketInput input, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, NativeInventoryMenuPreparation.Limits limits, NativeMenuBudget budget) {
        try { return new NativeMenuPacketPlan(owner, lineage, action, before, input, metadata, recipes, limits, budget); }
        catch (RuntimeException | Error failure) {
            if (action.state() == NativeWholeAction.State.PREPARING) action.poisonPreparation(); throw failure;
        }
    }
    private NativeMenuPacketPlan(NativeSessionMenuOwner owner, Object lineage, NativeWholeAction action,
            NativeSessionMenuBoundary before, NativeMenuPacketInput input, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, NativeInventoryMenuPreparation.Limits limits, NativeMenuBudget budget) {
        this.budget = budget; this.owner = owner; this.lineage = lineage; this.action = action; this.before = before; this.metadata = metadata; this.recipes = recipes;
        player = owner.host().player(); menu = before.activeMenu(); revision = owner.revision();
        owner.requirePreparing(action); before.requireUnchanged(metadata, budget);
        var transcript = NativeSessionMenuPreparation.prepare(owner, action, before, input, metadata, recipes, limits, budget);
        int finalCaptureBytes = NativeSessionMenuBoundary.captureReservation(transcript.after());
        budget.reserve(finalCaptureBytes); finalBoundaryBudget = new NativeMenuBudget(finalCaptureBytes);
        var journal = new NativeMenuPacketJournal(owner.host().session(), revision, input, transcript);
        effect = new NativeEffectCodec.SessionMenuPacket(action.world().world(), action.identity().ownerEpoch(),
                action.identity().actor(), action.identity().sequence(), journal);
        // Existing global64KiB effect cap stays unchanged; encode refusal is strictly precommit.
        budget.reserve(2 * io.earthplatform.minecraft.survival.world.NativeActionEffect.MAX_PAYLOAD_BYTES);
        if (!effect.equals(NativeEffectCodec.decode(NativeEffectCodec.encode(effect), player.registryAccess())))
            throw refused("Native packet evidence changed during effect codec round trip");
        grid = materialize(transcript.clicked().active().grid(), budget);
        result = materialize(transcript.clicked().active().result(), budget); carried = materialize(transcript.clicked().active().carried(), budget);
        recipe = transcript.clicked().active().recipeUsed() == null ? null : recipe(transcript.clicked().active().recipeUsed());
        remoteBefore = materialize(transcript.before().active().remoteSlots(), budget); lastBefore = materialize(transcript.before().active().lastSlots(), budget);
        remoteCarriedBefore = materialize(transcript.before().active().remoteCarried(), budget);
        predictions = materialize(input.changedSlots().stream().map(NativeMenuPacketInput.Prediction::stack).toList(), budget);
        predictedCarried = materialize(input.carried(), budget);
        var prepared = new ArrayList<PreparedNotice>(); var initial = new LinkedHashMap<Stat<?>, Integer>(); var target = new LinkedHashMap<Stat<?>, Integer>();
        int state = transcript.before().active().stateId();
        for (var notice : transcript.notices()) {
            budget.reserve(256);
            if (notice instanceof NativeInventoryMenuPreparation.Statistic statistic) {
                Stat<?> stat = resolve(statistic.stat()); int value = player.getStats().getValue(stat); initial.putIfAbsent(stat, value);
                target.put(stat, NativeEffectDeliveryHost.checkedAdd(target.getOrDefault(stat, value), statistic.stat().amount(), "Native packet crafted statistic"));
                prepared.add(new PreparedStat(stat, statistic.stat().amount()));
            } else if (notice instanceof NativeInventoryMenuPreparation.Crafted craft) {
                var value = recipe(craft.recipe()); metadata.requireCraftedRecipe(value); recipes.requireTake(value);
                prepared.add(new PreparedCrafted(value, materialize(craft.input(), budget)));
            } else if (notice instanceof NativeInventoryMenuPreparation.Unlocked unlock) {
                if (unlock.recipes().size() != 1) throw refused("Native packet unlock shape changed");
                var value = recipe(unlock.recipes().getFirst()); metadata.requireUnlockRecipe(value); prepared.add(new PreparedUnlock(List.of(value)));
            } else if (notice instanceof NativeInventoryMenuPreparation.SlotPacket slot) {
                state = (state + 1) & 32767;
                if (slot.container() != menu.containerId || slot.slot() != 0 || slot.state() != state) throw refused("Native result packet state progression changed");
                ItemStack stack = materialize(slot.stack(), budget); budget.reserve(32768 + 64 + slot.stack().encodedBytes());
                var nativePacket = new ClientboundContainerSetSlotPacket(slot.container(), slot.state(), slot.slot(), stack);
                NativeInventoryMenuPreparation.requireNativePacketRoundTrip(nativePacket, slot.stack(), player);
                prepared.add(new PreparedPacket(nativePacket, stack.copy()));
            } else throw refused("Unknown native packet preparation notice");
        }
        if (state != transcript.clicked().active().stateId()) throw refused("Native click made an uncaptured state-ID change");
        notices = List.copyOf(prepared); statsBefore = Map.copyOf(initial); statsAfter = Map.copyOf(target);
        int allowance = Math.min(limits.noticeBytes(), budget.remaining() / 3);
        // Retained delivery graph plus one encoder and decoded/materialized scratch peak, before allocation.
        budget.reserve(Math.multiplyExact(3, allowance));
        if (transcript.before().tableMenu() == null) {
            var nativeTranscript = new NativeInventoryMenuPreparation.Result(inventoryImage(transcript.before()), inventoryImage(transcript.after()),
                    transcript.notices(), List.of(inventoryImage(transcript.after())));
            delivery = NativeMenuMetadataDeliveryAdmission.capture(player, metadata, recipes, nativeTranscript, allowance);
        } else {
            var nativeTranscript = new NativeCraftingTablePreparation.Result(inactiveImage(transcript.before()), tableImage(transcript.before()), tableImage(transcript.after()),
                    transcript.notices(), List.of(tableImage(transcript.after())));
            delivery = NativeMenuMetadataDeliveryAdmission.captureTable(player, metadata, recipes, nativeTranscript, allowance);
        }
        int copies = 0;
        for (int i = 0; i < 46; i++) copies = Math.addExact(copies, 128 + NativeSessionMenuBoundary.slotImage(transcript.after(), i).encodedBytes() * 2);
        copies = Math.addExact(copies, 128 + transcript.after().active().carried().encodedBytes() * 2);
        reservedBytes = Math.addExact(delivery.reservedBytes(), Math.addExact(materializedBytes,
                Math.addExact(copies, Math.addExact(transcript.syncBytes() * 2, notices.size() * 256 + 1024))));
        if (reservedBytes > limits.noticeBytes()) throw refused("Native packet delivery exceeds its explicit output allowance");
        before.requireUnchanged(metadata, budget);
    }
    NativeEffectCodec.SessionMenuPacket effect() { return effect; }
    NativeSessionMenuBoundary.Data after() { return effect.journal().transcript().after(); }
    List<NativeStackSnapshot> inventoryAfter() { return after().inventory(); }
    int reservedDeliveryBytes() { return reservedBytes; }
    int reservedDeliveryOperations() { return Math.addExact(notices.size(), Math.addExact(47, delivery.reservedOperations())); }
    void requireBeforeCommit(NativeWholeAction exact, NativeEffectCodec.SessionMenuPacket supplied) {
        owner.requirePlan(this, lineage, exact, NativeWholeAction.State.PREVALIDATING, revision);
        if (exact != action || attempted || authorizedReceipt != null || !effect.equals(supplied)) throw refused("Foreign native menu packet evidence");
        requireBefore();
    }
    void requireBeforeApply(NativeWholeAction exact, NativeSurvivalWorld.CommitReceipt receipt) {
        owner.requirePlan(this, lineage, exact, NativeWholeAction.State.APPLYING, revision);
        if (exact != action || attempted || authorizedReceipt != null || !receipt.action().equals(action.identity())
                || !receipt.world().equals(action.world().world()) || !receipt.ownerEpoch().equals(action.world().ownerEpoch())
                || !receipt.changedCubes().isEmpty() || receipt.commitSequence() != action.world().commitSequence()
                || !receipt.effects().contains(NativeEffectCodec.encode(effect))) throw refused("Native packet lacks its exact committed receipt");
        requireBefore(); authorizedReceipt = receipt;
    }
    private void requireBefore() {
        before.requireUnchanged(metadata, budget); before.requireConservedPlayer(); delivery.require(); metadata.revalidate(); recipes.revalidate();
        for (var entry : statsBefore.entrySet()) if (player.getStats().getValue(entry.getKey()) != entry.getValue()) throw refused("Native packet statistic changed before commit");
    }
    void apply(NativeSurvivalWorld.CommitReceipt receipt) {
        owner.requirePlan(this, lineage, action, NativeWholeAction.State.APPLYING, revision);
        if (attempted || receipt != authorizedReceipt) throw refused("Native packet cannot be replayed or substituted");
        attempted = true;
        try {
            var transcript = effect.journal().transcript();
            requireInventory(transcript.clicked()); before.requireConservedPlayer(); delivery.require();
            // Host has applied exactly the derived41 inventory effects. Install only this exact
            // prepared menu's own grid/result/cursor, never a client prediction stack.
            replace(NativeSessionMenuBoundary.read(net.minecraft.world.inventory.TransientCraftingContainer.class, NativeSessionMenuPreparation.grid(menu), "items", NonNullList.class), grid);
            NativeSessionMenuPreparation.result(menu).setItem(0, result); NativeSessionMenuPreparation.result(menu).setRecipeUsed(recipe);
            menu.setCarried(carried);
            NativeSessionMenuBoundary.write(Inventory.class, player.getInventory(), "timesChanged", int.class, transcript.clicked().inventoryChanges());
            NativeSessionMenuBoundary.write(ResultSlot.class, menu.getSlot(0), "removeCount", int.class, transcript.clicked().active().removeCount());
            replace(NativeSessionMenuBoundary.read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class), lastBefore);
            replace(NativeSessionMenuBoundary.read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class), remoteBefore);
            menu.setRemoteCarried(remoteCarriedBefore); menu.suppressRemoteUpdates();
            for (var notice : notices) {
                if (notice instanceof PreparedStat stat) player.awardStat(stat.stat(), stat.amount());
                else if (notice instanceof PreparedCrafted crafted) player.triggerRecipeCrafted(crafted.recipe(), crafted.input());
                else if (notice instanceof PreparedUnlock unlock) player.awardRecipes(unlock.recipes());
                else if (notice instanceof PreparedPacket packet) {
                    menu.setRemoteSlotNoCopy(0, packet.remote());
                    if (menu.incrementStateId() != packet.packet().getStateId()) throw refused("Native result packet state changed during delivery");
                    player.connection.send(packet.packet());
                }
                requireInventory(transcript.clicked()); before.requireConservedPlayer(); delivery.require(); metadata.revalidate();
            }
            var clicked = NativeSessionMenuBoundary.observeDuringApply(player, action.world(), before.table(), metadata, budget.scratch());
            before.requireSameTopology(clicked);
            if (!clicked.data().equals(transcript.clicked())) throw refused("Live native clicked boundary differs before prediction reconciliation");
            var input = effect.journal().packet();
            for (int i = 0; i < input.changedSlots().size(); i++) menu.setRemoteSlotNoCopy(input.changedSlots().get(i).slot(), predictions.get(i));
            menu.setRemoteCarried(predictedCarried); menu.resumeRemoteUpdates();
            // Exact pinned native final listener and synchronizer calls, including stale-state resync.
            if (transcript.staleState()) menu.broadcastFullState(); else menu.broadcastChanges();
            requireInventory(transcript.after()); before.requireConservedPlayer(); delivery.require(); metadata.revalidate(); recipes.revalidate();
            var after = captureCurrent(); before.requireSameTopology(after);
            if (!after.data().equals(transcript.after())) throw refused("Native packet observer/cache result differs from prepared transcript");
            for (var entry : statsAfter.entrySet()) if (player.getStats().getValue(entry.getKey()) != entry.getValue()) throw refused("Native packet statistic delivery changed");
            owner.applied(this, lineage, action, receipt, after);
        } catch (RuntimeException | Error failure) { owner.failed(this, lineage); throw failure; }
    }
    private NativeSessionMenuBoundary captureCurrent() {
        return NativeSessionMenuBoundary.capture(player, action.world(), before.table(), metadata, finalBoundaryBudget);
    }
    private void requireInventory(NativeSessionMenuBoundary.Data expected) {
        if (player.containerMenu != menu || player.getInventory().selected != expected.selected()) throw refused("Native menu/current selection changed during delivery");
        for (int i = 0; i < 41; i++) if (!expected.inventory().get(i).matches(player.getInventory().getItem(i), player.registryAccess()))
            throw refused("Native menu callback changed shared inventory");
    }
    private ItemStack materialize(NativeStackSnapshot image, NativeMenuBudget budget) {
        materializedBytes = Math.addExact(materializedBytes, 128 + image.encodedBytes() * 2);
        budget.reserve(128 + image.encodedBytes() * 2); return image.materialize(player.registryAccess());
    }
    private List<ItemStack> materialize(List<NativeStackSnapshot> images, NativeMenuBudget budget) {
        budget.reserve(64 + images.size() * 8); var result = new ArrayList<ItemStack>(images.size());
        for (var image : images) result.add(materialize(image, budget)); return List.copyOf(result);
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void replace(List target, List<ItemStack> source) {
        if (target.size() != source.size()) throw refused("Native delivery vector size changed");
        for (int i = 0; i < source.size(); i++) target.set(i, source.get(i));
    }
    private RecipeHolder<?> recipe(net.minecraft.resources.ResourceLocation id) {
        return player.server.getRecipeManager().byKey(id).orElseThrow(() -> refused("Native packet recipe disappeared"));
    }
    private static NativeInventoryMenuSnapshot.Data inventoryImage(NativeSessionMenuBoundary.Data data) { return inventoryImage(data, data.active()); }
    private static NativeInventoryMenuSnapshot.Data inactiveImage(NativeSessionMenuBoundary.Data data) { return inventoryImage(data, data.inventoryMenu()); }
    private static NativeInventoryMenuSnapshot.Data inventoryImage(NativeSessionMenuBoundary.Data data, NativeSessionMenuBoundary.MenuData menu) {
        return new NativeInventoryMenuSnapshot.Data(data.inventory(), data.selected(), data.inventoryChanges(), menu.grid(), menu.result(), menu.carried(), menu.recipeUsed(),
                menu.removeCount(), menu.stateId(), menu.lastSlots(), menu.remoteSlots(), menu.remoteCarried(), menu.suppressRemoteUpdates(), menu.quickcraftType());
    }
    private static NativeCraftingTableSnapshot.Data tableImage(NativeSessionMenuBoundary.Data data) {
        var menu = data.active(); return new NativeCraftingTableSnapshot.Data(menu.containerId(), data.inventory(), data.selected(), data.inventoryChanges(), menu.grid(),
                menu.result(), menu.carried(), menu.recipeUsed(), menu.removeCount(), menu.stateId(), menu.lastSlots(), menu.remoteSlots(), menu.remoteCarried(), menu.suppressRemoteUpdates(), menu.quickcraftType());
    }
    private static Stat<?> resolve(NativeEffectCodec.PlayerStat effect) {
        return resolve(BuiltInRegistries.STAT_TYPE.getOptional(effect.category()).orElseThrow(() -> refused("Unknown native packet statistic")), effect);
    }
    private static <T> Stat<T> resolve(StatType<T> type, NativeEffectCodec.PlayerStat effect) {
        return type.get(type.getRegistry().getOptional(effect.value()).orElseThrow(() -> refused("Unknown native packet statistic value")));
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
