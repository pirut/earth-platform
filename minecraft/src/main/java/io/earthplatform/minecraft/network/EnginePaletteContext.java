package io.earthplatform.minecraft.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

/** Prepare once after registries freeze. Fingerprint binds serialized numeric IDs to exact names/states. */
public record EnginePaletteContext(Registry<Biome> biomes, String fingerprint) {
    public static EnginePaletteContext create(Registry<Biome> biomes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
                var state = Block.BLOCK_STATE_REGISTRY.byId(id);
                digest.update(("block-state:" + id + ":" + state + "\n").getBytes(StandardCharsets.UTF_8));
            }
            for (int id = 0; id < biomes.size(); id++) {
                var biome = biomes.byId(id);
                if (biome == null) throw new IllegalArgumentException("Biome registry has a non-dense ID map");
                digest.update(("biome:" + id + ":" + biomes.getKey(biome) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return new EnginePaletteContext(biomes, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public EnginePaletteContext {
        if (biomes == null || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Frozen biome registry and SHA-256 palette fingerprint required");
    }
}
