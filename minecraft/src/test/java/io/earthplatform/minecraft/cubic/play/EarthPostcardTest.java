package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class EarthPostcardTest {
    @Test void aCodeBringsBackItsSpotToAboutAMeter() {
        Random random = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            double latitude = random.nextDouble() * 160 - 80, longitude = random.nextDouble() * 360 - 180, bearing = random.nextInt(360);
            String code = EarthPostcard.code(latitude, longitude, bearing);
            assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"), code);
            EarthPostcard.Spot spot = EarthPostcard.spot(code);
            assertNotNull(spot, code);
            assertEquals(latitude, spot.latitude(), 0.00001);
            assertEquals(longitude, spot.longitude(), 0.00001);
            double turn = Math.abs(bearing - spot.bearing());
            assertTrue(Math.min(turn, 360 - turn) <= 2.9, code);
        }
    }

    @Test void theEdgesHold() {
        for (double[] at : new double[][] {{80, 180, 0}, {-80, -180, 359}, {0, 0, 180}, {80, 179.9999999, 359.9}}) {
            EarthPostcard.Spot spot = EarthPostcard.spot(EarthPostcard.code(at[0], at[1], at[2]));
            assertNotNull(spot);
            assertEquals(at[0], spot.latitude(), 0.00001);
            assertEquals(Math.cos(Math.toRadians(at[1])), Math.cos(Math.toRadians(spot.longitude())), 1e-9);
        }
    }

    @Test void caseDashesAndLookAlikesDoNotCount() {
        String code = EarthPostcard.code(37.7456, -119.5936, 90);
        EarthPostcard.Spot spot = EarthPostcard.spot(code);
        assertEquals(spot, EarthPostcard.spot(code.toLowerCase().replace("-", " ")));
        assertEquals(spot, EarthPostcard.spot(code.replace('0', 'O').replace('1', 'l')));
    }

    @Test void aMistypedCodeIsRefused() {
        String code = EarthPostcard.code(37.7456, -119.5936, 90);
        int caught = 0, tried = 0;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '-') continue;
            for (char c : "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()) {
                if (c == code.charAt(i)) continue;
                tried++;
                if (EarthPostcard.spot(code.substring(0, i) + c + code.substring(i + 1)) == null) caught++;
            }
        }
        assertTrue(caught >= tried * 0.9, caught + " of " + tried);
        assertNull(EarthPostcard.spot("hello"));
        assertNull(EarthPostcard.spot(code + "A"));
        assertNull(EarthPostcard.spot("UUUU-UUUU-UUUU"));
    }

    @Test void yawToBearing() {
        assertEquals(180, EarthPostcard.bearing(0));
        assertEquals(0, EarthPostcard.bearing(180));
        assertEquals(270, EarthPostcard.bearing(90));
        assertEquals(90, EarthPostcard.bearing(-90));
    }
}
