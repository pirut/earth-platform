package io.earthplatform.compat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

/** Restart-required installation choice. No game, renderer or optional-mod class is initialized. */
public final class PlayableProfile {
    public static final String FILE = "earthplatform-playable.properties";
    public static final String PROFILE = "finite-arena-shader-v1";
    public static final String EARTH_PROFILE = "finite-everest-shader-v1";
    public static final int MAX_BYTES = 1024;
    public enum State { DISABLED, SELECTED, BLOCKED }
    public record Selection(State state, String reason, String profile) {
        public Selection(State state, String reason) { this(state, reason, ""); }
        public boolean selected() { return state == State.SELECTED; }
        public boolean earth() { return selected() && EARTH_PROFILE.equals(profile); }
        public boolean arena() { return selected() && PROFILE.equals(profile); }
    }
    private PlayableProfile() {}
    private static final class Loaded {
        private static final Selection VALUE = read(FMLPaths.CONFIGDIR.get().resolve(FILE));
    }
    public static Selection selection() { return Loaded.VALUE; }
    public static Selection read(Path path) {
        if (!Files.exists(path)) return new Selection(State.DISABLED, "Playable profile is not installed");
        try (var input = Files.newInputStream(path)) {
            return parse(input.readNBytes(MAX_BYTES + 1));
        } catch (IOException failure) {
            return new Selection(State.BLOCKED, "Cannot read " + FILE + "; correct it and restart");
        }
    }
    public static Selection parse(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES)
            return new Selection(State.BLOCKED, "Playable profile exceeds its 1024-byte limit");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            return new Selection(State.BLOCKED, "Playable profile must be valid UTF-8");
        }
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if ((Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t') || c=='\u2028' || c=='\u2029')
                return new Selection(State.BLOCKED, "Playable profile contains forbidden control characters");
        }
        String value = null;
        for (String raw : text.split("\\R", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!line.startsWith("profile=") || value != null)
                return new Selection(State.BLOCKED, "Only one exact profile= setting is supported");
            value = line.substring("profile=".length());
        }
        if (PROFILE.equals(value)) return new Selection(State.SELECTED, "Finite landscape selected; installed compatibility checks still required", PROFILE);
        if (EARTH_PROFILE.equals(value)) return new Selection(State.SELECTED, "Bounded real Everest region selected; installed compatibility and source checks still required", EARTH_PROFILE);
        if ("disabled".equals(value)) return new Selection(State.DISABLED, "Playable profile disabled");
        return new Selection(State.BLOCKED, "Unknown or missing playable profile; use " + PROFILE + ", " + EARTH_PROFILE + " or disabled");
    }
}
