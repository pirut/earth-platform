package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Util/file tests plus explicitly modeled trace ordering. Not an installed mixin/Port proof. */
class OrdinarySaveTraceTest {
    @TempDir Path root;
    final Object storage=new Object(), player=new Object(), authority=new Object(), session=new Object(), durable=new Object(), emitted=new Object();
    final UUID uuid=UUID.fromString("f0cd9e1d-7dd8-4c62-b139-4ebd6e14a3c2");
    Path target(){return root.resolve(uuid+".dat");}
    Path backup(){return root.resolve(uuid+".dat_old");}
    Path temporary(){return root.resolve(uuid+"-12345.dat");}
    OrdinarySaveTrace trace(){return new OrdinarySaveTrace(storage,player,authority,session,durable,root,uuid);}
    void written(OrdinarySaveTrace t){t.enter(storage,player);t.serializing(storage,player);t.serialized(storage,player,emitted);t.writing(storage,player,emitted,temporary());t.written(storage,player);}
    void replaced(OrdinarySaveTrace t){written(t);t.replacing(storage,player,target(),temporary(),backup());t.replaced(storage,player,true);}
    @Test void actualNativeReplacementAndBoundedReadbackReachVerified() throws Exception {
        CompoundTag prior=new CompoundTag();prior.putInt("marker",1);NbtIo.writeCompressed(prior,target());
        CompoundTag next=new CompoundTag();next.putInt("marker",2);NbtIo.writeCompressed(next,temporary());
        var t=trace();written(t);t.replacing(storage,player,target(),temporary(),backup());
        t.replaced(storage,player,Util.safeReplaceOrMoveFile(target(),temporary(),backup(),false));
        t.exit(storage,player);
        assertEquals(next,BoundedOrdinarySaveNbt.readTarget(target()));assertEquals(prior,BoundedOrdinarySaveNbt.readTarget(backup()));
        t.verified();t.requireVerified();
    }
    @Test void actualNativeFalseIsNotUpgradedByAnExistingMatchingTarget() throws Exception {
        CompoundTag old=new CompoundTag();old.putInt("same",7);NbtIo.writeCompressed(old,target());NbtIo.writeCompressed(old,temporary());
        Files.createDirectory(backup());Files.writeString(backup().resolve("prevents-delete"),"keep");
        var t=trace();written(t);t.replacing(storage,player,target(),temporary(),backup());
        boolean replaced=Util.safeReplaceOrMoveFile(target(),temporary(),backup(),false);
        assertFalse(replaced);assertEquals(old,BoundedOrdinarySaveNbt.readTarget(target()));
        assertThrows(SurvivalRefusal.class,()->t.replaced(storage,player,replaced));
        assertThrows(SurvivalRefusal.class,t::verified);
    }
    @Test void swallowedPostReplacementExceptionPermanentlyPoisonsTrace(){
        var t=trace();replaced(t);t.poison(new IllegalStateException("native catch logger observed"));
        assertThrows(SurvivalRefusal.class,()->t.exit(storage,player));assertThrows(SurvivalRefusal.class,t::requireVerified);
    }
    @Test void missingSerializationWriteReplacementAndExitCannotComplete(){
        var t=trace();t.enter(storage,player);assertThrows(SurvivalRefusal.class,()->t.exit(storage,player));
        var t2=trace();written(t2);assertThrows(SurvivalRefusal.class,t2::verified);
        var t3=trace();replaced(t3);assertThrows(SurvivalRefusal.class,t3::verified);
    }
    @Test void sameUuidButDifferentPlayerOrStorageObjectsFail(){
        assertThrows(SurvivalRefusal.class,()->trace().enter(new Object(),player));
        assertThrows(SurvivalRefusal.class,()->trace().enter(storage,new Object()));
    }
    @Test void staleAuthoritySessionOrDurableObjectFails(){
        assertThrows(SurvivalRefusal.class,()->trace().current(new Object(),session,durable));
        assertThrows(SurvivalRefusal.class,()->trace().current(authority,new Object(),durable));
        assertThrows(SurvivalRefusal.class,()->trace().current(authority,session,new Object()));
    }
    @Test void duplicateOrNestedInvocationPoisonsEvenWhenCaught(){
        var t=trace();t.enter(storage,player);assertThrows(SurvivalRefusal.class,()->t.enter(storage,player));
        assertThrows(SurvivalRefusal.class,()->t.serializing(storage,player));
    }
    @Test void wrongTemporaryTagRootOrUuidCannotBind(){
        for(Path path:new Path[]{root.resolve("wrong-1.dat"),root.resolve("sub").resolve(uuid+"-1.dat"),target(),backup()}){
            var t=trace();t.enter(storage,player);t.serializing(storage,player);t.serialized(storage,player,emitted);
            assertThrows(SurvivalRefusal.class,()->t.writing(storage,player,emitted,path));
        }
        var t=trace();t.enter(storage,player);t.serializing(storage,player);t.serialized(storage,player,emitted);
        assertThrows(SurvivalRefusal.class,()->t.writing(storage,player,new Object(),temporary()));
    }
    @Test void replacementPathsMustMatchAllThreeExactBoundPaths(){
        for(int index=0;index<3;index++){
            var t=trace();written(t);Path[] paths={target(),temporary(),backup()};paths[index]=root.resolve("other.dat");
            assertThrows(SurvivalRefusal.class,()->t.replacing(storage,player,paths[0],paths[1],paths[2]));
        }
    }
    @Test void wrongThreadPoisonsOwnerScope() throws Exception {
        var t=trace();AtomicReference<Throwable> seen=new AtomicReference<>();
        Thread other=new Thread(()->{try{t.enter(storage,player);}catch(Throwable failure){seen.set(failure);}});
        other.start();other.join();assertInstanceOf(SurvivalRefusal.class,seen.get());
        assertThrows(SurvivalRefusal.class,()->t.enter(storage,player));
    }
    @Test void noScopeHooksRetainVanillaFileBehavior() throws Exception {
        assertDoesNotThrow(()->{NativeOrdinarySaveHooks.enter(null,null);NativeOrdinarySaveHooks.caught(null,null);NativeOrdinarySaveHooks.exit(null,null);});
        CompoundTag tag=new CompoundTag();tag.putString("outside","scope");
        NativeOrdinarySaveHooks.write(null,null,tag,temporary());
        NativeOrdinarySaveHooks.replace(null,null,target(),temporary(),backup());
        assertEquals(tag,BoundedOrdinarySaveNbt.readTarget(target()));assertFalse(Files.exists(temporary()));
        // A second unrelated save has no retained observer state.
        tag.putInt("second",2);NativeOrdinarySaveHooks.write(null,null,tag,temporary());
        NativeOrdinarySaveHooks.replace(null,null,target(),temporary(),backup());
        assertEquals(tag,BoundedOrdinarySaveNbt.readTarget(target()));
    }
}
