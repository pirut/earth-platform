package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Util;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/**
 * Package-private, synchronous observation of ONE actual native ordinary-player save. No public
 * begin/success token exists. There is intentionally no production Authority implementation.
 * The eventual Port owns the hold and durable authority; this observer cannot establish either.
 */
final class NativeOrdinarySaveObserver {
    /**
     * Only the eventual Port may mint/retain this capability, after native restore has settled.
     * Getters are callback-free field reads. requireHeldAndCurrent must prove the same coordinator's
     * CURRENT object-identical RETURNING checkpoint, retired consumed owners, exact request/ticket,
     * session/save ID/owner epoch, native connection/Level/storage/registry/datapack, and active hold.
     * It must reject every changed/stale/replayed state and admit the exact serialization callback
     * profile. This draft deliberately supplies no production implementation or factory.
     */
    abstract static class Authority {
        private boolean consumed;
        private synchronized void consumeOnce() {
            if (consumed) throw new SurvivalRefusal("Ordinary save authority was already consumed");
            consumed=true;
        }
        abstract ServerPlayer player();
        abstract PlayerDataStorage storage();
        abstract NativeSurvivalCheckpoint returning();
        abstract Object checkpointProvenance();
        abstract void requireHeldAndCurrent(NativeSurvivalCheckpoint exactReturning, Object exactProvenance);
    }
    /** This receipt is private to the bridge operation; not a transferable phase/save permit. */
    static final class Completion {
        private final Attempt attempt;
        private Completion(Attempt attempt) { this.attempt=attempt; }
        void requireSucceeded(Authority sameAuthority) {
            if (sameAuthority != attempt.authority) throw new SurvivalRefusal("Foreign ordinary-save completion consumer");
            if (!ACTIVE.compareAndSet(null,attempt)) {
                attempt.poison(new SurvivalRefusal("Ordinary completion consumed inside another native save scope"));
                throw new SurvivalRefusal("Ordinary completion scope is already owned");
            }
            try { attempt.checkCurrent(); attempt.trace.requireVerified(); if(attempt.installed!=null)attempt.installed.requireVerified(); }
            catch (Throwable failure) { attempt.poison(failure); throw failure; }
            finally { ACTIVE.compareAndSet(attempt,null); }
        }
        Throwable failure() { return attempt.failure.get(); }
    }
    private static final AtomicReference<Attempt> ACTIVE = new AtomicReference<>();
    private NativeOrdinarySaveObserver() {}

    /** Invokes storage.save(player) exactly once. Failure is sticky; there is no retry/restore API. */
    static Completion save(Authority authority) { return save(authority,null,null); }
    static Completion save(Authority authority,io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.Token token,
            NativeSessionExecutionGate exactGate) {
        Attempt attempt=new Attempt(Objects.requireNonNull(authority),token,exactGate);
        if (!ACTIVE.compareAndSet(null,attempt)) {
            Attempt previous=ACTIVE.get();
            SurvivalRefusal nested=new SurvivalRefusal("Nested ordinary native save scope");
            if(previous!=null)previous.poison(nested);
            throw nested;
        }
        try {
            authority.consumeOnce(); attempt.bind(); attempt.checkCurrent();
            if(token==null)attempt.storage.save(attempt.player);
            else {
                attempt.installed=InstalledOrdinarySaveEnvelope.open(token,exactGate,attempt.player,attempt.storage,attempt.session,attempt::checkCurrent,attempt::poison);
                attempt.installed.saveNativePlayerList();
            }
            attempt.checkCurrent();
            // RETURN observation must have occurred before this target can ever be considered.
            attempt.requireExited();
            CompoundTag read=attempt.installed==null ? BoundedOrdinarySaveNbt.readTarget(attempt.trace.target()) : attempt.installed.readPlayerTarget(attempt.trace.target());
            if (!attempt.frozen.equals(read)) throw new SurvivalRefusal("Ordinary target differs from the exact emitted full NBT");
            OrdinarySavePayload.requireEscrow(read,attempt.player.getUUID(),attempt.session,attempt.registries);
            attempt.checkCurrent();
            if(attempt.installed!=null)attempt.installed.completeLevelSave(attempt.frozen);
            attempt.checkCurrent(); attempt.trace.verified();
        } catch (Throwable failure) { attempt.poison(failure); }
        finally {
            if(attempt.installed!=null)attempt.installed.close();
            if (!ACTIVE.compareAndSet(attempt,null)) attempt.poison(new SurvivalRefusal("Ordinary native save observer scope was replaced"));
        }
        return new Completion(attempt);
    }

    static void enter(PlayerDataStorage storage,Player player) {
        Attempt a=ACTIVE.get(); if(a==null)return;
        a.checkCurrent();a.trace.enter(storage,player);if(a.installed!=null)a.installed.playerEnter();
    }
    static CompoundTag serialize(PlayerDataStorage storage,Player receiver,CompoundTag input,Player invocation) {
        Attempt a=ACTIVE.get(); if(a==null)return receiver.saveWithoutId(input);
        a.checkCurrent();a.trace.serializing(storage,invocation);
        if(receiver!=a.player)throw a.refuse("Native serializer receiver mismatch");
        CompoundTag emitted=receiver.saveWithoutId(input);
        a.trace.serialized(storage,invocation,emitted);
        if(a.installed!=null)a.installed.requirePlayerPayload(emitted);
        try{a.frozen=a.installed==null ? BoundedOrdinarySaveNbtUnchecked.freeze(emitted) : a.installed.freezePlayer(emitted);}
        catch(IOException failure){a.poison(failure);throw refuseWithCause("Cannot freeze exact installed ordinary payload",failure);}
        OrdinarySavePayload.requireEscrow(a.frozen,a.player.getUUID(),a.session,a.registries);
        a.emitted=emitted; a.checkCurrent();return emitted;
    }
    static void write(PlayerDataStorage storage,Player invocation,CompoundTag tag,Path temporary) throws IOException {
        Attempt a=ACTIVE.get(); if(a==null){NbtIo.writeCompressed(tag,temporary);return;}
        a.checkCurrent();a.trace.writing(storage,invocation,tag,temporary);
        a.requireEmittedUnchanged(tag);
        if (!Files.isRegularFile(temporary,LinkOption.NOFOLLOW_LINKS)) throw a.refuse("Native temporary file is missing or linked");
        NbtIo.writeCompressed(tag,temporary); // Native writer, same actual tag, once.
        a.trace.written(storage,invocation);a.requireEmittedUnchanged(tag);a.checkCurrent();
    }
    static void replace(PlayerDataStorage storage,Player invocation,Path current,Path temporary,Path backup) {
        Attempt a=ACTIVE.get();if(a==null){Util.safeReplaceFile(current,temporary,backup);return;}
        a.checkCurrent();a.trace.replacing(storage,invocation,current,temporary,backup);
        a.requireEmittedUnchanged(a.emitted);
        if(Files.isSymbolicLink(current)||Files.isSymbolicLink(temporary)||Files.isSymbolicLink(backup))
            throw a.refuse("Linked native save paths are outside the admitted storage profile");
        boolean result=Util.safeReplaceOrMoveFile(current,temporary,backup,false); // EXACTLY ONCE, same native algorithm/retries.
        a.trace.replaced(storage,invocation,result);a.checkCurrent();
    }
    static void caught(PlayerDataStorage storage,Player player) {
        Attempt a=ACTIVE.get();if(a==null)return;
        // Do not throw here: preserve the native logging call. The failure remains sticky.
        a.poison(new SurvivalRefusal("PlayerDataStorage.save caught an exception; clean completion is unproved"));
    }
    static void exit(PlayerDataStorage storage,Player player) {
        Attempt a=ACTIVE.get();if(a==null)return;
        a.checkCurrent();a.trace.exit(storage,player);a.exited=true;if(a.installed!=null)a.installed.playerDone();
    }

    private static final class Attempt {
        final Authority authority;
        private final PinProfile profile;
        final Thread owner=Thread.currentThread();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        ServerPlayer player;PlayerDataStorage storage;NativeSurvivalCheckpoint returning;Session session;
        Object provenance;ServerGamePacketListenerImpl connection;ServerLevel level;
        net.minecraft.core.RegistryAccess registries;
        Path root;Object rootFileKey;OrdinarySaveTrace trace;CompoundTag emitted,frozen;boolean exited,validating;
        PinnedNativeEventAdmission events;
        InstalledOrdinarySaveEnvelope installed;
        Attempt(Authority authority,io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.Token token,
                NativeSessionExecutionGate gate){this.authority=authority;profile=new PinProfile(token,gate);}
        void bind() throws IOException,NoSuchAlgorithmException {
            player=Objects.requireNonNull(authority.player());storage=Objects.requireNonNull(authority.storage());
            returning=Objects.requireNonNull(authority.returning());session=returning.session();
            provenance=Objects.requireNonNull(authority.checkpointProvenance());
            if(player.getClass()!=ServerPlayer.class||storage.getClass()!=PlayerDataStorage.class||!player.server.isSameThread()
                    ||session.phase()!=SessionPhase.RETURNING||!returning.binding().player().equals(player.getUUID())
                    ||session.ordinaryPose().dimension().equals(returning.binding().dimension()))
                throw refuse("Exact owner-thread ordinary player/storage/RETURNING binding required");
            root=storage.getPlayerDir().toPath().toAbsolutePath().normalize();
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!root.equals(root.toRealPath()))
                throw refuse("Canonical ordinary player storage directory required");
            rootFileKey=Objects.requireNonNull(Files.readAttributes(root,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey(),
                    "Stable native storage directory identity required");
            connection=Objects.requireNonNull(player.connection);level=player.serverLevel();registries=player.registryAccess();
            trace=new OrdinarySaveTrace(storage,player,authority,session,returning,root,player.getUUID());
            profile.requireSources(player);
            events=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        }
        void checkCurrent() {
            Throwable failed=failure.get();if(failed!=null)throw refuseWithCause("Ordinary save has failed",failed);
            if(Thread.currentThread()!=owner||player==null||trace==null||events==null)throw refuse("Unbound/off-thread native save observer");
            if(validating)throw refuse("Reentrant native save authority validation");
            validating=true;
            try {
                requireIdentities(); profile.requireCurrent(player); events.requireEmpty(PlayerEvent.SaveToFile.class); requireOrdinaryExact();
                authority.requireHeldAndCurrent(returning,provenance);
                trace.check();
                if(failure.get()!=null)throw refuseWithCause("Ordinary save was reentered",failure.get());
                requireIdentities(); profile.requireCurrent(player); events.requireEmpty(PlayerEvent.SaveToFile.class); requireOrdinaryExact();
            } finally { validating=false; }
        }
        void requireIdentities() {
            try {
                var attributes=Files.readAttributes(root,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!attributes.isDirectory()||!root.equals(root.toRealPath())||!rootFileKey.equals(attributes.fileKey()))
                    throw refuse("Native ordinary storage directory was replaced or linked");
            } catch(IOException cause){throw refuseWithCause("Cannot revalidate native ordinary storage directory",cause);}
            trace.current(authority,authority.returning().session(),authority.returning());
            if(authority.player()!=player||authority.storage()!=storage||authority.checkpointProvenance()!=provenance
                    ||!player.server.isSameThread()||player.connection!=connection||player.hasDisconnected()
                    ||player.serverLevel()!=level||player.registryAccess()!=registries||level.getClass()!=ServerLevel.class
                    ||player.server.getLevel(level.dimension())!=level
                    ||!storage.getPlayerDir().toPath().toAbsolutePath().normalize().equals(root)
                    ||!returning.binding().player().equals(player.getUUID())||player.hasAttachments())
                throw refuse("Ordinary native save identity or attachment callback profile changed");
        }
        void requireOrdinaryExact() {
            if(!session.ordinaryEscrow().equals(NativeInventorySnapshot.capture(player,session.ordinaryEscrow().encodedBytes()))
                    ||!session.ordinaryFood().equals(NativeFoodSnapshot.capture(player.getFoodData()))
                    ||!session.ordinaryPose().equals(NativePlayerPoseSnapshot.capture(player)))
                throw refuse("Held ordinary player differs from this RETURNING escrow");
        }
        void requireEmittedUnchanged(CompoundTag tag) {
            BoundedOrdinarySaveNbt.validate(tag);
            if(tag!=emitted||!frozen.equals(tag))throw refuse("Actual emitted native tag changed before completion");
        }
        void requireExited(){trace.check();if(!exited||frozen==null)throw refuse("Missing exact native save exit or serialization evidence");}
        void poison(Throwable cause){failure.compareAndSet(null,cause);if(trace!=null)trace.poison(cause);}
        SurvivalRefusal refuse(String reason){var failure=new SurvivalRefusal(reason);poison(failure);return failure;}
    }
    /** Immutable credentials, not caller-selectable hash authority. Authentication occurs only
     * inside the already retained Attempt, so a caught nested call still poisons that Attempt. */
    private static final class PinProfile {
        private final io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.Token token;
        private final NativeSessionExecutionGate gate;
        private PinProfile(io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.Token token,
                NativeSessionExecutionGate gate){this.token=token;this.gate=gate;}
        private void requireCurrent(ServerPlayer player){
            if((token==null)!=(gate==null))throw new SurvivalRefusal("Incomplete ordinary-save profile credentials");
            if(token!=null)token.requireOrdinarySave(gate,player);
        }
        private String expected(String development,String installed){return token==null?development:installed;}
        private void requireSources(ServerPlayer player) throws IOException,NoSuchAlgorithmException {
            requireCurrent(player); // Before selecting or reading any native resource; no mismatch fallback.
            requireBinary(PlayerDataStorage.class,expected("57ec7a9a8345f6bc1f038c6727d792a275272ae30f3a82a0fcf58945484d7288","96c286bd429ca4c7249c15d033a3a27a481c08de78cbaa8b743944668c6bce21"));
            requireBinary(Util.class,expected("95f40c025e74321bda3e00f1f7f454e67439d11e6a6893a688a9427a42ea2353","5e074737ae8085de47c55795d46449a5b7b31a2e6462421e0e155a816c50a614"));
            requireBinary(NbtIo.class,expected("1b9fc3297688a351bf806eb9d31dd51f5da3f5768e8bb837afeb6dae8e5c3f8c","6c124951f395280964900afe4c0c2a550d4808901c3362519806202fd48024d0"));
            requireBinary(NbtAccounter.class,expected("64a78bfd45cc55a97946bffff55b4e4f78ed179277a091cc31e88704aa668951","aec845812a3662d07164ba0b3c207e6be7bc6a2903f403ebeab8980e4c91f61c"));
            requireBinary(net.neoforged.neoforge.event.EventHooks.class,"f810377f87c95c04bb4de7a7ac68e8f4862f9ca33a34cc74e067e65e2a92d66b");
            requireBinary(net.neoforged.neoforge.attachment.AttachmentHolder.class,"82b876fa98c4b6e08a5991371df3f9cf79037a7253c98ac2e2a9cc87c25291ab");
            requireBinary(net.minecraft.world.entity.Entity.class,expected("6a5a5b7db211b144fe88f3079a0e1d93a0c31db069acab4ac593dce8d144e8cb","4f65b7d5d4628e9f10a6a23e5a0f68814520d8019f4bc001924cd752c00eed84"));
            requireBinary(Player.class,expected("1e68a34a6c5a3513d52cf80b2d2a8e82f890b3cbd3943d7f2fe8940949890747","277a886c7267e82baa4378282e3c892f16338c0a6a779f1cf361a0af7d7b09fd"));
            requireBinary(ServerPlayer.class,expected("d48041f234ec0a2097d1947398b14eb66ef4fb17c9574517d818147ea7df74c2","ec7c90a8b6a771cfd53b5038f377a72314b9efbf2301ed1ce540723b7d19c6d2"));
        }
    }
    private static final class BoundedOrdinarySaveNbtUnchecked {
        static CompoundTag freeze(CompoundTag tag) {
            try{return BoundedOrdinarySaveNbt.freeze(tag);}
            catch(IOException cause){throw refuseWithCause("Cannot freeze actual native save payload",cause);}
        }
    }
    private static SurvivalRefusal refuseWithCause(String reason,Throwable cause){var failure=new SurvivalRefusal(reason);failure.initCause(cause);return failure;}
    private static void requireBinary(Class<?> type,String expected) throws IOException,NoSuchAlgorithmException {
        try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
            if(stream==null||!expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readNBytes(2*1024*1024+1)))))
                throw new SurvivalRefusal("Native save binary differs from pinned profile: "+type.getName());
        }
    }
}
