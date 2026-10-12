package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePlayerPoseSnapshotTest {
    static NativePlayerPoseSnapshot value(double y, GameType previous) {
        return new NativePlayerPoseSnapshot(ResourceLocation.parse("minecraft:the_nether"), -13640057.5, y, -4213188.5,
                -127.25F, 42.5F, 0.125, -0.25, 0.375, 3.5F, false, GameType.CREATIVE, previous,
                new NativePlayerPoseSnapshot.AbilityState(true, true, true, true, false, 0.07F, 0.12F));
    }
    static byte[] encode(NativePlayerPoseSnapshot value) throws IOException {
        var bytes = new ByteArrayOutputStream(); value.write(new DataOutputStream(bytes)); return bytes.toByteArray();
    }
    static NativePlayerPoseSnapshot decode(byte[] bytes) throws IOException {
        return NativePlayerPoseSnapshot.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }
    @Test void fullSignedCoordinatesVelocityModesAndAbilitiesRoundtrip() throws Exception {
        for (double y : new double[]{-11000.25, 134.125, 7314.875, 7314.875 + 4096}) {
            var expected = value(y, GameType.SURVIVAL); byte[] bytes = encode(expected);
            assertEquals(expected.encodedBytes(), bytes.length);
            assertEquals(expected, decode(bytes)); assertArrayEquals(bytes, encode(decode(bytes)));
        }
        assertNotEquals(value(7314.875, null), value(7314.875 + 4096, null));
    }
    @Test void nativeUnsetPreviousModeRemainsUnset() throws Exception {
        var expected = value(134.125, null);
        assertNull(decode(encode(expected)).previousGameMode());
        assertEquals(expected, decode(encode(expected)));
    }
    @Test void everyModeAndAbilityBitIsPreservedWithoutModeBasedNormalization() throws Exception {
        var good = value(134.125, null);
        for (GameType current : GameType.values()) for (int prior = -1; prior < 4; prior++) for (int flags = 0; flags < 32; flags++) {
            var abilities = new NativePlayerPoseSnapshot.AbilityState((flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0, 0.125F, 0.25F);
            var expected = new NativePlayerPoseSnapshot(good.dimension(), good.x(), good.y(), good.z(), good.yaw(), good.pitch(),
                    good.velocityX(), good.velocityY(), good.velocityZ(), good.fallDistance(), true, current,
                    prior == -1 ? null : GameType.byId(prior), abilities);
            assertEquals(expected, decode(encode(expected)));
        }
    }
    @Test void exactAbilityCaptureIsImmutableAndIncludesAllFlagsAndBothNativeSpeeds() {
        var abilities = new Abilities(); abilities.invulnerable = true; abilities.flying = true;
        abilities.mayfly = true; abilities.instabuild = true; abilities.mayBuild = false;
        abilities.setFlyingSpeed(0.2F); abilities.setWalkingSpeed(0.3F);
        var snapshot = NativePlayerPoseSnapshot.AbilityState.capture(abilities);
        abilities.invulnerable = false; abilities.flying = false; abilities.mayfly = false;
        abilities.instabuild = false; abilities.mayBuild = true;
        abilities.setFlyingSpeed(0.4F); abilities.setWalkingSpeed(0.5F);
        assertEquals(new NativePlayerPoseSnapshot.AbilityState(true, true, true, true, false, 0.2F, 0.3F), snapshot);
        assertThrows(SurvivalRefusal.class, () -> NativePlayerPoseSnapshot.AbilityState.capture(new Abilities() {}));
        assertThrows(SurvivalRefusal.class, () -> NativePlayerPoseSnapshot.AbilityState.capture(null));
    }
    @Test void invalidNativePositionVelocityAndOrientationAreRefused() {
        for (double y : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 30_000_000D, -30_000_000D})
            assertThrows(IllegalArgumentException.class, () -> value(y, null));
        var good = value(134, null);
        assertThrows(IllegalArgumentException.class, () -> new NativePlayerPoseSnapshot(good.dimension(), good.x(), good.y(), good.z(),
                Float.NaN, 0, 0, 0, 0, 0, false, GameType.SURVIVAL, null, good.abilities()));
        assertThrows(IllegalArgumentException.class, () -> new NativePlayerPoseSnapshot(good.dimension(), good.x(), good.y(), good.z(),
                0, 0, 10.01, 0, 0, 0, false, GameType.SURVIVAL, null, good.abilities()));
        for (float speed : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01F, 1.01F}) {
            assertThrows(IllegalArgumentException.class, () -> new NativePlayerPoseSnapshot.AbilityState(false, false, false, false, true, speed, 0.1F));
            assertThrows(IllegalArgumentException.class, () -> new NativePlayerPoseSnapshot.AbilityState(false, false, false, false, true, 0.1F, speed));
        }
    }
    @Test void malformedNumericFieldsAreRejectedDuringRead() throws Exception {
        byte[] bytes = encode(value(134, null)); int numeric = 2 + value(134, null).dimension().toString().length();
        for (int offset : new int[]{numeric, numeric + 8, numeric + 16}) for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 30_000_000D, -30_000_000D}) {
            byte[] malformed = bytes.clone(); ByteBuffer.wrap(malformed).putDouble(offset, invalid);
            assertThrows(IllegalArgumentException.class, () -> decode(malformed));
        }
        for (int offset : new int[]{numeric + 32, numeric + 40, numeric + 48}) for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, 10.01, -10.01}) {
            byte[] malformed = bytes.clone(); ByteBuffer.wrap(malformed).putDouble(offset, invalid);
            assertThrows(IllegalArgumentException.class, () -> decode(malformed));
        }
        for (int offset : new int[]{numeric + 24, numeric + 28, numeric + 56, bytes.length - 8, bytes.length - 4}) {
            byte[] malformed = bytes.clone(); ByteBuffer.wrap(malformed).putFloat(offset, Float.NaN);
            assertThrows(IllegalArgumentException.class, () -> decode(malformed));
        }
        for (float invalid : new float[]{-90.01F, 90.01F}) {
            byte[] malformed = bytes.clone(); ByteBuffer.wrap(malformed).putFloat(numeric + 28, invalid);
            assertThrows(IllegalArgumentException.class, () -> decode(malformed));
        }
        byte[] negativeFall = bytes.clone(); ByteBuffer.wrap(negativeFall).putFloat(numeric + 56, -0.01F);
        assertThrows(IllegalArgumentException.class, () -> decode(negativeFall));
    }
    @Test void unknownModesAbilityBitsAndNoncanonicalGroundBytesAreRefused() throws Exception {
        byte[] bytes = encode(value(134, null));
        for (int invalid : new int[]{-1, 4, Integer.MAX_VALUE}) {
            byte[] malformed = bytes.clone(); ByteBuffer.wrap(malformed).putInt(bytes.length - 17, invalid);
            assertThrows(IllegalArgumentException.class, () -> decode(malformed));
        }
        for (int invalid : new int[]{-2, 4, Integer.MAX_VALUE}) {
            byte[] malformed = bytes.clone(); ByteBuffer.wrap(malformed).putInt(bytes.length - 13, invalid);
            assertThrows(IllegalArgumentException.class, () -> decode(malformed));
        }
        for (int invalid : new int[]{32, 64, 128, 255}) {
            byte[] malformed = bytes.clone(); malformed[bytes.length - 9] = (byte) invalid;
            assertThrows(IllegalArgumentException.class, () -> decode(malformed));
        }
        for (int invalid : new int[]{2, 255}) {
            byte[] malformed = bytes.clone(); malformed[bytes.length - 18] = (byte) invalid;
            assertThrows(IllegalArgumentException.class, () -> decode(malformed));
        }
    }
    @Test void everyTruncatedPoseFailsWithoutInventingMissingValues() throws Exception {
        byte[] bytes = encode(value(134, null));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IOException.class, () -> decode(truncated));
        }
    }
    @Test void dimensionLengthAndExactEncodedAccountingAreBounded() throws Exception {
        var good = value(134, null); String prefix = "earthplatform:";
        for (String name : new String[]{"a:b", "minecraft:overworld", prefix + "a".repeat(1024 - prefix.length())}) {
            var expected = new NativePlayerPoseSnapshot(ResourceLocation.parse(name), good.x(), good.y(), good.z(), good.yaw(), good.pitch(),
                    good.velocityX(), good.velocityY(), good.velocityZ(), good.fallDistance(), good.onGround(), good.gameMode(), null, good.abilities());
            assertEquals(80 + name.length(), expected.encodedBytes());
            assertEquals(expected.encodedBytes(), encode(expected).length); assertEquals(expected, decode(encode(expected)));
        }
        assertThrows(IllegalArgumentException.class, () -> new NativePlayerPoseSnapshot(ResourceLocation.parse(prefix + "a".repeat(1025 - prefix.length())),
                good.x(), good.y(), good.z(), good.yaw(), good.pitch(), good.velocityX(), good.velocityY(), good.velocityZ(),
                good.fallDistance(), good.onGround(), good.gameMode(), null, good.abilities()));
    }
    @Test void malformedDimensionAndMissingRequiredIdentityNeverAcquireDefaults() throws Exception {
        byte[] bytes = encode(value(134, null)); bytes[2] = 'M';
        assertThrows(RuntimeException.class, () -> decode(bytes));
        var good = value(134, null);
        assertThrows(NullPointerException.class, () -> new NativePlayerPoseSnapshot(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
                GameType.SURVIVAL, null, good.abilities()));
        assertThrows(NullPointerException.class, () -> new NativePlayerPoseSnapshot(good.dimension(), 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
                null, null, good.abilities()));
        assertThrows(NullPointerException.class, () -> new NativePlayerPoseSnapshot(good.dimension(), 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
                GameType.SURVIVAL, null, null));
    }
    @Test void admittedExtremesAndSignedZeroRoundtripWithoutClamping() throws Exception {
        var expected = new NativePlayerPoseSnapshot(ResourceLocation.parse("minecraft:overworld"), -29_999_984D, 29_999_984D, -0.0D,
                -360000F, -90F, -10D, 10D, -0.0D, 29_999_984F, true, GameType.ADVENTURE, GameType.SPECTATOR,
                new NativePlayerPoseSnapshot.AbilityState(false, true, false, true, false, 0F, 1F));
        assertEquals(expected, decode(encode(expected)));
        assertEquals(Double.doubleToRawLongBits(-0.0D), Double.doubleToRawLongBits(decode(encode(expected)).z()));
    }
}
