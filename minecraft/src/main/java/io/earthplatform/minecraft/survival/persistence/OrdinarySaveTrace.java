package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Observation ordering only. Unit tests of this class are not native-file proof. */
final class OrdinarySaveTrace {
    enum Stage { READY, ENTERED, SERIALIZING, SERIALIZED, WRITING, WRITTEN, REPLACING, REPLACED, EXITED, VERIFIED }
    private final Thread owner = Thread.currentThread();
    private final Object storage, player, authority, session, durable;
    private final Path root, target, backup;
    private final String prefix;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private Stage stage = Stage.READY;
    private Path temporary;
    private Object emitted;

    OrdinarySaveTrace(Object storage, Object player, Object authority, Object session, Object durable, Path root, UUID uuid) {
        this.storage=Objects.requireNonNull(storage); this.player=Objects.requireNonNull(player);
        this.authority=Objects.requireNonNull(authority); this.session=Objects.requireNonNull(session);
        this.durable=Objects.requireNonNull(durable); this.root=root.toAbsolutePath().normalize();
        target=this.root.resolve(uuid+".dat"); backup=this.root.resolve(uuid+".dat_old"); prefix=uuid+"-";
    }
    void current(Object authority, Object session, Object durable) {
        check(); if (this.authority != authority || this.session != session || this.durable != durable) reject("Stale native save authority/session/durable permit");
    }
    void enter(Object storage, Object player) { exact(storage,player); advance(Stage.READY,Stage.ENTERED); }
    void serializing(Object storage,Object player) { exact(storage,player); advance(Stage.ENTERED,Stage.SERIALIZING); }
    void serialized(Object storage,Object player,Object emitted) {
        exact(storage,player); advance(Stage.SERIALIZING,Stage.SERIALIZED); this.emitted=Objects.requireNonNull(emitted);
    }
    void writing(Object storage,Object player,Object emitted,Path temporary) {
        exact(storage,player); require(Stage.SERIALIZED);
        Path path=temporary.toAbsolutePath().normalize(); String name=path.getFileName().toString();
        if (this.emitted != emitted || !root.equals(path.getParent()) || !name.startsWith(prefix)
                || !name.endsWith(".dat") || name.length() <= prefix.length()+4 || path.equals(target) || path.equals(backup))
            reject("Native save write tag or temporary path mismatch");
        this.temporary=path;stage=Stage.WRITING;
    }
    void written(Object storage,Object player) { exact(storage,player); advance(Stage.WRITING,Stage.WRITTEN); }
    void replacing(Object storage,Object player,Path target,Path temporary,Path backup) {
        exact(storage,player); require(Stage.WRITTEN);
        if (!this.target.equals(target.toAbsolutePath().normalize()) || !this.temporary.equals(temporary.toAbsolutePath().normalize())
                || !this.backup.equals(backup.toAbsolutePath().normalize())) reject("Native save replacement path mismatch");
        stage=Stage.REPLACING;
    }
    void replaced(Object storage,Object player,boolean result) {
        exact(storage,player);require(Stage.REPLACING);
        if (!result) reject("Native safeReplaceOrMoveFile returned false");stage=Stage.REPLACED;
    }
    void exit(Object storage,Object player) { exact(storage,player);advance(Stage.REPLACED,Stage.EXITED); }
    void verified() { advance(Stage.EXITED,Stage.VERIFIED); }
    void requireVerified() { require(Stage.VERIFIED); }
    Path target() { return target; }
    void poison(Throwable cause) { failure.compareAndSet(null,Objects.requireNonNull(cause)); }
    Throwable failure() { return failure.get(); }
    void check() {
        if (Thread.currentThread() != owner) reject("Ordinary native save called off its owner thread");
        Throwable cause=failure.get();
        if (cause != null) { SurvivalRefusal refusal=new SurvivalRefusal("Ordinary native save observation failed"); refusal.initCause(cause);throw refusal; }
    }
    private void exact(Object storage,Object player) {
        check(); if (this.storage != storage || this.player != player) reject("Native save storage/player identity mismatch or nested save");
    }
    private void advance(Stage from,Stage to) { require(from);stage=to; }
    private void require(Stage expected) { check();if(stage!=expected) reject("Missing, duplicate, nested, or out-of-order native save observation: "+stage+" / "+expected); }
    private void reject(String reason) { SurvivalRefusal refusal=new SurvivalRefusal(reason);poison(refusal);throw refusal; }
}
