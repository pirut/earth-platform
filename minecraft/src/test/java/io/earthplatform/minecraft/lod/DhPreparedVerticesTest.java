package io.earthplatform.minecraft.lod;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** CPU-only ownership tests; these neither load DH classes nor require GL. */
class DhPreparedVerticesTest {
    @Test void countsExactlyOneThroughMaximumQuads() {
        for (int quads : new int[]{1, 2, 4095, 4096}) {
            byte[] source = pattern(quads * 64);
            var payload = new DhPreparedVertices(source);
            assertEquals(source.length, payload.byteCount());
            assertEquals(quads, payload.quads());
            assertArrayEquals(source, payload.copyBytes());
        }
    }

    @Test void rejectsNullEmptyPartialQuadsAndOversizedPayloads() {
        assertThrows(NullPointerException.class, () -> new DhPreparedVertices(null));
        for (int length : new int[]{0, 1, 16, 63, 65, 127, 4096 * 64 - 1,
                4096 * 64 + 1, 4097 * 64}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DhPreparedVertices(new byte[length]), "length " + length);
        }
    }

    @Test void defensivelyCopiesBothConstructorInputAndEveryReturnedArray() {
        byte[] source = pattern(128);
        byte[] expected = source.clone();
        var payload = new DhPreparedVertices(source);
        Arrays.fill(source, (byte) 0);
        byte[] firstCopy = payload.copyBytes();
        byte[] secondCopy = payload.copyBytes();
        assertNotSame(source, firstCopy);
        assertNotSame(firstCopy, secondCopy);
        assertArrayEquals(expected, firstCopy);
        Arrays.fill(firstCopy, (byte) -1);
        assertArrayEquals(expected, secondCopy);
        assertArrayEquals(expected, payload.copyBytes());
        assertEquals(128, payload.byteCount());
        assertEquals(2, payload.quads());
    }

    @Test void writesExactlyAtCurrentPositionWithoutChangingLimitOrderOrOtherBytes() {
        byte[] source = pattern(128);
        var payload = new DhPreparedVertices(source);
        byte[] destination = new byte[160];
        Arrays.fill(destination, (byte) 0x55);
        ByteBuffer target = ByteBuffer.wrap(destination).order(ByteOrder.LITTLE_ENDIAN);
        target.position(7).mark().limit(145);
        payload.writeTo(target);
        assertEquals(135, target.position());
        assertEquals(145, target.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
        assertArrayEquals(source, Arrays.copyOfRange(destination, 7, 135));
        for (int i = 0; i < destination.length; i++) {
            if (i < 7 || i >= 135) assertEquals((byte) 0x55, destination[i], "offset " + i);
        }
        target.reset();
        assertEquals(7, target.position());
        Arrays.fill(destination, (byte) 0);
        assertArrayEquals(source, payload.copyBytes());
    }

    @Test void writesToDirectAndSlicedBuffersWithExactlyEnoughRemainingSpace() {
        byte[] source = pattern(64);
        var payload = new DhPreparedVertices(source);
        ByteBuffer parent = ByteBuffer.allocate(80);
        parent.position(8).limit(72);
        for (ByteBuffer target : new ByteBuffer[]{ByteBuffer.allocateDirect(64), parent.slice()}) {
            payload.writeTo(target);
            assertEquals(64, target.position());
            assertEquals(0, target.remaining());
            byte[] copied = new byte[64];
            target.flip().get(copied);
            assertArrayEquals(source, copied);
            target.clear().put(0, (byte) 0);
            assertArrayEquals(source, payload.copyBytes());
        }
        assertEquals(8, parent.position());
        assertEquals(72, parent.limit());
    }

    @Test void insufficientRemainingSpaceDoesNotPartiallyWriteOrMovePositionOrMark() {
        var payload = new DhPreparedVertices(pattern(128));
        byte[] destination = pattern(256);
        byte[] before = destination.clone();
        ByteBuffer target = ByteBuffer.wrap(destination);
        target.position(3).mark().position(5).limit(132);
        assertEquals(127, target.remaining());
        assertThrows(BufferOverflowException.class, () -> payload.writeTo(target));
        assertArrayEquals(before, destination);
        assertEquals(5, target.position());
        assertEquals(132, target.limit());
        target.reset();
        assertEquals(3, target.position());
    }

    @Test void maximumPayloadCanBeCopiedWithoutTruncation() {
        byte[] source = pattern(4096 * 64);
        ByteBuffer target = ByteBuffer.allocate(source.length);
        new DhPreparedVertices(source).writeTo(target);
        assertEquals(source.length, target.position());
        assertArrayEquals(source, target.array());
    }

    @Test void readOnlyTargetDoesNotChangeBackingBytesPositionLimitOrMark() {
        var payload = new DhPreparedVertices(pattern(64));
        byte[] backing = pattern(96);
        byte[] before = backing.clone();
        ByteBuffer target = ByteBuffer.wrap(backing).asReadOnlyBuffer();
        target.position(2).mark().position(8).limit(80);
        assertThrows(ReadOnlyBufferException.class, () -> payload.writeTo(target));
        assertArrayEquals(before, backing);
        assertEquals(8, target.position());
        assertEquals(80, target.limit());
        target.reset();
        assertEquals(2, target.position());
    }

    @Test void rejectsNullTargetWithoutChangingPayload() {
        byte[] source = pattern(64);
        var payload = new DhPreparedVertices(source);
        assertThrows(NullPointerException.class, () -> payload.writeTo(null));
        assertArrayEquals(source, payload.copyBytes());
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }
}
