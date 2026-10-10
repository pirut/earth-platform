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
    @Test void recoveryLivesUnderVanillaClonePersistedKeyAndPreservesOtherModData(){
        var point=new CoreReturnPoint(Level.OVERWORLD,1,2,3,4,5,GameType.CREATIVE);var root=new CompoundTag();var kept=new CompoundTag();kept.putString("another-mod","keep");root.put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG,kept);root.putString("unrelated","keep-root");point.remember(root);
        assertFalse(root.contains(CoreReturnPoint.KEY));assertTrue(root.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG).contains(CoreReturnPoint.KEY));assertEquals(point,CoreReturnPoint.remembered(root).orElseThrow());CoreReturnPoint.clear(root);assertTrue(CoreReturnPoint.remembered(root).isEmpty());assertEquals("keep",kept.getString("another-mod"));assertEquals("keep-root",root.getString("unrelated"));
    }
    @Test void cloneCopiesOnlyValidatedGateRecoveryAndLegacyRecordMigrates(){
        var point=new CoreReturnPoint(Level.OVERWORLD,1,2,3,4,5,GameType.ADVENTURE);var original=new CompoundTag();original.put(CoreReturnPoint.KEY,point.encode());original.putString("private-other-mod","not-copied");var replacement=new CompoundTag();CoreReturnPoint.copyRecovery(original,replacement);assertEquals(point,CoreReturnPoint.remembered(replacement).orElseThrow());assertFalse(replacement.contains("private-other-mod"));assertFalse(replacement.contains(CoreReturnPoint.KEY));
    }
    @Test void malformedNewRecoveryCannotReviveAnOldStaleOrigin(){
        var point=new CoreReturnPoint(Level.OVERWORLD,1,2,3,4,5,GameType.CREATIVE);var root=new CompoundTag();root.put(CoreReturnPoint.KEY,point.encode());var kept=new CompoundTag();kept.putString(CoreReturnPoint.KEY,"malformed");root.put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG,kept);assertTrue(CoreReturnPoint.remembered(root).isEmpty());var replacement=new CompoundTag();CoreReturnPoint.copyRecovery(root,replacement);assertTrue(CoreReturnPoint.remembered(replacement).isEmpty());
    }
}
