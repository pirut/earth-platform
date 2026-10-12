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
import io.earthplatform.minecraft.survival.world.CubeCell;
import net.minecraft.world.MenuProvider;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import net.minecraft.world.level.block.Blocks;
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

/**
 * Native 3×3 detached script preparation for a separately owned resolved table lifecycle. This is the short
 * dependency for a receipt-scoped live menu owner, NOT live packet handling or menu delivery.
 * Constructors run inside the private creation scope; no live inventory/menu fields are swapped.
 * All native recipe/result/remainder/click code executes on exact detached native objects. Native
 * metadata call sites append immutable notices. The public comparison poisons the whole action before return,
 * so these notices cannot accidentally commit without their grid/cursor/inventory ownership.
 */
public final class NativeCraftingTablePreparation {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private NativeCraftingTablePreparation() {}
    public record Result(NativeInventoryMenuSnapshot.Data inventoryMenuBefore,
                         NativeCraftingTableSnapshot.Data before, NativeCraftingTableSnapshot.Data after,
                         List<Notice> notices, List<NativeCraftingTableSnapshot.Data> steps) {
        public Result { notices = List.copyOf(notices); steps = List.copyOf(steps); }
    }
    static boolean active() { return ACTIVE.get()!=null; }
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
        final List<NativeCraftingTableSnapshot.Data> steps = new ArrayList<>();
        Inventory inventory;
        CraftingMenu menu;
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
            if (phase == Phase.CREATING || requested != menu)
                throw refused("Native menu callback did not belong to the exact constructed detached menu");
        }
        void requireHolder(RecipeCraftingHolder holder, Player requested) {
            requireMenu(menu);
            if (phase != Phase.CLICKING || holder != NativeCraftingTableSnapshot.result(menu) || requested != player || takingRecipe == null)
                throw refused("Native recipe award escaped the exact detached result removal");
        }
        void add(Notice notice, int size) {
            if (size < 0 || notices.size() >= limits.notices() || size > limits.noticeBytes() - bytes)
                throw refused("Native menu metadata journal bound exceeded");
            notices.add(notice); bytes += size;
        }
        List<NativeStackSnapshot> grid() {
            return NativeCraftingTableSnapshot.grid(menu).getItems().stream().map(stack -> NativeStackSnapshot.capture(stack, player.registryAccess())).toList();
        }
        void click(Click click) {
            require(); metadata.revalidate();
            metadata.requireItems(inventory.items); metadata.requireItems(inventory.armor); metadata.requireItems(inventory.offhand);
            metadata.requireItems(NativeCraftingTableSnapshot.grid(menu).getItems()); metadata.requireItems(List.of(menu.getCarried()));
            requireClick(click);
            phase = Phase.CLICKING; takingRecipe = null; takingStack = null;
            if (click.slot() == 0 && !menu.getSlot(0).getItem().isEmpty()) {
                ResultContainer result = NativeCraftingTableSnapshot.result(menu);
                if (preview == null || preview.recipe == null || result.getRecipeUsed() != preview.recipe
                        || !preview.grid.equals(grid()) || !preview.result.matches(result.getItem(0), player.registryAccess()))
                    throw refused("Native result preview is stale or unrelated to its exact current grid");
                recipes.requireTake(preview.recipe); metadata.requireItems(List.of(result.getItem(0)));
                takingRecipe = preview.recipe; takingStack = result.getItem(0);
            }
            menu.clicked(click.slot(), click.button(), click.type(), player);
            if (inGrid) throw refused("Native grid callback did not leave its preparation scope");
            metadata.requireItems(inventory.items); metadata.requireItems(inventory.armor); metadata.requireItems(inventory.offhand);
            metadata.requireItems(NativeCraftingTableSnapshot.grid(menu).getItems()); metadata.requireItems(List.of(menu.getCarried()));
            phase = Phase.INSPECTING;
            steps.add(NativeCraftingTableSnapshot.capture(player, menu, inventory, snapshots).data());
        }
    }
    static void requireClick(Click click) {
        if (click == null || click.type() != ClickType.PICKUP || (click.button() != 0 && click.button() != 1)
                || !NativeFirstLoopMetadata.MenuShape.TABLE_3X3.admitsInitialPickupSlot(click.slot()))
            throw refused("Only bounded native3x3 result/grid/main/hotbar PICKUP is admitted");
    }
    public static Result prepareScript(NativeWholeAction action, ServerPlayer player, CubeCell table, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, List<Click> clicks, Limits limits) {
        return prepare(action, player, table, metadata, recipes, clicks, limits, true, null);
    }
    /** Only the package-private retained delivery plan can keep this successful preparation open. */
    static Result prepareResolvedScript(NativeWholeAction action, ServerPlayer player, CubeCell table, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, List<Click> clicks, Limits limits, NativeInventoryMenuSnapshot.Budget sharedBudget) {
        return prepare(action, player, table, metadata, recipes, clicks, limits, false, java.util.Objects.requireNonNull(sharedBudget));
    }
    private static Result prepare(NativeWholeAction action, ServerPlayer player, CubeCell table, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, List<Click> clicks, Limits limits, boolean abortOnly, NativeInventoryMenuSnapshot.Budget sharedBudget) {
        boolean prepared = false;
        if (ACTIVE.get() != null || NativeInventoryMenuPreparation.active() || action==null || NativeWholeAction.current() != action || action.state() != NativeWholeAction.State.PREPARING) {
            var failure=refused("Native detached menu comparison cannot reenter");
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
        try {
            if (clicks.isEmpty() || clicks.size() > limits.clicks()) throw refused("Native click script exceeds its explicit bound");
            NativeInventorySnapshot.requireResolvedMenu(player);
            if (CommonHooks.getCraftingPlayer() != null) throw refused("Another native crafting context is active");
            metadata.requirePlayerIdentity(player); recipes.requirePlayerIdentity(player);
            var binding = SurvivalLevelBinding.find(player.level());
            if (binding == null || !binding.actions().owns(action) || binding.world() != action.world()
                    || !action.identity().actor().equals(player.getUUID()))
                throw refused("Native detached menu requires the exact level/actor action binding");
            metadata.revalidate(); recipes.revalidate();
            new PinnedNativeEventAdmission(NeoForge.EVENT_BUS).requireEmpty(ItemStackedOnOtherEvent.class);
            var snapshots = sharedBudget == null ? new NativeInventoryMenuSnapshot.Budget(limits.menuSnapshotBytes()) : sharedBudget;
            var before = NativeInventoryMenuSnapshot.capture(player, player.inventoryMenu, player.getInventory(), snapshots);
            var state=action.read(table);
            if (!state.is(Blocks.CRAFTING_TABLE) || !player.canInteractWithBlock(NativeVanillaPreparation.nativePos(table),1.0))
                throw refused("Native crafting table is absent or outside exact reach");
            MenuProvider provider=state.getMenuProvider(player.level(),NativeVanillaPreparation.nativePos(table));
            NativeTableMenuLifecycleAdmission.requireProvider(provider,player,table);
            int counter=NativeInventoryMenuSnapshot.read(ServerPlayer.class,player,"containerCounter",int.class);
            int next=NativeTableMenuLifecycleAdmission.nextCounter(counter);
            if (before.data().selected() != action.selectedSlot())
                throw refused("Native action hotbar selection differs from the exact live menu before-image");
            for (int slot = 0; slot < 41; slot++)
                if (!before.data().inventory().get(slot).matches(action.stack(slot), player.registryAccess()))
                    throw refused("Native action inventory differs from the exact live menu before-image");
            var scope = new Scope(action, player, metadata, recipes, limits, snapshots);
            ACTIVE.set(scope); // Before native Inventory and actual table provider construction.
            try {
                scope.inventory = new Inventory(player);
                for (int slot=0;slot<41;slot++) scope.inventory.setItem(slot,before.data().inventory().get(slot).materialize(player.registryAccess()));
                scope.inventory.selected=before.data().selected();
                NativeTableMenuLifecycleAdmission.installCounter(scope.inventory,before.data().inventoryChanges());
                var created=provider.createMenu(next,scope.inventory,player);
                if (created==null || created.getClass()!=CraftingMenu.class) throw refused("Native table provider did not create exact CraftingMenu");
                scope.menu=(CraftingMenu)created;
                NativeCraftingTableSnapshot.requireShape(player,scope.menu,scope.inventory);
                if (!scope.menu.stillValid(player)) throw refused("Actual native table menu rejected its placed block/reach");
                scope.phase = Phase.HYDRATING;
                // Native cache operations, without live observers, predict initMenu's initial image.
                scope.menu.broadcastChanges(); scope.menu.sendAllDataToRemote(); scope.menu.incrementStateId();
                scope.phase = Phase.INSPECTING;
                var opened=NativeCraftingTableSnapshot.capture(player,scope.menu,scope.inventory,snapshots).data();
                for (Click click : List.copyOf(clicks)) scope.click(click);
                var after = scope.steps.getLast(); // Already captured/charged after the final native click.
                before.requireUnchanged(player); metadata.revalidate(); recipes.revalidate();
                if (!abortOnly) NativeResolvedCraftingTablePlan.requireResolved(after);
                prepared = true;
                return new Result(before.data(), opened, after, scope.notices, scope.steps);
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
    static void requireSnapshotSource(ServerPlayer player, CraftingMenu menu, Inventory inventory) {
        if (menu == player.containerMenu && inventory == player.getInventory()) return;
        Scope scope = ACTIVE.get();
        if (scope == null || scope.player != player || scope.menu != menu || scope.inventory != inventory)
            throw refused("Native snapshot source is neither the real menu nor this detached preparation");
        scope.require();
    }
    public static void enterGrid(AbstractContainerMenu menu, Level level, Player player, CraftingContainer grid, ResultContainer result) {
        try {
        Scope scope = ACTIVE.get(); if (scope == null) return;
        scope.requireMenu(menu);
        if (scope.phase != Phase.CLICKING || scope.inGrid || scope.level != level || scope.player != player
                || NativeCraftingTableSnapshot.grid(scope.menu) != grid || NativeCraftingTableSnapshot.result(scope.menu) != result)
            throw refused("Native crafting-grid callback is foreign or reentrant");
        scope.recipes.requireInput(grid.getItems()); scope.inGrid = true; scope.gridRecipe = null;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static void exitGrid(AbstractContainerMenu menu) {
        try {
        Scope scope = ACTIVE.get(); if (scope == null) return;
        scope.requireMenu(menu);
        if (!scope.inGrid) throw refused("Native grid callback has no entry capability");
        scope.preview = new Preview(scope.grid(), scope.gridRecipe,
                NativeStackSnapshot.capture(scope.menu.getSlot(0).getItem(), scope.player.registryAccess()));
        scope.inGrid = false;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static Optional<RecipeHolder<CraftingRecipe>> findRecipe(RecipeManager manager, RecipeType<CraftingRecipe> type,
            CraftingInput input, Level level, RecipeHolder<CraftingRecipe> previous) {
        try {
        Scope scope = ACTIVE.get();
        if (scope == null) return manager.getRecipeFor(type, input, level, previous);
        scope.requireMenu(scope.menu);
        if (!scope.inGrid || manager != scope.player.server.getRecipeManager() || type != RecipeType.CRAFTING
                || level != scope.level || previous != null) throw refused("Foreign native3x3 recipe search");
        scope.recipes.requireInput(input.items());
        var found = manager.getRecipeFor(type, input, level, previous);
        if (found.isPresent()) { scope.recipes.requirePreview(found.get()); scope.gridRecipe = found.get(); }
        return found;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
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
        var stack = NativeStackSnapshot.capture(slot.getItem(), scope.player.registryAccess());
        requireNativePacketRoundTrip(slot, stack, scope.player);
        scope.add(new SlotPacket(slot.getContainerId(), slot.getStateId(), slot.getSlot(), stack), 16 + stack.encodedBytes());
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
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
        var images = input.stream().map(stack -> NativeStackSnapshot.capture(stack, scope.player.registryAccess())).toList();
        int size = 64; for (var image : images) size = Math.addExact(size, image.encodedBytes());
        scope.add(new Crafted(recipe.id(), images), size);
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
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
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
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
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
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
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
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
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static Inventory inventory(ResultSlot slot, Player player) {
        try {
        Scope scope = ACTIVE.get(); if (scope == null) return player.getInventory();
        scope.requireMenu(scope.menu);
        if (slot != scope.menu.getSlot(0) || player != scope.player) throw refused("Foreign native remainder inventory request");
        return scope.inventory;
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    public static ItemEntity drop(ResultSlot slot, Player player, ItemStack stack, boolean random) {
        try {
        if (ACTIVE.get() == null) return player.drop(stack, random);
        throw refused("Detached native first-loop result cannot drop a remainder");
    
        } catch (RuntimeException | Error failure) {
            poisonActivePreparation(failure); NativeInventoryMenuPreparation.poisonActivePreparation(failure); throw failure;
        }
    }
    private static Scope requireScope() { Scope scope = ACTIVE.get(); if (scope == null) throw refused("Native detached menu scope is absent"); return scope; }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
