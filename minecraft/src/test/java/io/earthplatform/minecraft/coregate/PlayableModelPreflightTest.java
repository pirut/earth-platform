package io.earthplatform.minecraft.coregate;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlayableModelPreflightTest {
    private int[] quad(Direction d){int[] v=new int[32];int axis=d.getAxis()==Direction.Axis.X?0:d.getAxis()==Direction.Axis.Y?1:2;for(int i=0;i<4;i++){int bit=0;for(int a=0;a<3;a++){float n=a==axis?(d.getAxisDirection()==Direction.AxisDirection.POSITIVE?1:0):((i>>(bit++))&1);v[i*8+a]=Float.floatToRawIntBits(n);}}return v;}
    @Test void allSixExactCubeFacesFitCensus(){for(var d:Direction.values())assertDoesNotThrow(()->PlayableModelPreflight.requireUnitQuad(quad(d),d));}
    @Test void duplicateCornerFails(){var v=quad(Direction.UP);System.arraycopy(v,0,v,8,8);assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(v,Direction.UP));}
    @Test void fractionalAndOutOfRangeCoordinatesFail(){for(float n:new float[]{.5f,-1f,2f,Float.NaN,Float.POSITIVE_INFINITY}){var v=quad(Direction.UP);v[0]=Float.floatToRawIntBits(n);assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(v,Direction.UP));}}
    @Test void wrongPlaneFails(){assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(quad(Direction.DOWN),Direction.UP));}
    @Test void WrongFormatAndNullFail(){assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(new int[52],Direction.UP));assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(null,Direction.UP));}
}
