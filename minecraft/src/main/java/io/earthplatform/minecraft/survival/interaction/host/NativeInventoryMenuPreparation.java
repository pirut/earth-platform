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

/**
 * Abort-only semantic comparison for actual native2x2 InventoryMenu.clicked. This is the short
 * dependency for a receipt-scoped live menu owner, NOT live packet handling or menu delivery.
 * Constructors run inside the private creation scope; no live inventory/menu fields are swapped.
 * All native recipe/result/remainder/click code executes on exact detached native objects. Native
 * metadata call sites append immutable notices. The whole action is always poisoned before return,
 * so these notices cannot accidentally commit without their grid/cursor/inventory ownership.
 */
public final class NativeInventoryMenuPreparation {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private NativeInventoryMenuPreparation() {}
    static boolean active() { return ACTIVE.get()!=null; }
    public record Click(int slot, int button, ClickType type) {}
    public record Limits(int clicks, int notices, int noticeBytes, int menuSnapshotBytes) {
        public Limits {
            if (clicks < 1 || clicks > 128 || notices < 1 || notices > 1024 || noticeBytes < 1 || noticeBytes > 1048576
                    || menuSnapshotBytes < 1 || menuSnapshotBytes > 16*1024*1024)
                throw new IllegalArgumentException("Explicit bounded native click comparison required");
        }
    }
    public sealed interface Notice permits Statistic, Crafted, Unlocked, SlotPacket {}
    public record Statistic(NativeEffectCodec.PlayerStat stat) implements Notice {}
    public record Crafted(ResourceLocation recipe, List<NativeStackSnapshot> input) implements Notice {
        public Crafted { input = List.copyOf(input); }
    }
    public record Unlocked(List<ResourceLocation> recipes) implements Notice {
        public Unlocked { recipes = List.copyOf(recipes); }
    }
    public record SlotPacket(int container, int state, int slot, NativeStackSnapshot stack) implements Notice {}
    public record Result(NativeInventoryMenuSnapshot.Data before, NativeInventoryMenuSnapshot.Data after,
                         List<Notice> notices, List<NativeInventoryMenuSnapshot.Data> steps) {
        public Result { notices = List.copyOf(notices); steps = List.copyOf(steps); }
    }
    static void poisonActivePreparation(Throwable failure) {
        Scope scope=ACTIVE.get();
        if (scope!=null && NativeWholeAction.current()==scope.action && scope.action.state()==NativeWholeAction.State.PREPARING
                && scope.binding.actions().owns(scope.action)) {
            try { scope.action.poisonPreparation(); }
            catch (RuntimeException | Error secondary) { failure.addSuppressed(secondary); }
        }
    }
    private enum Phase { CREATING, HYDRATING, CLICKING, INSPECTING }
    private static final class Preview {
        final List<NativeStackSnapshot> grid;
        final RecipeHolder<CraftingRecipe> recipe;
        final NativeStackSnapshot result;
        Preview(List<NativeStackSnapshot> grid, RecipeHolder<CraftingRecipe> recipe, NativeStackSnapshot result) {
            this.grid = grid; this.recipe = recipe; this.result = result;
        }
    }
    private static final class Scope {
        final NativeWholeAction action;
        final ServerPlayer player;
        final Inventory liveInventory;
        final InventoryMenu liveMenu;
        final Level level;
        final SurvivalLevelBinding binding;
        final ServerGamePacketListenerImpl connection;
        final NativeFirstLoopMetadata metadata;
        final NativeCraftingRecipeAdmission recipes;
        final Limits limits;
        final NativeInventoryMenuSnapshot.Budget snapshots;
        final List<Notice> notices = new ArrayList<>();
        final List<NativeInventoryMenuSnapshot.Data> steps = new ArrayList<>();
        Inventory inventory;
        InventoryMenu menu;
        Phase phase = Phase.CREATING;
        RecipeHolder<CraftingRecipe> gridRecipe;
        Preview preview;
        RecipeHolder<?> takingRecipe;
        ItemStack takingStack;
        boolean inGrid;
        int bytes;
        Scope(NativeWholeAction action, ServerPlayer player, NativeFirstLoopMetadata metadata,
                NativeCraftingRecipeAdmission recipes, Limits limits, NativeInventoryMenuSnapshot.Budget snapshots) {
            this.snapshots = snapshots; this.action = action; this.player = player; this.metadata = metadata; this.recipes = recipes; this.limits = limits;
            liveInventory = player.getInventory(); liveMenu = player.inventoryMenu; level = player.level(); connection = player.connection;
            binding = SurvivalLevelBinding.find(level);
            if (binding == null || !binding.actions().owns(action) || binding.world() != action.world()
                    || !action.identity().actor().equals(player.getUUID()))
                throw refused("Native detached menu requires the exact level/actor action binding");
        }
        void require() {
            if (!player.server.isSameThread() || player.getClass() != ServerPlayer.class || player.level() != level
                    || player.connection != connection || player.getInventory() != liveInventory || player.inventoryMenu != liveMenu
                    || player.containerMenu != liveMenu || SurvivalLevelBinding.find(level) != binding
                    || !binding.actions().owns(action) || NativeWholeAction.current() != action
                    || action.state() != NativeWholeAction.State.PREPARING)
                throw refused("Detached native menu callback escaped its exact preparing action/player");
        }
        void requireMenu(AbstractContainerMenu requested) {
            require();
            if (phase == Phase.CREATING || requested != menu || menu == liveMenu)
                throw refused("Native menu callback did not belong to the exact constructed detached menu");
        }
        void requireHolder(RecipeCraftingHolder holder, Player requested) {
            requireMenu(menu);
            if (phase != Phase.CLICKING || holder != NativeInventoryMenuSnapshot.result(menu) || requested != player || takingRecipe == null)
                throw refused("Native recipe award escaped the exact detached result removal");
        }
        void add(Notice notice, int size) {
            if (size < 0 || notices.size() >= limits.notices || size > limits.noticeBytes - bytes)
                throw refused("Native menu metadata journal bound exceeded");
            notices.add(notice); bytes += size;
        }
        List<NativeStackSnapshot> grid() {
            return menu.getCraftSlots().getItems().stream().map(stack -> NativeStackSnapshot.capture(stack, player.registryAccess())).toList();
        }
        void click(Click click) {
            require(); metadata.revalidate();
            metadata.requireItems(inventory.items); metadata.requireItems(inventory.armor); metadata.requireItems(inventory.offhand);
            metadata.requireItems(menu.getCraftSlots().getItems()); metadata.requireItems(List.of(menu.getCarried()));
            requireClick(click);
            phase = Phase.CLICKING; takingRecipe = null; takingStack = null;
            if (click.slot == 0 && !menu.getSlot(0).getItem().isEmpty()) {
                ResultContainer result = NativeInventoryMenuSnapshot.result(menu);
                if (preview == null || preview.recipe == null || result.getRecipeUsed() != preview.recipe
                        || !preview.grid.equals(grid()) || !preview.result.matches(result.getItem(0), player.registryAccess()))
                    throw refused("Native result preview is stale or unrelated to its exact current grid");
                recipes.requireTake(preview.recipe); metadata.requireItems(List.of(result.getItem(0)));
                takingRecipe = preview.recipe; takingStack = result.getItem(0);
            }
            menu.clicked(click.slot, click.button, click.type, player);
            if (inGrid) throw refused("Native grid callback did not leave its preparation scope");
            metadata.requireItems(inventory.items); metadata.requireItems(inventory.armor); metadata.requireItems(inventory.offhand);
            metadata.requireItems(menu.getCraftSlots().getItems()); metadata.requireItems(List.of(menu.getCarried()));
            phase = Phase.INSPECTING;
            steps.add(NativeInventoryMenuSnapshot.capture(player, menu, inventory, snapshots).data());
        }
    }
    static void requireClick(Click click) {
        if (click == null || click.type != ClickType.PICKUP || (click.button != 0 && click.button != 1)
                || !NativeFirstLoopMetadata.MenuShape.INVENTORY_2X2.admitsInitialPickupSlot(click.slot))
            throw refused("Only bounded native2x2 result/grid/main/hotbar PICKUP is admitted");
    }
    public static Result prepareScript(NativeWholeAction action, ServerPlayer player, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, List<Click> clicks, Limits limits) {
        return prepare(action, player, metadata, recipes, clicks, limits, true, null);
    }
    /** Only the package-private retained delivery plan can keep this successful preparation open. */
    static Result prepareResolvedScript(NativeWholeAction action, ServerPlayer player, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, List<Click> clicks, Limits limits, NativeInventoryMenuSnapshot.Budget sharedBudget) {
        return prepare(action, player, metadata, recipes, clicks, limits, false, java.util.Objects.requireNonNull(sharedBudget));
    }
    private static Result prepare(NativeWholeAction action, ServerPlayer player, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, List<Click> clicks, Limits limits, boolean abortOnly, NativeInventoryMenuSnapshot.Budget sharedBudget) {
        boolean prepared = false;
        if (ACTIVE.get() != null || NativeCraftingTablePreparation.active() || NativeSessionMenuPreparation.active() || action==null || NativeWholeAction.current() != action || action.state() != NativeWholeAction.State.PREPARING) {
            var failure=refused("Native detached menu comparison cannot reenter");
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
        try {
            if (clicks.isEmpty() || clicks.size() > limits.clicks) throw refused("Native click script exceeds its explicit bound");
            NativeInventorySnapshot.requireResolvedMenu(player);
            if (CommonHooks.getCraftingPlayer() != null) throw refused("Another native crafting context is active");
            metadata.requirePlayerIdentity(player); recipes.requirePlayerIdentity(player);
            var binding = SurvivalLevelBinding.find(player.level());
            if (binding == null || !binding.actions().owns(action) || binding.world() != action.world()
                    || !action.identity().actor().equals(player.getUUID()))
                throw refused("Native detached menu requires the exact level/actor action binding");
            metadata.revalidate(); recipes.revalidate();
            new PinnedNativeEventAdmission(NeoForge.EVENT_BUS).requireEmpty(ItemStackedOnOtherEvent.class);
            var snapshots = sharedBudget == null ? new NativeInventoryMenuSnapshot.Budget(limits.menuSnapshotBytes) : sharedBudget;
            var before = NativeInventoryMenuSnapshot.capture(player, player.inventoryMenu, player.getInventory(), snapshots);
            if (before.data().selected() != action.selectedSlot())
                throw refused("Native action hotbar selection differs from the exact live menu before-image");
            for (int slot = 0; slot < 41; slot++)
                if (!before.data().inventory().get(slot).matches(action.stack(slot), player.registryAccess()))
                    throw refused("Native action inventory differs from the exact live menu before-image");
            var scope = new Scope(action, player, metadata, recipes, limits, snapshots);
            ACTIVE.set(scope); // Before both native Inventory and InventoryMenu constructors.
            try {
                scope.inventory = new Inventory(player);
                scope.menu = new InventoryMenu(scope.inventory, true, player);
                scope.phase = Phase.HYDRATING;
                before.hydrateDetached(player, scope.menu, scope.inventory);
                scope.phase = Phase.INSPECTING;
                for (Click click : List.copyOf(clicks)) scope.click(click);
                var after = scope.steps.getLast(); // Already captured/charged after the final native click.
                before.requireUnchanged(player); metadata.revalidate(); recipes.revalidate();
                if (!abortOnly) NativeResolvedInventoryMenuPlan.requireResolved(after);
                prepared = true;
                return new Result(before.data(), after, scope.notices, scope.steps);
            } finally {
                // Native ResultSlot lacks a finally around this ThreadLocal. Clean only our exact
                // player after a captured exception, never overwrite another native owner.
                if (CommonHooks.getCraftingPlayer() == player) CommonHooks.setCraftingPlayer(null);
                ACTIVE.remove();
            }
        } finally {
            // Returning successful comparison data never grants live inventory/stat authority.
            if ((abortOnly || !prepared) && action.state() == NativeWholeAction.State.PREPARING) action.poisonPreparation();
        }
    }
    static void requireSnapshotSource(ServerPlayer player, InventoryMenu menu, Inventory inventory) {
        if (menu == player.inventoryMenu && inventory == player.getInventory()) return;
        Scope scope = ACTIVE.get();
        if (scope == null || scope.player != player || scope.menu != menu || scope.inventory != inventory)
            throw refused("Native snapshot source is neither the real menu nor this detached preparation");
        scope.require();
    }
    static void requireHydration(ServerPlayer player, InventoryMenu menu, Inventory inventory) {
        Scope scope = requireScope(); scope.requireMenu(menu);
        if (scope.phase != Phase.HYDRATING || scope.player != player || scope.inventory != inventory)
            throw refused("Native menu hydration lacks the private detached creation capability");
    }
    public static void enterGrid(AbstractContainerMenu menu, Level level, Player player, CraftingContainer grid, ResultContainer result) {
        try {
        if (NativeSessionMenuPreparation.active()) { NativeSessionMenuPreparation.enterGrid(menu, level, player, grid, result); return; }
        if (NativeCraftingTablePreparation.active()) { NativeCraftingTablePreparation.enterGrid(menu, level, player, grid, result); return; }
        Scope scope = ACTIVE.get(); if (scope == null) return;
        scope.requireMenu(menu);
        if (scope.phase != Phase.CLICKING || scope.inGrid || scope.level != level || scope.player != player
                || scope.menu.getCraftSlots() != grid || NativeInventoryMenuSnapshot.result(scope.menu) != result)
            throw refused("Native crafting-grid callback is foreign or reentrant");
        scope.recipes.requireInput(grid.getItems()); scope.inGrid = true; scope.gridRecipe = null;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static void exitGrid(AbstractContainerMenu menu) {
        try {
        if (NativeSessionMenuPreparation.active()) { NativeSessionMenuPreparation.exitGrid(menu); return; }
        if (NativeCraftingTablePreparation.active()) { NativeCraftingTablePreparation.exitGrid(menu); return; }
        Scope scope = ACTIVE.get(); if (scope == null) return;
        scope.requireMenu(menu);
        if (!scope.inGrid) throw refused("Native grid callback has no entry capability");
        scope.preview = new Preview(scope.grid(), scope.gridRecipe,
                NativeStackSnapshot.capture(scope.menu.getSlot(0).getItem(), scope.player.registryAccess()));
        scope.inGrid = false;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static Optional<RecipeHolder<CraftingRecipe>> findRecipe(RecipeManager manager, RecipeType<CraftingRecipe> type,
            CraftingInput input, Level level, RecipeHolder<CraftingRecipe> previous) {
        try {
        if (NativeSessionMenuPreparation.active()) { return NativeSessionMenuPreparation.findRecipe(manager, type, input, level, previous); }
        if (NativeCraftingTablePreparation.active()) { return NativeCraftingTablePreparation.findRecipe(manager, type, input, level, previous); }
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
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static void packet(ServerGamePacketListenerImpl connection, Packet<?> packet) {
        try {
        if (NativeSessionMenuPreparation.active()) { NativeSessionMenuPreparation.packet(connection, packet); return; }
        if (NativeCraftingTablePreparation.active()) { NativeCraftingTablePreparation.packet(connection, packet); return; }
        Scope scope = ACTIVE.get();
        if (scope == null) { connection.send(packet); return; }
        scope.requireMenu(scope.menu);
        if (!scope.inGrid || connection != scope.connection || !(packet instanceof ClientboundContainerSetSlotPacket slot)
                || slot.getContainerId() != 0 || slot.getSlot() != 0) throw refused("Unexpected native crafting result packet");
        scope.recipes.requirePreviewStack(slot.getItem());
        var stack = NativeStackSnapshot.capture(slot.getItem(), scope.player.registryAccess());
        requireNativePacketRoundTrip(slot, stack, scope.player);
        scope.add(new SlotPacket(slot.getContainerId(), slot.getStateId(), slot.getSlot(), stack), 16 + stack.encodedBytes());
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
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
        if (NativeSessionMenuPreparation.active()) { NativeSessionMenuPreparation.crafted(holder, player, recipe, input); return; }
        if (NativeCraftingTablePreparation.active()) { NativeCraftingTablePreparation.crafted(holder, player, recipe, input); return; }
        Scope scope = ACTIVE.get();
        if (scope == null) { player.triggerRecipeCrafted(recipe, input); return; }
        scope.requireHolder(holder, player);
        if (recipe != scope.takingRecipe) throw refused("Native crafted recipe differs from approved result removal");
        scope.recipes.requireTake(recipe); scope.metadata.requireItems(input);
        var images = input.stream().map(stack -> NativeStackSnapshot.capture(stack, scope.player.registryAccess())).toList();
        int size = 64; for (var image : images) size = Math.addExact(size, image.encodedBytes());
        scope.add(new Crafted(recipe.id(), images), size);
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static int unlocked(RecipeCraftingHolder holder, Player player, Collection<RecipeHolder<?>> recipes) {
        try {
        if (NativeSessionMenuPreparation.active()) { return NativeSessionMenuPreparation.unlocked(holder, player, recipes); }
        if (NativeCraftingTablePreparation.active()) { return NativeCraftingTablePreparation.unlocked(holder, player, recipes); }
        Scope scope = ACTIVE.get();
        if (scope == null) return player.awardRecipes(recipes);
        scope.requireHolder(holder, player);
        if (recipes.size() != 1 || recipes.iterator().next() != scope.takingRecipe)
            throw refused("Unexpected native result recipe unlock list");
        scope.metadata.requireUnlockRecipe(scope.takingRecipe);
        scope.add(new Unlocked(List.of(scope.takingRecipe.id())), 64);
        return 0; // This exact native default-method call site discards the return value.
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static void statistic(ItemStack craftedStack, Player player, Stat<?> stat, int amount) {
        try {
        if (NativeSessionMenuPreparation.active()) { NativeSessionMenuPreparation.statistic(craftedStack, player, stat, amount); return; }
        if (NativeCraftingTablePreparation.active()) { NativeCraftingTablePreparation.statistic(craftedStack, player, stat, amount); return; }
        Scope scope = ACTIVE.get();
        if (scope == null) { player.awardStat(stat, amount); return; }
        scope.requireMenu(scope.menu);
        if (scope.phase != Phase.CLICKING || player != scope.player || craftedStack != scope.takingStack
                || scope.takingRecipe == null || amount < 1 || amount > craftedStack.getCount())
            throw refused("Crafted statistic escaped the exact detached result removal");
        scope.add(new Statistic(statEffect(stat, amount)), 128);
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    private static <T> NativeEffectCodec.PlayerStat statEffect(Stat<T> stat, int amount) {
        return new NativeEffectCodec.PlayerStat(BuiltInRegistries.STAT_TYPE.getKey(stat.getType()), stat.getType().getRegistry().getKey(stat.getValue()), amount);
    }
    public static void enterTake(ResultSlot slot, Player player, ItemStack stack) {
        try {
        if (NativeSessionMenuPreparation.active()) { NativeSessionMenuPreparation.enterTake(slot, player, stack); return; }
        if (NativeCraftingTablePreparation.active()) { NativeCraftingTablePreparation.enterTake(slot, player, stack); return; }
        Scope scope = ACTIVE.get(); if (scope == null) return;
        scope.requireMenu(scope.menu);
        if (scope.phase != Phase.CLICKING || slot != scope.menu.getSlot(0) || player != scope.player
                || stack != scope.takingStack || scope.takingRecipe == null)
            throw refused("Native result onTake escaped its approved detached removal");
        scope.recipes.requireTake(scope.takingRecipe);
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static NonNullList<ItemStack> remainders(ResultSlot slot, RecipeManager manager, RecipeType<CraftingRecipe> type,
            CraftingInput input, Level level) {
        try {
        if (NativeSessionMenuPreparation.active()) { return NativeSessionMenuPreparation.remainders(slot, manager, type, input, level); }
        if (NativeCraftingTablePreparation.active()) { return NativeCraftingTablePreparation.remainders(slot, manager, type, input, level); }
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
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static Inventory inventory(ResultSlot slot, Player player) {
        try {
        if (NativeSessionMenuPreparation.active()) { return NativeSessionMenuPreparation.inventory(slot, player); }
        if (NativeCraftingTablePreparation.active()) { return NativeCraftingTablePreparation.inventory(slot, player); }
        Scope scope = ACTIVE.get(); if (scope == null) return player.getInventory();
        scope.requireMenu(scope.menu);
        if (slot != scope.menu.getSlot(0) || player != scope.player) throw refused("Foreign native remainder inventory request");
        return scope.inventory;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static ItemEntity drop(ResultSlot slot, Player player, ItemStack stack, boolean random) {
        try {
        if (NativeSessionMenuPreparation.active()) { return NativeSessionMenuPreparation.drop(slot, player, stack, random); }
        if (NativeCraftingTablePreparation.active()) { return NativeCraftingTablePreparation.drop(slot, player, stack, random); }
        if (ACTIVE.get() == null) return player.drop(stack, random);
        throw refused("Detached native first-loop result cannot drop a remainder");
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeCraftingTablePreparation.poisonActivePreparation(failure); NativeSessionMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    private static Scope requireScope() { Scope scope = ACTIVE.get(); if (scope == null) throw refused("Native detached menu scope is absent"); return scope; }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
