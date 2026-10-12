package io.earthplatform.minecraft.lod;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SurvivalDhLayoutTest {
    @Test void survivalLayoutCannotMasqueradeAsEarthAuthority(){
        assertNotEquals(DhLodLayout.EARTH_ALPINE_2048.identity(),SurvivalDhLayout.IDENTITY);
        assertEquals(2048,SurvivalDhLayout.RADIUS);assertEquals(128,SurvivalDhLayout.ALIGNMENT);
        assertEquals(2048,SurvivalDhLayout.RINGS.getLast().radius());
        int previous=0;for(var ring:SurvivalDhLayout.RINGS){assertEquals(0,previous%ring.cellSize());assertEquals(0,128%ring.cellSize());previous=ring.radius();}
        assertThrows(UnsupportedOperationException.class,()->SurvivalDhLayout.RINGS.clear());
    }
}
