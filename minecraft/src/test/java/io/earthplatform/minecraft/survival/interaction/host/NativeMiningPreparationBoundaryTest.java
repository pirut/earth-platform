package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeMiningPreparationBoundaryTest {
    @Test void outsideCallersCannotConstructOwnersPlansOrClockOrigins() throws Exception {
        assertFalse(Modifier.isPublic(NativeMiningControlPreparation.Owner.class.getModifiers()));
        assertTrue(Modifier.isPrivate(Class.forName(NativeMiningControlPreparation.class.getName()+"$Plan").getModifiers()));
        for(var constructor:NativeMiningScheduledOrigin.Origin.class.getDeclaredConstructors())assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        for(var method:NativeMiningControlPreparation.class.getDeclaredMethods())if(Modifier.isPublic(method.getModifiers())) {
            assertFalse(method.getName().startsWith("prepare"));assertFalse(method.getName().startsWith("apply"));
            assertFalse(method.getName().startsWith("commit"));
        }
    }
    @Test void nativeDispatchMustBeAdjacentRatherThanMerelyAnAncestor() {
        var origin=new NativeMiningScheduledOrigin.Frame(NativeMiningScheduledOrigin.class,"runOriginalConnectionTick");
        var wrapper=new NativeMiningScheduledOrigin.Frame(net.minecraft.server.network.ServerConnectionListener.class,"wrapOperation$abc000$earthplatform$exactMiningClock");
        var tick=new NativeMiningScheduledOrigin.Frame(net.minecraft.server.network.ServerConnectionListener.class,"tick");
        var parent=new NativeMiningScheduledOrigin.Frame(net.minecraft.server.MinecraftServer.class,"tickChildren");
        var serverTick=new NativeMiningScheduledOrigin.Frame(net.minecraft.server.MinecraftServer.class,"tickServer");
        var gameTestTick=new NativeMiningScheduledOrigin.Frame(net.minecraft.gametest.framework.GameTestServer.class,"tickServer");
        var loop=new NativeMiningScheduledOrigin.Frame(net.minecraft.server.MinecraftServer.class,"runServer");
        var callback=new NativeMiningScheduledOrigin.Frame(NativeMiningPreparationBoundaryTest.class,"serverTickCallbackManuallyTicksListener");
        assertTrue(NativeMiningScheduledOrigin.authenticDispatch(java.util.List.of(origin,wrapper,tick,parent,serverTick,gameTestTick,loop)));
        assertFalse(NativeMiningScheduledOrigin.authenticDispatch(java.util.List.of(origin,wrapper,tick,callback,parent,serverTick,gameTestTick,loop)));
        assertFalse(NativeMiningScheduledOrigin.authenticDispatch(java.util.List.of(origin,wrapper,callback,tick,parent,serverTick,gameTestTick,loop)));
        assertFalse(NativeMiningScheduledOrigin.authenticDispatch(java.util.List.of(origin,tick,parent,serverTick,gameTestTick,loop)));
        assertFalse(NativeMiningScheduledOrigin.authenticDispatch(java.util.List.of(origin,wrapper,tick,parent,serverTick,gameTestTick,callback,loop)));
    }
    @Test void foreignClockFactoryIsRejectedBeforeCallerCallback() {
        boolean[] ran={false};
        assertThrows(SurvivalRefusal.class,()->NativeMiningScheduledOrigin.runOriginalConnectionTick(null,null,()->ran[0]=true));
        assertFalse(ran[0]);
    }
}
