package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConnectionListener;

/** Genuine native Connection.tick origin only. No public tick count, READY or origin constructor. */
public final class NativeMiningScheduledOrigin {
    private static final ThreadLocal<Origin> ACTIVE = new ThreadLocal<>();
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private NativeMiningScheduledOrigin() {}
    static final class Origin {
        final MinecraftServer server;
        final ServerConnectionListener listener;
        final Connection connection;
        final int rawServerTick;
        final Thread owner=Thread.currentThread();
        private Throwable failure;
        private Origin(MinecraftServer server,ServerConnectionListener listener,Connection connection) {
            this.server=server;this.listener=listener;this.connection=connection;rawServerTick=server.getTickCount();
        }
        void require(ServerPlayer player) {
            if(failure instanceof RuntimeException e)throw e;
            if(failure instanceof Error e)throw e;
            if(ACTIVE.get()!=this||Thread.currentThread()!=owner||!server.isSameThread()
                    ||server.getTickCount()!=rawServerTick||server.getConnection()!=listener||player.server!=server
                    ||player.getClass()!=ServerPlayer.class||player.connection==null||player.connection.player!=player
                    ||NativeSessionMenuBoundary.read(ServerCommonPacketListenerImpl.class,player.connection,"connection",Connection.class)!=connection)
                throw poison("Native mining pulse lost its genuine exact Connection.tick origin");
        }
        SurvivalRefusal poison(String message) {
            var result=new SurvivalRefusal(message);if(failure==null)failure=result;return result;
        }
    }
    /** Called by the injected operation inside the original ServerConnectionListener class. */
    public static void runOriginalConnectionTick(ServerConnectionListener listener,Connection connection,Runnable original) {
        // The callback is not an externally usable pulse factory: the exact transformed native
        // caller and actual tickChildren path are checked before installing any thread-local state.
        if(WALKER.getCallerClass()!=ServerConnectionListener.class)
            throw refused("Foreign caller cannot create native mining scheduler provenance");
        if(listener==null||listener.getClass()!=ServerConnectionListener.class||connection==null||original==null)
            throw refused("Native mining scheduler implementation identity changed");
        var server=listener.getServer();
        if(server.getClass()!=GameTestServer.class){original.run();return;}
        if(connection.getClass()!=Connection.class)throw refused("Mining pulse requires the pinned exact native Connection");
        var packetListener=connection.getPacketListener();
        if(!(packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl gameplay)
                ||io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate.find(gameplay.player)==null){original.run();return;}
        if(!server.isSameThread()||server.getConnection()!=listener)
            throw refused("Native mining scheduler is not its exact server owner");
        boolean authentic=WALKER.walk(frames->authenticDispatch(frames.limit(12)
                .map(frame->new Frame(frame.getDeclaringClass(),frame.getMethodName())).toList()));
        if(!authentic)throw refused("Manually called connection tick is not a genuine server mining pulse");
        NativeMiningControlPins.require(MinecraftServer.class);
        NativeMiningControlPins.require(ServerConnectionListener.class);
        NativeMiningControlPins.require(Connection.class);
        if(ACTIVE.get()!=null)throw ACTIVE.get().poison("Reentrant native connection scheduler origin");
        var origin=new Origin(server,listener,connection);ACTIVE.set(origin);
        try {
            original.run();
            if(origin.failure instanceof RuntimeException e)throw e;
            if(origin.failure instanceof Error e)throw e;
        } catch(RuntimeException|Error failure){if(origin.failure==null)origin.failure=failure;throw failure;}
        finally{ACTIVE.remove();}
    }
    record Frame(Class<?> owner,String method) {}
    /** Read-only regression seam; only the actual StackWalker result can create an Origin. */
    static boolean authenticDispatch(java.util.List<Frame> frames) {
        if(frames.size()<4||frames.get(0).owner()!=NativeMiningScheduledOrigin.class
                ||!frames.get(0).method().equals("runOriginalConnectionTick"))return false;
        int index=1;
        // Mixin can rename our injected handler, but every allowed wrapper belongs to the
        // original listener class and carries this exact injected handler's suffix.
        while(index<frames.size()&&frames.get(index).owner()==ServerConnectionListener.class
                &&frames.get(index).method().endsWith("earthplatform$exactMiningClock"))index++;
        if(index==1||index+4>=frames.size())return false;
        var nativeTick=frames.get(index);var nativeParent=frames.get(index+1);
        var serverTick=frames.get(index+2);var gameTestTick=frames.get(index+3);var loop=frames.get(index+4);
        return nativeTick.owner()==ServerConnectionListener.class&&nativeTick.method().equals("tick")
                &&nativeParent.owner()==MinecraftServer.class&&nativeParent.method().equals("tickChildren")
                &&serverTick.owner()==MinecraftServer.class&&serverTick.method().equals("tickServer")
                &&gameTestTick.owner()==GameTestServer.class&&gameTestTick.method().equals("tickServer")
                &&loop.owner()==MinecraftServer.class&&loop.method().equals("runServer");
    }
    static Origin requireCurrent(ServerPlayer player) {
        var origin=ACTIVE.get();if(origin==null)throw refused("Native mining requires the actual native connection scheduler");
        origin.require(player);return origin;
    }
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
