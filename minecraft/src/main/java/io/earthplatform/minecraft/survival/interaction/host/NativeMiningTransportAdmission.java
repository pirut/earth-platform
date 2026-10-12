package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.DefaultChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Exact registered embedded transport closure. Real installed channels require a separate admission. */
final class NativeMiningTransportAdmission {
    private final ServerGamePacketListenerImpl receiver;
    private final Connection connection;
    private final Queue<?> pending;
    private final EmbeddedChannel channel;
    private final Object pipeline,head,tail,context;
    private final Class<?> contextType,handlerContextType,pipelineType,headType,tailType;
    NativeMiningTransportAdmission(ServerGamePacketListenerImpl receiver) {
        this.receiver=receiver;
        connection=NativeSessionMenuBoundary.read(ServerCommonPacketListenerImpl.class,receiver,"connection",Connection.class);
        if(connection==null||connection.getClass()!=Connection.class)throw refused("Mining transport requires exact native Connection");
        NativeMiningControlPins.require(Connection.class);NativeMiningControlPins.require(ServerCommonPacketListenerImpl.class);
        pending=NativeSessionMenuBoundary.read(Connection.class,connection,"pendingActions",Queue.class);
        if(pending==null||pending.getClass()!=ConcurrentLinkedQueue.class)throw refused("Mining transport has foreign pending consumer backing");
        Object raw=NativeSessionMenuBoundary.read(Connection.class,connection,"channel",Channel.class);
        if(raw==null||raw.getClass()!=EmbeddedChannel.class)throw refused("Only the separately source-qualified registered embedded mining channel is admitted");
        channel=(EmbeddedChannel)raw;
        PinnedNativeEventAdmission.requireClassBytes(EmbeddedChannel.class,"bcd62ca598bfa31c3b8059165ed2b953c84044789cd0041a4925da25eadf6b8e");
        PinnedNativeEventAdmission.requireClassBytes(io.netty.channel.AbstractChannel.class,"6f790ba9e5e6021719033d098679300d107f2c509ad3bf9830802bf393b9ae59");
        PinnedNativeEventAdmission.requireClassBytes(DefaultChannelPipeline.class,"2b05a545e17c71ae82e5690f504a03372a315f4a8a213c9ecb643e4955d4cc1a");
        pipelineType=nativeClass("io.netty.channel.embedded.EmbeddedChannel$EmbeddedChannelPipeline");
        PinnedNativeEventAdmission.requireClassBytes(pipelineType,"442358e6159abe32d1dc6674bd06a731a797754e4fea20c3613c13c93fa01551");
        contextType=nativeClass("io.netty.channel.AbstractChannelHandlerContext");handlerContextType=nativeClass("io.netty.channel.DefaultChannelHandlerContext");
        PinnedNativeEventAdmission.requireClassBytes(contextType,"129fcae765065009e97db763833e324a31ebf2269c01276dc0c2fa7fc458af43");
        PinnedNativeEventAdmission.requireClassBytes(handlerContextType,"9e0c927521c2d51493f6f3e7ef8571d9cac8df76b6842170e7f652ac5efd1809");
        headType=nativeClass("io.netty.channel.DefaultChannelPipeline$HeadContext");tailType=nativeClass("io.netty.channel.DefaultChannelPipeline$TailContext");
        PinnedNativeEventAdmission.requireClassBytes(headType,"fd4973769c6b0f09d5ec5cb8c356af4daa03736c03b3ac17fc67bbb69accd950");
        PinnedNativeEventAdmission.requireClassBytes(tailType,"667ec0493a10830aca9d2db73f108d66f9a75e3164ba1114e24c47636bdf5511");
        pipeline=channel.pipeline();if(pipeline.getClass()!=pipelineType)throw refused("Mining embedded pipeline was replaced");
        head=NativeSessionMenuBoundary.read(DefaultChannelPipeline.class,pipeline,"head",headType);
        tail=NativeSessionMenuBoundary.read(DefaultChannelPipeline.class,pipeline,"tail",tailType);
        if(head==null||head.getClass()!=headType||tail==null||tail.getClass()!=tailType)throw refused("Foreign native pipeline endpoint");
        context=NativeSessionMenuBoundary.read(contextType,head,"next",contextType);
        require();
    }
    void require() {
        if(NativeSessionMenuBoundary.read(ServerCommonPacketListenerImpl.class,receiver,"connection",Connection.class)!=connection
                ||NativeSessionMenuBoundary.read(Connection.class,connection,"pendingActions",Queue.class)!=pending
                ||pending.getClass()!=ConcurrentLinkedQueue.class||!pending.isEmpty()
                ||NativeSessionMenuBoundary.read(Connection.class,connection,"channel",Channel.class)!=channel
                ||connection.getPacketListener()!=receiver||!channel.isOpen()||channel.pipeline()!=pipeline
                ||NativeSessionMenuBoundary.read(DefaultChannelPipeline.class,pipeline,"head",headType)!=head
                ||NativeSessionMenuBoundary.read(DefaultChannelPipeline.class,pipeline,"tail",tailType)!=tail
                ||context==null||context.getClass()!=handlerContextType
                ||NativeSessionMenuBoundary.read(contextType,head,"next",contextType)!=context
                ||NativeSessionMenuBoundary.read(contextType,context,"prev",contextType)!=head
                ||NativeSessionMenuBoundary.read(contextType,context,"next",contextType)!=tail
                ||NativeSessionMenuBoundary.read(contextType,tail,"prev",contextType)!=context
                ||NativeSessionMenuBoundary.read(handlerContextType,context,"handler",ChannelHandler.class)!=connection)
            throw refused("Native mining transport changed, closed, queued callbacks, or gained foreign handlers");
    }
    private static Class<?> nativeClass(String name) {
        try{return Class.forName(name,false,EmbeddedChannel.class.getClassLoader());}
        catch(ClassNotFoundException failure){throw refused("Mining transport class unavailable: "+name);}
    }
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
