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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Private host-retained, receipt-scoped delivery for a resolved-to-resolved native click script. */
final class NativeResolvedInventoryMenuPlan {
    private sealed interface PreparedNotice permits PreparedStat, PreparedCrafted, PreparedUnlock, PreparedPacket {}
    private record PreparedStat(Stat<?> stat, int amount) implements PreparedNotice {}
    private record PreparedCrafted(RecipeHolder<?> recipe, List<ItemStack> input) implements PreparedNotice {}
    private record PreparedUnlock(List<RecipeHolder<?>> recipes) implements PreparedNotice {}
    private record PreparedPacket(ClientboundContainerSetSlotPacket packet, ItemStack remote) implements PreparedNotice {}
    private final NativeEffectDeliveryHost host;
    private final NativeWholeAction action;
    private final ServerPlayer player;
    private final InventoryMenu menu;
    private final Field inventoryCounter;
    private final ServerGamePacketListenerImpl connection;
    private final NativeFirstLoopMetadata metadata;
    private final NativeCraftingRecipeAdmission recipes;
    private final NativeInventoryMenuSnapshot before;
    private final NativeInventoryMenuBroadcastAdmission broadcast;
    private final NativeMenuMetadataDeliveryAdmission metadataDelivery;
    private final NativeEffectCodec.ResolvedMenuScript effect;
    private final NativeFoodSnapshot food;
    private final NativePlayerPoseSnapshot pose;
    private final int xpBits, xpTotal, xpLevel;
    private final List<PreparedNotice> notices;
    private final Map<Stat<?>, Integer> statsBefore, statsAfter;
    private final RecipeHolder<?> finalRecipe;
    private final NativeInventoryMenuSnapshot.Data expectedAfterBroadcast;
    private final int reservedBytes;
    private NativeSurvivalWorld.CommitReceipt authorizedReceipt;
    private boolean attempted;

    static NativeResolvedInventoryMenuPlan prepare(NativeEffectDeliveryHost host, NativeWholeAction action,
            NativeFirstLoopMetadata metadata, NativeCraftingRecipeAdmission recipes,
            List<NativeInventoryMenuPreparation.Click> clicks, NativeInventoryMenuPreparation.Limits limits) {
        try { return new NativeResolvedInventoryMenuPlan(host, action, metadata, recipes, clicks, limits); }
        catch (RuntimeException | Error failure) {
            if (action.state() == NativeWholeAction.State.PREPARING) action.poisonPreparation();
            throw failure;
        }
    }
    private NativeResolvedInventoryMenuPlan(NativeEffectDeliveryHost host, NativeWholeAction action,
            NativeFirstLoopMetadata metadata, NativeCraftingRecipeAdmission recipes,
            List<NativeInventoryMenuPreparation.Click> clicks, NativeInventoryMenuPreparation.Limits limits) {
        this.host = host; this.action = action; player = host.player(); menu = player.inventoryMenu; connection = player.connection;
        this.metadata = metadata; this.recipes = recipes; inventoryCounter = resolveNativeCounter();
        if (!host.actions().owns(action) || NativeWholeAction.current() != action || action.state() != NativeWholeAction.State.PREPARING)
            throw refused("Resolved native menu plan requires its host's exact preparing action");
        metadata.requirePlayerIdentity(player); recipes.requirePlayerIdentity(player);
        NativeInventorySnapshot.requireResolvedMenu(player);
        broadcast = NativeInventoryMenuBroadcastAdmission.capture(player);
        var sharedSnapshots = new NativeInventoryMenuSnapshot.Budget(limits.menuSnapshotBytes());
        before = NativeInventoryMenuSnapshot.capture(player, menu, player.getInventory(), sharedSnapshots);
        food = NativeFoodSnapshot.capture(player.getFoodData()); pose = NativePlayerPoseSnapshot.capture(player);
        xpBits = Float.floatToRawIntBits(player.experienceProgress); xpTotal = player.totalExperience; xpLevel = player.experienceLevel;
        var transcript = NativeInventoryMenuPreparation.prepareResolvedScript(action, player, metadata, recipes, clicks, limits, sharedSnapshots);
        if (!before.data().equals(transcript.before())) throw refused("Native resolved script before-image differs from its retained owner");
        requireResolved(transcript.after());
        effect = new NativeEffectCodec.ResolvedMenuScript(action.world().world(), action.identity().ownerEpoch(),
                action.identity().actor(), action.identity().sequence(), clicks, transcript);
        // Preserve exact encoded cap before retaining delivery buffers or staging any live write.
        NativeEffectCodec.encode(effect);
        var prepared = new ArrayList<PreparedNotice>();
        var beforeStats = new LinkedHashMap<Stat<?>, Integer>(); var afterStats = new LinkedHashMap<Stat<?>, Integer>();
        int bytes = broadcast.reservePayload(transcript.after(), limits.noticeBytes());
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
                if (crafted.input().size() > 4) throw refused("Native2x2 crafted input exceeds its shape");
                for (var image : crafted.input()) { input.add(image.materialize(player.registryAccess())); bytes = charge(bytes, 64 + image.encodedBytes(), limits); }
                metadata.requireItems(input); prepared.add(new PreparedCrafted(recipe, List.copyOf(input)));
            } else if (notice instanceof NativeInventoryMenuPreparation.Unlocked unlocked) {
                if (unlocked.recipes().size() != 1) throw refused("Unexpected resolved native recipe unlock count");
                var values = new ArrayList<RecipeHolder<?>>();
                for (var id : unlocked.recipes()) { var recipe = recipe(id); metadata.requireUnlockRecipe(recipe); values.add(recipe); }
                prepared.add(new PreparedUnlock(List.copyOf(values))); bytes = charge(bytes, 128, limits);
            } else if (notice instanceof NativeInventoryMenuPreparation.SlotPacket packet) {
                state = (state + 1) & 32767;
                if (packet.container() != 0 || packet.slot() != 0 || packet.state() != state)
                    throw refused("Native result packet state progression differs from its actual script");
                ItemStack stack = packet.stack().materialize(player.registryAccess()); recipes.requirePreviewStack(stack);
                var nativePacket = new ClientboundContainerSetSlotPacket(0, state, 0, stack);
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
        metadataDelivery = NativeMenuMetadataDeliveryAdmission.capture(player, metadata, recipes, transcript, limits.noticeBytes() - bytes);
        reservedBytes = charge(bytes, metadataDelivery.reservedBytes(), limits);
        expectedAfterBroadcast = expectedBroadcast(transcript.after());
        before.requireUnchanged(player); requireConserved(false); broadcast.require(); metadata.revalidate(); recipes.revalidate();
    }
    NativeEffectCodec.ResolvedMenuScript effect() { return effect; }
    List<NativeStackSnapshot> inventoryAfter() { return effect.transcript().after().inventory(); }
    int reservedDeliveryBytes() { return reservedBytes; }
    int reservedDeliveryOperations() {
        int operations = metadataDelivery.reservedOperations();
        var after = effect.transcript().after();
        for (int i = 0; i < 46; i++) {
            var finalStack = NativeInventoryMenuBroadcastAdmission.slotImage(after, i);
            if (!finalStack.equals(after.lastSlots().get(i))) operations = Math.addExact(operations, 1);
            if (!finalStack.equals(after.remoteSlots().get(i))) operations = Math.addExact(operations, 1);
        }
        if (!after.carried().equals(after.remoteCarried())) operations = Math.addExact(operations, 1);
        return operations;
    }
    void requireBeforeCommit(NativeWholeAction exactAction, NativeEffectCodec.ResolvedMenuScript supplied) {
        if (exactAction != action || !effect.equals(supplied) || action.state() != NativeWholeAction.State.PREVALIDATING
                || NativeWholeAction.current() != action || !host.actions().owns(action) || attempted || authorizedReceipt != null)
            throw refused("Resolved native menu precommit capability is foreign or stale");
        requireBefore();
    }
    void requireBeforeApply(NativeWholeAction exactAction, NativeSurvivalWorld.CommitReceipt receipt) {
        if (exactAction != action || action.state() != NativeWholeAction.State.APPLYING || NativeWholeAction.current() != action
                || !host.actions().owns(action) || attempted || authorizedReceipt != null || !receipt.action().equals(action.identity())
                || !receipt.world().equals(action.world().world()) || !receipt.ownerEpoch().equals(action.world().ownerEpoch())
                || !receipt.changedCubes().isEmpty() || receipt.commitSequence() != action.world().commitSequence()
                || !receipt.effects().contains(NativeEffectCodec.encode(effect)))
            throw refused("Resolved native menu delivery lacks its exact committed receipt");
        requireBefore(); authorizedReceipt = receipt;
    }
    private void requireBefore() {
        if (player.connection != connection || player.inventoryMenu != menu || host.player() != player)
            throw refused("Resolved native menu owner/connection changed");
        before.requireUnchanged(player); requireConserved(false); broadcast.require(); metadataDelivery.require();
        metadata.revalidate(); recipes.revalidate();
        for (var entry : statsBefore.entrySet()) if (player.getStats().getValue(entry.getKey()) != entry.getValue())
            throw refused("Native crafted statistic changed before committed delivery");
    }
    /** Called after only the host's exact41 InventorySlot effects; failed attempts remain permanently retained. */
    void apply(NativeSurvivalWorld.CommitReceipt receipt) {
        if (attempted || receipt != authorizedReceipt || action.state() != NativeWholeAction.State.APPLYING
                || NativeWholeAction.current() != action || !host.actions().owns(action))
            throw refused("Resolved native menu transcript cannot be replayed or supplied independently");
        attempted = true;
        requireConserved(true); broadcast.require(); metadataDelivery.require();
        setNativeCounter(effect.transcript().after().inventoryChanges());
        NativeInventoryMenuSnapshot.result(menu).setRecipeUsed(finalRecipe);
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
        var current = NativeInventoryMenuSnapshot.captureLive(player);
        if (!current.equals(effect.transcript().after())) throw refused("Resolved live menu differs from native script before final broadcast");
        broadcast.require();
        menu.broadcastChanges(); // Genuine native inventory criteria and synchronization, exactly once.
        requireConserved(true); broadcast.require(); metadataDelivery.require(); metadata.revalidate(); recipes.revalidate();
        if (!NativeInventoryMenuSnapshot.captureLive(player).equals(expectedAfterBroadcast))
            throw refused("Native final broadcast differs from its bounded observer/cache projection");
        for (var entry : statsAfter.entrySet()) if (player.getStats().getValue(entry.getKey()) != entry.getValue())
            throw refused("Native crafted statistic delivery differs from its exact prepared result");
    }
    private void requireConserved(boolean deliveredInventory) {
        var expected = deliveredInventory ? effect.transcript().after() : before.data();
        if (player.inventoryMenu != menu || player.containerMenu != menu || player.connection != connection
                || player.getInventory().selected != expected.selected()) throw refused("Native menu owner or selection changed");
        for (int i = 0; i < 41; i++) if (!expected.inventory().get(i).matches(player.getInventory().getItem(i), player.registryAccess()))
            throw refused("Native menu callbacks changed conserved inventory");
        NativeInventorySnapshot.requireResolvedMenu(player);
        if (!food.equals(NativeFoodSnapshot.capture(player.getFoodData())) || !pose.equals(NativePlayerPoseSnapshot.capture(player))
                || xpBits != Float.floatToRawIntBits(player.experienceProgress) || xpTotal != player.totalExperience || xpLevel != player.experienceLevel)
            throw refused("Native menu metadata changed conserved food/pose/XP");
    }
    static void requireResolved(NativeInventoryMenuSnapshot.Data data) {
        if (!data.result().isEmpty() || !data.carried().isEmpty() || data.grid().stream().anyMatch(stack -> !stack.isEmpty())
                || data.removeCount() != 0 || data.suppressRemoteUpdates())
            throw refused("Native committed script must resolve grid/result/cursor and remote state");
    }
    static NativeInventoryMenuSnapshot.Data expectedBroadcast(NativeInventoryMenuSnapshot.Data after) {
        var slots = new ArrayList<NativeStackSnapshot>(46); int state = after.stateId();
        for (int i = 0; i < 46; i++) {
            var stack = NativeInventoryMenuBroadcastAdmission.slotImage(after, i); slots.add(stack);
            if (!stack.equals(after.remoteSlots().get(i))) state = (state + 1) & 32767;
        }
        if (!after.carried().equals(after.remoteCarried())) state = (state + 1) & 32767;
        return new NativeInventoryMenuSnapshot.Data(after.inventory(), after.selected(), after.inventoryChanges(), after.grid(), after.result(),
                after.carried(), after.recipeUsed(), 0, state, slots, slots, after.carried(), false, after.quickcraftType());
    }
    private static Field resolveNativeCounter() {
        try {
            Field field = Inventory.class.getDeclaredField("timesChanged");
            if (field.getType() != int.class || java.lang.reflect.Modifier.isFinal(field.getModifiers()) || !field.trySetAccessible())
                throw refused("Pinned native inventory counter is unavailable");
            return field;
        } catch (ReflectiveOperationException failure) { throw refused("Native inventory counter admission failed"); }
    }
    private void setNativeCounter(int counter) {
        try { inventoryCounter.setInt(player.getInventory(), counter); }
        catch (IllegalAccessException failure) { throw refused("Native inventory counter delivery failed"); }
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
