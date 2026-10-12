package io.earthplatform.minecraft.survival.interaction.host;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
/** Exact original source-resource pins; these do not assert that a Mixin actually applied. */
final class NativeOriginalConnectionPins {
    private static final String[][] PINS={
        {"net/minecraft/network/Connection.class","43773faa0f56d9dfd7a836c32375c3ee97f42773a5925049c160291f2ad505ff"},
        {"io/netty/channel/embedded/EmbeddedChannel.class","bcd62ca598bfa31c3b8059165ed2b953c84044789cd0041a4925da25eadf6b8e"},
        {"io/netty/channel/embedded/EmbeddedChannel$EmbeddedUnsafe.class","7bf511bb0642388af104c3ee9f7aa79ba2a29de81feaf2528686e51534bacedd"},
        {"io/netty/channel/embedded/EmbeddedChannel$EmbeddedUnsafe$1.class","aea9f4ff65f5fe7294094e795c51ec96b5ef14e302ca18a735cda1eee5ac14d8"},
        {"io/netty/channel/embedded/EmbeddedEventLoop.class","642baf7c0ab32ce0daa2e20cf5d7c4db35fcf44604a9d3a4799bb62250ef528e"},
        {"io/netty/channel/DefaultChannelPipeline.class","2b05a545e17c71ae82e5690f504a03372a315f4a8a213c9ecb643e4955d4cc1a"},
        {"io/netty/channel/AbstractChannel.class","6f790ba9e5e6021719033d098679300d107f2c509ad3bf9830802bf393b9ae59"},
        {"io/netty/channel/ChannelOutboundBuffer.class","cc9e30ee5564a1a702bfbfc8a7b66de4a66efe3626bc0a34b985a26bb0f9e4cf"},
        {"io/netty/channel/DefaultChannelConfig.class","2d491c24f0dd13479aa021d1245a03231fc5ebd2756c9df18cf749d5b53fd1db"},
        {"io/netty/channel/DefaultChannelPromise.class","69ee9d9904956efc023dcc2911e1fe3ef40b79d165176f23e2eb30eb309713f8"},
        {"io/netty/channel/AbstractChannelHandlerContext.class","129fcae765065009e97db763833e324a31ebf2269c01276dc0c2fa7fc458af43"},
        {"io/netty/util/concurrent/DefaultPromise.class","c4abd3cfcc590f0aab08350452d46cbc9f20065cea9238aa5e26ae7c8377ec5e"}
    };
    private NativeOriginalConnectionPins(){}
    static void require(){
        for(String[] pin:PINS){
            ClassLoader loader=pin[0].startsWith("net/minecraft/")?net.minecraft.network.Connection.class.getClassLoader():io.netty.channel.Channel.class.getClassLoader();
            if(!pin[1].equals(hash(loader,pin[0])))throw new IllegalStateException("Original Connection seam resource changed: "+pin[0]);
        }
    }
    private static String hash(ClassLoader loader,String path){
        try(var input=loader.getResourceAsStream(path)){
            if(input==null)throw new IllegalStateException("Original Connection seam resource absent: "+path);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] scratch=new byte[4096];int total=0;
            for(int n;(n=input.read(scratch))>=0;){total=Math.addExact(total,n);if(total>1024*1024)throw new IllegalStateException("Original class resource is too large");digest.update(scratch,0,n);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException failure){throw new IllegalStateException("Original Connection seam resource unavailable",failure);}
    }
}
