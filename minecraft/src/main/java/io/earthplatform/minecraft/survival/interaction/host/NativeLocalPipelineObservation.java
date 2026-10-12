package io.earthplatform.minecraft.survival.interaction.host;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.DefaultChannelPipeline;
import io.netty.channel.local.LocalChannel;
import java.lang.reflect.Array;
import java.lang.reflect.Field;

/** Minimal original-loop identity snapshot. Raw admitted fields only; no handler, estimator,
 * allocator, queue, or arbitrary collection method is called. It establishes no stable cut.
 */
final class NativeLocalPipelineObservation {
    private NativeLocalPipelineObservation(){}
    static String[] capture(Channel channel,Object protocol,int side)throws ReflectiveOperationException{
        if(channel.getClass()!=LocalChannel.class||channel.config().getClass()!=DefaultChannelConfig.class
                ||!NativeLocalInstalledPins.loopClass(channel.eventLoop().getClass(),side)||!channel.eventLoop().inEventLoop())
            throw new IllegalStateException("Exact original Local loop required for identity snapshot");
        Object pipeline=channel.pipeline();if(pipeline.getClass()!=DefaultChannelPipeline.class)throw new IllegalStateException("Unadmitted pipeline");
        Object config=channel.config();Object allocator=field(DefaultChannelConfig.class,"allocator",config);
        Object estimator=field(DefaultChannelConfig.class,"msgSizeEstimator",config);
        Object watermark=field(DefaultChannelConfig.class,"writeBufferWaterMark",config);
        StringBuilder settings=new StringBuilder(4096);add(settings,"LOCAL_CONFIG side="+side+" protocol="+identity(protocol)+" allocator="+identity(allocator)+" estimator="+identity(estimator)+" watermark="+identity(watermark));
        if(exact(allocator,"io.netty.channel.PreferHeapByteBufAllocator"))allocator=field(type("io.netty.channel.PreferHeapByteBufAllocator"),"allocator",allocator);
        add(settings," backingAllocator="+identity(allocator));
        if(exact(allocator,"io.netty.buffer.PooledByteBufAllocator")){
            Class<?> type=type("io.netty.buffer.PooledByteBufAllocator");
            add(settings," heapArenaCount="+arrayLength(field(type,"heapArenas",allocator))+" directArenaCount="+arrayLength(field(type,"directArenas",allocator))
                    +" chunkSize="+integer(type,"chunkSize",allocator)+" smallCacheSize="+integer(type,"smallCacheSize",allocator)+" normalCacheSize="+integer(type,"normalCacheSize",allocator)
                    +" threadCache="+identity(field(type,"threadCache",allocator)));
        }
        if(exact(watermark,"io.netty.channel.WriteBufferWaterMark"))add(settings," low="+integer(type("io.netty.channel.WriteBufferWaterMark"),"low",watermark)+" high="+integer(type("io.netty.channel.WriteBufferWaterMark"),"high",watermark));
        Object head=field(DefaultChannelPipeline.class,"head",pipeline),tail=field(DefaultChannelPipeline.class,"tail",pipeline);
        if(!exact(head,"io.netty.channel.DefaultChannelPipeline$HeadContext")||!exact(tail,"io.netty.channel.DefaultChannelPipeline$TailContext"))throw new IllegalStateException("Unadmitted pipeline terminals");
        Class<?> context=type("io.netty.channel.AbstractChannelHandlerContext");Object node=field(context,"next",head);
        StringBuilder handlers=new StringBuilder(4096);add(handlers,"LOCAL_PIPELINE side="+side+" protocol="+identity(protocol));
        int count=0;
        while(node!=tail){
            if(count++==32||!exact(node,"io.netty.channel.DefaultChannelHandlerContext"))throw new IllegalStateException("Finite exact pipeline context profile required");
            Object name=field(context,"name",node);if(!(name instanceof String label)||label.length()>128)throw new IllegalStateException("Bounded original pipeline name required");
            Object handler=field(type("io.netty.channel.DefaultChannelHandlerContext"),"handler",node);
            Object executor=field(context,"executor",node);
            add(handlers," ["+label+":"+identity(handler)+" executor="+identity(executor)+" state="+integer(context,"handlerState",node)+"]");
            node=field(context,"next",node);
        }
        add(handlers," pendingHandlerCallback="+(field(DefaultChannelPipeline.class,"pendingHandlerCallbackHead",pipeline)!=null));
        return new String[]{settings.toString(),handlers.toString()};
    }
    private static int arrayLength(Object array){if(array==null)return 0;if(!array.getClass().getName().equals("[Lio.netty.buffer.PoolArena;"))throw new IllegalStateException("Unadmitted arena array");return Array.getLength(array);}
    private static String identity(Object value){if(value==null)return "null";String name=value.getClass().getName();if(name.length()>256)throw new IllegalStateException("Bounded class name");return name+"@"+Integer.toUnsignedString(System.identityHashCode(value),16);}
    private static boolean exact(Object value,String name)throws ClassNotFoundException{return value!=null&&value.getClass()==type(name);}
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,Channel.class.getClassLoader());}
    private static Object field(Class<?> owner,String name,Object receiver)throws ReflectiveOperationException{Field field=owner.getDeclaredField(name);field.setAccessible(true);return field.get(receiver);}
    private static int integer(Class<?> owner,String name,Object receiver)throws ReflectiveOperationException{Field field=owner.getDeclaredField(name);field.setAccessible(true);return field.getInt(receiver);}
    private static void add(StringBuilder builder,String value){if(value.length()>1024||builder.length()+value.length()>4096)throw new IllegalStateException("Bounded local identity report");builder.append(value);}
}
