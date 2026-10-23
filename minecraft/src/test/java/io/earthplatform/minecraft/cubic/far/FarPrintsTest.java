package io.earthplatform.minecraft.cubic.far;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FarPrintsTest {
    private static final List<byte[]> SECTIONS = List.of(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, new byte[0]);

    @Test void printsTellColumnsApart() {
        long print = FarPrints.of(-64, SECTIONS, new short[] {3});
        assertTrue(print == FarPrints.of(-64, List.of(SECTIONS.get(0).clone(), new byte[0]), new short[] {3}));
        assertNotEquals(print, FarPrints.of(-48, SECTIONS, new short[] {3}));
        assertNotEquals(print, FarPrints.of(-64, SECTIONS, new short[] {4}));
        assertNotEquals(print, FarPrints.of(-64, List.of(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12}, new byte[0]), new short[] {3}));
        assertNotEquals(print, FarPrints.of(-64, List.of(new byte[0], SECTIONS.get(0)), new short[] {3}));
    }

    @Test void onlyAChangedRemakeIsSent() {
        FarPrints prints = new FarPrints();
        prints.made(7, 100);
        prints.edited(7);
        prints.made(7, 100);
        assertTrue(prints.remadeSame(7));
        prints.edited(7);
        prints.made(7, 101);
        assertFalse(prints.remadeSame(7));
        // A column never made, or no longer remembered, is always sent.
        prints.edited(8);
        prints.made(8, 100);
        assertFalse(prints.remadeSame(8));
    }

    @Test void aRemakeIsComparedWithWhatPlayersHeldAtTheEdit() {
        FarPrints prints = new FarPrints();
        prints.made(7, 100);
        prints.edited(7);
        // Someone asking for the column made it with the edit before its remake came round.
        prints.made(7, 101);
        prints.made(7, 101);
        assertFalse(prints.remadeSame(7));
    }
}
