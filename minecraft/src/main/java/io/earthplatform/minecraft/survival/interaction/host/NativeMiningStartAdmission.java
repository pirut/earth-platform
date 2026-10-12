package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.common.NeoForge;

/** New original START closure beyond the separately admitted destroy-progress/loot path. */
final class NativeMiningStartAdmission {
    private record BorderState(long centerX,long centerZ,int absoluteMaximum,long size,long minX,long minZ,long maxX,long maxZ) {}
    private final ServerPlayer player;
    private final ServerLevel level;
    private final WorldBorder border;
    private final Object extent;
    private final Class<?> extentInterface,staticExtent;
    private final BorderState before;
    private final PinnedNativeEventAdmission events;
    private NativeMiningStartAdmission(ServerPlayer player) {
        this.player=player;level=player.serverLevel();
        events=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        extentInterface=nativeClass("net.minecraft.world.level.border.WorldBorder$BorderExtent");
        staticExtent=nativeClass("net.minecraft.world.level.border.WorldBorder$StaticBorderExtent");
        NativeMiningControlPins.requireAll();
        if(level.getClass()!=ServerLevel.class||player.server.getClass()!=net.minecraft.gametest.framework.GameTestServer.class)
            throw refused("Native START requires exact original GameTest server/level");
        requireDeclaring(player.server.getClass(),"isUnderSpawnProtection",MinecraftServer.class,ServerLevel.class,BlockPos.class,Player.class);
        requireDeclaring(level.getClass(),"mayInteract",ServerLevel.class,Player.class,BlockPos.class);
        requireDeclaring(level.getClass(),"getWorldBorder",Level.class);
        // Read the original final field before invoking the getter or any extent method.
        border=NativeSessionMenuBoundary.read(Level.class,level,"worldBorder",WorldBorder.class);
        if(border==null||border.getClass()!=WorldBorder.class)
            throw refused("Native START requires exact unscaled native WorldBorder");
        extent=NativeSessionMenuBoundary.read(WorldBorder.class,border,"extent",extentInterface);
        if(extent==null||extent.getClass()!=staticExtent
                ||NativeSessionMenuBoundary.read(staticExtent,extent,"this$0",WorldBorder.class)!=border)
            throw refused("Native START requires the exact original stationary border extent/back-reference");
        before=borderState();requireEmptyHitClosure();
    }
    static NativeMiningStartAdmission capture(ServerPlayer player){return new NativeMiningStartAdmission(player);}
    void revalidate() {
        if(player.serverLevel()!=level||!player.server.isSameThread()
                ||NativeSessionMenuBoundary.read(Level.class,level,"worldBorder",WorldBorder.class)!=border
                ||NativeSessionMenuBoundary.read(WorldBorder.class,border,"extent",extentInterface)!=extent
                ||NativeSessionMenuBoundary.read(staticExtent,extent,"this$0",WorldBorder.class)!=border
                ||!before.equals(borderState()))throw refused("Native START border owner/extent/scalars changed");
        requireEmptyHitClosure();
    }
    private BorderState borderState() {
        double cx=readDouble(WorldBorder.class,border,"centerX"),cz=readDouble(WorldBorder.class,border,"centerZ");
        int absolute=NativeSessionMenuBoundary.read(WorldBorder.class,border,"absoluteMaxSize",int.class);
        double size=readDouble(staticExtent,extent,"size"),minX=readDouble(staticExtent,extent,"minX"),minZ=readDouble(staticExtent,extent,"minZ"),
                maxX=readDouble(staticExtent,extent,"maxX"),maxZ=readDouble(staticExtent,extent,"maxZ");
        if(absolute<1||size<=0||minX>=maxX||minZ>=maxZ)
            throw refused("Native START stationary border is outside finite nonempty bounds");
        return new BorderState(Double.doubleToRawLongBits(cx),Double.doubleToRawLongBits(cz),absolute,Double.doubleToRawLongBits(size),
                Double.doubleToRawLongBits(minX),Double.doubleToRawLongBits(minZ),Double.doubleToRawLongBits(maxX),Double.doubleToRawLongBits(maxZ));
    }
    private void requireEmptyHitClosure() {
        var stack=player.getMainHandItem();
        if(stack==null||stack.getClass()!=ItemStack.class||!stack.isEmpty()||stack.getItem()!=Items.AIR
                ||Items.AIR.getClass()!=net.minecraft.world.item.AirItem.class||stack.getComponents()!=DataComponentMap.EMPTY
                ||DataComponentMap.EMPTY.getClass()!=nativeClass("net.minecraft.core.component.DataComponentMap$1")
                ||ItemEnchantments.EMPTY.getClass()!=ItemEnchantments.class)
            throw refused("Native START on-hit closure is exact empty-hand/empty-component only");
        Object backing=NativeSessionMenuBoundary.read(ItemEnchantments.class,ItemEnchantments.EMPTY,"enchantments",it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap.class);
        if(backing==null||backing.getClass()!=it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap.class
                ||!((it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?>)backing).isEmpty())
            throw refused("Native EMPTY enchantment backing was replaced or populated");
        requireDeclaring(ItemStack.class,"getAllEnchantments",net.neoforged.neoforge.common.extensions.IItemStackExtension.class,HolderLookup.RegistryLookup.class);
        requireDeclaring(Items.AIR.getClass(),"getAllEnchantments",net.neoforged.neoforge.common.extensions.IItemExtension.class,ItemStack.class,HolderLookup.RegistryLookup.class);
        events.requireEmpty(net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent.class);
        events.requireEmpty(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.class);
        if(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()!=player.server)
            throw refused("Native START enchantment lookup resolved a different server");
        // Original onHitBlock constructs one EnchantedItemInUse and visits the resulting empty
        // original ItemEnchantments map. No visitor/onBreak callback can run in this closure.
        for(var block:java.util.List.of(Blocks.GRASS_BLOCK,Blocks.DIRT))
            requireDeclaring(block.getClass(),"attack",BlockBehaviour.class,BlockState.class,Level.class,BlockPos.class,Player.class);
    }
    private static double readDouble(Class<?> type,Object object,String name) {
        double value=NativeSessionMenuBoundary.read(type,object,name,double.class);
        if(!Double.isFinite(value))throw refused("Native START border contains nonfinite "+name);return value;
    }
    private static Class<?> nativeClass(String name) {
        try{return Class.forName(name,false,WorldBorder.class.getClassLoader());}
        catch(ClassNotFoundException failure){throw refused("Native START class unavailable: "+name);}
    }
    private static void requireDeclaring(Class<?> type,String name,Class<?> expected,Class<?>... arguments) {
        Method found=null;
        for(Class<?> current=type;current!=null;current=current.getSuperclass()) {
            try{found=current.getDeclaredMethod(name,arguments);break;}catch(NoSuchMethodException ignored){}
        }
        if(found==null)try{found=type.getMethod(name,arguments);}catch(NoSuchMethodException ignored){}
        if(found==null||found.getDeclaringClass()!=expected)throw refused("Native START callback implementation changed: "+name);
    }
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
