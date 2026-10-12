package io.earthplatform.minecraft.survival.interaction.host;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

final class NativeOriginalPacketWriteShapeTest {
    @Test void realScalarPacketIsAccepted(){assertTrue(NativeOriginalPacketWriteShape.accepts(new ServerboundHelloPacket("shape",new java.util.UUID(0,1))));}
    @Test void plainControlIsPassThrough(){assertFalse(NativeOriginalPacketWriteShape.accepts(new Object()));}
    @Test void nullIsNeverAnEncodedPacket(){assertFalse(NativeOriginalPacketWriteShape.accepts(null));}
    @Test void arbitraryEncoderCannotClaimMatcher(){assertFalse(NativeOriginalPacketWriteShape.exactMatcher(new Object()));assertFalse(NativeOriginalPacketWriteShape.exactMatcher(null));}
}
