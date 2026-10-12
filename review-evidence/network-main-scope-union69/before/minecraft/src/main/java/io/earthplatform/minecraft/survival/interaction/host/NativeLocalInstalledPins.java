package io.earthplatform.minecraft.survival.interaction.host;
import java.security.MessageDigest;
import java.util.HexFormat;
/** Installed originals only. Development counterparts are separately retained evidence, never alternatives.
 * The parent launch receipt must bind both installer outputs below before Java starts.
 */
final class NativeLocalInstalledPins {
    static final String PROFILE="installed-local-frontier-root-observation-v1";
    static final String NAMED_CLIENT="a649bd3ce77f122d0f83ae1b37b4b303e662470f928b9bee5fbc76580805ffd4";
    static final String PATCHED_CLIENT="aff67d0c3bbd015ecd881bb37416b740ada2e1e854433501ec5bb9381dbdd1c2";
    private static volatile boolean checked;
    private static volatile int failedPin=-1;
    private static volatile String failedCode="NONE", observedDigest="NONE";
    private static final String[][] PINS={
        {"net/minecraft/network/UnconfiguredPipelineHandler$OutboundConfigurationTask.class","9de36ab6b826552c1b7ea258503f5cd6fd6b648a3116ca753b48a91ad7568d0c"},
        {"net/minecraft/network/UnconfiguredPipelineHandler$InboundConfigurationTask.class","c26d44e3a7b510392f452ca45740b1319c2f06d3d5df408528cdeeb2c164f839"},
        {"net/minecraft/network/Connection.class","a01831a60461ab3b356c4706eb6e606026e272f7db1226a8681192c43ef64405"},
        {"net/minecraft/network/Connection$3.class","e05b66b4308bd1f7a82fdcca69bf11eb6575403f3611028fb27accdbd150e35d"},
        {"net/minecraft/server/network/ServerConnectionListener$2.class","9b6ad438ff29825bb8db8a6ed8a09bfe7a8723e12bf2e4e34dbba0233c7b5e52"},
        {"net/minecraft/server/network/ServerConnectionListener.class","5721d6dce07ac98300ddcf62852bbf4591f79de9f337403803b23eb6d1cb2dbb"},
        {"net/minecraft/network/protocol/PacketUtils.class","d62e0c60740250d0d5d210999021ac70fadac6f73643aa57e110c6bf1842e864"},
        {"net/minecraft/client/multiplayer/ClientLevel.class","75ba059841ae6e711a83a686665ffd7980f028887af2b965f5a86380603973cd"},
        {"net/minecraft/client/Minecraft.class","495f5dc97d07476e23835fcdd034019a020a58b980f7c764d4953ce4ed16d283"},
        {"net/minecraft/client/server/IntegratedServer.class","26a16a6055bb148e1ffbe0d73bf74c38b710046a96f50600f3ae6b38a618bf5a"},
        {"net/minecraft/server/MinecraftServer.class","3f1b7a260a249ae836d9d71bf0a0fe7575b62aff48f2b8fac10dd06aaa9efd2f"},
        {"net/minecraft/network/PacketEncoder.class","3f31d0f73c042d643fc861fb84e90a9eb3236ec8d5e12bd46733d07d028bdbbc"},
        {"net/minecraft/network/PacketDecoder.class","f5311461fce9bec0d97b508a44b8137f1b48a17ee129d67e390f4d6cbfa0712b"},
        {"net/minecraft/network/PacketBundlePacker.class","330f615cddb8bf6ad760296fa227e9bc80424f1c51d8a608703ac8d0e2b7d908"},
        {"net/minecraft/network/PacketBundleUnpacker.class","02d027d0977364cbda99767321815e36e4e7be2a78090ae2d6b133915c650c88"},
        {"net/minecraft/network/NoOpFrameEncoder.class","47846db3c0c7e355aa4d65c17ca43d449d5fe4d727fa49e41f83cc4607220fc4"},
        {"net/minecraft/network/NoOpFrameDecoder.class","bb6f7e01dee4177bb60a02f50902370f6ca81bd5cd4634e6973856429f672f39"},
        {"net/minecraft/network/UnconfiguredPipelineHandler.class","7acc4672dcfde5b7307e9810badf792021fc9c7ef219a803240b063ed5e6c057"},
        {"net/minecraft/network/UnconfiguredPipelineHandler$Inbound.class","9f564681db43d80529ac3dd6f4909dd5c657a05a0d8484f94cb22a804a5c9e6f"},
        {"net/minecraft/network/UnconfiguredPipelineHandler$Outbound.class","017ddbd83711a16a44f470008471231c02a3e1c742051bc1df9eb5a730e06575"},
        {"net/minecraft/network/codec/IdDispatchCodec.class","fb87491fd48c2732f24cd5b1fd647d9472c9f968a7a38731086935f40ca506f2"},
        {"net/minecraft/network/VarInt.class","c13b21f4d4d4d447ba70a56485baac3de6e9b42181e39b31e72538e93a949834"},
        {"net/minecraft/util/thread/BlockableEventLoop.class","fbaad002561c030a73293a53102d972336d7034f1fad6400cd174ead87866f64"},
        {"net/minecraft/server/TickTask.class","5bbf61f5a108df9ba6c59de6a22ce1cca750de5383daad7f56a522db95bb257e"},
        {"io/netty/channel/local/LocalChannel.class","e1e0cf6f8ad36c79ac246f66a4805b12854592ddc37bb34ac9b429321195fbb5"},
        {"io/netty/channel/DefaultChannelConfig.class","2d491c24f0dd13479aa021d1245a03231fc5ebd2756c9df18cf749d5b53fd1db"},
        {"io/netty/channel/DefaultEventLoop.class","312eea7005813b7ac3da6ed0309875c17296fdc7b8d66486fb8f8e9cbf11a939"},
        {"io/netty/channel/DefaultEventLoopGroup.class","44c6498c762e92b4e64c9c8594bc2c0b91240fb935dd6b480b8fc2789c249516"},
        {"io/netty/channel/nio/NioEventLoop.class","6af552218ba87ef5278e79f9a437e53ff5d7e760b3010c9c1ceceee4299507fb"},
        {"io/netty/channel/nio/NioEventLoopGroup.class","8f54fddc4c3a3d1c331b14a4de467b43df291f6dc7a36a5163b077ae5806b279"},
        {"io/netty/channel/SingleThreadEventLoop.class","8f912a5e89bb25c1323f9f50aba21b2e4b99e3b1c49273d88448159d568d6e1d"},
        {"io/netty/channel/DefaultChannelHandlerContext.class","9e0c927521c2d51493f6f3e7ef8571d9cac8df76b6842170e7f652ac5efd1809"},
        {"io/netty/channel/AbstractChannelHandlerContext.class","129fcae765065009e97db763833e324a31ebf2269c01276dc0c2fa7fc458af43"},
        {"io/netty/channel/DefaultChannelPromise.class","69ee9d9904956efc023dcc2911e1fe3ef40b79d165176f23e2eb30eb309713f8"},
        {"io/netty/channel/DefaultChannelPipeline.class","2b05a545e17c71ae82e5690f504a03372a315f4a8a213c9ecb643e4955d4cc1a"},
        {"io/netty/channel/PreferHeapByteBufAllocator.class","094373201a6dd1e31de22ed51dfa8082b19396ba65b3b37f853ec65810804254"},
        {"io/netty/channel/ChannelOutboundBuffer.class","cc9e30ee5564a1a702bfbfc8a7b66de4a66efe3626bc0a34b985a26bb0f9e4cf"},
        {"io/netty/channel/AbstractChannel.class","6f790ba9e5e6021719033d098679300d107f2c509ad3bf9830802bf393b9ae59"},
        {"io/netty/channel/DefaultChannelPipeline$HeadContext.class","fd4973769c6b0f09d5ec5cb8c356af4daa03736c03b3ac17fc67bbb69accd950"},
        {"io/netty/channel/DefaultChannelPipeline$TailContext.class","667ec0493a10830aca9d2db73f108d66f9a75e3164ba1114e24c47636bdf5511"},
        {"io/netty/channel/WriteBufferWaterMark.class","00358476d8405b4303bcecb963a8b15776632c2f9419d347af7531329286f34f"},
        {"io/netty/handler/codec/ByteToMessageDecoder.class","0a9004cd0765389d36e1f24bf51d65c5e8e2cb80632ebc7c8429159697c7a7ca"},
        {"io/netty/handler/codec/MessageToByteEncoder.class","ed33a27d7c6d12c9dd0bfcf0e113ab540340a02400ab74ee3243cdb48c38c606"},
        {"io/netty/handler/codec/MessageToMessageEncoder.class","f0a6d201a9e0fe12be8ad1ac8c72efaba71f28b14f3e1d7e692b35a3647a296a"},
        {"io/netty/handler/codec/MessageToMessageDecoder.class","ed95405fac922777198532f0d4c08d509e19eba29578b4dda6e4cda7e70a0bd4"},
        {"io/netty/util/concurrent/DefaultPromise.class","c4abd3cfcc590f0aab08350452d46cbc9f20065cea9238aa5e26ae7c8377ec5e"},
        {"io/netty/util/concurrent/DefaultPromise$1.class","2e85be74f74ad3d19fb286c693e8a278d09f64db69d86c0effc47b7a5c5cdfa2"},
        {"io/netty/util/concurrent/SingleThreadEventExecutor.class","f5bbc6a75e0e616fd6eeafc1ee05cf979424bf96e90ae0fd760fef10c26f9382"},
        {"io/netty/util/concurrent/AbstractEventExecutor.class","a5419d19f50a8b112b19bc2eb8d9d55df2c3f79f37305253922b62d31bb029ab"},
        {"io/netty/buffer/AbstractByteBuf.class","dcdd9edf938fa2e657f413f37ddbe60e82a84559fd1c7390876f624d7f4b8ae6"},
        {"io/netty/buffer/AbstractReferenceCountedByteBuf.class","dd0e7577506300d3c7405fffec1ff1af215eafbf63c662866645884fead14c86"},
        {"io/netty/buffer/PooledByteBuf.class","e958b595e9a78ca8eba168d1246e06c4486af9e4e45034c1a26ce75f4a9d7499"},
        {"io/netty/buffer/PooledHeapByteBuf.class","4cc7992bf4ab547113eff009d15582951ecac6dc4cc2726f3b573e298ca75ebb"},
        {"io/netty/buffer/PooledUnsafeHeapByteBuf.class","a722e1ddc43aede7c62840270cf7d5eeb78dcba36047e4ea0c0f3884a636dd9f"},
        {"io/netty/buffer/PooledDirectByteBuf.class","15ad74717bd0b055b05bd691460343eb6458e377fd8019427564de8bbb61cfda"},
        {"io/netty/buffer/PooledUnsafeDirectByteBuf.class","6a62c7b86188754f5825a9fa5424007520637b3b92cddfb2eac02cbb8b23d1ce"},
        {"io/netty/buffer/SimpleLeakAwareByteBuf.class","0a7da7633d9199d90b1fba4c3999bbbb4d4ca8bde6209444abbc9e578f424f8c"},
        {"io/netty/buffer/AdvancedLeakAwareByteBuf.class","3c413fcee2219da7d607c9fa3fca328dfc38a536b17abdc99da6a8f4ff658bfa"},
        {"io/netty/buffer/WrappedByteBuf.class","4b60bf79bc657556882f6a0e9a8b2eadeb5e7464813b33f0c908bc4451753ace"},
        {"io/netty/buffer/UnpooledHeapByteBuf.class","2850cf312f1951e0e9e944e6c107b119628674703c99d410842e4d03138bca1a"},
        {"io/netty/buffer/UnpooledUnsafeHeapByteBuf.class","ea2ad8546fc0bb22f81af1d16e9b5669402f29e4b348b78a1a8acac919512bbf"},
        {"io/netty/buffer/UnpooledByteBufAllocator$InstrumentedUnpooledHeapByteBuf.class","1b99a044403c6907023168931e5008805a19f310442cf9cdaa856db1feb37b6e"},
        {"io/netty/buffer/UnpooledByteBufAllocator$InstrumentedUnpooledUnsafeHeapByteBuf.class","3c0b7c5b64fbbfe7f9076e99d7b811fbe5eeb3c610328ed521e25d4b876de897"},
        {"io/netty/buffer/PooledByteBufAllocator.class","80f6cca50115166cf4bdef4f2c5a6c587675eb942cd336502174037c8e6c2c43"},
        {"io/netty/buffer/AbstractByteBufAllocator.class","3dbd62a26ba407633003a4a1a189b57e493b68eca00f26ed862e643502ad7603"},
        {"io/netty/buffer/PooledByteBufAllocator$PoolThreadLocalCache.class","55f1f90b1a935664e80d9fede56875818009286de6392804ffd224271be00b2e"}
    };
    private NativeLocalInstalledPins(){}
    static boolean qualified(){return checked;}
    static boolean check(){
        try{
            byte[] scratch=new byte[4096];
            for(int index=0;index<PINS.length;index++){
                failedPin=index;observedDigest="NONE";failedCode="CHECKING";String[] pin=PINS[index];
                ClassLoader loader=pin[0].startsWith("net/minecraft/")?net.minecraft.network.Connection.class.getClassLoader():io.netty.channel.Channel.class.getClassLoader();
                try(var input=loader.getResourceAsStream(pin[0])){
                    if(input==null){failedCode="MISSING";return false;}
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");int total=0;
                    for(int count;(count=input.read(scratch))>=0;){total=Math.addExact(total,count);if(total>1_048_576){failedCode="OVERSIZE";return false;}digest.update(scratch,0,count);}
                    observedDigest=HexFormat.of().formatHex(digest.digest());if(!pin[1].equals(observedDigest)){failedCode="HASH";return false;}
                }
            }
            failedPin=-1;observedDigest="NONE";checked=true;return true;
        }catch(Exception failure){failedCode="EXCEPTION";return false;}
    }
    static String failureSummary(){
        int index=failedPin;
        if(checked)return "LOCAL_PIN_FAILURE none";
        if(index<0||index>=PINS.length)return "LOCAL_PIN_FAILURE index="+index+" code="+failedCode;
        return "LOCAL_PIN_FAILURE index="+index+" resource="+PINS[index][0]+" expected="+PINS[index][1]+" observed="+observedDigest+" code="+failedCode;
    }
    static boolean loopClass(Class<?> type,int side){return checked&&type==(side==0?io.netty.channel.DefaultEventLoop.class:side==1?io.netty.channel.nio.NioEventLoop.class:null);}
    static boolean contextClass(Class<?> type){return checked&&type.getClassLoader()==io.netty.channel.Channel.class.getClassLoader()
            &&type.getName().equals("io.netty.channel.DefaultChannelHandlerContext");}
    static boolean byteBufClass(Class<?> type){
        if(!checked||type.getClassLoader()!=io.netty.buffer.ByteBuf.class.getClassLoader())return false;
        return switch(type.getName()){
            case "io.netty.buffer.PooledHeapByteBuf" -> true;
            case "io.netty.buffer.PooledUnsafeHeapByteBuf" -> true;
            case "io.netty.buffer.PooledDirectByteBuf" -> true;
            case "io.netty.buffer.PooledUnsafeDirectByteBuf" -> true;
            case "io.netty.buffer.SimpleLeakAwareByteBuf" -> true;
            case "io.netty.buffer.AdvancedLeakAwareByteBuf" -> true;
            case "io.netty.buffer.UnpooledHeapByteBuf" -> true;
            case "io.netty.buffer.UnpooledUnsafeHeapByteBuf" -> true;
            case "io.netty.buffer.UnpooledByteBufAllocator$InstrumentedUnpooledHeapByteBuf" -> true;
            case "io.netty.buffer.UnpooledByteBufAllocator$InstrumentedUnpooledUnsafeHeapByteBuf" -> true;
            default -> false;
        };
    }
}
