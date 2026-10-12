package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Transient native menu ownership boundary. This is deliberately NOT a durable checkpoint.
 * InventoryMenu is captured once when active. With a table, its inactive grid/cursor/caches are
 * separately captured while all 41 inventory slots remain a single shared inventory authority.
 */
public final class NativeSessionMenuBoundary {
    public record MenuData(int containerId, List<NativeStackSnapshot> grid, NativeStackSnapshot result,
            NativeStackSnapshot carried, ResourceLocation recipeUsed, int removeCount, int stateId,
            List<NativeStackSnapshot> lastSlots, List<NativeStackSnapshot> remoteSlots,
            NativeStackSnapshot remoteCarried, int quickcraftType, int quickcraftStatus,
            List<Integer> quickcraftSlots, boolean suppressRemoteUpdates) {
        public MenuData {
            if (grid == null || lastSlots == null || remoteSlots == null || quickcraftSlots == null
                    || containerId < 0 || containerId > 100 || grid.size() != (containerId == 0 ? 4 : 9)
                    || lastSlots.size() != 46 || remoteSlots.size() != 46 || result == null || carried == null
                    || remoteCarried == null || removeCount != 0 || stateId < 0 || stateId > 32767
                    || quickcraftType < -1 || quickcraftType > 2 || quickcraftStatus != 0 || !quickcraftSlots.isEmpty())
                throw new IllegalArgumentException("Exact bounded idle native menu state required");
            grid = List.copyOf(grid); lastSlots = List.copyOf(lastSlots); remoteSlots = List.copyOf(remoteSlots);
            quickcraftSlots = List.copyOf(quickcraftSlots);
        }
        public boolean table() { return containerId != 0; }
        public boolean resolved() {
            return result.isEmpty() && carried.isEmpty() && grid.stream().allMatch(NativeStackSnapshot::isEmpty)
                    && removeCount == 0 && !suppressRemoteUpdates;
        }
    }
    public record Data(List<NativeStackSnapshot> inventory, int selected, int inventoryChanges, int containerCounter,
            MenuData inventoryMenu, MenuData tableMenu) {
        public Data {
            if (inventory == null || inventory.size() != 41 || selected < 0 || selected > 8
                    || containerCounter < 0 || containerCounter > 100 || inventoryMenu == null || inventoryMenu.table()
                    || tableMenu != null && (!tableMenu.table() || tableMenu.containerId() != containerCounter))
                throw new IllegalArgumentException("Complete shared41/native menu boundary required");
            inventory = List.copyOf(inventory);
        }
        public MenuData active() { return tableMenu == null ? inventoryMenu : tableMenu; }
        public boolean durableResolved() { return tableMenu == null && inventoryMenu.resolved(); }
    }
    private static final class Graph {
        final AbstractContainerMenu menu;
        final Object slotsList, lastSlots, remoteSlots, listenersList, synchronizer, dataSlots, remoteDataSlots, quickcraftSlots;
        final Slot[] slots;
        final Object[] listeners;
        final Object grid, gridItems, result, resultItems, access;
        final Object[] delegates;
        final RecipeHolder<?> recipe;
        Graph(ServerPlayer player, AbstractContainerMenu menu, Inventory inventory, CubeCell table) {
            this.menu = menu;
            preflightGraph(player, menu, inventory);
            if (menu.getClass() == InventoryMenu.class) NativeInventoryMenuSnapshot.requireShape(player, (InventoryMenu) menu, inventory);
            else if (menu.getClass() == CraftingMenu.class && table != null) {
                NativeCraftingTableSnapshot.requireShape(player, (CraftingMenu) menu, inventory);
                NativeTableMenuLifecycleAdmission.requireAccess((CraftingMenu) menu, player, table);
            } else throw refused("Only the exact retained native inventory/table menu is admitted");
            slotsList = menu.slots; slots = menu.slots.toArray(Slot[]::new);
            grid = menu.getSlot(1).container; gridItems = read(TransientCraftingContainer.class, grid, "items", NonNullList.class);
            result = menu.getSlot(0).container;
            resultItems = read(ResultContainer.class, result, "itemStacks", NonNullList.class);
            recipe = ((ResultContainer) result).getRecipeUsed();
            delegates = new Object[]{delegate(menu.slots), delegate(inventory.items), delegate(inventory.armor), delegate(inventory.offhand),
                    delegate((NonNullList<?>)gridItems), delegate((NonNullList<?>)resultItems),
                    delegate(read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class)),
                    delegate(read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class)),
                    read(Inventory.class, inventory, "compartments", List.class)};
            access = menu instanceof CraftingMenu ? read(CraftingMenu.class, menu, "access", net.minecraft.world.inventory.ContainerLevelAccess.class) : null;
            lastSlots = read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class);
            remoteSlots = read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class);
            listenersList = read(AbstractContainerMenu.class, menu, "containerListeners", List.class);
            if (((List<?>) listenersList).size() != 1) throw refused("One exact native menu listener required");
            listeners = ((List<?>) listenersList).toArray();
            synchronizer = read(AbstractContainerMenu.class, menu, "synchronizer", ContainerSynchronizer.class);
            Object nativeListener = read(ServerPlayer.class, player, "containerListener", ContainerListener.class);
            Object nativeSynchronizer = read(ServerPlayer.class, player, "containerSynchronizer", ContainerSynchronizer.class);
            requireObserver(nativeListener, "net.minecraft.server.level.ServerPlayer$2", player);
            requireObserver(nativeSynchronizer, "net.minecraft.server.level.ServerPlayer$1", player);
            if (listeners[0] != nativeListener || synchronizer != nativeSynchronizer) throw refused("Foreign native menu observers");
            dataSlots = read(AbstractContainerMenu.class, menu, "dataSlots", List.class);
            remoteDataSlots = read(AbstractContainerMenu.class, menu, "remoteDataSlots", it.unimi.dsi.fastutil.ints.IntList.class);
            quickcraftSlots = read(AbstractContainerMenu.class, menu, "quickcraftSlots", Set.class);
            if (listenersList.getClass() != ArrayList.class || dataSlots.getClass() != ArrayList.class
                    || remoteDataSlots.getClass() != it.unimi.dsi.fastutil.ints.IntArrayList.class
                    || quickcraftSlots.getClass() != java.util.HashSet.class || slotsList.getClass() != NonNullList.class
                    || lastSlots.getClass() != NonNullList.class || remoteSlots.getClass() != NonNullList.class)
                throw refused("Native menu observer collection implementation changed");
            for (int index = 0; index < 46; index++) {
                Slot slot = slots[index]; boolean isTable = menu instanceof CraftingMenu;
                String expected = index == 0 ? ResultSlot.class.getName() : !isTable && index >= 5 && index <= 8
                        ? "net.minecraft.world.inventory.ArmorSlot" : !isTable && index == 45
                        ? "net.minecraft.world.inventory.InventoryMenu$1" : Slot.class.getName();
                int backing = index == 0 ? 0 : index < (isTable ? 10 : 5) ? index - 1 : inventoryIndex(isTable, index);
                if (!slot.getClass().getName().equals(expected) || slot.getClass().getClassLoader() != Slot.class.getClassLoader()
                        || slot.index != index || slot.getContainerSlot() != backing) throw refused("Native slot identity/remapping changed");
            }
        }
        void same(Graph now) { same(now, true); }
        void same(Graph now, boolean checkRecipe) {
            if (menu != now.menu || slotsList != now.slotsList || lastSlots != now.lastSlots || remoteSlots != now.remoteSlots
                    || listenersList != now.listenersList || synchronizer != now.synchronizer || dataSlots != now.dataSlots
                    || remoteDataSlots != now.remoteDataSlots || quickcraftSlots != now.quickcraftSlots || grid != now.grid
                    || gridItems != now.gridItems || result != now.result || resultItems != now.resultItems || access != now.access
                    || checkRecipe && recipe != now.recipe || listeners.length != now.listeners.length)
                throw refused("Native menu graph or recipe incarnation changed");
            for (int i = 0; i < delegates.length; i++) if (delegates[i] != now.delegates[i]) throw refused("Native vector backing incarnation changed");
            for (int i = 0; i < 46; i++) if (slots[i] != now.slots[i]) throw refused("Native menu slot incarnation changed");
            for (int i = 0; i < listeners.length; i++) if (listeners[i] != now.listeners[i]) throw refused("Native listener incarnation changed");
        }
    }
    private final ServerPlayer player;
    private final Inventory inventory;
    private final Object items, armor, offhand, connection, level;
    private final Graph inventoryGraph, tableGraph;
    private final CubeCell table;
    private final NativeSurvivalWorld world;
    private final NativeSurvivalWorld.CubeStamp stamp;
    record ConservedScalars(NativeFoodSnapshot food, NativePlayerPoseSnapshot pose, int healthBits, int absorptionBits,
            int air, int fire, int frozen, int tickCount, int invulnerableTime, int hurtTime, int hurtDuration, int deathTime,
            int xpBits, int xpTotal, int xpLevel, boolean noPhysics) {}
    static ConservedScalars conservedScalars(ServerPlayer player) {
        if (player == null || player.getClass() != ServerPlayer.class || !player.server.isSameThread()) throw refused("Exact native scalar owner required");
        float health = player.getHealth(), absorption = player.getAbsorptionAmount();
        if (!Float.isFinite(health) || health <= 0 || !Float.isFinite(absorption) || absorption < 0)
            throw refused("Native menu health/absorption is outside its finite alive profile");
        return new ConservedScalars(NativeFoodSnapshot.capture(player.getFoodData()), transientPose(player),
                Float.floatToRawIntBits(health), Float.floatToRawIntBits(absorption), player.getAirSupply(),
                player.getRemainingFireTicks(), player.getTicksFrozen(), player.tickCount, player.invulnerableTime,
                player.hurtTime, player.hurtDuration, player.deathTime, Float.floatToRawIntBits(player.experienceProgress),
                player.totalExperience, player.experienceLevel, player.noPhysics);
    }
    private final ConservedScalars scalars;
    private final NativeFoodSnapshot food;
    private final NativePlayerPoseSnapshot pose;
    private final int xpBits, xpTotal, xpLevel;
    private final Data data;
    private final int retainedBytes;
    private NativeSessionMenuBoundary(ServerPlayer player, NativeSurvivalWorld world, CubeCell table,
            NativeFirstLoopMetadata metadata, NativeMenuBudget budget, boolean allowSuppressed, NativeSurvivalWorld.CubeStamp expectedStamp) {
        int chargedBefore = budget.used();
        this.player = player; this.world = world; this.table = table;
        if (!player.server.isSameThread() || player.getClass() != ServerPlayer.class) throw refused("Exact native server-thread player required");
        scalars = conservedScalars(player);
        inventory = player.getInventory(); items = inventory.items; armor = inventory.armor; offhand = inventory.offhand;
        connection = player.connection; level = player.level();
        if (player.containerMenu != player.inventoryMenu && (table == null || player.containerMenu.getClass() != CraftingMenu.class)
                || player.containerMenu == player.inventoryMenu && table != null) throw refused("Retained native menu/table alias mismatch");
        budget.reserve(4096);
        inventoryGraph = new Graph(player, player.inventoryMenu, inventory, null);
        tableGraph = table == null ? null : new Graph(player, player.containerMenu, inventory, table);
        metadata.requirePlayerIdentity(player); metadata.revalidate();
        var currentAction = io.earthplatform.minecraft.survival.interaction.NativeWholeAction.current();
        boolean transactional = world.hasActiveTransaction();
        stamp = table == null ? null : transactional ? java.util.Objects.requireNonNull(expectedStamp, "Retained table stamp required during action") : world.stamp(table.cube());
        if (table != null) {
            // Native stillValid reads through the generic binding, so run it only at an idle or
            // PREPARING boundary. APPLYING retains the exact proven access/reach, pose and stamp.
            boolean preparing = currentAction != null && currentAction.state() == io.earthplatform.minecraft.survival.interaction.NativeWholeAction.State.PREPARING;
            if ((!transactional || preparing) && !world.read(table).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE))
                throw refused("Retained native table block changed");
            if ((currentAction == null || preparing) && !player.containerMenu.stillValid(player)) throw refused("Retained native table lost native reach");
        }
        metadata.requireItems(inventory.items); metadata.requireItems(inventory.armor); metadata.requireItems(inventory.offhand);
        var stackImages = new ArrayList<NativeStackSnapshot>(41);
        for (int i = 0; i < 41; i++) stackImages.add(budget.image(inventory.getItem(i), player));
        data = new Data(stackImages, inventory.selected, inventory.getTimesChanged(), read(ServerPlayer.class, player, "containerCounter", int.class),
                captureMenu(player, player.inventoryMenu, metadata, budget),
                tableGraph == null ? null : captureMenu(player, player.containerMenu, metadata, budget));
        if (!allowSuppressed && (data.inventoryMenu.suppressRemoteUpdates() || data.active().suppressRemoteUpdates())) throw refused("Native menu is inside a synchronization callback");
        food = NativeFoodSnapshot.capture(player.getFoodData()); pose = transientPose(player);
        xpBits = Float.floatToRawIntBits(player.experienceProgress); xpTotal = player.totalExperience; xpLevel = player.experienceLevel;
        if (!scalars.equals(conservedScalars(player))) throw refused("Native menu capture changed conserved player scalars");
        retainedBytes = budget.used() - chargedBefore;
    }
    static NativeSessionMenuBoundary capture(ServerPlayer player, NativeSurvivalWorld world, CubeCell table,
            NativeFirstLoopMetadata metadata, NativeMenuBudget budget) {
        return new NativeSessionMenuBoundary(player, world, table, metadata, budget, false, null);
    }
    static NativeSessionMenuBoundary observeDuringApply(ServerPlayer player, NativeSurvivalWorld world, CubeCell table,
            NativeFirstLoopMetadata metadata, NativeMenuBudget budget) {
        var action = io.earthplatform.minecraft.survival.interaction.NativeWholeAction.current();
        var host = NativeEffectDeliveryHost.findAttached(player.serverLevel());
        if (action == null || action.state() != io.earthplatform.minecraft.survival.interaction.NativeWholeAction.State.APPLYING
                || action.world() != world || host == null || host.player() != player || !host.actions().owns(action))
            throw refused("Native mid-delivery observation requires its exact applying owner");
        return new NativeSessionMenuBoundary(player, world, table, metadata, budget, true, null);
    }
    void requireInventoryTopology(NativeSessionMenuBoundary other) {
        if (player != other.player || inventory != other.inventory || items != other.items || armor != other.armor
                || offhand != other.offhand || connection != other.connection || level != other.level)
            throw refused("Native lifecycle changed the inactive inventory owner");
        inventoryGraph.same(other.inventoryGraph, false);
    }
    void requireSameTopology(NativeSessionMenuBoundary other) {
        if (player != other.player || inventory != other.inventory || items != other.items || armor != other.armor || offhand != other.offhand
                || connection != other.connection || level != other.level || !java.util.Objects.equals(table, other.table)
                || !java.util.Objects.equals(stamp, other.stamp) || (tableGraph == null) != (other.tableGraph == null))
            throw refused("Native delivery changed retained boundary identity");
        inventoryGraph.same(other.inventoryGraph, false);
        if (tableGraph != null) tableGraph.same(other.tableGraph, false);
    }
    public Data data() { return data; }
    int retainedBytes() { return retainedBytes; }
    static int captureReservation(Data data) {
        int bytes = 8192 + NativeStackSnapshot.MAX_BYTES; // exact graph/vector overhead plus one capture scratch peak
        for (var image : data.inventory()) bytes = Math.addExact(bytes, 64 + image.encodedBytes());
        bytes = Math.addExact(bytes, menuReservation(data.inventoryMenu()));
        if (data.tableMenu() != null) bytes = Math.addExact(bytes, menuReservation(data.tableMenu()));
        return bytes;
    }
    private static int menuReservation(MenuData menu) {
        int bytes = 2048;
        for (var images : List.of(menu.grid(), menu.lastSlots(), menu.remoteSlots()))
            for (var image : images) bytes = Math.addExact(bytes, 64 + image.encodedBytes());
        for (var image : List.of(menu.result(), menu.carried(), menu.remoteCarried())) bytes = Math.addExact(bytes, 64 + image.encodedBytes());
        return bytes;
    }
    AbstractContainerMenu activeMenu() { return tableGraph == null ? inventoryGraph.menu : tableGraph.menu; }
    CubeCell table() { return table; }
    NativeSurvivalWorld.CubeStamp tableStamp() { return stamp; }
    RecipeHolder<?> activeRecipe() { return tableGraph == null ? inventoryGraph.recipe : tableGraph.recipe; }
    void requireUnchanged(NativeFirstLoopMetadata metadata, NativeMenuBudget actionBudget) {
        if (player.getInventory() != inventory || inventory.items != items || inventory.armor != armor || inventory.offhand != offhand
                || player.connection != connection || player.level() != level || player.inventoryMenu != inventoryGraph.menu
                || player.containerMenu != activeMenu()) throw refused("Native session menu/player identity changed");
        var now = new NativeSessionMenuBoundary(player, world, table, metadata, actionBudget.scratch(), false, stamp);
        if (!scalars.equals(now.scalars) || !data.equals(now.data) || !food.equals(now.food) || !pose.equals(now.pose) || xpBits != now.xpBits
                || xpTotal != now.xpTotal || xpLevel != now.xpLevel || !java.util.Objects.equals(stamp, now.stamp))
            throw refused("Native session transient before-image changed");
        inventoryGraph.same(now.inventoryGraph);
        if (tableGraph != null) tableGraph.same(now.tableGraph);
    }
    void requireConservedPlayer() {
        if (!scalars.equals(conservedScalars(player)) || player.connection != connection || player.level() != level || player.getInventory() != inventory
                || !food.equals(NativeFoodSnapshot.capture(player.getFoodData())) || !pose.equals(transientPose(player))
                || xpBits != Float.floatToRawIntBits(player.experienceProgress) || xpTotal != player.totalExperience || xpLevel != player.experienceLevel)
            throw refused("Native menu callback changed food/pose/XP or player identity");
    }
    static MenuData captureMenu(ServerPlayer player, AbstractContainerMenu menu, NativeFirstLoopMetadata metadata, NativeMenuBudget budget) {
        boolean table = menu.getClass() == CraftingMenu.class;
        if (!table && menu.getClass() != InventoryMenu.class || menu.slots.size() != 46) throw refused("Exact bounded native menu required");
        var grid = (TransientCraftingContainer) menu.getSlot(1).container;
        var result = (ResultContainer) menu.getSlot(0).container;
        metadata.requireItems(grid.getItems()); metadata.requireItems(List.of(menu.getCarried()));
        var recipe = result.getRecipeUsed();
        var quickSlots = read(AbstractContainerMenu.class, menu, "quickcraftSlots", Set.class);
        if (!quickSlots.isEmpty()) throw refused("Native drag state is not admitted by PICKUP profile");
        return new MenuData(menu.containerId, images(grid.getItems(), table ? 9 : 4, player, metadata, budget),
                budget.image(result.getItem(0), player), budget.image(menu.getCarried(), player), recipe == null ? null : recipe.id(),
                read(ResultSlot.class, menu.getSlot(0), "removeCount", int.class), menu.getStateId(),
                images(read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class), 46, player, metadata, budget),
                images(read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class), 46, player, metadata, budget),
                budget.image(read(AbstractContainerMenu.class, menu, "remoteCarried", ItemStack.class), player),
                read(AbstractContainerMenu.class, menu, "quickcraftType", int.class),
                read(AbstractContainerMenu.class, menu, "quickcraftStatus", int.class), List.of(),
                read(AbstractContainerMenu.class, menu, "suppressRemoteUpdates", boolean.class));
    }
    private static List<NativeStackSnapshot> images(List<?> source, int size, ServerPlayer player, NativeFirstLoopMetadata metadata, NativeMenuBudget budget) {
        if (source.size() != size) throw refused("Native menu vector shape changed");
        budget.reserve(64 + size * 8);
        var result = new ArrayList<NativeStackSnapshot>(size);
        for (Object entry : source) {
            if (!(entry instanceof ItemStack stack)) throw refused("Native menu contains a non-stack entry");
            result.add(budget.image(stack, player));
        }
        return List.copyOf(result);
    }
    static NativePlayerPoseSnapshot transientPose(ServerPlayer player) {
        // Same durable scalar profile, but intentionally separate menu admission. No durable API is weakened.
        if (!player.server.isSameThread() || player.getClass() != ServerPlayer.class || !player.isAlive() || player.isRemoved()
                || player.isPassenger() || player.isVehicle() || player.isSleeping() || player.isChangingDimension()
                || player.portalProcess != null || player.isUsingItem() || !player.getActiveEffects().isEmpty()
                || player.getCamera() != player || player.isFallFlying() || player.isAutoSpinAttack() || player.isSwimming()
                || player.getPose() != net.minecraft.world.entity.Pose.STANDING) throw refused("Native menu player is outside transient stance profile");
        var motion = player.getDeltaMovement();
        return new NativePlayerPoseSnapshot(player.level().dimension().location(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), motion.x, motion.y, motion.z, player.fallDistance, player.onGround(),
                player.gameMode.getGameModeForPlayer(), player.gameMode.getPreviousGameModeForPlayer(),
                NativePlayerPoseSnapshot.AbilityState.capture(player.getAbilities()));
    }
    static void requireSupportedStance(ServerPlayer player) {
        transientPose(player);
        if (!player.onGround() || player.noPhysics || player.hasDisconnected() || player.mainSupportingBlockPos.isEmpty()
                || player.getDeltaMovement().x != 0 || player.getDeltaMovement().z != 0
                || player.getDeltaMovement().y < -0.08D || player.getDeltaMovement().y > 0
                || player.getGravity() != 0.08D || player.isNoGravity()
                || read(net.minecraft.world.entity.LivingEntity.class, player, "discardFriction", boolean.class) || player.fallDistance != 0
                || player.isInWater() || player.isInLava() || player.isOnFire() || player.isSpectator()
                || player.getAbilities().flying || player.getAbilities().instabuild)
            throw refused("The first native menu session requires a stationary supported dry survival player");
    }
    static int inventoryIndex(boolean table, int menuIndex) {
        if (!table) return NativeInventoryMenuBroadcastAdmission.inventoryIndex(menuIndex);
        if (menuIndex >= 10 && menuIndex < 37) return menuIndex - 1;
        if (menuIndex >= 37 && menuIndex < 46) return menuIndex - 37;
        throw refused("No shared inventory backing for this native table slot");
    }
    static NativeStackSnapshot slotImage(Data data, int slot) {
        var active = data.active(); int gridEnd = active.table() ? 10 : 5;
        if (slot == 0) return active.result();
        if (slot < gridEnd) return active.grid().get(slot - 1);
        return data.inventory().get(inventoryIndex(active.table(), slot));
    }
    static void requireTrustedLiveCollections(ServerPlayer player) {
        if (player == null || player.getClass() != ServerPlayer.class || !player.server.isSameThread()) throw refused("Exact native server-thread player required");
        preflightGraph(player, player.inventoryMenu, player.getInventory());
        if (player.containerMenu != player.inventoryMenu) preflightGraph(player, player.containerMenu, player.getInventory());
    }
    private static Object delegate(NonNullList<?> values) { return read(NonNullList.class, values, "list", List.class); }
    private static void preflightGraph(ServerPlayer player, AbstractContainerMenu menu, Inventory inventory) {
        if (inventory == null || inventory.getClass() != Inventory.class || inventory.player != player
                || menu == null || menu.getClass() != InventoryMenu.class && menu.getClass() != CraftingMenu.class)
            throw refused("Pinned native menu/inventory class required before collection traversal");
        requireVector(inventory.items, 36, true); requireVector(inventory.armor, 4, true); requireVector(inventory.offhand, 1, true);
        List<?> compartments = read(Inventory.class, inventory, "compartments", List.class);
        if (!compartments.getClass().getName().equals("com.google.common.collect.RegularImmutableList")
                || compartments.getClass().getClassLoader() != com.google.common.collect.ImmutableList.class.getClassLoader())
            throw refused("Foreign native inventory compartment collection");
        if (compartments.size() != 3 || compartments.get(0) != inventory.items || compartments.get(1) != inventory.armor || compartments.get(2) != inventory.offhand)
            throw refused("Native inventory compartment identity changed");
        requireVector(menu.slots, 46, false);
        requireVector(read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class), 46, false);
        requireVector(read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class), 46, false);
        Object listeners = read(AbstractContainerMenu.class, menu, "containerListeners", List.class);
        Object data = read(AbstractContainerMenu.class, menu, "dataSlots", List.class);
        Object remote = read(AbstractContainerMenu.class, menu, "remoteDataSlots", it.unimi.dsi.fastutil.ints.IntList.class);
        Object quick = read(AbstractContainerMenu.class, menu, "quickcraftSlots", Set.class);
        if (listeners.getClass() != ArrayList.class || data.getClass() != ArrayList.class
                || remote.getClass() != it.unimi.dsi.fastutil.ints.IntArrayList.class || quick.getClass() != java.util.HashSet.class)
            throw refused("Foreign native observer/data/drag collection before traversal");
        if (((List<?>)listeners).size() != 1 || !((List<?>)data).isEmpty() || !((it.unimi.dsi.fastutil.ints.IntList)remote).isEmpty() || !((Set<?>)quick).isEmpty())
            throw refused("Native observer/data/drag shape changed");
        Class<?> shape = menu.getClass();
        var grid = read(shape, menu, "craftSlots", net.minecraft.world.inventory.CraftingContainer.class);
        var result = read(shape, menu, "resultSlots", ResultContainer.class);
        if (grid.getClass() != TransientCraftingContainer.class || result.getClass() != ResultContainer.class)
            throw refused("Foreign native grid/result implementation before traversal");
        requireVector(read(TransientCraftingContainer.class, grid, "items", NonNullList.class), shape == InventoryMenu.class ? 4 : 9, true);
        requireVector(read(ResultContainer.class, result, "itemStacks", NonNullList.class), 1, true);
        if (read(TransientCraftingContainer.class, grid, "menu", AbstractContainerMenu.class) != menu
                || read(shape, menu, shape == InventoryMenu.class ? "owner" : "player", Player.class) != player)
            throw refused("Native menu/grid player back-reference changed");
        for (int index = 0; index < 46; index++) {
            Object slot = menu.slots.get(index); boolean table = shape == CraftingMenu.class;
            String expected = index == 0 ? ResultSlot.class.getName() : !table && index >= 5 && index <= 8
                    ? "net.minecraft.world.inventory.ArmorSlot" : !table && index == 45 ? "net.minecraft.world.inventory.InventoryMenu$1" : Slot.class.getName();
            if (slot == null || !slot.getClass().getName().equals(expected) || slot.getClass().getClassLoader() != Slot.class.getClassLoader())
                throw refused("Foreign native slot class before shape callbacks");
        }
        if (read(ResultSlot.class, menu.slots.get(0), "player", Player.class) != player
                || read(ResultSlot.class, menu.slots.get(0), "craftSlots", net.minecraft.world.inventory.CraftingContainer.class) != grid)
            throw refused("Native result slot player/grid back-reference changed");
        // Exact native collection graph is established before component map traversal or any codec.
        requireStackVector(inventory.items); requireStackVector(inventory.armor); requireStackVector(inventory.offhand);
        requireStackVector(read(TransientCraftingContainer.class, grid, "items", NonNullList.class));
        requireStackVector(read(ResultContainer.class, result, "itemStacks", NonNullList.class));
        requireStackVector(read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class));
        requireStackVector(read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class));
        NativeMenuBudget.requireComponents(menu.getCarried());
        NativeMenuBudget.requireComponents(read(AbstractContainerMenu.class, menu, "remoteCarried", ItemStack.class));
    }
    private static void requireStackVector(List<?> values) {
        for (Object value : values) {
            if (!(value instanceof ItemStack stack)) throw refused("Native stack vector contains a non-stack");
            NativeMenuBudget.requireComponents(stack);
        }
    }
    private static void requireVector(NonNullList<?> list, int size, boolean fixed) {
        if (list == null || list.getClass() != NonNullList.class) throw refused("Foreign native stack-vector implementation");
        List<?> delegate = read(NonNullList.class, list, "list", List.class);
        String expected = fixed ? "java.util.Arrays$ArrayList" : "java.util.ArrayList";
        if (!delegate.getClass().getName().equals(expected) || delegate.getClass().getClassLoader() != ArrayList.class.getClassLoader())
            throw refused("Foreign native stack-vector delegate before traversal");
        if (delegate.size() != size) throw refused("Native stack-vector size changed");
    }
    static <T> T read(Class<?> owner, Object object, String name, Class<T> type) {
        return NativeInventoryMenuSnapshot.read(owner, object, name, type);
    }
    static void write(Class<?> owner, Object object, String name, Class<?> type, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != type || !field.trySetAccessible()) throw refused("Pinned native menu write descriptor changed: " + name);
            field.set(object, value);
        } catch (ReflectiveOperationException failure) { throw refused("Pinned native menu state write failed: " + name); }
    }
    private static void requireObserver(Object observer, String name, ServerPlayer player) {
        if (observer == null || !observer.getClass().getName().equals(name)
                || observer.getClass().getClassLoader() != ServerPlayer.class.getClassLoader()
                || read(observer.getClass(), observer, "this$0", ServerPlayer.class) != player) throw refused("Pinned native observer closure changed");
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
