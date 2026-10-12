package io.earthplatform.minecraft.survival.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.PlayerDataStorage;

/**
 * AUTHORED, NOT EXECUTED. Test-only helper for the parent's later real-server lease.
 * Supply an exact live ServerPlayer in an actual registered ordinary Level, an isolated
 * disposable PlayerDataStorage with fresh UUID, and a fixed test RETURNING image whose
 * ordinary escrow equals that player. The caller establishes the test hold. This helper's
 * authority is TEST-ONLY and deliberately does not claim a real coordinator acknowledgment,
 * transition Port, normal scheduling, transport settling, or crash/restart conservation.
 */
final class NativeOrdinarySaveDisposableProbe {
    record Evidence(Path target,boolean observerCompleted,boolean repeatedCapabilityRefused,boolean outsideScopeSaveMatched) {}
    private NativeOrdinarySaveDisposableProbe() {}
    static Evidence run(ServerPlayer player,PlayerDataStorage storage,NativeSurvivalCheckpoint returning,
            Runnable assertFixtureHold) throws IOException {
        Objects.requireNonNull(assertFixtureHold);
        Path target=storage.getPlayerDir().toPath().toAbsolutePath().normalize().resolve(player.getStringUUID()+".dat");
        if(Files.exists(target)||Files.exists(target.resolveSibling(player.getStringUUID()+".dat_old")))
            throw new IllegalArgumentException("Disposable probe requires a fresh unique UUID with no native target/backup");
        var authority=new NativeOrdinarySaveObserver.Authority(){
            final Object provenance=new Object();
            ServerPlayer player(){return player;}
            PlayerDataStorage storage(){return storage;}
            NativeSurvivalCheckpoint returning(){return returning;}
            Object checkpointProvenance(){return provenance;}
            void requireHeldAndCurrent(NativeSurvivalCheckpoint actual,Object exact){
                if(actual!=returning||exact!=provenance)throw new IllegalStateException("Fixture permit changed");
                assertFixtureHold.run();
            }
        };
        var completion=NativeOrdinarySaveObserver.save(authority);completion.requireSucceeded(authority);
        var replay=NativeOrdinarySaveObserver.save(authority);
        if(replay.failure()==null)throw new AssertionError("Consumed native save authority was replayable");
        assertFixtureHold.run();
        // This separate comparison is explicitly a fixture fresh-file/semantic check, not a second completion receipt.
        CompoundTag outsideExpected=BoundedOrdinarySaveNbt.freeze(player.saveWithoutId(new CompoundTag()));
        storage.save(player);
        if(!outsideExpected.equals(BoundedOrdinarySaveNbt.readTarget(target)))
            throw new AssertionError("Extra ordinary save outside observer scope did not preserve vanilla payload");
        assertFixtureHold.run();
        return new Evidence(target,true,true,true);
    }
}
