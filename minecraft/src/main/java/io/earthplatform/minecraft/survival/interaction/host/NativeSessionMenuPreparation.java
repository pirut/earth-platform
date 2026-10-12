package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import static io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.*;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;

/** One actual detached native clicked call for one retained live menu packet. No live swap or callback rollback. */
public final class NativeSessionMenuPreparation {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private NativeSessionMenuPreparation() {}
    public record Result(NativeSessionMenuBoundary.Data before, NativeSessionMenuBoundary.Data clicked,
            NativeSessionMenuBoundary.Data after, List<Notice> notices, boolean staleState, int syncPackets, int syncBytes) {
        public Result { notices = List.copyOf(notices); }
    }
    static boolean active() { return ACTIVE.get() != null; }
    static void poisonActivePreparation(Throwable failure) {
        Scope scope = ACTIVE.get();
        if (scope != null && NativeWholeAction.current() == scope.action && scope.action.state() == NativeWholeAction.State.PREPARING
                && scope.owner.host().actions().owns(scope.action)) {
            try { scope.action.poisonPreparation(); } catch (RuntimeException | Error secondary) { failure.addSuppressed(secondary); }
        }
    }
    private enum Phase { CREATING, HYDRATING, CLICKING, SYNCHRONIZING, INSPECTING }
    private record Preview(List<NativeStackSnapshot> grid, RecipeHolder<?> recipe, NativeStackSnapshot result) {}
    private static final class Scope {
        final NativeSessionMenuOwner owner;
        final NativeWholeAction action;
        final NativeSessionMenuBoundary before;
        final ServerPlayer player;
        final Level level;
        final ServerGamePacketListenerImpl connection;
        final NativeFirstLoopMetadata metadata;
        final NativeCraftingRecipeAdmission recipes;
        final NativeMenuBudget budget;
        final Limits limits;
        final List<Notice> notices = new ArrayList<>();
        Inventory inventory;
        AbstractContainerMenu menu;
        Phase phase = Phase.CREATING;
        RecipeHolder<CraftingRecipe> gridRecipe;
        Preview preview;
        RecipeHolder<?> takingRecipe;
        ItemStack takingStack;
        boolean inGrid;
        int bytes, syncPackets, syncBytes;
        Scope(NativeSessionMenuOwner owner, NativeWholeAction action, NativeSessionMenuBoundary before,
                NativeFirstLoopMetadata metadata, NativeCraftingRecipeAdmission recipes, Limits limits, NativeMenuBudget budget) {
            this.owner = owner; this.action = action; this.before = before; this.metadata = metadata; this.recipes = recipes;
            this.limits = limits; this.budget = budget; player = owner.host().player(); level = player.level(); connection = player.connection;
        }
        void require() {
            owner.requirePreparing(action);
            if (player.level() != level || player.connection != connection || player.containerMenu != before.activeMenu())
                throw refused("Native per-packet callback escaped its exact retained menu");
        }
        void requireMenu(AbstractContainerMenu requested) {
            require();
            if (phase == Phase.CREATING || requested != menu || menu == player.containerMenu || menu == player.inventoryMenu)
                throw refused("Native callback lacks the exact detached per-packet menu capability");
        }
        void requireHolder(RecipeCraftingHolder holder, Player requested) {
            requireMenu(menu);
            if (phase != Phase.CLICKING || holder != result(menu) || requested != player || takingRecipe == null)
                throw refused("Native recipe award escaped its exact per-packet result removal");
        }
        void add(Notice notice, int size) {
            if (size < 0 || notices.size() >= limits.notices() || size > limits.noticeBytes() - bytes)
                throw refused("Native per-packet notice allowance exhausted");
            budget.reserve(size + 64); notices.add(notice); bytes += size;
        }
        List<NativeStackSnapshot> grid() {
            return NativeSessionMenuPreparation.grid(menu).getItems().stream().map(stack -> budget.image(stack, player)).toList();
        }
        NativeSessionMenuBoundary.Data image() {
            budget.reserve(512);
            var images = new ArrayList<NativeStackSnapshot>(41);
            metadata.requireItems(inventory.items); metadata.requireItems(inventory.armor); metadata.requireItems(inventory.offhand);
            for (int i = 0; i < 41; i++) images.add(budget.image(inventory.getItem(i), player));
            var current = NativeSessionMenuBoundary.captureMenu(player, menu, metadata, budget);
            var original = before.data();
            return new NativeSessionMenuBoundary.Data(images, inventory.selected, inventory.getTimesChanged(), original.containerCounter(),
                    current.table() ? original.inventoryMenu() : current, current.table() ? current : null);
        }
        void syncPacket(Packet<?> packet, int allowance) {
            requireMenu(menu);
            if (phase != Phase.SYNCHRONIZING || ++syncPackets > 47) throw refused("Native synchronization escaped its fixed packet profile");

            var raw = io.netty.buffer.Unpooled.buffer(256, allowance);
            try {
                var buf = new net.minecraft.network.RegistryFriendlyByteBuf(raw, player.registryAccess());
                if (packet instanceof ClientboundContainerSetSlotPacket slot) {
                    ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(buf, slot);
                    syncBytes = Math.addExact(syncBytes, buf.readableBytes());
                    var decoded = ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buf);
                    if (buf.isReadable() || decoded.getContainerId() != slot.getContainerId() || decoded.getStateId() != slot.getStateId()
                            || decoded.getSlot() != slot.getSlot() || !ItemStack.matches(decoded.getItem(), slot.getItem()))
                        throw refused("Native slot synchronization codec changed");
                } else if (packet instanceof ClientboundContainerSetContentPacket content) {
                    ClientboundContainerSetContentPacket.STREAM_CODEC.encode(buf, content);
                    syncBytes = Math.addExact(syncBytes, buf.readableBytes());
                    var decoded = ClientboundContainerSetContentPacket.STREAM_CODEC.decode(buf);
                    if (buf.isReadable() || decoded.getContainerId() != content.getContainerId() || decoded.getStateId() != content.getStateId()
                            || decoded.getItems().size() != 46 || !ItemStack.matches(decoded.getCarriedItem(), content.getCarriedItem()))
                        throw refused("Native full synchronization codec changed");
                    for (int i = 0; i < 46; i++) if (!ItemStack.matches(decoded.getItems().get(i), content.getItems().get(i)))
                        throw refused("Native full synchronization changed a stack");
                } else throw refused("Unadmitted native menu synchronization packet");
            } finally { raw.release(); }
        }
    }
    private static final class Synchronizer implements ContainerSynchronizer {
        private final Scope scope;
        Synchronizer(Scope scope) { this.scope = scope; }
        public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> items, ItemStack carried, int[] data) {
            if (menu != scope.menu || items.size() != 46 || data.length != 0) throw refused("Foreign native full synchronization");
            scope.budget.reserve(2 * 1024 * 1024);
            scope.syncPacket(new ClientboundContainerSetContentPacket(menu.containerId, menu.incrementStateId(), items, carried), 1024 * 1024);
        }
        public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {
            if (menu != scope.menu) throw refused("Foreign native slot synchronization");
            scope.budget.reserve(65536);
            scope.syncPacket(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), slot, stack), 32768);
        }
        public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) {
            if (menu != scope.menu) throw refused("Foreign native cursor synchronization");
            scope.budget.reserve(65536);
            scope.syncPacket(new ClientboundContainerSetSlotPacket(-1, menu.incrementStateId(), -1, stack), 32768);
        }
        public void sendDataChange(AbstractContainerMenu menu, int id, int value) { throw refused("Native PICKUP menu has no data slots"); }
    }
    static Result prepare(NativeSessionMenuOwner owner, NativeWholeAction action, NativeSessionMenuBoundary before,
            NativeMenuPacketInput packet, NativeFirstLoopMetadata metadata, NativeCraftingRecipeAdmission recipes,
            Limits limits, NativeMenuBudget budget) {
        boolean succeeded = false;
        if (ACTIVE.get() != null || NativeInventoryMenuPreparation.active() || NativeCraftingTablePreparation.active()) {
            var failure = refused("Native per-packet menu preparation cannot reenter"); poisonActivePreparation(failure); throw failure;
        }
        try {
            owner.requirePreparing(action); before.requireUnchanged(metadata, budget);
            metadata.requirePlayerIdentity(owner.host().player()); recipes.requirePlayerIdentity(owner.host().player());
            metadata.revalidate(); recipes.revalidate(); packet.requireShape(before.data().active().table());
            if (packet.containerId() != before.data().active().containerId()) throw refused("Native packet container differs from retained owner");
            if (CommonHooks.getCraftingPlayer() != null) throw refused("Another native crafting context is active");
            new PinnedNativeEventAdmission(NeoForge.EVENT_BUS).requireEmpty(ItemStackedOnOtherEvent.class);
            var scope = new Scope(owner, action, before, metadata, recipes, limits, budget);
            ACTIVE.set(scope);
            try {
                budget.reserve(256 * 1024); scope.inventory = new Inventory(scope.player);
                if (before.table() == null) scope.menu = new InventoryMenu(scope.inventory, true, scope.player);
                else {
                    var state = action.read(before.table());
                    if (!state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) throw refused("Retained native table disappeared");
                    var provider = state.getMenuProvider(scope.level, io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation.nativePos(before.table()));
                    NativeTableMenuLifecycleAdmission.requireProvider(provider, scope.player, before.table());
                    scope.menu = provider.createMenu(packet.containerId(), scope.inventory, scope.player);
                    if (scope.menu == null || scope.menu.getClass() != CraftingMenu.class) throw refused("Native table factory changed");
                }
                scope.phase = Phase.HYDRATING;
                hydrate(scope, before.data());
                // Install our private synchronization recorder directly. Calling setSynchronizer would
                // incorrectly send an initial packet during hydration and advance the state counter.
                NativeSessionMenuBoundary.write(AbstractContainerMenu.class, scope.menu, "synchronizer", ContainerSynchronizer.class, new Synchronizer(scope));
                scope.phase = Phase.INSPECTING;
                for (int i = 0; i < 41; i++) if (!before.data().inventory().get(i).matches(action.stack(i), scope.player.registryAccess()))
                    throw refused("Native packet action inventory differs from retained boundary");
                var active = before.data().active();
                scope.preview = new Preview(active.grid(), before.activeRecipe(), active.result());
                boolean stale = packet.stateId() != scope.menu.getStateId();
                scope.menu.suppressRemoteUpdates();
                scope.phase = Phase.CLICKING;
                if (packet.slot() == 0 && !scope.menu.getSlot(0).getItem().isEmpty()) {
                    if (scope.preview.recipe() == null || result(scope.menu).getRecipeUsed() != scope.preview.recipe()
                            || !scope.preview.grid().equals(scope.grid()) || !scope.preview.result().matches(result(scope.menu).getItem(0), scope.player.registryAccess()))
                        throw refused("Native retained recipe preview is stale");
                    recipes.requireTake(scope.preview.recipe()); scope.takingRecipe = scope.preview.recipe(); scope.takingStack = result(scope.menu).getItem(0);
                }
                scope.menu.clicked(packet.slot(), packet.button(), ClickType.PICKUP, scope.player);
                if (scope.inGrid) throw refused("Native grid callback did not leave packet scope");
                scope.phase = Phase.INSPECTING;
                var clicked = scope.image();
                // Exactly the pinned handler order. These client values only replace remote caches.
                for (var predicted : packet.changedSlots()) scope.menu.setRemoteSlotNoCopy(predicted.slot(), predicted.stack().materialize(scope.player.registryAccess()));
                scope.menu.setRemoteCarried(packet.carried().materialize(scope.player.registryAccess()));
                scope.menu.resumeRemoteUpdates();
                scope.phase = Phase.SYNCHRONIZING;
                if (stale) scope.menu.broadcastFullState(); else scope.menu.broadcastChanges();
                scope.phase = Phase.INSPECTING;
                var after = scope.image();
                NativeMenuCloseCapacity.requireResolvable(owner, action, after, budget);
                before.requireUnchanged(metadata, budget); metadata.revalidate(); recipes.revalidate(); succeeded = true;
                return new Result(before.data(), clicked, after, scope.notices, stale, scope.syncPackets, scope.syncBytes);
            } finally {
                if (CommonHooks.getCraftingPlayer() == scope.player) CommonHooks.setCraftingPlayer(null);
                ACTIVE.remove();
            }
        } finally {
            if (!succeeded && action.state() == NativeWholeAction.State.PREPARING) action.poisonPreparation();
        }
    }
    private static void hydrate(Scope scope, NativeSessionMenuBoundary.Data data) {
        scope.requireMenu(scope.menu);
        if (scope.phase != Phase.HYDRATING || scope.inventory == scope.player.getInventory()) throw refused("Foreign native hydration request");
        for (int i = 0; i < 41; i++) scope.inventory.setItem(i, data.inventory().get(i).materialize(scope.player.registryAccess()));
        scope.inventory.selected = data.selected();
        NativeSessionMenuBoundary.write(Inventory.class, scope.inventory, "timesChanged", int.class, data.inventoryChanges());
        var menu = data.active();
        replace(NativeSessionMenuBoundary.read(net.minecraft.world.inventory.TransientCraftingContainer.class, grid(scope.menu), "items", NonNullList.class), menu.grid(), scope.player);
        result(scope.menu).setItem(0, menu.result().materialize(scope.player.registryAccess())); result(scope.menu).setRecipeUsed(scope.before.activeRecipe());
        scope.menu.setCarried(menu.carried().materialize(scope.player.registryAccess()));
        replace(NativeSessionMenuBoundary.read(AbstractContainerMenu.class, scope.menu, "lastSlots", NonNullList.class), menu.lastSlots(), scope.player);
        replace(NativeSessionMenuBoundary.read(AbstractContainerMenu.class, scope.menu, "remoteSlots", NonNullList.class), menu.remoteSlots(), scope.player);
        scope.menu.setRemoteCarried(menu.remoteCarried().materialize(scope.player.registryAccess()));
        NativeSessionMenuBoundary.write(AbstractContainerMenu.class, scope.menu, "stateId", int.class, menu.stateId());
        NativeSessionMenuBoundary.write(AbstractContainerMenu.class, scope.menu, "quickcraftType", int.class, menu.quickcraftType());
        NativeSessionMenuBoundary.write(AbstractContainerMenu.class, scope.menu, "suppressRemoteUpdates", boolean.class, menu.suppressRemoteUpdates());
        if (!NativeSessionMenuBoundary.read(AbstractContainerMenu.class, scope.menu, "containerListeners", List.class).isEmpty()) throw refused("Detached menu owns foreign observers");
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void replace(List target, List<NativeStackSnapshot> images, ServerPlayer player) {
        if (target.size() != images.size()) throw refused("Native hydration vector size changed");
        for (int i = 0; i < images.size(); i++) target.set(i, images.get(i).materialize(player.registryAccess()));
    }
    static net.minecraft.world.inventory.TransientCraftingContainer grid(AbstractContainerMenu menu) { return (net.minecraft.world.inventory.TransientCraftingContainer) menu.getSlot(1).container; }
    static ResultContainer result(AbstractContainerMenu menu) { return (ResultContainer) menu.getSlot(0).container; }
    public static void enterGrid(AbstractContainerMenu menu, Level level, Player player, CraftingContainer grid, ResultContainer result) {
        try {
        Scope scope = ACTIVE.get(); if (scope == null) return;
        scope.requireMenu(menu);
        if (scope.phase != Phase.CLICKING || scope.inGrid || scope.level != level || scope.player != player
                || grid(scope.menu) != grid || result(scope.menu) != result)
            throw refused("Native crafting-grid callback is foreign or reentrant");
        scope.recipes.requireInput(grid.getItems()); scope.inGrid = true; scope.gridRecipe = null;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    public static void exitGrid(AbstractContainerMenu menu) {
        try {
        Scope scope = ACTIVE.get(); if (scope == null) return;
        scope.requireMenu(menu);
        if (!scope.inGrid) throw refused("Native grid callback has no entry capability");
        scope.preview = new Preview(scope.grid(), scope.gridRecipe,
                scope.budget.previewImage(scope.menu.getSlot(0).getItem(), scope.player));
        scope.inGrid = false;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    public static Optional<RecipeHolder<CraftingRecipe>> findRecipe(RecipeManager manager, RecipeType<CraftingRecipe> type,
            CraftingInput input, Level level, RecipeHolder<CraftingRecipe> previous) {
        try {
        Scope scope = ACTIVE.get();
        if (scope == null) return manager.getRecipeFor(type, input, level, previous);
        scope.requireMenu(scope.menu);
        if (!scope.inGrid || manager != scope.player.server.getRecipeManager() || type != RecipeType.CRAFTING
                || level != scope.level || previous != null) throw refused("Foreign native2x2 recipe search");
        scope.recipes.requireInput(input.items());
        var found = manager.getRecipeFor(type, input, level, previous);
        if (found.isPresent()) { scope.recipes.requirePreview(found.get()); scope.gridRecipe = found.get(); }
        return found;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    public static void packet(ServerGamePacketListenerImpl connection, Packet<?> packet) {
        try {
        Scope scope = ACTIVE.get();
        if (scope == null) { connection.send(packet); return; }
        scope.requireMenu(scope.menu);
        if (!scope.inGrid || connection != scope.connection || !(packet instanceof ClientboundContainerSetSlotPacket slot)
                || slot.getContainerId() != scope.menu.containerId || slot.getSlot() != 0) throw refused("Unexpected native crafting result packet");
        scope.recipes.requirePreviewStack(slot.getItem());
        var stack = scope.budget.previewImage(slot.getItem(), scope.player);
        requireNativePacketRoundTrip(slot, stack, scope.player);
        scope.add(new SlotPacket(slot.getContainerId(), slot.getStateId(), slot.getSlot(), stack), 16 + stack.encodedBytes());
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    static void requireNativePacketRoundTrip(ClientboundContainerSetSlotPacket packet,
            NativeStackSnapshot expected, ServerPlayer player) {
        var bytes = io.netty.buffer.Unpooled.buffer(256, 32768);
        try {
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(bytes, player.registryAccess());
            ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable() || decoded.getContainerId() != packet.getContainerId()
                    || decoded.getStateId() != packet.getStateId() || decoded.getSlot() != packet.getSlot()
                    || !expected.matches(decoded.getItem(), player.registryAccess()))
                throw refused("Native result packet codec does not retain the exact bounded preview");
        } finally { bytes.release(); }
    }
    public static void crafted(RecipeCraftingHolder holder, Player player, RecipeHolder<?> recipe, List<ItemStack> input) {
        try {
        Scope scope = ACTIVE.get();
        if (scope == null) { player.triggerRecipeCrafted(recipe, input); return; }
        scope.requireHolder(holder, player);
        if (recipe != scope.takingRecipe) throw refused("Native crafted recipe differs from approved result removal");
        scope.recipes.requireTake(recipe); scope.metadata.requireItems(input);
        var images = input.stream().map(stack -> scope.budget.image(stack, scope.player)).toList();
        int size = 64; for (var image : images) size = Math.addExact(size, image.encodedBytes());
        scope.add(new Crafted(recipe.id(), images), size);
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    public static int unlocked(RecipeCraftingHolder holder, Player player, Collection<RecipeHolder<?>> recipes) {
        try {
        Scope scope = ACTIVE.get();
        if (scope == null) return player.awardRecipes(recipes);
        scope.requireHolder(holder, player);
        if (recipes.size() != 1 || recipes.iterator().next() != scope.takingRecipe)
            throw refused("Unexpected native result recipe unlock list");
        scope.metadata.requireUnlockRecipe(scope.takingRecipe);
        scope.add(new Unlocked(List.of(scope.takingRecipe.id())), 64);
        return 0; // This exact native default-method call site discards the return value.
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    public static void statistic(ItemStack craftedStack, Player player, Stat<?> stat, int amount) {
        try {
        Scope scope = ACTIVE.get();
        if (scope == null) { player.awardStat(stat, amount); return; }
        scope.requireMenu(scope.menu);
        if (scope.phase != Phase.CLICKING || player != scope.player || craftedStack != scope.takingStack
                || scope.takingRecipe == null || amount < 1 || amount > craftedStack.getCount())
            throw refused("Crafted statistic escaped the exact detached result removal");
        scope.add(new Statistic(statEffect(stat, amount)), 128);
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    private static <T> NativeEffectCodec.PlayerStat statEffect(Stat<T> stat, int amount) {
        return new NativeEffectCodec.PlayerStat(BuiltInRegistries.STAT_TYPE.getKey(stat.getType()), stat.getType().getRegistry().getKey(stat.getValue()), amount);
    }
    public static void enterTake(ResultSlot slot, Player player, ItemStack stack) {
        try {
        Scope scope = ACTIVE.get(); if (scope == null) return;
        scope.requireMenu(scope.menu);
        if (scope.phase != Phase.CLICKING || slot != scope.menu.getSlot(0) || player != scope.player
                || stack != scope.takingStack || scope.takingRecipe == null)
            throw refused("Native result onTake escaped its approved detached removal");
        scope.recipes.requireTake(scope.takingRecipe);
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    public static NonNullList<ItemStack> remainders(ResultSlot slot, RecipeManager manager, RecipeType<CraftingRecipe> type,
            CraftingInput input, Level level) {
        try {
        Scope scope = ACTIVE.get();
        if (scope == null) return manager.getRemainingItemsFor(type, input, level);
        scope.requireMenu(scope.menu);
        if (scope.phase != Phase.CLICKING || slot != scope.menu.getSlot(0) || scope.takingRecipe == null
                || manager != scope.player.server.getRecipeManager() || type != RecipeType.CRAFTING || level != scope.level)
            throw refused("Native remainders escaped the exact detached result slot");
        try {
            var remainder = manager.getRemainingItemsFor(type, input, level);
            if (remainder.size() != input.size() || remainder.stream().anyMatch(stack -> !stack.isEmpty()))
                throw refused("Unexpected native first-loop crafting remainder");
            return remainder;
        } catch (RuntimeException | Error failure) {
            if (CommonHooks.getCraftingPlayer() == scope.player) CommonHooks.setCraftingPlayer(null);
            throw failure;
        }
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    public static Inventory inventory(ResultSlot slot, Player player) {
        try {
        Scope scope = ACTIVE.get(); if (scope == null) return player.getInventory();
        scope.requireMenu(scope.menu);
        if (slot != scope.menu.getSlot(0) || player != scope.player) throw refused("Foreign native remainder inventory request");
        return scope.inventory;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    public static ItemEntity drop(ResultSlot slot, Player player, ItemStack stack, boolean random) {
        try {
        if (ACTIVE.get() == null) return player.drop(stack, random);
        throw refused("Detached native first-loop result cannot drop a remainder");
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); throw failure;
        }
    }
    private static Scope requireScope() { Scope scope = ACTIVE.get(); if (scope == null) throw refused("Native packet scope is absent"); return scope; }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
