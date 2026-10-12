package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.world.level.GameType;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/**
 * Bounded, owner-thread clean-transition protocol. This is NOT a runtime installation or a
 * player.dat/crash transaction. The production Port is intentionally not supplied by this class.
 * In particular, a fake Port test establishes ordering, never native teleport/save correctness.
 *
 * <p>The Port must remain the one delivery host's authority. The controller never constructs a
 * coordinator, calls captureBoundary, accepts external acknowledgements, or restores from a
 * RETURNED tombstone. Failed mutation/settling/save calls are sticky; only an exact retained
 * checkpoint operation has a retry path. An interrupted old session requires separately proved
 * recovery, not a new controller guessing whether ordinary inventory was already restored.
 */
public final class SurvivalSessionTransitions {
    public enum State {
        ORDINARY, DRAINING_ENTRY, ENTERING_CHECKPOINT, SETTLING_EARTH, ACTIVE_CHECKPOINT, ACTIVE,
        DRAINING_ACTIVE_SAVE, ACTIVE_SAVE_CHECKPOINT,
        DRAINING_RETURN, RETURNING_CHECKPOINT, SETTLING_ORDINARY, ORDINARY_SAVE,
        RETURNED_CHECKPOINT, RETURNED, RECOVERY_REQUIRED, FAILED
    }
    public enum CaptureKind { INITIAL_EARTH, RETAINED_EARTH, LIVE_EARTH }
    public enum CheckpointPurpose { TRANSITION, ACTIVE_SAVE }
    public enum Status { PENDING, SUCCEEDED, FAILED }

    public record OrdinaryState(NativeInventorySnapshot inventory, NativeFoodSnapshot food,
                                NativePlayerPoseSnapshot pose) {
        public OrdinaryState { Objects.requireNonNull(inventory); Objects.requireNonNull(food); Objects.requireNonNull(pose); }
        private Session session(UUID id, SessionPhase phase) { return new Session(id, phase, inventory, food, pose); }
    }
    public record Result(Status status, Throwable failure) {
        public Result {
            Objects.requireNonNull(status);
            if ((status == Status.FAILED) != (failure != null)) throw new IllegalArgumentException("Failure must match result status");
        }
        public static Result pending() { return new Result(Status.PENDING, null); }
        public static Result succeeded() { return new Result(Status.SUCCEEDED, null); }
        public static Result failed(Throwable cause) { return new Result(Status.FAILED, Objects.requireNonNull(cause)); }
    }

    /**
     * Only this controller can mint a request. Retained images are object-identical images read
     * from this Port's durable authority; callers cannot supply a replacement Boundary or phase.
     * The production adapter must bind the request object itself to its existing host's private
     * capture/ticket and reject replay, replacement, overtaking and changed action/item ownership.
     */
    public static final class CheckpointRequest {
        private final Object authority;
        private final CheckpointPurpose purpose;
        private final Binding binding;
        private final UUID ownerEpoch;
        private final NativeSurvivalCheckpoint previous;
        private final Session session;
        private final CaptureKind kind;
        private final Boundary retainedBoundary;
        private CheckpointRequest(Object authority, Binding binding, UUID ownerEpoch,
                NativeSurvivalCheckpoint previous, Session session, CaptureKind kind, Boundary retainedBoundary, CheckpointPurpose purpose) {
            this.authority = authority; this.binding = binding; this.ownerEpoch = ownerEpoch; this.purpose=Objects.requireNonNull(purpose);
            this.previous = previous; this.session = session; this.kind = kind; this.retainedBoundary = retainedBoundary;
        }
        public Object authority() { return authority; }
        public CheckpointPurpose purpose() { return purpose; }
        public Binding binding() { return binding; }
        public UUID ownerEpoch() { return ownerEpoch; }
        public NativeSurvivalCheckpoint previous() { return previous; }
        public long previousGeneration() { return previous == null ? 0 : previous.generation(); }
        public Session session() { return session; }
        public CaptureKind kind() { return kind; }
        public Boundary retainedBoundary() { return retainedBoundary; }
    }

    public interface CheckpointOperation {
        /** Genuine coordinator.poll + exact host retirement, not writer-future completion. */
        Result poll();
        /** Pending uncertain executor ownership may still drain; false preserves this held operation. */
        default boolean retryReady(){return true;}
        /** Exact failed frozen image/ticket only: never capture again or swap coordinator. */
        void retryExact();
    }
    public interface PreparedChange {
        /** All stacks/targets prevalidated. Invoke at most once, using the exact durable permit. */
        void applyOnce(NativeSurvivalCheckpoint permit);
        /** Read/settle the same operation; never invoke its native install/restore again. */
        Result pollSettled();
    }
    public interface SaveOperation {
        /** Actual bound ordinary-player save completion. File existence/mtime is not evidence. */
        Result poll();
    }
    public interface Port {
        /** Stable identity of the sole delivery-host/coordinator authority, not a player UUID. */
        Object authority();
        Binding binding();
        UUID ownerEpoch();
        /** Verify exact native player, connection, Levels, registries, owner and unfenced delivery. */
        void requireHealthy();
        /** The identical object currently published by the bound coordinator, or null. */
        NativeSurvivalCheckpoint durable();
        /** Install the hold before returning. No inventory mutation, close/drop callback or teleport. */
        void hold();
        /**
         * Hold must gate movement, action ingress, pickup/drop/use, menus, items/ticks, player save
         * interception and reentrant callbacks. Do not use actions.fenced(), which blocks capture.
         * Return false while already accepted work drains; fail on unsupported/lost ownership.
         */
        boolean heldAndQuiescent();
        /** Read-only exact inventory/food/pose admission; unresolved menus etc. must be refused. */
        OrdinaryState captureOrdinary();
        /** Re-read all three snapshots. The hold must not silently permit stale escrow or restore. */
        void requireOrdinaryExact(Session session);
        /** First visit only: no ordinary contents, items, scheduled work or privileged abilities. */
        Boundary initialEarthBoundary();
        /** Predecode/validate without applying; includes exact target and collision/transport admission. */
        PreparedChange prepareEarth(Boundary plannedEarth);
        PreparedChange prepareOrdinary(Session session);
        /** Revalidate full installed Earth roster/clock-rebased ticks/pose against this held capture. */
        void requireInstalledEarthExact(NativeSurvivalCheckpoint active);
        /** Pure return preflight. False leaves ACTIVE and its resolving controls available.
         * A throwing or reentrant implementation is an authority failure, never a temporary refusal.
         * Existing nonnative Ports retain their prior behavior unless they provide this check. */
        default boolean returnReady() { return true; }
        /** Pure readiness refusal leaves resolving menu clicks available before the hold. */
        default boolean activeSaveReady() { return false; }
        /** Complete live boundary/receipt recheck, privately mints the ACTIVE-save release grant. */
        default void requireSavedEarthExact(NativeSurvivalCheckpoint active) { throw new SurvivalRefusal("ACTIVE-save release is not implemented by this Port"); }
        /**
         * The sole host freezes/submits this capability. Return a retained operation on known
         * checkpoint failures. If this throws, ownership is uncertain and no generic retry exists.
         */
        CheckpointOperation beginCheckpoint(CheckpointRequest request);
        /** Start only after ordinary restoration has settled; bind completion to this exact session. */
        SaveOperation saveOrdinary(Session session, NativeSurvivalCheckpoint returning);
        /** Atomic gate-field release only: no callbacks, native work or throwable work after opening. */
        void release();
    }

    private final Thread owner = Thread.currentThread();
    private final Port port;
    private final Object authority;
    private final Binding binding;
    private final UUID ownerEpoch;
    private State state;
    private boolean advancing;
    private Throwable failure, checkpointFailure;
    private SurvivalRefusal reentrancyFailure;
    private Session session;
    private NativeSurvivalCheckpoint previous, entering, returning, activeSaveBase;
    private CheckpointRequest request;
    private CheckpointOperation checkpoint;
    private PreparedChange change;
    private SaveOperation save;

    public SurvivalSessionTransitions(Port port) {
        this.port = Objects.requireNonNull(port);
        authority = Objects.requireNonNull(external(port::authority)); binding = Objects.requireNonNull(external(port::binding));
        ownerEpoch = Objects.requireNonNull(external(port::ownerEpoch));
        checkPort(); previous = external(port::durable); requireBinding(previous);
        if (previous != null && previous.session().phase() != SessionPhase.RETURNED) {
            state = State.RECOVERY_REQUIRED;
            externalVoid(port::hold); // Never replay an old ENTERING/ACTIVE/RETURNING escrow automatically.
        } else state = State.ORDINARY;
    }
    public State state() { checkOwner(); return state; }
    public Throwable failure() { checkOwner(); return failure; }
    public Throwable checkpointFailure() { checkOwner(); return checkpointFailure; }
    public UUID sessionId() { checkOwner(); return session == null ? null : session.id(); }
    /** Runtime ingress must consult these gates in addition to its exact native authority checks. */
    public boolean allowsEarthActions() { checkOwner(); return !advancing && state == State.ACTIVE; }
    public boolean allowsOrdinaryControls() { checkOwner(); return !advancing && (state == State.ORDINARY || state == State.RETURNED); }

    public void enter() {
        checkCallable();
        if (state != State.ORDINARY && state != State.RETURNED) throw new SurvivalRefusal("An unfinished session cannot be replaced");
        advance(() -> {
            checkPort(); previous = external(port::durable); requireBinding(previous);
            if (previous != null && previous.session().phase() != SessionPhase.RETURNED)
                throw new SurvivalRefusal("Current authority is not a returned tombstone");
            state = State.DRAINING_ENTRY; externalVoid(port::hold);
        });
    }
    public void leave() {
        checkCallable();
        if (state != State.ACTIVE) throw new SurvivalRefusal("Only an active session can begin return");
        final boolean[] refused = {false};
        try {
            advance(() -> {
                checkPort();
                if (!external(port::returnReady)) { refused[0] = true; return; }
                state = State.DRAINING_RETURN; externalVoid(port::hold);
            });
        } catch (RuntimeException | Error error) {
            // A callback/census failure is sticky even though the readiness check preceded hold.
            advancing = true;
            try { externalVoid(port::hold); }
            catch (RuntimeException | Error hold) { if (hold != error) error.addSuppressed(hold); }
            finally { advancing = false; }
            throw error;
        }
        if (refused[0]) throw new SurvivalRefusal("Resolve outstanding native items, scheduled work and menus before return");
    }
    /** Real held save, without changing the session phase or restoring either inventory. */
    public void saveActive() {
        checkCallable();
        if(state!=State.ACTIVE)throw new SurvivalRefusal("Only ACTIVE can request a held gameplay checkpoint");
        final boolean[] refused={false};
        try { advance(() -> {
            checkPort();
            if(!external(port::activeSaveReady)){refused[0]=true;return;}
            activeSaveBase=external(port::durable);requireCurrentSession(activeSaveBase);
            if(activeSaveBase.session().phase()!=SessionPhase.ACTIVE)throw new SurvivalRefusal("ACTIVE-save lost its exact durable phase");
            state=State.DRAINING_ACTIVE_SAVE;externalVoid(port::hold);
        }); } catch(RuntimeException|Error error) {
            advancing=true;
            try{externalVoid(port::hold);}catch(RuntimeException|Error hold){if(hold!=error)error.addSuppressed(hold);}finally{advancing=false;}
            throw error;
        }
        if(refused[0])throw new SurvivalRefusal("Resolve the native menu and accepted work before ACTIVE save");
    }
    /** One owner-tick step; no blocking I/O, busy loop or asynchronous live-native access. */
    public void tick() {
        checkCallable();
        if (state == State.FAILED || state == State.RECOVERY_REQUIRED || state == State.ORDINARY || state == State.RETURNED) return;
        advance(() -> {
            checkPort();
            if (state == State.ACTIVE) return;
            if (!external(port::heldAndQuiescent)) return;
            if (state == State.ENTERING_CHECKPOINT || state == State.ORDINARY_SAVE || state == State.RETURNED_CHECKPOINT)
                externalVoid(() -> port.requireOrdinaryExact(session));
            switch (state) {
                case DRAINING_ENTRY -> prepareEntry();
                case ENTERING_CHECKPOINT, ACTIVE_CHECKPOINT, ACTIVE_SAVE_CHECKPOINT, RETURNING_CHECKPOINT, RETURNED_CHECKPOINT -> pollCheckpoint();
                case DRAINING_ACTIVE_SAVE -> {
                    if(external(port::durable)!=activeSaveBase)throw new SurvivalRefusal("ACTIVE-save previous checkpoint was overtaken");
                    beginCheckpoint(SessionPhase.ACTIVE,CaptureKind.LIVE_EARTH,null,State.ACTIVE_SAVE_CHECKPOINT);
                }
                case SETTLING_EARTH -> {
                    if (succeeded(external(change::pollSettled))) beginCheckpoint(SessionPhase.ACTIVE, CaptureKind.LIVE_EARTH, null, State.ACTIVE_CHECKPOINT);
                }
                case DRAINING_RETURN -> {
                    requireCurrentSession(external(port::durable));
                    change = Objects.requireNonNull(external(() -> port.prepareOrdinary(session)));
                    beginCheckpoint(SessionPhase.RETURNING, CaptureKind.LIVE_EARTH, null, State.RETURNING_CHECKPOINT);
                }
                case SETTLING_ORDINARY -> {
                    if (succeeded(external(change::pollSettled))) {
                        externalVoid(() -> port.requireOrdinaryExact(session));
                        state = State.ORDINARY_SAVE;
                        save = Objects.requireNonNull(external(() -> port.saveOrdinary(session, returning)));
                    }
                }
                case ORDINARY_SAVE -> {
                    if (succeeded(external(save::poll))) {
                        externalVoid(() -> port.requireOrdinaryExact(session));
                        if (external(port::durable) != returning) throw new SurvivalRefusal("Earth-final return authority was replaced during ordinary save");
                        beginCheckpoint(SessionPhase.RETURNED, CaptureKind.RETAINED_EARTH, returning.boundary(), State.RETURNED_CHECKPOINT);
                    }
                }
                default -> throw new IllegalStateException("Unexpected transition state " + state);
            }
        });
    }
    public void retryCheckpoint() {
        checkCallable();
        if (checkpointFailure == null || checkpoint == null || state == State.FAILED)
            throw new SurvivalRefusal("Only the exact retained failed checkpoint can be retried");
        final boolean[] waiting={false};
        advance(() -> {
            checkPort();
            if (!external(port::heldAndQuiescent)) throw new SurvivalRefusal("Checkpoint retry requires the exact quiescent hold");
            if(!external(checkpoint::retryReady)){waiting[0]=true;return;}
            if (state == State.ENTERING_CHECKPOINT || state == State.RETURNED_CHECKPOINT)
                externalVoid(() -> port.requireOrdinaryExact(session));
            externalVoid(checkpoint::retryExact); checkpointFailure = null;
        });
        if(waiting[0])throw new SurvivalRefusal("The exact failed writer has not physically drained yet");
    }
    private void prepareEntry() {
        if (external(port::durable) != previous) throw new SurvivalRefusal("Ordinary entry authority changed while draining");
        var ordinary = Objects.requireNonNull(external(port::captureOrdinary));
        if (ordinary.pose().dimension().equals(binding.dimension())) throw new SurvivalRefusal("Ordinary escrow cannot originate in the Earth carrier");
        UUID id;
        do { id = UUID.randomUUID(); } while (previous != null && previous.session().id().equals(id));
        session = ordinary.session(id, SessionPhase.ENTERING);
        Boundary earth = previous == null ? Objects.requireNonNull(external(port::initialEarthBoundary)) : previous.boundary();
        requireEarth(earth);
        if (previous == null && (earth.inventory().slots().stream().anyMatch(stack -> !stack.isEmpty())
                || !earth.items().isEmpty() || !earth.scheduledTicks().isEmpty()))
            throw new SurvivalRefusal("First Earth visit must start empty-handed without imported item/tick ownership");
        change = Objects.requireNonNull(external(() -> port.prepareEarth(earth)));
        beginCheckpoint(SessionPhase.ENTERING, previous == null ? CaptureKind.INITIAL_EARTH : CaptureKind.RETAINED_EARTH,
                earth, State.ENTERING_CHECKPOINT);
    }
    private void beginCheckpoint(SessionPhase phase, CaptureKind kind, Boundary retained, State waiting) {
        NativeSurvivalCheckpoint base = external(port::durable); requireBinding(base);
        if (phase != SessionPhase.ENTERING) requireCurrentSession(base);
        if (phase == SessionPhase.ENTERING && base != previous || phase == SessionPhase.ACTIVE && base != (waiting==State.ACTIVE_SAVE_CHECKPOINT?activeSaveBase:entering)
                || phase == SessionPhase.RETURNING && base.session().phase() != SessionPhase.ACTIVE
                || phase == SessionPhase.RETURNED && base != returning)
            throw new SurvivalRefusal("Transition lost the exact preceding durable phase");
        Session target = new Session(session.id(), phase, session.ordinaryEscrow(), session.ordinaryFood(), session.ordinaryPose());
        if (phase == SessionPhase.ENTERING || phase == SessionPhase.RETURNED)
            externalVoid(() -> port.requireOrdinaryExact(target));
        request = new CheckpointRequest(authority, binding, ownerEpoch, base, target, kind, retained,
                waiting==State.ACTIVE_SAVE_CHECKPOINT?CheckpointPurpose.ACTIVE_SAVE:CheckpointPurpose.TRANSITION);
        state = waiting; checkpointFailure = null;
        checkpoint = Objects.requireNonNull(external(() -> port.beginCheckpoint(request)));
    }
    private void pollCheckpoint() {
        if (checkpointFailure != null) return;
        Result result = Objects.requireNonNull(external(checkpoint::poll));
        if (result.status() == Status.PENDING) return;
        if (result.status() == Status.FAILED) { checkpointFailure = result.failure(); return; }
        NativeSurvivalCheckpoint value = external(port::durable);
        requireAcknowledgement(value);
        session = value.session(); checkpoint = null; checkpointFailure = null;
        switch (state) {
            case ENTERING_CHECKPOINT -> {
                externalVoid(() -> port.requireOrdinaryExact(session));
                entering = value; state = State.SETTLING_EARTH;
                externalVoid(() -> change.applyOnce(value)); // State advances before potentially reentrant native mutation.
            }
            case ACTIVE_CHECKPOINT -> {
                requireInstalledEarth(value); externalVoid(() -> port.requireInstalledEarthExact(value));
                state = State.ACTIVE; externalVoid(port::release); change = null; entering=null;previous=null;request=null;
            }
            case ACTIVE_SAVE_CHECKPOINT -> {
                externalVoid(() -> port.requireSavedEarthExact(value));
                state=State.ACTIVE;externalVoid(port::release);activeSaveBase=null;request=null;
            }
            case RETURNING_CHECKPOINT -> {
                returning = value; state = State.SETTLING_ORDINARY; externalVoid(() -> change.applyOnce(value));
            }
            case RETURNED_CHECKPOINT -> {
                externalVoid(() -> port.requireOrdinaryExact(session)); state = State.RETURNED; externalVoid(port::release); change = null; save = null;returning=null;previous=null;request=null; }
            default -> throw new IllegalStateException("Unexpected durable checkpoint phase");
        }
    }
    private void requireAcknowledgement(NativeSurvivalCheckpoint value) {
        requireBinding(value);
        if (value == null || value.generation() != Math.incrementExact(request.previousGeneration())
                || !value.captureOwner().equals(ownerEpoch) || !value.session().equals(request.session()))
            throw new SurvivalRefusal("Checkpoint acknowledgement does not name this exact session/owner/generation");
        requireEarth(value.boundary());
        if (request.retainedBoundary() != null && value.boundary() != request.retainedBoundary())
            throw new SurvivalRefusal("Transition replaced its exact retained Earth boundary");
        if (request.kind() == CaptureKind.RETAINED_EARTH) {
            NativeSurvivalCheckpoint base = request.previous();
            if (base == null || request.retainedBoundary() != base.boundary()
                    || value.commitSequence() != base.commitSequence() || !value.actorSequences().equals(base.actorSequences())
                    || !sameEarthCubes(base, value))
                throw new SurvivalRefusal("Retained transition changed Earth authority");
        }
    }
    private void requireInstalledEarth(NativeSurvivalCheckpoint active) {
        Boundary before = entering.boundary(), after = active.boundary();
        if (!before.inventory().equals(after.inventory()) || !before.food().equals(after.food()) || !before.pose().equals(after.pose())
                || !before.items().equals(after.items()) || !entering.scheduledTicksAt(after.gameTime()).equals(after.scheduledTicks())
                || entering.commitSequence() != active.commitSequence() || !entering.actorSequences().equals(active.actorSequences())
                || !sameEarthCubes(entering, active))
            throw new SurvivalRefusal("Earth restoration changed its held immutable image before activation");
    }
    private static boolean sameEarthCubes(NativeSurvivalCheckpoint a, NativeSurvivalCheckpoint b) {
        if (!a.cubes().keySet().equals(b.cubes().keySet())) return false;
        for (var entry : a.cubes().entrySet()) {
            Cube before = entry.getValue(), after = b.cubes().get(entry.getKey());
            if (before.simulationRevision() != after.simulationRevision() || before.blocks()!=after.blocks()
                    || after.durableRevision() < before.durableRevision()) return false;
        }
        return true;
    }
    private void requireCurrentSession(NativeSurvivalCheckpoint value) {
        requireBinding(value);
        if (value == null || !value.session().id().equals(session.id())
                || !value.session().ordinaryEscrow().equals(session.ordinaryEscrow())
                || !value.session().ordinaryFood().equals(session.ordinaryFood()) || !value.session().ordinaryPose().equals(session.ordinaryPose()))
            throw new SurvivalRefusal("Current authority replaced the live session or ordinary escrow");
    }
    private void requireBinding(NativeSurvivalCheckpoint value) {
        if (value != null && !value.binding().equals(binding)) throw new SurvivalRefusal("Foreign checkpoint binding");
    }
    private void requireEarth(Boundary value) {
        var pose = value.pose(); var abilities = pose.abilities();
        if (!pose.dimension().equals(binding.dimension()) || pose.gameMode() != GameType.SURVIVAL
                || abilities.invulnerable() || abilities.flying() || abilities.mayFly() || abilities.instantBuild() || !abilities.mayBuild())
            throw new SurvivalRefusal("Earth transition requires the exact carrier and ordinary Survival abilities");
    }
    private boolean succeeded(Result result) {
        Objects.requireNonNull(result);
        if (result.status() == Status.FAILED) { failure = result.failure(); state = State.FAILED; return false; }
        return result.status() == Status.SUCCEEDED;
    }
    private void checkPort() {
        if (external(port::authority) != authority || !binding.equals(external(port::binding)) || !ownerEpoch.equals(external(port::ownerEpoch)))
            throw new SurvivalRefusal("Session authority/owner binding was replaced");
        externalVoid(port::requireHealthy);
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new SurvivalRefusal("Session transitions require their exact owner thread");
    }
    private void checkCallable() {
        checkOwner();
        if (advancing) {
            if (reentrancyFailure == null) reentrancyFailure = new SurvivalRefusal("Session transitions cannot reenter through native callbacks");
            throw reentrancyFailure;
        }
    }
    private <T> T external(Supplier<T> call) {
        T result = call.get();
        if (reentrancyFailure != null) throw reentrancyFailure;
        return result;
    }
    private void externalVoid(Runnable call) { external(() -> { call.run(); return null; }); }
    private void advance(Runnable operation) {
        advancing = true;
        try { operation.run(); }
        catch (RuntimeException | Error cause) { failure = cause; state = State.FAILED; throw cause; }
        finally { advancing = false; }
    }
}
