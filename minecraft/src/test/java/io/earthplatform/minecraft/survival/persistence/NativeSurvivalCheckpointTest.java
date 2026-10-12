package io.earthplatform.minecraft.survival.persistence;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.interaction.*;
import io.earthplatform.minecraft.survival.world.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

class NativeSurvivalCheckpointTest {
    static HolderLookup.Provider registries;
    static SurvivalPalette palette;
    static WorldIdentity worldIdentity;
    static Binding binding;
    static final UUID PLAYER = new UUID(1, 2), SESSION = new UUID(3, 4);
    static final CubeCell HIGH = CubeCell.of(-19344520, 7314, -4297490);
    static final Limits LIMITS = new Limits(32, 32, 32, 32, 4 * 1024 * 1024);
    static final NativeSurvivalWorld.Bounds BOUNDS = new NativeSurvivalWorld.Bounds(
            new CubePos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), new CubePos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    @BeforeAll static void setup() {
        registries = VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
        worldIdentity = new WorldIdentity(39, "bounded-native-survival", "first039", "marin-test", palette.identity());
        binding = new Binding(new UUID(5, 6), "marin-survival", worldIdentity, "exact-source-hash", "exact-registry-hash",
                "exact-data-pack-hash", PLAYER, ResourceLocation.parse("earthplatform:survival_carrier"));
    }
    static SurvivalCheckpointCodec codec() { return new SurvivalCheckpointCodec(binding, LIMITS, registries, palette, BOUNDS); }
    static NativeInventorySnapshot inventory() {
        List<NativeStackSnapshot> slots = new ArrayList<>();
        for (int i = 0; i < 41; i++) slots.add(NativeStackSnapshot.capture(ItemStack.EMPTY, registries));
        ItemStack tool = new ItemStack(Items.WOODEN_PICKAXE); tool.setDamageValue(37);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("Survivor"));
        slots.set(0, NativeStackSnapshot.capture(tool, registries));
        slots.set(36, NativeStackSnapshot.capture(new ItemStack(Items.LEATHER_BOOTS), registries));
        slots.set(40, NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG, 17), registries));
        return new NativeInventorySnapshot(slots, 8);
    }
    static NativeFoodSnapshot food() { return new NativeFoodSnapshot(17, 31, 3.5F, 0.125F); }
    static NativePlayerPoseSnapshot ordinaryPose() {
        return pose(ResourceLocation.parse("minecraft:the_nether"), -11000.25, GameType.CREATIVE, null);
    }
    static NativePlayerPoseSnapshot earthPose() {
        return pose(binding.dimension(), 7314.875, GameType.SURVIVAL, GameType.CREATIVE);
    }
    static NativePlayerPoseSnapshot pose(ResourceLocation dimension, double y, GameType mode, GameType previous) {
        return new NativePlayerPoseSnapshot(dimension, -19344520.5, y, -4297490.75,
                -127.25F, 42.5F, 0.125, -0.25, 0.375, 3.5F, false, mode, previous,
                new NativePlayerPoseSnapshot.AbilityState(true, true, true, true, false, 0.07F, 0.12F));
    }
    static Session entering() { return new Session(SESSION, SessionPhase.ENTERING, inventory(), food(), ordinaryPose()); }
    static Boundary boundary() { return new Boundary(1234567, inventory(), food(), earthPose(), List.of(), List.of()); }
    static NativeSurvivalCheckpoint model() {
        var tick = new NativeEffectCodec.ScheduledBlockTick(HIGH, ResourceLocation.parse("minecraft:oak_leaves"), 1234597, -2, 12);
        var vertical = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        var cube = new Cube(7, 3, ImmutableCube.uniform(palette.id(vertical)));
        return new NativeSurvivalCheckpoint(LIMITS, binding, new UUID(9, 10), 4, 11, Map.of(PLAYER, 15L),
                Map.of(HIGH.cube(), cube, CubeCell.of(HIGH.x(), HIGH.y() - 4096, HIGH.z()).cube(), new Cube(2, 1,
                        ImmutableCube.uniform(palette.id(Blocks.AIR.defaultBlockState())))),
                new Boundary(1234567, inventory(), food(), earthPose(), List.of(item()), List.of(tick)), entering());
    }
    static NativeItemEntitySnapshot item() {
        CompoundTag tag = new CompoundTag(); tag.putUUID("UUID", new UUID(11, 12));
        tag.put("Pos", doubles(-19344520.5, 7314.25, -4297490.75));
        tag.put("Motion", doubles(0.1, -0.25, 0.2));
        ListTag rotation = new ListTag(); rotation.add(FloatTag.valueOf(12)); rotation.add(FloatTag.valueOf(-30)); tag.put("Rotation", rotation);
        tag.putShort("Health", (short) 4); tag.putShort("Age", (short) 597); tag.putShort("PickupDelay", (short) 27);
        tag.putInt("Lifespan", 7800); tag.putUUID("Owner", new UUID(13, 14)); tag.putUUID("Thrower", new UUID(15, 16));
        tag.putFloat("FallDistance", 3); tag.putShort("Fire", (short) -20); tag.putShort("Air", (short) 300);
        tag.putBoolean("OnGround", true); tag.putBoolean("Invulnerable", false); tag.putInt("PortalCooldown", 12);
        ItemStack tool = new ItemStack(Items.WOODEN_PICKAXE); tool.setDamageValue(31);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("Dropped tool")); tag.put("Item", tool.save(registries));
        return NativeItemEntitySnapshot.decode(NativeItemEntitySnapshot.write(tag), registries);
    }
    static ListTag doubles(double... numbers) { ListTag list = new ListTag(); for (double number : numbers) list.add(DoubleTag.valueOf(number)); return list; }

    @Test void namedNativeStatesFullInventoryItemsAndTicksRoundtripAtOneBoundary() {
        var codec = codec(); var model = model(); byte[] bytes = codec.encode(model); var restored = codec.decode(bytes);
        assertArrayEquals(bytes, codec.encode(restored)); assertEquals(binding, restored.binding());
        assertEquals(food(), restored.boundary().food()); assertEquals(food(), restored.session().ordinaryFood());
        assertEquals(earthPose(), restored.boundary().pose()); assertEquals(ordinaryPose(), restored.session().ordinaryPose());
        assertEquals(model.captureOwner(), restored.captureOwner()); assertEquals(4, restored.generation());
        assertEquals(1234567, restored.boundary().gameTime()); assertEquals(model.cubes(), restored.cubes());
        assertEquals(model.boundary().inventory(), restored.boundary().inventory());
        assertEquals(37, restored.boundary().inventory().slots().get(0).materialize(registries).getDamageValue());
        assertEquals(17, restored.boundary().inventory().slots().get(40).materialize(registries).getCount());
        assertEquals(model.boundary().items(), restored.boundary().items());
        assertEquals(model.boundary().scheduledTicks(), restored.boundary().scheduledTicks());
        assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains("minecraft:oak_log"));
        assertEquals(7314, restored.boundary().scheduledTicks().getFirst().cell().y());
        assertEquals(13, restored.nextScheduledTickOrder());
        assertEquals(5030, restored.scheduledTicksAt(5000).getFirst().triggerTick());
        assertThrows(ArithmeticException.class, () -> restored.scheduledTicksAt(Long.MAX_VALUE));
        assertNotEquals(HIGH.cube(), CubeCell.of(HIGH.x(), HIGH.y() - 4096, HIGH.z()).cube());
    }
    @Test void identityChangesFailClosedIncludingSaveProfileSourceRegistryPacksAndPlayer() {
        byte[] bytes = codec().encode(model());
        List<Binding> others = List.of(
                new Binding(UUID.randomUUID(), binding.profileId(), worldIdentity, binding.sourceIdentity(), binding.registryIdentity(), binding.dataPackIdentity(), PLAYER, binding.dimension()),
                new Binding(binding.saveId(), "other-profile", worldIdentity, binding.sourceIdentity(), binding.registryIdentity(), binding.dataPackIdentity(), PLAYER, binding.dimension()),
                new Binding(binding.saveId(), binding.profileId(), worldIdentity, "other-source", binding.registryIdentity(), binding.dataPackIdentity(), PLAYER, binding.dimension()),
                new Binding(binding.saveId(), binding.profileId(), worldIdentity, binding.sourceIdentity(), "other-registry", binding.dataPackIdentity(), PLAYER, binding.dimension()),
                new Binding(binding.saveId(), binding.profileId(), worldIdentity, binding.sourceIdentity(), binding.registryIdentity(), "other-packs", PLAYER, binding.dimension()),
                new Binding(binding.saveId(), binding.profileId(), worldIdentity, binding.sourceIdentity(), binding.registryIdentity(), binding.dataPackIdentity(), UUID.randomUUID(), binding.dimension()),
                new Binding(binding.saveId(), binding.profileId(), worldIdentity, binding.sourceIdentity(), binding.registryIdentity(), binding.dataPackIdentity(), PLAYER, ResourceLocation.parse("earthplatform:other_carrier")));
        for (Binding other : others) {
            var codec = new SurvivalCheckpointCodec(other, LIMITS, registries, palette, BOUNDS);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes));
            assertNotEquals(codec().storageIdentity(), codec.storageIdentity());
        }
    }
    @Test void malformedBoundedAndTrailingImagesRefuseWithoutPartialRecovery() {
        var codec = codec(); byte[] bytes = codec.encode(model());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[LIMITS.payloadBytes() + 1]));
        bytes[0] ^= 1; assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes));
        var wrongGeneration = new SurvivalCheckpointStore.Image(new SurvivalCheckpointStore.Limits(1, LIMITS.payloadBytes(), LIMITS.payloadBytes(), 1024), 99,
                Map.of(SurvivalCheckpointCodec.PAYLOAD, codec.encode(model())));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(wrongGeneration));
    }
    @Test void immutableComponentsEscrowAndBytesCannotBeMutatedThroughAliases() {
        var original = model(); var item = original.boundary().items().getFirst(); byte[] bytes = item.encoded(); bytes[0] = 0;
        assertEquals(31, item.stack().materialize(registries).getDamageValue());
        CompoundTag tag = item.tagCopy(); tag.putShort("Age", (short) 0);
        assertEquals(597, item.tagCopy().getShort("Age"));
        assertEquals(new UUID(13, 14), item.tagCopy().getUUID("Owner"));
        assertEquals(new UUID(15, 16), item.tagCopy().getUUID("Thrower")); assertEquals(7800, item.tagCopy().getInt("Lifespan"));
        assertThrows(UnsupportedOperationException.class, () -> original.boundary().inventory().slots().clear());
        var live = original.boundary().inventory().materialize(registries); live.getFirst().setDamageValue(2);
        assertEquals(37, original.boundary().inventory().slots().getFirst().materialize(registries).getDamageValue());
        assertThrows(UnsupportedOperationException.class, () -> original.cubes().clear());
    }
    @Test void nativeItemCodecRefusesLossyClampsMalformedNbtAndDuplicateOwners() {
        var item = item(); CompoundTag motion = item.tagCopy(); motion.put("Motion", doubles(11, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> NativeItemEntitySnapshot.decode(NativeItemEntitySnapshot.write(motion), registries));
        CompoundTag position = item.tagCopy(); position.put("Pos", doubles(0, 20000001, 0));
        assertThrows(IllegalArgumentException.class, () -> NativeItemEntitySnapshot.decode(NativeItemEntitySnapshot.write(position), registries));
        CompoundTag nan = item.tagCopy(); nan.put("Pos", doubles(0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> NativeItemEntitySnapshot.decode(NativeItemEntitySnapshot.write(nan), registries));
        CompoundTag missing = item.tagCopy(); missing.remove("UUID");
        assertThrows(IllegalArgumentException.class, () -> NativeItemEntitySnapshot.decode(NativeItemEntitySnapshot.write(missing), registries));
        assertThrows(IllegalArgumentException.class, () -> NativeItemEntitySnapshot.decode(Arrays.copyOf(item.encoded(), item.encodedBytes() + 1), registries));
        assertThrows(IllegalArgumentException.class, () -> new Boundary(1, inventory(), food(), earthPose(), List.of(item, item), List.of()));
    }
    @Test void duplicateTickAddressesOrOrdersCannotBeRestoredAsIndependentEffects() {
        var one = model().boundary().scheduledTicks().getFirst();
        var sameAddress = new NativeEffectCodec.ScheduledBlockTick(one.cell(), one.block(), one.triggerTick() + 1, 0, 13);
        var sameOrder = new NativeEffectCodec.ScheduledBlockTick(CubeCell.of(0, 0, 0), one.block(), one.triggerTick(), 0, one.subTickOrder());
        assertThrows(IllegalArgumentException.class, () -> new Boundary(1, inventory(), food(), earthPose(), List.of(), List.of(one, sameAddress)));
        assertThrows(IllegalArgumentException.class, () -> new Boundary(1, inventory(), food(), earthPose(), List.of(), List.of(one, sameOrder)));
    }
    @Test void allNativeInventorySlotsAndSelectionAreMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new NativeInventorySnapshot(inventory().slots().subList(0, 40), 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeInventorySnapshot(inventory().slots(), 9));
        ItemStack[] live = new ItemStack[41]; Arrays.fill(live, ItemStack.EMPTY); live[40] = new ItemStack(Items.OAK_LOG, 3);
        var view = new NativeWholeAction.InventoryView() {
            public int size() { return live.length; } public ItemStack getItem(int slot) { return live[slot]; } public int selectedSlot() { return 4; }
        };
        var frozen = NativeInventorySnapshot.capture(view, registries); live[40].grow(1);
        assertEquals(3, frozen.slots().get(40).materialize(registries).getCount()); assertEquals(4, frozen.selected());
        assertThrows(SurvivalRefusal.class, () -> NativeInventorySnapshot.capture(view, registries, 16));
    }
    @Test void nativePayloadAndSerializationBoundsAreCheckedBeforeGrowingCopies() {
        var snapshot = item();
        int exact = NativeSurvivalCheckpoint.checkedBoundaryBytes(inventory(), food(), earthPose(), List.of(snapshot), List.of(), Integer.MAX_VALUE);
        assertEquals(exact, NativeSurvivalCheckpoint.checkedBoundaryBytes(inventory(), food(), earthPose(), List.of(snapshot), List.of(), exact));
        assertThrows(SurvivalRefusal.class, () -> NativeSurvivalCheckpoint.checkedBoundaryBytes(inventory(), food(), earthPose(), List.of(snapshot), List.of(), exact - 1));
        assertThrows(IllegalArgumentException.class, () -> NativeItemEntitySnapshot.write(snapshot.tagCopy(), 16));
        assertThrows(IllegalArgumentException.class, () -> NativeItemEntitySnapshot.write(snapshot.tagCopy(), Integer.MAX_VALUE));
        assertThrows(SurvivalRefusal.class, () -> NativeSurvivalCheckpoint.checkedBoundaryBytes(inventory(), food(), earthPose(), Collections.nCopies(20000, snapshot), List.of(), 4096));
    }
    @Test void snapshotLimitsAndUnsupportedScheduledBlockTypesFailClosed() {
        var smaller = new SurvivalCheckpointCodec(binding, new Limits(1, 0, 0, 1, 4096), registries, palette, BOUNDS);
        assertThrows(IllegalArgumentException.class, () -> smaller.encode(model()));
        var bad = new NativeEffectCodec.ScheduledBlockTick(HIGH, ResourceLocation.parse("minecraft:water"), 1234597, 0, 1);
        var value = new NativeSurvivalCheckpoint(LIMITS, binding, UUID.randomUUID(), 1, 0, Map.of(), Map.of(),
                new Boundary(1234567, inventory(), food(), earthPose(), List.of(), List.of(bad)), entering());
        assertThrows(RuntimeException.class, () -> codec().encode(value));
    }

    @Test void eachSessionPhaseCarriesSeparateExactOrdinaryAndEarthPoses() {
        var original = model();
        for (SessionPhase phase : SessionPhase.values()) {
            var session = new Session(SESSION, phase, inventory(), food(), ordinaryPose());
            var checkpoint = new NativeSurvivalCheckpoint(LIMITS, binding, original.captureOwner(), original.generation(),
                    original.commitSequence(), original.actorSequences(), original.cubes(), original.boundary(), session);
            var restored = codec().decode(codec().encode(checkpoint));
            assertEquals(session, restored.session()); assertEquals(earthPose(), restored.boundary().pose());
            assertEquals(-11000.25, restored.session().ordinaryPose().y());
            assertEquals(7314.875, restored.boundary().pose().y());
            assertNull(restored.session().ordinaryPose().previousGameMode());
            assertEquals(GameType.CREATIVE, restored.boundary().pose().previousGameMode());
            assertNotEquals(restored.session().ordinaryPose().dimension(), restored.boundary().pose().dimension());
            assertEquals(original.cubes(), restored.cubes()); assertEquals(original.actorSequences(), restored.actorSequences());
            assertEquals(original.boundary().items(), restored.boundary().items());
            assertEquals(original.boundary().scheduledTicks(), restored.boundary().scheduledTicks());
        }
    }
    @Test void poseIsMandatoryForEveryPhaseAndBoundaryDimensionMustMatchCarrier() {
        for (SessionPhase phase : SessionPhase.values())
            assertThrows(NullPointerException.class, () -> new Session(SESSION, phase, inventory(), food(), null));
        assertThrows(NullPointerException.class, () -> new Boundary(1, inventory(), food(), null, List.of(), List.of()));
        var foreign = new Boundary(1, inventory(), food(), ordinaryPose(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new NativeSurvivalCheckpoint(LIMITS, binding, UUID.randomUUID(), 1, 0,
                Map.of(), Map.of(), foreign, entering()));
    }
    @Test void versionThreeAndItsStorageIdentityRefuseLegacyPoseFreeImages() throws Exception {
        var codec = codec(); byte[] current = codec.encode(model());
        assertEquals(3, ByteBuffer.wrap(current).getInt(4));
        assertTrue(codec.storageIdentity().startsWith("native-survival-v3:"));
        assertEquals("native-survival-v3:".length() + 64, codec.storageIdentity().length());
        assertEquals(codec.storageIdentity(), codec().storageIdentity());
        byte[] legacy = withoutPose(withoutPose(current, ordinaryPose()), earthPose());
        ByteBuffer.wrap(legacy).putInt(4, 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(legacy));
        for (int version : new int[]{0, 1, 2, 4, Integer.MAX_VALUE}) {
            byte[] wrong = current.clone(); ByteBuffer.wrap(wrong).putInt(4, version);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(wrong));
        }
    }
    @Test void missingMalformedAndTruncatedOrdinaryOrEarthPoseNeverAcquireDefaults() throws Exception {
        byte[] current = codec().encode(model());
        for (NativePlayerPoseSnapshot pose : List.of(ordinaryPose(), earthPose())) {
            byte[] encoded = encodePose(pose); int start = uniqueOffset(current, encoded);
            byte[] missing = withoutPose(current, pose);
            assertThrows(RuntimeException.class, () -> codec().decode(missing));
            byte[] badMode = current.clone(); ByteBuffer.wrap(badMode).putInt(start + encoded.length - 17, 4);
            assertThrows(IllegalArgumentException.class, () -> codec().decode(badMode));
            byte[] badPrevious = current.clone(); ByteBuffer.wrap(badPrevious).putInt(start + encoded.length - 13, -2);
            assertThrows(IllegalArgumentException.class, () -> codec().decode(badPrevious));
            byte[] badFlags = current.clone(); badFlags[start + encoded.length - 9] = 32;
            assertThrows(IllegalArgumentException.class, () -> codec().decode(badFlags));
            byte[] badGround = current.clone(); badGround[start + encoded.length - 18] = 2;
            assertThrows(IllegalArgumentException.class, () -> codec().decode(badGround));
            byte[] badCoordinate = current.clone(); ByteBuffer.wrap(badCoordinate).putDouble(start + 2 + pose.dimension().toString().length(), Double.NaN);
            assertThrows(IllegalArgumentException.class, () -> codec().decode(badCoordinate));
            byte[] truncated = Arrays.copyOf(current, start + encoded.length - 1);
            assertThrows(IllegalArgumentException.class, () -> codec().decode(truncated));
        }
    }
    @Test void exactBoundaryBudgetIncludesPoseAndPreservesAllExistingPayloadCharges() {
        var value = model().boundary(); var tick = value.scheduledTicks().getFirst(); var item = value.items().getFirst();
        int exact = 16 + value.inventory().encodedBytes() + value.food().encodedBytes() + value.pose().encodedBytes()
                + 4 + item.encodedBytes() + 46 + tick.block().toString().length();
        assertEquals(exact, checkedBoundaryBytes(value.inventory(), value.food(), value.pose(), value.items(), value.scheduledTicks(), exact));
        assertThrows(SurvivalRefusal.class, () -> checkedBoundaryBytes(value.inventory(), value.food(), value.pose(), value.items(), value.scheduledTicks(), exact - 1));
        assertThrows(SurvivalRefusal.class, () -> checkedBoundaryBytes(value.inventory(), value.food(), value.pose(), value.items(), value.scheduledTicks(), exact - value.pose().encodedBytes()));
    }
    @Test void bothPosePayloadsArePresentAndOrdinaryDimensionBytesAreChargedExactly() throws Exception {
        var original = model(); byte[] encoded = codec().encode(original);
        byte[] noPoses = withoutPose(withoutPose(encoded, ordinaryPose()), earthPose());
        assertEquals(original.session().ordinaryPose().encodedBytes() + original.boundary().pose().encodedBytes(), encoded.length - noPoses.length);
        var longer = pose(ResourceLocation.parse("earthplatform:ordinary_archive/" + "a".repeat(128)), -11000.25, GameType.CREATIVE, null);
        var session = new Session(SESSION, SessionPhase.ENTERING, inventory(), food(), longer);
        var altered = new NativeSurvivalCheckpoint(LIMITS, binding, original.captureOwner(), original.generation(), original.commitSequence(),
                original.actorSequences(), original.cubes(), original.boundary(), session);
        assertEquals(longer.encodedBytes() - ordinaryPose().encodedBytes(), codec().encode(altered).length - encoded.length);
        assertEquals(longer, codec().decode(codec().encode(altered)).session().ordinaryPose());
    }
    static byte[] encodePose(NativePlayerPoseSnapshot pose) throws IOException {
        var bytes = new ByteArrayOutputStream(); pose.write(new DataOutputStream(bytes)); return bytes.toByteArray();
    }
    static int uniqueOffset(byte[] haystack, byte[] needle) {
        int found = -1;
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            if (Arrays.equals(haystack, start, start + needle.length, needle, 0, needle.length)) {
                assertEquals(-1, found, "Pose fixture must have an unambiguous encoded location"); found = start;
            }
        }
        assertNotEquals(-1, found, "Complete exact pose payload must be present"); return found;
    }
    static byte[] withoutPose(byte[] encoded, NativePlayerPoseSnapshot pose) throws IOException {
        byte[] payload = encodePose(pose); int start = uniqueOffset(encoded, payload);
        byte[] result = new byte[encoded.length - payload.length];
        System.arraycopy(encoded, 0, result, 0, start);
        System.arraycopy(encoded, start + payload.length, result, start, result.length - start); return result;
    }
}
