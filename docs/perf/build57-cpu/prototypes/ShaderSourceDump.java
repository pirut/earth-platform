package io.earthplatform.minecraft.cubic.client;
import java.nio.file.*;
/** Dumps exact production GLSL for reference-compiler validation; no GL/native dependency. */
public final class ShaderSourceDump {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]); Files.createDirectories(out);
        Files.writeString(out.resolve("sun.vert"),FarLodShaders.FULL_SCREEN);
        Files.writeString(out.resolve("sun-original.frag"),FarLodShaders.SUN);
        Files.writeString(out.resolve("sun-hints.frag"),FarLodShaders.SUN_HINTS);
    }
}
