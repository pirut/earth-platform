package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import net.minecraft.util.RandomSource;

/**
 * A single action's detached native random stream. Construction accepts only immutable identity
 * values: there is no Level, global RNG, clock, entity, supplier, or externally supplied stream.
 * The seed effect must accompany the eventual coherent cube/player/entity checkpoint. Recovery
 * delivers already prepared effects and must never rerun a loot callback from this seed.
 */
public final class NativeActionRandom {
    private final NativeEffectCodec.RandomSeed seedEffect;
    private final RandomSource source;

    NativeActionRandom(WorldIdentity world, NativeSurvivalWorld.ActionIdentity identity) {
        Objects.requireNonNull(world); Objects.requireNonNull(identity);
        long seed = derive(world, identity);
        seedEffect = new NativeEffectCodec.RandomSeed(world, identity.ownerEpoch(), identity.actor(), identity.sequence(), seed);
        source = RandomSource.create(seed);
    }
    public NativeEffectCodec.RandomSeed seedEffect() { return seedEffect; }
    RandomSource source() { return source; }

    static long derive(WorldIdentity world, NativeSurvivalWorld.ActionIdentity identity) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF("earth-native-whole-action-random-v1");
                output.writeLong(world.seed()); output.writeUTF(world.generatorId()); output.writeUTF(world.generatorVersion());
                output.writeUTF(world.datasetVersion()); output.writeUTF(world.blockPaletteVersion());
                output.writeLong(identity.ownerEpoch().getMostSignificantBits()); output.writeLong(identity.ownerEpoch().getLeastSignificantBits());
                output.writeLong(identity.actor().getMostSignificantBits()); output.writeLong(identity.actor().getLeastSignificantBits());
                output.writeLong(identity.sequence());
            }
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())).getLong();
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException("Cannot derive native action seed", impossible); }
    }
}
