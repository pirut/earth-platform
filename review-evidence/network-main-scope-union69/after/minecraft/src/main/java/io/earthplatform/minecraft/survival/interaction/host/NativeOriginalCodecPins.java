package io.earthplatform.minecraft.survival.interaction.host;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
/** Exact original source-resource pins; these do not assert that a Mixin actually applied. */
final class NativeOriginalCodecPins {
    private static final String[][] PINS={
        {"net/minecraft/network/PacketEncoder.class","680c79a39686fed3a02ce14e48ebc134d220bf93736bca100790fa2e819b30e1"},
        {"net/minecraft/network/PacketDecoder.class","5069bad3aa8c02825d4b4ed12c966c6cc57d6685a191c276b7c32ad6bf204712"},
        {"net/minecraft/network/NoOpFrameEncoder.class","61f2fddc74170d4dedcfac3d5c628500990acb5ed880531c0fb754ca04d20bd6"},
        {"net/minecraft/network/NoOpFrameDecoder.class","12a51670d2dfc72945be358c47b6684513e60f5604f24be5875f7ba3177a4194"},
        {"net/minecraft/network/protocol/login/ServerboundHelloPacket.class","cb160d0e0297b0d8f28345f214255b6ff533ed6ff3ee7bb04cac33eba6fe181f"},
        {"net/minecraft/network/protocol/login/LoginProtocols.class","ef65767c0859dda93be66c109dac92063df98d45276cb6608d61793aced437e6"},
        {"net/minecraft/network/codec/IdDispatchCodec.class","9651b06b3dfd988a51c11eeb9e3b10b363be0e618432639bd9c657eaf0db61a6"},
        {"net/minecraft/network/FriendlyByteBuf.class","066baeaf87081a582a60ebbfc36521939a6c8447ef83b14c039307be76a85fd4"},
        {"net/minecraft/network/Utf8String.class","386411083b7589734ad38e40804779e664f6243df2303f3063863c9881db2179"},
        {"net/minecraft/network/VarInt.class","357581c146d95e7d177b42aecebd01779111541b4219804a923e41ece187c9e6"},
        {"net/minecraft/network/protocol/Packet.class","d718c7bd3f5374bcdcea91bdaf5a5db9c5f7ffe3694becb1a3be43015762120e"},
        {"net/minecraft/network/ProtocolSwapHandler.class","10b0cedabfff2da362f761d99c445a637a7aa5edefa84ebf7310935e4231421c"},
        {"net/minecraft/util/profiling/jfr/JvmProfiler.class","71230c0d0cc37bc4dd6675a517fcf66b570130670091956cf7ce2c41b11dda08"},
        {"net/minecraft/util/profiling/jfr/JvmProfiler$NoOpProfiler.class","1b38d4c739f7efa85c10dbaa9c2d5c71c968edd155be638770c87a709b48a63b"},
        {"net/minecraft/util/profiling/jfr/JfrProfiler.class","ae5dec48c4788843d25f06e3e16047b2faf5a8ed424ae70cc9c88fce63c7a0f3"},
        {"net/minecraft/util/profiling/jfr/event/PacketSentEvent.class","53bfd09a9e528834af21ed176d7ce60d0714906f0644ce861f3ad9ca565d242f"},
        {"net/minecraft/util/profiling/jfr/event/PacketReceivedEvent.class","ec9d11e1b90a1e9dcdedf030d270046590ba0e197909c7e57f9a569c6d1d5b48"},
        {"net/minecraft/util/profiling/jfr/event/NetworkSummaryEvent.class","2e4f8dec4e33386de70d1c6af82d029f9604c116a3f0752132c42ab1f1083e3f"},
        {"io/netty/channel/embedded/EmbeddedChannel.class","bcd62ca598bfa31c3b8059165ed2b953c84044789cd0041a4925da25eadf6b8e"},
        {"io/netty/channel/embedded/EmbeddedChannel$EmbeddedUnsafe.class","7bf511bb0642388af104c3ee9f7aa79ba2a29de81feaf2528686e51534bacedd"},
        {"io/netty/channel/embedded/EmbeddedChannel$EmbeddedUnsafe$1.class","aea9f4ff65f5fe7294094e795c51ec96b5ef14e302ca18a735cda1eee5ac14d8"},
        {"io/netty/channel/embedded/EmbeddedEventLoop.class","642baf7c0ab32ce0daa2e20cf5d7c4db35fcf44604a9d3a4799bb62250ef528e"},
        {"io/netty/channel/PreferHeapByteBufAllocator.class","094373201a6dd1e31de22ed51dfa8082b19396ba65b3b37f853ec65810804254"},
        {"io/netty/channel/DefaultChannelPromise.class","69ee9d9904956efc023dcc2911e1fe3ef40b79d165176f23e2eb30eb309713f8"},
        {"io/netty/util/internal/TypeParameterMatcher.class","5b5e22dd9d052a4e082990a6cbd1b63992b212857788ebcf4b3b6347607a01ac"},
        {"io/netty/util/internal/TypeParameterMatcher$ReflectiveMatcher.class","a64101d8ab850a18a3f0d8f0eca4e483d5db68caacbfccc19e06f08cff7c7e49"},
        {"io/netty/handler/codec/MessageToByteEncoder.class","ed33a27d7c6d12c9dd0bfcf0e113ab540340a02400ab74ee3243cdb48c38c606"},
        {"io/netty/handler/codec/ByteToMessageDecoder.class","0a9004cd0765389d36e1f24bf51d65c5e8e2cb80632ebc7c8429159697c7a7ca"},
        {"io/netty/handler/codec/ByteToMessageDecoder$1.class","e859cc0dfdc812d92b506b177842e9f7ad86bbe4ee0d72d01b90cebce1bf92d8"},
        {"io/netty/handler/codec/CodecOutputList.class","08ba55a4affaf59eff87f5739b7b89df20b260ddbba5bd3995612bc445a59e49"},
        {"io/netty/handler/codec/CodecOutputList$CodecOutputLists.class","1ff4e581a265fc337b3d711075a42044882aab5c75b9789e35700259ba066ec2"},
        {"io/netty/buffer/UnpooledByteBufAllocator.class","8c2231cfd475ccaf4b3ba9d73ce225fc155e21e0d7e85577c76aef6855d9f3d4"},
        {"io/netty/buffer/AbstractByteBufAllocator.class","3dbd62a26ba407633003a4a1a189b57e493b68eca00f26ed862e643502ad7603"},
        {"io/netty/buffer/UnpooledHeapByteBuf.class","2850cf312f1951e0e9e944e6c107b119628674703c99d410842e4d03138bca1a"},
        {"io/netty/buffer/UnpooledUnsafeHeapByteBuf.class","ea2ad8546fc0bb22f81af1d16e9b5669402f29e4b348b78a1a8acac919512bbf"},
        {"io/netty/buffer/UnpooledByteBufAllocator$InstrumentedUnpooledHeapByteBuf.class","1b99a044403c6907023168931e5008805a19f310442cf9cdaa856db1feb37b6e"},
        {"io/netty/buffer/UnpooledByteBufAllocator$InstrumentedUnpooledUnsafeHeapByteBuf.class","3c0b7c5b64fbbfe7f9076e99d7b811fbe5eeb3c610328ed521e25d4b876de897"}
    };
    private NativeOriginalCodecPins(){}
    static void require(){
        byte[] scratch=new byte[4096];
        for(String[] pin:PINS){
            ClassLoader loader=pin[0].startsWith("net/minecraft/")?net.minecraft.network.PacketEncoder.class.getClassLoader():io.netty.channel.Channel.class.getClassLoader();
            if(!pin[1].equals(hash(loader,pin[0],scratch)))throw new IllegalStateException("Original codec seam resource changed: "+pin[0]);
        }
    }
    private static String hash(ClassLoader loader,String path,byte[] scratch){
        try(var input=loader.getResourceAsStream(path)){
            if(input==null)throw new IllegalStateException("Original codec seam resource absent: "+path);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");int total=0;
            for(int n;(n=input.read(scratch))>=0;){total=Math.addExact(total,n);if(total>128*1024)throw new IllegalStateException("Original class resource is too large");digest.update(scratch,0,n);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException failure){throw new IllegalStateException("Original codec seam resource unavailable",failure);}
    }
}
