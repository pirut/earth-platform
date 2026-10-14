package io.earthplatform.minecraft.coregate;

import io.earthplatform.compat.PlayableProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlayableProfileTest {
    @TempDir Path directory;
    private PlayableProfile.Selection parse(String text) { return PlayableProfile.parse(text.getBytes(StandardCharsets.UTF_8)); }
    @Test void exactProfileSelectsWithoutSystemProperties() { assertTrue(parse("# A restart is required\nprofile=finite-arena-shader-v1\n").selected()); }
    @Test void earthSelectionIsExplicitAndDistinct() {
        var earth=parse("profile=finite-everest-shader-v1\n");
        assertTrue(earth.selected());assertTrue(earth.earth());assertFalse(earth.arena());
        assertEquals(PlayableProfile.EARTH_PROFILE,earth.profile());
        var arena=parse("profile=finite-arena-shader-v1\n");
        assertTrue(arena.arena());assertFalse(arena.earth());
        assertFalse(parse("profile=disabled").earth());
        assertEquals(PlayableProfile.State.BLOCKED,parse("profile=finite-everest-shader-v1\nprofile=finite-arena-shader-v1").state());
    }
    @Test void absentAndExplicitDisabledAreSafe() { assertEquals(PlayableProfile.State.DISABLED, PlayableProfile.read(directory.resolve("missing")).state()); assertEquals(PlayableProfile.State.DISABLED, parse("profile=disabled\n").state()); }
    @Test void duplicatesUnknownKeysAndUnknownProfilesBlock() { for (String text : new String[]{"profile=finite-arena-shader-v1\nprofile=disabled", "other=true\nprofile=finite-arena-shader-v1", "profile=legacy", "", "profile =finite-arena-shader-v1"}) assertEquals(PlayableProfile.State.BLOCKED, parse(text).state(), text); }
    @Test void malformedUtf8AndOversizeBlock() { assertEquals(PlayableProfile.State.BLOCKED, PlayableProfile.parse(new byte[]{(byte)0xc3,0x28}).state()); assertEquals(PlayableProfile.State.BLOCKED, PlayableProfile.parse(new byte[1025]).state()); }
    @Test void boundedFileReadRejectsOversizeAndDirectory() throws Exception { Path file=directory.resolve("profile");Files.write(file,new byte[2048]);assertEquals(PlayableProfile.State.BLOCKED,PlayableProfile.read(file).state());assertEquals(PlayableProfile.State.BLOCKED,PlayableProfile.read(directory).state()); }
}
