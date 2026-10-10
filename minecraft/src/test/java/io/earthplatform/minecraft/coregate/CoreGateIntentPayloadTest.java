package io.earthplatform.minecraft.coregate;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreGateIntentPayloadTest {
    private RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void wireContainsOnlyEpochAndActionNeverClientTarget(){for(long epoch:new long[]{1,99,Long.MAX_VALUE})for(boolean place:new boolean[]{false,true}){var b=buffer();try{var intent=new CoreGateIntentPayload(epoch,place);CoreGateIntentPayload.CODEC.encode(b,intent);assertEquals(9,b.readableBytes());assertEquals(intent,CoreGateIntentPayload.CODEC.decode(b));assertFalse(b.isReadable());}finally{b.release();}}}
    @Test void rejectsInvalidEpochAndEveryTruncatedWirePrefix(){assertThrows(IllegalArgumentException.class,()->new CoreGateIntentPayload(0,true));assertThrows(IllegalArgumentException.class,()->new CoreGateIntentPayload(-1,false));for(int count=0;count<9;count++){var b=buffer();try{b.writeBytes(new byte[]{0,0,0,0,0,0,0,1,1},0,count);assertThrows(RuntimeException.class,()->CoreGateIntentPayload.CODEC.decode(b));}finally{b.release();}}}
}
