package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;
import static org.junit.jupiter.api.Assertions.*;

/** Data guards only. Host retirement still requires the actual bound coordinator/store receipt. */
class NativeCheckpointRetirementGuardTest {
    static final UUID OWNER = new UUID(1, 2), PLAYER = new UUID(3, 4), SESSION = new UUID(5, 6);
    static final Limits LIMITS = new Limits(1, 16, 16, 1, 4096);
    static Binding binding;
    static Session session;
    static Boundary boundary;
    @BeforeAll static void bootstrap() {
        var registries = VanillaRegistries.createLookup();
        var empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        var inventory = new NativeInventorySnapshot(Collections.nCopies(41, empty), 0);
        var food = new NativeFoodSnapshot(20, 0, 5, 0);
        binding = new Binding(new UUID(7, 8), "native-retirement-test",
                new WorldIdentity(39, "cubic", "039", "fixture", "native"), "source", "registries", "packs",
                PLAYER, ResourceLocation.parse("earthplatform:survival_carrier"));
        var pose = new NativePlayerPoseSnapshot(binding.dimension(), -17, 8849, -2, 180, 0, 0, 0, 0, 0, true,
                GameType.SURVIVAL, null, new NativePlayerPoseSnapshot.AbilityState(false, false, false, false, true, .05F, .1F));
        session = new Session(SESSION, SessionPhase.ACTIVE, inventory, food, pose);
        boundary = new Boundary(100, inventory, food, pose, List.of(), List.of());
    }
    static NativeSurvivalCheckpoint model(Boundary value, UUID owner, long generation, long sequence) {
        return new NativeSurvivalCheckpoint(LIMITS, binding, owner, generation, sequence,
                Map.of(PLAYER, sequence), Map.of(), value, session);
    }
    static void require(NativeSurvivalCheckpoint model) {
        NativeEffectDeliveryHost.requireExactDurableCapture(model, boundary, binding, session, OWNER, 3, 12);
    }
    @Test void identicalCapturedBoundaryAndProvenancePassTheDataGuard() {
        assertDoesNotThrow(() -> require(model(boundary, OWNER, 3, 12)));
    }
    @Test void structurallyEqualDeserializedBoundaryCannotSubstituteForExactCapture() {
        var copy = new Boundary(boundary.gameTime(), boundary.inventory(), boundary.food(), boundary.pose(), boundary.items(), boundary.scheduledTicks());
        assertEquals(boundary, copy); assertNotSame(boundary, copy);
        assertThrows(SurvivalRefusal.class, () -> require(model(copy, OWNER, 3, 12)));
    }
    @Test void missingDurableOrMissingCaptureRefuses() {
        assertThrows(SurvivalRefusal.class, () -> require(null));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireExactDurableCapture(
                model(boundary, OWNER, 3, 12), null, binding, session, OWNER, 3, 12));
    }
    @Test void foreignOrPreviousOwnerEpochCannotRetireCurrentOwners() {
        assertThrows(SurvivalRefusal.class, () -> require(model(boundary, new UUID(9, 10), 3, 12)));
    }
    @Test void olderAndOvertakingGenerationsOrCommitsCannotImplyExactDurability() {
        assertThrows(SurvivalRefusal.class, () -> require(model(boundary, OWNER, 2, 12)));
        assertThrows(SurvivalRefusal.class, () -> require(model(boundary, OWNER, 4, 12)));
        assertThrows(SurvivalRefusal.class, () -> require(model(boundary, OWNER, 3, 11)));
        assertThrows(SurvivalRefusal.class, () -> require(model(boundary, OWNER, 3, 13)));
    }
    @Test void ordinaryPoseEscrowCannotBeSubstitutedDuringRetirement() {
        var pose = session.ordinaryPose();
        var changedPose = new NativePlayerPoseSnapshot(pose.dimension(), pose.x() + 1, pose.y(), pose.z(), pose.yaw(), pose.pitch(),
                pose.velocityX(), pose.velocityY(), pose.velocityZ(), pose.fallDistance(), pose.onGround(),
                pose.gameMode(), pose.previousGameMode(), pose.abilities());
        var wrongPose = new Session(SESSION, SessionPhase.ACTIVE, session.ordinaryEscrow(), session.ordinaryFood(), changedPose);
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireExactDurableCapture(
                model(boundary, OWNER, 3, 12), boundary, binding, wrongPose, OWNER, 3, 12));
    }
    @Test void sessionPhaseEscrowAndCodecBindingRemainExact() {
        var model = model(boundary, OWNER, 3, 12);
        var wrongPhase = new Session(SESSION, SessionPhase.RETURNING, session.ordinaryEscrow(), session.ordinaryFood(), session.ordinaryPose());
        var wrongFood = new Session(SESSION, SessionPhase.ACTIVE, session.ordinaryEscrow(), new NativeFoodSnapshot(19, 0, 5, 0), session.ordinaryPose());
        var wrongBinding = new Binding(new UUID(8, 7), binding.profileId(), binding.world(), binding.sourceIdentity(),
                binding.registryIdentity(), binding.dataPackIdentity(), binding.player(), binding.dimension());
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireExactDurableCapture(model, boundary, binding, wrongPhase, OWNER, 3, 12));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireExactDurableCapture(model, boundary, binding, wrongFood, OWNER, 3, 12));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireExactDurableCapture(model, boundary, wrongBinding, session, OWNER, 3, 12));
    }
}
