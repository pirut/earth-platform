package io.earthplatform.minecraft.coregate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CoreReturnPointTest {
    @Test void persistsExactPositionDimensionAndOriginalMode(){for(var mode:GameType.values()){var point=new CoreReturnPoint(Level.OVERWORLD,123.25,-41.5,-999.875,179,-42,mode);assertEquals(point,CoreReturnPoint.decode(point.encode()).orElseThrow());}}
    @Test void missingInvalidOrUnknownDataNeverTeleportsToDefaultZero(){assertTrue(CoreReturnPoint.decode(new CompoundTag()).isEmpty());var tag=new CoreReturnPoint(Level.OVERWORLD,1,2,3,0,0,GameType.CREATIVE).encode();tag.remove("x");assertTrue(CoreReturnPoint.decode(tag).isEmpty());tag.putDouble("x",Double.NaN);assertTrue(CoreReturnPoint.decode(tag).isEmpty());tag.putDouble("x",1);tag.putString("mode","unknown");assertTrue(CoreReturnPoint.decode(tag).isEmpty());}
    @Test void rejectsOutOfDomainAndNonfinitePose(){assertThrows(IllegalArgumentException.class,()->new CoreReturnPoint(Level.OVERWORLD,30000001,0,0,0,0,GameType.SURVIVAL));assertThrows(IllegalArgumentException.class,()->new CoreReturnPoint(Level.OVERWORLD,1,0,0,Float.NaN,0,GameType.SURVIVAL));}
}
