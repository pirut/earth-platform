package io.earthplatform.minecraft.survival.interaction.host;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Actual local Netty transport tests. These do not qualify Minecraft bootstrap or transformed hooks. */
class NativePhysicalOutputLedgerNettyTest {
    @Test void actualOriginalUnflushedPromiseAndOriginalReadOutboundStayDistinct(){
        var channel=new EmbeddedChannel();Throwable primary=null;
        try{
            var ledger=new NativePhysicalOutputLedger();
            for(int n=1;n<=32;n++){
                Object packet=new Object();ChannelPromise promise=channel.newPromise();
                var cell=ledger.reserve(NativePhysicalOutputLedger.Lane.ACTION,packet,new Object());
                ledger.writeEntered(cell,promise,0);
                promise.addListener(future->ledger.promiseCompleted(cell,promise,future.isSuccess(),future.cause()));
                var returned=channel.write(packet,promise);assertSame(promise,returned);ledger.accepted(cell,returned);
                assertFalse(returned.isDone());assertFalse(ledger.settled());assertNull(channel.readOutbound());
                ledger.enterFlush(n);channel.flush();ledger.exitFlush(n);
                assertTrue(returned.isSuccess());assertFalse(ledger.settled());
                Object consumed=channel.readOutbound();assertSame(packet,consumed);ledger.consumed(cell,consumed,returned);
                ledger.release(cell);assertTrue(ledger.settled());assertNull(channel.readOutbound());
            }
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{try{channel.finishAndReleaseAll();}catch(RuntimeException|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
    }
    @Test void anActualClosedChannelFailedPromiseCannotReleaseItsRetainedOutput(){
        var channel=new EmbeddedChannel();channel.close();
        var ledger=new NativePhysicalOutputLedger();Object packet=new Object();ChannelPromise promise=channel.newPromise();
        var cell=ledger.reserve(NativePhysicalOutputLedger.Lane.ACTION,packet,new Object());ledger.writeEntered(cell,promise,1);
        promise.addListener(future->ledger.promiseCompleted(cell,promise,future.isSuccess(),future.cause()));
        var returned=channel.writeAndFlush(packet,promise);assertSame(promise,returned);assertTrue(returned.isDone());assertFalse(returned.isSuccess());
        assertSame(returned.cause(),ledger.failure());assertEquals(1,ledger.pending());
        assertThrows(RuntimeException.class,ledger::settled);assertEquals(1,ledger.pending());
    }
}
