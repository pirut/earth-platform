package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * {@code earth.coarseDirt} is pinned only when it is off, and a world that never pinned it keeps coarse dirt.
 * Works on isolated {@link Properties}, so it never touches JVM properties or the shared data sources.
 */
class CubicSettingsCoarseDirtPinTest {
    @Test
    void absentPropertyKeepsCoarseDirtAndOnlyFalseTurnsItOff() {
        assertTrue(CubicSettings.coarseDirt(new Properties()), "no pin means coarse dirt, as every world made before had");
        Properties on = new Properties();
        on.setProperty("earth.coarseDirt", "true");
        assertTrue(CubicSettings.coarseDirt(on));
        Properties off = new Properties();
        off.setProperty("earth.coarseDirt", "false");
        assertFalse(CubicSettings.coarseDirt(off));
    }

    @Test
    void pinWritesNothingWhenOnAndFalseWhenOff() {
        Properties normal = new Properties();
        CubicSettings.pinCoarseDirt(normal, true);
        assertNull(normal.getProperty("earth.coarseDirt"), "a default world writes nothing, so its file is as it was");
        assertTrue(CubicSettings.coarseDirt(normal));

        Properties plain = new Properties();
        CubicSettings.pinCoarseDirt(plain, false);
        assertEquals("false", plain.getProperty("earth.coarseDirt"));
        assertFalse(CubicSettings.coarseDirt(plain), "a saved world reopens with the choice it was made with");
    }
}
