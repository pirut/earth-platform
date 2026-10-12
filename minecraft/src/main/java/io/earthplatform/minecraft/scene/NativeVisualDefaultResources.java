package io.earthplatform.minecraft.scene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** Exact default asset allowlist for the first backend; replacements need an independently qualified contract. */
final class NativeVisualDefaultResources {
    private NativeVisualDefaultResources() {}
    private static final String[][] ASSETS = {
        {"blockstates/stone.json", "34476f2bb98e7abec029c07c3cbfd8ba3a0141c0df3f02ff255018dbec625bbc"},
        {"blockstates/diorite.json", "3dac0a7007dcf4f8d6443fc542639c31fb04d959416ee5f180853b719a78f224"},
        {"blockstates/andesite.json", "831f843b371f1f59766ddf2ace20e5b144dce629eb60323640a149b4edfdf5e6"},
        {"blockstates/tuff.json", "9a367ca895b1c364fe6203d9a7a971811e036d1bcd692aa5a8efcac16e520238"},
        {"blockstates/snow_block.json", "a5037bb9fa856b69257d7d722d2b55e2700f960a2b0c9039c88a8c381408e824"},
        {"models/block/stone.json", "c0734082e7f1e2f44809737b2fbe1136fc7304bde7232e45fbe921884a4a327e"},
        {"models/block/stone_mirrored.json", "f6127c3048b1812b664209825271553c71c2b2dae9d6a19933702b6fdbe34370"},
        {"models/block/diorite.json", "4eb8731e843d388471aa1aab79b62b9857596b69949caf5688ed96c5133621bc"},
        {"models/block/andesite.json", "d552abc7c49f4e0977093288e1bd26c1259f86891d773436bd5e498cc51fee4f"},
        {"models/block/tuff.json", "cf7b4e3bb50bfaf75b422d5a7fc47a5dbbeebd16d990c43adb99f64df00e8100"},
        {"models/block/snow_block.json", "2693a597b23f10470fcd0046523af081ca3c5e20a55295d232421c192fe2f36b"},
        {"models/block/cube_all.json", "be3205d629ffb9e03834c3d1d083f8a2c62e9f9ae80820755129408634e9144a"},
        {"models/block/cube_mirrored_all.json", "eb0682e3126d537e8a15af343bc40e32339baa63f4f541143ed969e25e9231e0"},
        {"models/block/cube.json", "3e4aacd02e816aeba38f83076596e18ded4cf49c01e17c62d1fce79850ffb84e"},
        {"models/block/cube_mirrored.json", "e0a7b21709196c8641177e68a0a5e2a25d1de8f82e7ece75120dd38ed5d7a0fa"},
        {"models/block/block.json", "3ef6c442f1ab55d2a57fa58e28bb831268159052659f12b453b637b31ded1da8"},
        {"textures/block/stone.png", "33ac70ba8cb701087642a12c5fd29c1ba3006dc4730e9b014ed3a19ce7cdda7b"},
        {"textures/block/diorite.png", "5f44c6743cd72c00a074e6c43cc9c46b9cf54a82c0cf87c5eb675a7207cff648"},
        {"textures/block/andesite.png", "bc1fec2c9136bfe15f10dcc8fbd447d82c876c01342fedb5c7092291e89f50f0"},
        {"textures/block/tuff.png", "f418841b6de0de42ea5c31c65ddad368c8d721ef172f59cf3b975923413a66ab"},
        {"textures/block/snow.png", "05201107fc2c60db4dc59f778a9ca7cb6101b2a332da696b33ce4b0cd2a02019"},
    };
    static String verify(ResourceManager resources) {
        StringBuilder identity = new StringBuilder();
        try {
            for (String[] asset : ASSETS) {
                var location = ResourceLocation.fromNamespaceAndPath("minecraft", asset[0]);
                var resource = resources.getResource(location).orElseThrow(() -> new IllegalStateException("Missing native default asset " + location));
                byte[] bytes;
                try (var stream = resource.open()) { bytes = stream.readNBytes(1 << 20); if (stream.read() != -1) throw new IllegalStateException("Oversized default resource " + location); }
                String digest = NativeVisualCapsule.sha256(bytes, bytes.length);
                if (!digest.equals(asset[1])) throw new IllegalStateException("Unsupported native visual resource replacement: " + location);
                identity.append(location).append(':').append(digest).append('\n');
            }
            for (String texture : List.of("stone", "diorite", "andesite", "tuff", "snow")) {
                for (String suffix : List.of(".png.mcmeta", "_n.png", "_s.png"))
                    if (resources.getResource(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/" + texture + suffix)).isPresent())
                        throw new IllegalStateException("Unsupported native material sidecar: " + texture + suffix);
            }
        } catch (IOException failure) { throw new IllegalStateException("Unable to qualify native default resources", failure); }
        byte[] bytes = identity.toString().getBytes(StandardCharsets.UTF_8);
        return NativeVisualCapsule.sha256(bytes, bytes.length);
    }
}
