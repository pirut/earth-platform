package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.*;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.PatchedDataComponentMap;
/** Pure trusted graph/patch admission BEFORE any ItemStack codec or resolved-menu callback. */
final class NativePlayerMoveSnapshotAdmission {
 static void require(ServerPlayer player) {
  if(player==null||player.getClass()!=ServerPlayer.class||!player.server.isSameThread()
     ||player.containerMenu!=player.inventoryMenu)throw refused("MOVE requires exact resolved native inventory owner");
  Inventory inventory=player.getInventory();preflightGraph(player,player.inventoryMenu,inventory);
  requireStacks(inventory.items);requireStacks(inventory.armor);requireStacks(inventory.offhand);
  var menu=player.inventoryMenu;
  requireStacks(read(AbstractContainerMenu.class,menu,"lastSlots",NonNullList.class));
  requireStacks(read(AbstractContainerMenu.class,menu,"remoteSlots",NonNullList.class));
  requireStacks(menu.getCraftSlots().getItems());
  requireStacks(read(ResultContainer.class,menu.getSlot(0).container,"itemStacks",NonNullList.class));
  requireComponents(menu.getCarried());requireComponents(read(AbstractContainerMenu.class,menu,"remoteCarried",ItemStack.class));
  io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.requireResolvedMenu(player);
 }
 private static void requireStacks(List<?> list){for(Object value:list){if(!(value instanceof ItemStack stack))throw refused("Native stack vector contains a non-stack");requireComponents(stack);}}
 static void requireComponents(ItemStack stack) {
  if(stack==null||stack.getClass()!=ItemStack.class)throw refused("Exact native stack class required before codec admission");
  if(stack.isEmpty())return; // NativeStackSnapshot uses its constant empty encoding without component callbacks.
  var item=stack.getItem();PinnedNativeEventAdmission.requireOrdinaryItemType(item);
  var key=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
  if(key==null||net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key)!=item)throw refused("Unregistered native item singleton");
  if(stack.getCount()<1||stack.getCount()>64||net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString().length()>64)
   throw refused("MOVE native stack scalar encoding exceeds admitted shape");
  Object components=NativePlayerStepSnapshot.field(ItemStack.class,stack,"components");
  if(components==null||components.getClass()!=PatchedDataComponentMap.class)throw refused("Foreign native component map");
  Object prototype=NativePlayerStepSnapshot.field(PatchedDataComponentMap.class,components,"prototype");
  Object expected=NativePlayerStepSnapshot.field(net.minecraft.world.item.Item.class,item,"components");
  if(prototype!=expected||prototype==null||prototype.getClass()!=nativeClass("net.minecraft.core.component.DataComponentMap$Builder$SimpleMap",PatchedDataComponentMap.class))
   throw refused("Native component prototype identity/class changed");
  Object base=NativePlayerStepSnapshot.field(prototype.getClass(),prototype,"map");
  if(base.getClass()!=it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.class
      &&base.getClass()!=it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap.class)throw refused("Foreign native prototype backing map");
  if(((it.unimi.dsi.fastutil.objects.Reference2ObjectMap<?,?>)base).size()>16)throw refused("Native prototype component count exceeds first-loop bound");
  Object raw=NativePlayerStepSnapshot.field(PatchedDataComponentMap.class,components,"patch");
  if(raw==null||raw.getClass()!=it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.class
     &&raw.getClass()!=it.unimi.dsi.fastutil.objects.Reference2ObjectMaps.emptyMap().getClass())throw refused("Foreign native component patch collection");
  var patch=(it.unimi.dsi.fastutil.objects.Reference2ObjectMap<?,?>)raw;
  if(patch.size()>1)throw refused("MOVE component patch exceeds native damage-only shape");
  for(var entry:patch.reference2ObjectEntrySet()) {
   if(entry.getKey()!=net.minecraft.core.component.DataComponents.DAMAGE||!(entry.getValue() instanceof Optional<?> value)
      ||value.isEmpty()||value.get().getClass()!=Integer.class||(Integer)value.get()<0||(Integer)value.get()>65535)
    throw refused("Unadmitted native component before codec execution");
  }
 }
 static Class<?> nativeClass(String name,Class<?> owner){try{return Class.forName(name,false,owner.getClassLoader());}catch(ClassNotFoundException|LinkageError failure){throw refused("Pinned native class missing: "+name);}}
    private static void preflightGraph(ServerPlayer player, AbstractContainerMenu menu, Inventory inventory) {
        if (inventory == null || inventory.getClass() != Inventory.class || inventory.player != player
                || menu == null || menu.getClass() != InventoryMenu.class)
            throw refused("Pinned native menu/inventory class required before collection traversal");
        requireVector(inventory.items, 36, true); requireVector(inventory.armor, 4, true); requireVector(inventory.offhand, 1, true);
        List<?> compartments = read(Inventory.class, inventory, "compartments", List.class);
        if (compartments.getClass() != com.google.common.collect.ImmutableList.of(1,2,3).getClass())
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
            if (slot == null || slot.getClass() != nativeClass(expected, Slot.class))
                throw refused("Foreign native slot class before shape callbacks");
            Object expectedContainer=index==0?result:index<5?grid:inventory;
            if(((Slot)slot).container!=expectedContainer)throw refused("Foreign native slot backing before item callbacks");
        }
        if (read(ResultSlot.class, menu.slots.get(0), "player", Player.class) != player
                || read(ResultSlot.class, menu.slots.get(0), "craftSlots", net.minecraft.world.inventory.CraftingContainer.class) != grid)
            throw refused("Native result slot player/grid back-reference changed");
    }
    private static void requireVector(NonNullList<?> list, int size, boolean fixed) {
        if (list == null || list.getClass() != NonNullList.class) throw refused("Foreign native stack-vector implementation");
        List<?> delegate = read(NonNullList.class, list, "list", List.class);
        String expected = fixed ? "java.util.Arrays$ArrayList" : "java.util.ArrayList";
        if (delegate.getClass() != (fixed ? java.util.Arrays.asList(new Object[1]).getClass() : ArrayList.class))
            throw refused("Foreign native stack-vector delegate before traversal");
        if (delegate.size() != size) throw refused("Native stack-vector size changed");
    }
    static <T> T read(Class<?> owner, Object object, String name, Class<T> type) {
        return NativeInventoryMenuSnapshot.read(owner, object, name, type);
    }
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
