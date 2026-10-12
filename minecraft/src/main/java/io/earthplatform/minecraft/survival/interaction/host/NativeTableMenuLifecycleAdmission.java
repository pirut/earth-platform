package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.neoforged.neoforge.client.extensions.IMenuProviderExtension;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/** Exact native empty-open/resolved-close profile for one committed internal server script. */
final class NativeTableMenuLifecycleAdmission {
    private final ServerPlayer player;
    private final CubeCell table;
    private final int counterBefore;
    private final NativeInventoryMenuBroadcastAdmission inventoryObservers;
    private final Field inventoryCounter;
    private final PinnedNativeEventAdmission events;
    NativeTableMenuLifecycleAdmission(ServerPlayer player,CubeCell table) {
        this.player=player; this.table=table;
        inventoryObservers=NativeInventoryMenuBroadcastAdmission.capture(player);
        counterBefore=NativeInventoryMenuSnapshot.read(ServerPlayer.class,player,"containerCounter",int.class);
        nextCounter(counterBefore); inventoryCounter=counterField();
        events=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS); requireBefore();
    }
    int counterBefore() { return counterBefore; }
    static int nextCounter(int before) {
        if (before<0 || before>100) throw refused("Native container counter is outside its pinned cycle");
        return before%100+1;
    }
    void requireBefore() {
        inventoryObservers.require();
        if (NativeInventoryMenuSnapshot.read(ServerPlayer.class,player,"containerCounter",int.class)!=counterBefore)
            throw refused("Native container counter changed before table open");
        requireEvents();
    }
    void requireEvents() {
        events.requireEmpty(PlayerContainerEvent.Open.class); events.requireEmpty(PlayerContainerEvent.Close.class);
    }
    void requireOpened(CraftingMenu menu) {
        if (player.containerMenu!=menu || menu.containerId!=nextCounter(counterBefore)
                || NativeInventoryMenuSnapshot.read(ServerPlayer.class,player,"containerCounter",int.class)!=menu.containerId)
            throw refused("Native table open did not preserve its exact container counter/menu identity");
        requireAccess(menu,player,table); requireEvents();
    }
    void installLiveCounter(int value) { writeCounter(inventoryCounter,player.getInventory(),value); }
    static void installCounter(Inventory inventory,int value) {
        if (inventory==inventory.player.getInventory()) throw refused("Detached table counter installation cannot target live inventory");
        writeCounter(counterField(),inventory,value);
    }
    private static Field counterField() {
        try {
            Field field=Inventory.class.getDeclaredField("timesChanged");
            if (field.getType()!=int.class || Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible())
                throw refused("Native inventory counter field is unavailable before table delivery");
            return field;
        } catch (ReflectiveOperationException failure) { throw refused("Native inventory counter descriptor changed"); }
    }
    private static void writeCounter(Field field,Inventory inventory,int value) {
        try { field.setInt(inventory,value); }
        catch (IllegalAccessException failure) { throw refused("Native admitted inventory counter write failed"); }
    }
    static void requireProvider(MenuProvider provider,ServerPlayer player,CubeCell table) {
        if (provider==null || provider.getClass()!=SimpleMenuProvider.class || Blocks.CRAFTING_TABLE.getClass()!=CraftingTableBlock.class)
            throw refused("Only the exact native placed-table provider is admitted");
        try {
            if (provider.getClass().getMethod("writeClientSideData",AbstractContainerMenu.class,RegistryFriendlyByteBuf.class).getDeclaringClass()!=IMenuProviderExtension.class
                    || provider.getClass().getMethod("shouldTriggerClientSideContainerClosingOnOpen").getDeclaringClass()!=IMenuProviderExtension.class)
                throw refused("Native table provider has custom lifecycle/client-data callbacks");
        } catch (ReflectiveOperationException failure) { throw refused("Native table provider callback descriptor changed"); }
        var constructor=NativeInventoryMenuSnapshot.read(SimpleMenuProvider.class,provider,"menuConstructor",MenuConstructor.class);
        // Obtain the exact pinned native factory class without constructing or opening any menu.
        var nativeProvider=Blocks.CRAFTING_TABLE.defaultBlockState().getMenuProvider(player.level(),NativeVanillaPreparation.nativePos(table));
        var expected=NativeInventoryMenuSnapshot.read(SimpleMenuProvider.class,nativeProvider,"menuConstructor",MenuConstructor.class);
        if (constructor.getClass()!=expected.getClass() || !constructor.getClass().isSynthetic()
                || constructor.getClass().getNestHost()!=CraftingTableBlock.class)
            throw refused("Foreign native table menu factory is not admitted");
        requireLevelPositionCapture(constructor,player.level(),NativeVanillaPreparation.nativePos(table));
        requireTitle(provider.getDisplayName());
    }
    static void requireAccess(CraftingMenu menu,ServerPlayer player,CubeCell table) {
        var access=NativeInventoryMenuSnapshot.read(CraftingMenu.class,menu,"access",ContainerLevelAccess.class);
        Object expected=ContainerLevelAccess.create(player.level(),NativeVanillaPreparation.nativePos(table));
        if (access.getClass()!=expected.getClass()) throw refused("Native table access is not the actual level/position closure");
        requireLevelPositionCapture(access,player.level(),NativeVanillaPreparation.nativePos(table));
    }
    private static void requireLevelPositionCapture(Object closure,Level level,BlockPos pos) {
        Field[] fields=closure.getClass().getDeclaredFields();
        if (fields.length!=2) throw refused("Native table factory/access captured-field count changed");
        boolean foundLevel=false,foundPos=false;
        for (Field field:fields) {
            if (Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers()) || !field.trySetAccessible())
                throw refused("Native table factory/access capture is not inspectable");
            try {
                Object value=field.get(closure);
                if (field.getType()==Level.class && value==level && !foundLevel) foundLevel=true;
                else if (field.getType()==BlockPos.class && value!=null && value.getClass()==BlockPos.class && value.equals(pos) && !foundPos) foundPos=true;
                else throw refused("Native table factory/access captured a foreign level or position");
            } catch (IllegalAccessException failure) { throw refused("Native table factory/access inspection failed"); }
        }
        if (!foundLevel || !foundPos) throw refused("Native table factory/access lacks its exact capture");
    }
    static void requireTitle(Component title) {
        if (title.getClass()!=MutableComponent.class || !title.getStyle().isEmpty() || !title.getSiblings().isEmpty()
                || !(title.getContents() instanceof TranslatableContents text) || text.getClass()!=TranslatableContents.class
                || !text.getKey().equals("container.crafting") || text.getArgs().length!=0 || text.getFallback()!=null)
            throw refused("Native crafting-table title differs from its bounded translation");
    }
    static int reserveOpen(ServerPlayer player,NativeCraftingTableSnapshot.Data before,int allowance) {
        var values=net.minecraft.core.NonNullList.<net.minecraft.world.item.ItemStack>withSize(46,net.minecraft.world.item.ItemStack.EMPTY);
        int bytes=256;
        for (int i=0;i<46;i++) {
            var image=NativeCraftingTableBroadcastAdmission.slotImage(before,i);
            values.set(i,image.materialize(player.registryAccess()));
            bytes=NativeCraftingTableBroadcastAdmission.checkedCharge(bytes,64+image.encodedBytes()*2,allowance);
        }
        var raw=io.netty.buffer.Unpooled.buffer(256,Math.max(256,allowance));
        try {
            var buffer=new RegistryFriendlyByteBuf(raw,player.registryAccess());
            var title=Component.translatable("container.crafting"); requireTitle(title);
            var open=new ClientboundOpenScreenPacket(before.containerId(),MenuType.CRAFTING,title);
            ClientboundOpenScreenPacket.STREAM_CODEC.encode(buffer,open);
            int openBytes=buffer.readableBytes();
            var decoded=ClientboundOpenScreenPacket.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable() || decoded.getContainerId()!=before.containerId() || decoded.getType()!=MenuType.CRAFTING)
                throw refused("Native crafting-table open packet round trip changed identity");
            requireTitle(decoded.getTitle()); buffer.clear();
            var packet=new ClientboundContainerSetContentPacket(before.containerId(),before.stateId(),values,before.carried().materialize(player.registryAccess()));
            ClientboundContainerSetContentPacket.STREAM_CODEC.encode(buffer,packet); int contentBytes=buffer.readableBytes();
            var content=ClientboundContainerSetContentPacket.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable() || content.getContainerId()!=before.containerId() || content.getStateId()!=before.stateId() || content.getItems().size()!=46
                    || !before.carried().matches(content.getCarriedItem(),player.registryAccess())) throw refused("Native table initial-content packet changed its shape");
            for (int i=0;i<46;i++) if (!NativeCraftingTableBroadcastAdmission.slotImage(before,i).matches(content.getItems().get(i),player.registryAccess()))
                throw refused("Native table initial-content packet changed a stack");
            return NativeCraftingTableBroadcastAdmission.checkedCharge(bytes,Math.addExact(openBytes,contentBytes),allowance);
        } finally { raw.release(); }
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
