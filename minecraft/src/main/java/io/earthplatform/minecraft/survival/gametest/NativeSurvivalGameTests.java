package io.earthplatform.minecraft.survival.gametest;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.interaction.NativeMiningProgress;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.world.CubeCell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.earthplatform.minecraft.survival.gametest.NativeSurvivalTestFixture.require;

/**
 * Headless real-server integration gates, discovered only by NeoForge's GameTest runner.
 * These gates cover preparation, full-coordinate reads, native item drop/tick/landing/pickup
 * and checkpointing. Supplied-inventory capacity/menu comparisons are explicitly labeled
 * components. They do not claim live block-break dispatch, per-packet menu delivery, native
 * owner-release lifecycle or crash recovery. The detached native menu comparison always aborts.
 */
@GameTestHolder("earthplatform")
@PrefixGameTestTemplate(false)
public final class NativeSurvivalGameTests {
    private NativeSurvivalGameTests() {}

    @GameTest(template="native_survival_empty", timeoutTicks=200)
    public static void naturalMarinNativePreparation(GameTestHelper helper) throws IOException {
        try (var fixture = NativeSurvivalTestFixture.create(helper.getLevel())) {
            var anchor = CubeCell.of(MarinSurvivalDefinition.ANCHOR_X, MarinSurvivalDefinition.GROUND_Y,
                    MarinSurvivalDefinition.ANCHOR_Z);
            fixture.admit(anchor.cube());
            require(fixture.level.getBlockState(NativeVanillaPreparation.nativePos(anchor)).is(Blocks.GRASS_BLOCK),
                    "Unmodified NOAA Marin ground is not native grass at actual Y=134");
            var tree = fixture.definition.treesIntersecting(anchor.x()-32, anchor.z()-32, anchor.x()+32, anchor.z()+32)
                    .stream().min(Comparator.comparingLong(value ->
                            Math.abs(value.x()-anchor.x())+Math.abs(value.z()-anchor.z()))).orElseThrow();
            var log = CubeCell.of(tree.x(), tree.groundY()+1, tree.z());
            fixture.admitNeighborhood(log);
            fixture.standNear(log);
            fixture.requireSurvival();
            require(fixture.level.getBlockState(NativeVanillaPreparation.nativePos(log)).is(Blocks.OAK_LOG),
                    "Selected log must originate from the pinned natural generator");
            var original = fixture.world.snapshot(log.cube());
            try (var action = fixture.begin()) {
                var pos = NativeVanillaPreparation.nativePos(log);
                var state = fixture.level.getBlockState(pos);
                float nativeRate = state.getDestroyProgress(fixture.player, fixture.level, pos);
                float preparedRate = fixture.preparation.destroyProgress(fixture.player, fixture.level, log);
                require(Float.floatToIntBits(nativeRate)==Float.floatToIntBits(preparedRate)
                        && nativeRate>0 && nativeRate<1, "Native bare-hand log hardness/progress mismatch");
                var progress = new NativeMiningProgress();
                require(progress.start(log, 0, nativeRate).status()==NativeMiningProgress.Status.PROGRESS,
                        "Survival log must not break instantly");
                long stopTick = Math.max(0, (long)Math.ceil(0.7 / nativeRate)-1);
                require(progress.stop(log, stopTick, nativeRate, true).status()==NativeMiningProgress.Status.READY,
                        "Mining STOP must honor native accumulated-progress threshold");
                var mining = fixture.preparation.mining(fixture.player, log, RandomSource.create(3901));
                var direct = state.getDrops(new LootParams.Builder(fixture.level)
                        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .withParameter(LootContextParams.THIS_ENTITY, fixture.player));
                var reference = direct.stream().filter(stack -> !stack.isEmpty())
                        .map(stack -> NativeStackSnapshot.capture(stack, fixture.level.registryAccess())).toList();
                require(mining.harvestable() && mining.loot().equals(reference),
                        "Natural-log prepared loot differs from direct native block loot");
                require(mining.loot().size()==1, "Native oak-log table must yield one stack");
                ItemStack actualLogLoot = mining.loot().getFirst().materialize(fixture.level.registryAccess());
                require(actualLogLoot.is(Items.OAK_LOG) && actualLogLoot.getCount()==1,
                        "Expected one native log from bare-hand natural-log preparation");
                var planks = fixture.preparation.recipe(fixture.level, 1, 1, List.of(actualLogLoot));
                ItemStack plankOutput = planks.output().materialize(fixture.level.registryAccess());
                require(plankOutput.is(Items.OAK_PLANKS) && plankOutput.getCount()==4,
                        "Native recipe must produce four planks from actual prepared log loot");
                var referenceRecipe = fixture.level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
                        CraftingInput.of(1, 1, List.of(actualLogLoot.copy())), fixture.level).orElseThrow();
                require(planks.id().equals(referenceRecipe.id()) && planks.output().equals(NativeStackSnapshot.capture(
                        referenceRecipe.value().assemble(CraftingInput.of(1, 1, List.of(actualLogLoot.copy())),
                                fixture.level.registryAccess()), fixture.level.registryAccess())),
                        "Prepared recipe differs from loaded native recipe manager");
                var tableInput = new ArrayList<ItemStack>();
                for (int i=0; i<4; i++) tableInput.add(plankOutput.copyWithCount(1));
                var table = fixture.preparation.recipe(fixture.level, 2, 2, tableInput).output()
                        .materialize(fixture.level.registryAccess());
                require(table.is(Items.CRAFTING_TABLE) && table.getCount()==1,
                        "Native 2x2 recipe must produce the crafting table");
                require(fixture.player.getInventory().isEmpty(), "Detached preparation changed live inventory");
                require(!mining.tool().durabilityChanged(), "Bare-hand mining cannot consume tool durability");
                // Intentionally abort: no effect host has delivered a drop or inventory change.
            }
            require(fixture.world.snapshot(log.cube()).equals(original) && fixture.world.commitSequence()==0,
                    "Preparation-only gate changed committed terrain");
            require(fixture.player.getInventory().isEmpty(), "Preparation gate granted starter/progression inventory");
        }
        helper.succeed();
    }

    @GameTest(template="native_survival_empty", timeoutTicks=200)
    public static void fullCoordinateLevelReadsAndTransactionalAliases(GameTestHelper helper) throws IOException {
        try (var fixture = NativeSurvivalTestFixture.create(helper.getLevel())) {
            long x = MarinSurvivalDefinition.ANCHOR_X, z = MarinSurvivalDefinition.ANCHOR_Z;
            var high = CubeCell.of(x, 4230, z); // 4096 + actual Marin ground: no packing/rebase.
            var aliases = List.of(CubeCell.of(x, 134, z), CubeCell.of(x, 2182, z),
                    CubeCell.of(x, 6278, z), CubeCell.of(x, 8326, z));
            fixture.admit(high.cube());
            for (var alias : aliases) fixture.admit(alias.cube());
            fixture.standNear(high);
            var before = fixture.world.snapshot(high.cube());
            try (var action = fixture.begin()) {
                // Synthetic transaction marker is solely an address-routing probe; abort it.
                action.set(high, Blocks.CRAFTING_TABLE.defaultBlockState());
                require(fixture.level.getBlockState(NativeVanillaPreparation.nativePos(high)).is(Blocks.CRAFTING_TABLE),
                        "Native ServerLevel must read exact cubic transaction at Y=4230");
                for (var alias : aliases) {
                    var nativeState = fixture.level.getBlockState(NativeVanillaPreparation.nativePos(alias));
                    require(nativeState == fixture.world.read(alias) && !nativeState.is(Blocks.CRAFTING_TABLE),
                            "Full-coordinate read aliased by 2048 or 4096 blocks at Y="+alias.y());
                }
                require(fixture.player.getY()==4230 && fixture.player.blockPosition().getY()==4230,
                        "Real ServerPlayer coordinates were translated to a low-Y carrier");
            }
            require(fixture.world.snapshot(high.cube()).equals(before) && fixture.world.commitSequence()==0,
                    "Aborted high-Y routing marker escaped its transaction");
        }
        helper.succeed();
    }

    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void committedNaturalLogDropAndCheckpoint(GameTestHelper helper) throws IOException {
        // Keep the complete bounded native wait inside this invocation. try-with-resources
        // physically closes the fixture for every failure; no timeout can orphan a callback.
        try (var fixture=NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var log=fixture.nearestNaturalLog();
            fixture.beginNativeCarrierLoad(log);
            fixture.awaitNativeCarrierLoad();
            fixture.bindNativeDelivery();
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
            var delivered=commitNaturalLogDrop(fixture.level,fixture.player,fixture.world,
                    fixture.preparation,fixture.delivery,log,1);
            var persisted=fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            require(persisted.commitSequence()==delivered.receipt().commitSequence(),
                    "Durable checkpoint must include the exact delivered action");
            require(persisted.boundary().items().size()==1
                    && persisted.boundary().items().getFirst().stack().equals(delivered.expectedDrops().getFirst().stack())
                    && persisted.boundary().items().getFirst().id().equals(delivered.expectedDrops().getFirst().id()),
                    "Durable checkpoint lost the native dropped item identity/full stack");
            require(persisted.cubes().get(log.cube()).blocks().get(log.localIndex())
                    ==fixture.palette.id(Blocks.AIR.defaultBlockState()),"Durable checkpoint lost the explicit air edit");
            require(persisted.boundary().inventory().slots().stream().allMatch(NativeStackSnapshot::isEmpty),
                    "Component drop/checkpoint gate must not grant pickup inventory");
            // Dedicated fixture disposal only: retain all host item references through actual
            // native physical level shutdown. This is not lifecycle-clean owned-item release.
        }
        helper.succeed();
    }

    private record DeliveryResult(NativeSurvivalWorld.CommitReceipt receipt, NativeEffectDeliveryHost.Capture capture,
                  List<NativeEffectCodec.ItemSpawn> expectedDrops) {}

    private static DeliveryResult commitNaturalLogDrop(ServerLevel level, ServerPlayer player, NativeSurvivalWorld world,
            NativeVanillaPreparation preparation, NativeEffectDeliveryHost host, CubeCell log, long sequence) {
        require(level.getBlockState(NativeVanillaPreparation.nativePos(log)).is(Blocks.OAK_LOG),
                "Item-delivery target must be a generated natural log");
        require(player.getInventory().isEmpty(), "First log-delivery gate starts with no inventory");
        Map<CubePos, NativeSurvivalWorld.CubeStamp> expected = new HashMap<>();
        for (var cube : world.ownedPositions()) expected.put(cube, world.stamp(cube));
        var identity = world.actionIdentity(player.getUUID(), sequence);
        var expectedDrops = new ArrayList<NativeEffectCodec.ItemSpawn>();
        NativeSurvivalWorld.CommitReceipt receipt;
        try (var action = host.begin(identity, expected)) {
            var mining = preparation.mining(player, log, action.random());
            require(mining.harvestable() && mining.loot().size()==1, "Native log loot must be harvestable");
            var loot = mining.loot().getFirst().materialize(level.registryAccess());
            require(loot.is(Items.OAK_LOG) && loot.getCount()==1, "Expected exact native natural-log loot");
            require(!mining.tool().durabilityChanged(), "Empty hand must not acquire tool damage");
            action.set(log, Blocks.AIR.defaultBlockState());
            for (int i=0; i<mining.loot().size(); i++) {
                // Exact pinned Block.popResource and ItemEntity velocity formula/draw ordering.
                // Only the source of gameplay entropy is the already-owned action stream.
                var random = action.random();
                double x = log.x()+0.5+Mth.nextDouble(random, -0.25, 0.25);
                double y = log.y()+0.5+Mth.nextDouble(random, -0.25, 0.25)-EntityType.ITEM.getHeight()/2.0;
                double z = log.z()+0.5+Mth.nextDouble(random, -0.25, 0.25);
                double velocityX = random.nextDouble()*0.2-0.1;
                double velocityZ = random.nextDouble()*0.2-0.1;
                // UUID ownership is metadata and must not perturb the gameplay random sequence.
                UUID id = UUID.nameUUIDFromBytes(("native-gametest-drop:"+identity+":"+i).getBytes(StandardCharsets.UTF_8));
                var drop = new NativeEffectCodec.ItemSpawn(id, mining.loot().get(i), x,y,z,
                        velocityX,0.2,velocityZ,0,10);
                expectedDrops.add(drop); action.stage(drop);
            }
            receipt = action.commit();
        }
        var capture = host.captureNativeState();
        require(host.lastAppliedReceipt()==receipt && receipt.commitSequence()==world.commitSequence(),
                "Committed receipt must be the exact delivered owner receipt");
        require(level.getBlockState(NativeVanillaPreparation.nativePos(log)).isAir(), "Committed natural log is not air");
        require(player.getInventory().isEmpty(), "Item delivery must not silently perform pickup");
        require(capture.items().size()==expectedDrops.size(), "Native tracked drop count mismatch");
        for (var expectedDrop : expectedDrops) {
            ItemEntity actual = capture.items().stream().filter(item -> item.getUUID().equals(expectedDrop.id())).findFirst().orElseThrow();
            require(actual.getClass()==ItemEntity.class && level.getEntity(actual.getUUID())==actual,
                    "Drop must be the actual tracked native ItemEntity");
            require(expectedDrop.stack().matches(actual.getItem(),level.registryAccess()), "Native drop stack/components changed");
            require(actual.getX()==expectedDrop.x() && actual.getY()==expectedDrop.y() && actual.getZ()==expectedDrop.z(),
                    "Native drop lost its full position");
            require(actual.getDeltaMovement().x==expectedDrop.velocityX()
                    && actual.getDeltaMovement().y==expectedDrop.velocityY()
                    && actual.getDeltaMovement().z==expectedDrop.velocityZ(), "Native drop velocity changed");
            var nativeData = new net.minecraft.nbt.CompoundTag(); actual.addAdditionalSaveData(nativeData);
            require(nativeData.getShort("Age")==0 && nativeData.getShort("PickupDelay")==10,
                    "Native initial age/pickup delay differs from Block.popResource");
        }
        return new DeliveryResult(receipt,capture,List.copyOf(expectedDrops));
    }

    @GameTest(template="native_survival_empty", timeoutTicks=200)
    public static void dryRouterInitializationFailurePoisonsRealNativeAction(GameTestHelper helper) throws IOException {
        try (var fixture = NativeSurvivalTestFixture.create(helper.getLevel())) {
            var ground = CubeCell.of(MarinSurvivalDefinition.ANCHOR_X, MarinSurvivalDefinition.GROUND_Y,
                    MarinSurvivalDefinition.ANCHOR_Z);
            fixture.admit(ground.cube());
            var original = fixture.world.snapshot(ground.cube());
            var marker = new IllegalStateException("intentional native tick-order supplier failure");
            int[] calls = {0};
            var router = new io.earthplatform.minecraft.survival.host.NativeDryMutationRouter(
                    fixture.level, fixture.actions,
                    new io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission(
                            net.neoforged.neoforge.common.NeoForge.EVENT_BUS),
                    () -> { calls[0]++; throw marker; }, 64);
            try (var action = fixture.begin()) {
                boolean supplierFailed = false;
                try { router.set(action, ground, Blocks.AIR.defaultBlockState(), 0, 0); }
                catch (IllegalStateException failure) { require(failure == marker, "Router replaced supplier failure"); supplierFailed = true; }
                require(supplierFailed && calls[0] == 1
                        && action.state() == io.earthplatform.minecraft.survival.interaction.NativeWholeAction.State.ABORTED,
                        "Caught router initialization failure must poison the whole native action");
                boolean commitRefused = false;
                try { action.commit(); }
                catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { commitRefused = true; }
                require(commitRefused, "Poisoned action remained committable");
                boolean reentryRefused = false;
                try (var unexpected = fixture.begin()) { /* Any successful begin is a failure below. */ }
                catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { reentryRefused = true; }
                require(reentryRefused && fixture.actions.hasCurrentAction()
                        && io.earthplatform.minecraft.survival.interaction.NativeWholeAction.current() == action,
                        "Poisoned scope must retain ownership and forbid replacement until close");
            }
            require(!fixture.actions.hasCurrentAction()
                    && io.earthplatform.minecraft.survival.interaction.NativeWholeAction.current() == null,
                    "Explicit close failed to retire the aborted native scope");
            try (var later = fixture.begin()) {
                require(later.state() == io.earthplatform.minecraft.survival.interaction.NativeWholeAction.State.PREPARING,
                        "A new nonreentrant action could not start after explicit close");
            }
            require(fixture.world.snapshot(ground.cube()).equals(original) && fixture.world.commitSequence() == 0
                    && fixture.player.getInventory().isEmpty(), "Initialization refusal changed native terrain/inventory");
        }
        helper.succeed();
    }

    @GameTest(template="native_survival_empty", timeoutTicks=200)
    public static void loadedFirstLoopMetadataClosureIsReadOnly(GameTestHelper helper) throws IOException {
        try (var fixture = NativeSurvivalTestFixture.create(helper.getLevel())) {
            var recipesBefore = fixture.player.getRecipeBook().toNbt();
            int xpBefore = fixture.player.totalExperience, levelBefore = fixture.player.experienceLevel;
            float fractionBefore = fixture.player.experienceProgress;
            var capability = io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata.capture(
                    fixture.player, new io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata.Limits(
                            4096, 16384, 8192, 65536, 128, 128, 1));
            var summary = capability.summary();
            require(summary.loadedAdvancements() == 1400 && summary.relevantCriteria() == 2724,
                    "Actual loaded metadata census differs from the pinned native profile");
            require(summary.reachableAdvancements().size() == 55 && summary.unlockRecipes().size() == 52,
                    "Actual runtime first-loop/leaf-drop reward closure differs from source census");
            var axe = fixture.level.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.withDefaultNamespace("wooden_axe")).orElseThrow();
            var furnace = fixture.level.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.withDefaultNamespace("furnace")).orElseThrow();
            for (var unlockedButUncraftable : List.of(axe, furnace)) {
                capability.requireUnlockRecipe(unlockedButUncraftable);
                boolean refused = false;
                try { capability.requireCraftedRecipe(unlockedButUncraftable); }
                catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { refused = true; }
                require(refused, "Native unlock metadata improperly authorized an out-of-profile craft");
            }
            capability.requireItems(List.of(new ItemStack(Items.APPLE), new ItemStack(Items.OAK_SAPLING)));
            capability.revalidate();
            require(fixture.player.getInventory().isEmpty() && fixture.world.commitSequence() == 0
                    && recipesBefore.equals(fixture.player.getRecipeBook().toNbt())
                    && fixture.player.totalExperience == xpBefore && fixture.player.experienceLevel == levelBefore
                    && Float.floatToIntBits(fixture.player.experienceProgress) == Float.floatToIntBits(fractionBefore),
                    "Read-only metadata capture changed items, cubic state, recipe book or conserved XP");
        }
        helper.succeed();
    }

    // Component-capacity fixtures deliberately supply inventory and a zero-delay item at setup.
    // They are not empty-handed survival progression and never substitute for natural delay ticks.
    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativePickupPartialCapacityComponent(GameTestHelper helper) throws IOException {
        pickupCapacityComponent(helper, 63, 0, 1);
    }
    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativePickupNoSpaceComponent(GameTestHelper helper) throws IOException {
        pickupCapacityComponent(helper, 64, 0, 0);
    }
    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativePickupDelayComponent(GameTestHelper helper) throws IOException {
        pickupCapacityComponent(helper, -1, 10, 0);
    }
    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativePickupFullAndRepeatedComponent(GameTestHelper helper) throws IOException {
        pickupCapacityComponent(helper, -1, 0, 2);
    }
    private static void pickupCapacityComponent(GameTestHelper helper, int existingLogs, int delay, int expectedTransferred)
            throws IOException {
        try (var fixture = NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var location = fixture.nearestNaturalLog();
            fixture.beginNativeCarrierLoad(location); fixture.awaitNativeCarrierLoad();
            // Fixture positioning overlaps the native pickup envelope; no entity relocation.
            fixture.player.moveTo(location.x() + 0.5, location.y(), location.z() + 1.0, 180, 0);
            if (existingLogs >= 0) {
                // Explicit Earth-side capacity input; the ordinary pre-transfer escrow remains empty.
                for (int slot = 0; slot < 36; slot++) fixture.player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
                fixture.player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, existingLogs));
            }
            fixture.bindNativeDelivery();
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
            UUID sourceId = UUID.randomUUID();
            try (var action = fixture.begin()) {
                action.stage(new NativeEffectCodec.ItemSpawn(sourceId,
                        NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG, 2), fixture.level.registryAccess()),
                        location.x() + 0.5, location.y() + 0.5, location.z() + 0.5, 0, 0, 0, 0, delay));
                action.commit();
            }
            ItemEntity source = fixture.delivery.captureNativeState().items().getFirst();
            require(source.getUUID().equals(sourceId), "Capacity fixture lost source identity");
            var before = io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(source);
            var inventoryBefore = io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player);
            int statBefore = fixture.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP, Items.OAK_LOG);
            long commitBefore = fixture.world.commitSequence();
            var receiptBefore = fixture.delivery.lastAppliedReceipt();
            source.playerTouch(fixture.player); // Actual native entry point; installed mixin owns dispatch.
            if (expectedTransferred == 0) {
                require(fixture.world.commitSequence() == commitBefore && fixture.delivery.lastAppliedReceipt() == receiptBefore,
                        "Native delayed/full-inventory no-transfer path committed an action");
                require(before.equals(io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(source))
                        && inventoryBefore.equals(io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player)),
                        "Native no-transfer pickup changed source/inventory");
            } else {
                var receipt = fixture.delivery.lastAppliedReceipt();
                require(receipt != receiptBefore && receipt.commitSequence() == commitBefore + 1,
                        "Native transfer must have one exact committed receipt");
                var effects = receipt.effects().stream().map(value -> NativeEffectCodec.decode(value, fixture.level.registryAccess())).toList();
                var pickup = effects.stream().filter(NativeEffectCodec.ItemPickup.class::isInstance)
                        .map(NativeEffectCodec.ItemPickup.class::cast).findFirst().orElseThrow();
                require(pickup.amount() == expectedTransferred, "Native transfer quantity differs from component capacity");
                if (expectedTransferred == 1) {
                    // In this exact pinned case Inventory.add moves1 from2 into63, then returns false.
                    // Native playerTouch therefore performs no take/stat callback despite transfer.
                    require(source.isAlive() && !source.isRemoved() && source.getItem().getCount() == 1
                            && fixture.player.getInventory().getItem(0).getCount() == 64 && pickup.takeAmount() == 0,
                            "Partial native false-return transfer/remainder semantics changed");
                    require(effects.stream().noneMatch(NativeEffectCodec.PlayerStat.class::isInstance),
                            "Partial false-return pickup fabricated a native statistic");
                    var expectedTag = before.tagCopy(); expectedTag.put("Item", source.getItem().save(fixture.level.registryAccess()));
                    require(expectedTag.equals(io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(source).tagCopy()),
                            "Partial pickup changed unrelated full native entity data");
                } else {
                    require(source.isRemoved() && source.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED
                            && fixture.player.getInventory().getItem(0).is(Items.OAK_LOG)
                            && fixture.player.getInventory().getItem(0).getCount() == 2 && pickup.takeAmount() == 2,
                            "Full native pickup failed source discard/exact inventory ownership");
                }
                long once = fixture.world.commitSequence();
                source.playerTouch(fixture.player);
                require(fixture.world.commitSequence() == once && fixture.delivery.lastAppliedReceipt() == receipt,
                        "Repeated consumed/no-space pickup minted a duplicate action");
            }
            int expectedStat = statBefore + (expectedTransferred == 2 ? 2 : 0);
            require(fixture.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP, Items.OAK_LOG) == expectedStat,
                    "Actual native pickup statistic differs from pinned full/partial/no-transfer semantics");
            require(fixture.delivery.usage().retainedEntityOwners() == 1,
                    "Native pickup owner was released before durable acknowledgement");
            var persisted = fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            require(persisted.boundary().items().size() == (expectedTransferred == 2 ? 0 : 1)
                    && persisted.boundary().inventory().equals(io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player)),
                    "Pickup checkpoint does not own the exact final native item/inventory state");
            require(fixture.delivery.usage().retainedEntityOwners() == (expectedTransferred == 2 ? 0 : 1)
                    && fixture.lastRetiredOwners == (expectedTransferred == 2 ? 1 : 0) && !fixture.delivery.usage().failedDelivery(),
                    "Durable checkpoint retired a live owner or failed to retire its exactly captured consumed owner");
            require(persisted.session().ordinaryEscrow().slots().stream().allMatch(NativeStackSnapshot::isEmpty),
                    "Earth-side component inventory overwrote ordinary pre-transfer escrow");
            // Any still-live native item owner remains retained through physical level closure.
        }
        helper.succeed();
    }

    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void naturalLogNativeDelayGravityLandingPickupAndCheckpoint(GameTestHelper helper) throws IOException {
        try (var fixture = NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var log = fixture.nearestNaturalLog();
            fixture.beginNativeCarrierLoadForDryItemTicks(log); fixture.awaitNativeCarrierLoad(); fixture.bindNativeDelivery();
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
            var delivered = commitNaturalLogDrop(fixture.level, fixture.player, fixture.world,
                    fixture.preparation, fixture.delivery, log, 1);
            ItemEntity source = delivered.capture().items().getFirst();
            // Test pose at the genuinely removed log; the item keeps its original drop position,
            // velocity, age and PickupDelay10. This does not claim native player movement packets.
            fixture.player.moveTo(log.x() + 0.5, log.y(), log.z() + 0.5, 180, 0);
            require(fixture.player.getInventory().isEmpty(), "Natural pickup must begin empty-handed");
            var beforeDelayedTouch = io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(source);
            long beforeDelayCommit = fixture.world.commitSequence();
            source.playerTouch(fixture.player);
            require(fixture.world.commitSequence() == beforeDelayCommit
                    && beforeDelayedTouch.equals(io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(source))
                    && fixture.player.getInventory().isEmpty(), "Native initial pickup delay did not prevent early transfer");
            int originalTicks = source.tickCount, ticks = 0;
            boolean fell = false, landed = false;
            double previousY = source.getY();
            while (ticks < 40) {
                long previousCommit = fixture.world.commitSequence();
                int previousTickCount = source.tickCount;
                fixture.level.tickNonPassenger(source); // Genuine native outer tick through installed ownership hook.
                ticks++;
                var receipt = fixture.delivery.lastAppliedReceipt();
                require(receipt.commitSequence() == previousCommit + 1 && source.tickCount == previousTickCount + 1,
                        "Native tick did not run exactly once under one accepted receipt");
                var image = io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(source);
                require(image.tagCopy().getShort("Age") == ticks
                        && image.tagCopy().getShort("PickupDelay") == Math.max(0, 10 - ticks)
                        && delivered.expectedDrops().getFirst().stack().equals(image.stack()),
                        "Native original delay/age/stack semantics changed during gravity");
                require(!io.earthplatform.minecraft.survival.interaction.host.NativeItemTickReadScope.permits(fixture.binding),
                        "Accepted native tick left its private read capability live");
                fell |= source.getY() < previousY - 1.0E-10; previousY = source.getY();
                landed |= source.onGround();
                if (ticks >= 10 && source.onGround()) break;
            }
            require(ticks >= 10 && ticks < 40 && source.tickCount == originalTicks + ticks && fell && landed && source.onGround(),
                    "Original natural drop did not complete native gravity/landing and its full pickup delay");
            require(source.mainSupportingBlockPos.isPresent()
                    && fixture.level.getBlockState(source.mainSupportingBlockPos.get()).is(Blocks.GRASS_BLOCK),
                    "Native landing must use the actual generated grass support, not an empty carrier floor");
            require(fixture.player.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(source.getBoundingBox()),
                    "Naturally moved drop escaped the fixture player's native pickup envelope");
            long tickCommit = fixture.world.commitSequence();
            int statBefore = fixture.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP, Items.OAK_LOG);
            source.playerTouch(fixture.player);
            var pickup = fixture.delivery.lastAppliedReceipt();
            require(pickup.commitSequence() == tickCommit + 1 && source.isRemoved()
                    && source.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED,
                    "Native delayed natural-log pickup was not conserved and discarded exactly once");
            require(fixture.player.getInventory().getItem(0).is(Items.OAK_LOG)
                    && fixture.player.getInventory().getItem(0).getCount() == 1
                    && fixture.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP, Items.OAK_LOG) == statBefore + 1,
                    "Actual native pickup did not place the earned log/stat in empty native inventory");
            for (int slot = 1; slot < 41; slot++) require(fixture.player.getInventory().getItem(slot).isEmpty(),
                    "Natural pickup granted unrelated inventory");
            source.playerTouch(fixture.player);
            require(fixture.delivery.lastAppliedReceipt() == pickup && fixture.world.commitSequence() == pickup.commitSequence(),
                    "Repeated natural-log touch duplicated a consumed owner");
            require(fixture.delivery.usage().retainedEntityOwners() == 1,
                    "Natural pickup owner was released before its actual durable acknowledgement");
            var persisted = fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            require(persisted.boundary().items().isEmpty()
                    && persisted.boundary().inventory().equals(io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player))
                    && persisted.boundary().inventory().slots().getFirst().matches(new ItemStack(Items.OAK_LOG), fixture.level.registryAccess())
                    && persisted.commitSequence() == pickup.commitSequence(),
                    "Natural delay/tick/pickup checkpoint lost the final earned inventory or ownership boundary");
            require(fixture.delivery.usage().retainedEntityOwners() == 0 && fixture.lastRetiredOwners == 1
                    && !fixture.delivery.usage().failedDelivery(),
                    "Natural pickup did not retire exactly its durably captured consumed owner");
            require(persisted.boundary().pose().equals(io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot.capture(fixture.player))
                    && persisted.session().ordinaryPose().dimension().equals(helper.getLevel().dimension().location()),
                    "Checkpoint lost actual Earth pose or original ordinary-level pose escrow");
        }
        helper.succeed();
    }

    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativeItemTickEntryPointRefusalsDoNotLeakReadScope(GameTestHelper helper) throws IOException {
        try (var fixture = NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var log = fixture.nearestNaturalLog();
            fixture.beginNativeCarrierLoadForDryItemTicks(log); fixture.awaitNativeCarrierLoad(); fixture.bindNativeDelivery();
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
            ItemEntity source = commitNaturalLogDrop(fixture.level, fixture.player, fixture.world,
                    fixture.preparation, fixture.delivery, log, 1).capture().items().getFirst();
            var retained = fixture.binding.view();
            var pos = NativeVanillaPreparation.nativePos(log);
            // Explicit unregistered native component for the inverse foreign-receiver refusal.
            var ordinary = new ItemEntity(helper.getLevel(), log.x() + 0.5, log.y() + 0.5, log.z() + 0.5,
                    new ItemStack(Items.OAK_LOG), 0, 0, 0);
            for (int attempt = 0; attempt < 3; attempt++) {
                ItemEntity target = attempt == 2 ? ordinary : source;
                var before = io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(target);
                int ticks = target.tickCount; long commits = fixture.world.commitSequence();
                double xo = target.xo, yo = target.yo, zo = target.zo;
                double xOld = target.xOld, yOld = target.yOld, zOld = target.zOld;
                float yRotO = target.yRotO, xRotO = target.xRotO;
                boolean refused = false;
                try {
                    if (attempt == 0) source.tick(); // Forbidden bypass of the owned native outer tick.
                    else if (attempt == 1) helper.getLevel().tickNonPassenger(source); // Bound source -> ordinary receiver.
                    else fixture.level.tickNonPassenger(ordinary); // Ordinary source -> bound receiver.
                } catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { refused = true; }
                require(refused && before.equals(io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot.capture(target))
                        && target.tickCount == ticks && fixture.world.commitSequence() == commits
                        && target.xo == xo && target.yo == yo && target.zo == zo
                        && target.xOld == xOld && target.yOld == yOld && target.zOld == zOld
                        && target.yRotO == yRotO && target.xRotO == xRotO,
                        "Rejected native tick entry changed full state, old pose, tickCount or commit ownership");
                require(!io.earthplatform.minecraft.survival.interaction.host.NativeItemTickReadScope.permits(fixture.binding),
                        "Rejected native tick entry leaked an accepted read capability");
                boolean scopeReadRefused = false;
                try { io.earthplatform.minecraft.survival.interaction.host.NativeItemTickReadScope.read(fixture.binding, pos); }
                catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { scopeReadRefused = true; }
                require(scopeReadRefused && retained.getBlockState(pos).isAir(),
                        "Retained BlockGetter bypassed revocation or stopped using ordinary committed cubic state");
            }
            fixture.level.tickNonPassenger(source);
            require(!io.earthplatform.minecraft.survival.interaction.host.NativeItemTickReadScope.permits(fixture.binding),
                    "Successful native tick leaked its scoped read capability");
            // A retained view must use the next real transaction, never a prior sealed snapshot.
            Map<CubePos, NativeSurvivalWorld.CubeStamp> expected = new HashMap<>();
            expected.put(log.cube(), fixture.world.stamp(log.cube()));
            var identity = fixture.world.actionIdentity(fixture.player.getUUID(), fixture.world.nextActionSequence(fixture.player.getUUID()));
            try (var action = fixture.delivery.begin(identity, expected)) {
                action.set(log, Blocks.CRAFTING_TABLE.defaultBlockState());
                require(retained.getBlockState(pos).is(Blocks.CRAFTING_TABLE), "Retained view reused an expired sealed tick image");
                // Aborted address/read probe, not a granted table or a gameplay mutation.
            }
            require(retained.getBlockState(pos).isAir(), "Aborted retained-view marker escaped its action");
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
        }
        helper.succeed();
    }

    // Explicitly supplied component inputs compare native 2x2 semantics. This gate always aborts
    // the detached action and does not claim earned crafting progression or packet dispatch.
    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativeInventoryMenuDetachedComparisonComponent(GameTestHelper helper) throws IOException {
        try (var reference = NativeSurvivalTestFixture.create(helper.getLevel());
                var detached = NativeSurvivalTestFixture.create(helper.getLevel())) {
            reference.player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            detached.player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            reference.requireSurvival(); detached.requireSurvival();
            var referenceMetadata = menuMetadata(reference.player);
            var metadata = menuMetadata(detached.player);
            var recipeAdmission = menuRecipeAdmission(detached.player, metadata);
            require(recipeAdmission.recipeCount() == 902, "Native crafting candidate census changed");
            var before = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(detached.player);
            require(before.equals(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(reference.player)),
                    "Native reference and detached component setup differ");
            var recipeBookBefore = detached.player.getRecipeBook().toNbt();
            var foodBefore = io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot.capture(detached.player.getFoodData());
            int totalXp = detached.player.totalExperience, xpLevel = detached.player.experienceLevel;
            float fraction = detached.player.experienceProgress;
            drainNativeMenuPackets(reference); drainNativeMenuPackets(detached);
            var script = nativeFirstLoop2x2Clicks();
            var referenceSteps = new ArrayList<io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.Data>();
            for (var click : script) {
                // No replacement matcher/result/click implementation is used on this native reference.
                reference.player.inventoryMenu.clicked(click.slot(), click.button(), click.type(), reference.player);
                referenceSteps.add(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(reference.player));
            }
            referenceMetadata.revalidate();
            var nativePackets = drainNativeMenuPackets(reference);
            io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Result prepared;
            try (var action = detached.begin()) {
                prepared = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.prepareScript(
                        action, detached.player, metadata, recipeAdmission, script, menuPreparationLimits());
                require(action.state() == io.earthplatform.minecraft.survival.interaction.NativeWholeAction.State.ABORTED,
                        "Successful detached menu comparison retained commit authority");
                boolean refused = false;
                try { action.commit(); } catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { refused = true; }
                require(refused, "Abort-only native menu comparison could commit");
            }
            require(prepared.before().equals(before) && prepared.steps().equals(referenceSteps)
                    && prepared.after().equals(referenceSteps.getLast()),
                    "Detached native 2x2 inventory/grid/cursor/result/state/remote vectors differ from reference");
            var packetNotices = prepared.notices().stream()
                    .filter(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.SlotPacket.class::isInstance)
                    .map(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.SlotPacket.class::cast).toList();
            require(packetNotices.equals(nativePackets), "Detached native result packet journal differs from actual native reference packets");
            require(drainNativeMenuPackets(detached).isEmpty(), "Detached menu sent a live result packet");
            var pressurePlate = net.minecraft.resources.ResourceLocation.withDefaultNamespace("oak_pressure_plate");
            require(prepared.steps().stream().anyMatch(step -> pressurePlate.equals(step.recipeUsed())
                    && step.result().materialize(detached.level.registryAccess()).is(Items.OAK_PRESSURE_PLATE)),
                    "Native table construction never traversed its genuine pressure-plate intermediate preview");
            var stats = new HashMap<net.minecraft.resources.ResourceLocation, Integer>();
            var crafted = new ArrayList<net.minecraft.resources.ResourceLocation>();
            var unlocked = new ArrayList<net.minecraft.resources.ResourceLocation>();
            for (var notice : prepared.notices()) {
                if (notice instanceof io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Statistic statistic) {
                    require(statistic.stat().category().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("crafted")),
                            "Unexpected native crafting statistic category");
                    stats.merge(statistic.stat().value(), statistic.stat().amount(), Integer::sum);
                } else if (notice instanceof io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Crafted item) {
                    crafted.add(item.recipe());
                    require(item.input().size() == 4, "Native crafted notice lost the exact 2x2 input vector");
                } else if (notice instanceof io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Unlocked item)
                    unlocked.addAll(item.recipes());
            }
            var expectedCrafts = List.of("oak_planks", "oak_planks", "stick", "crafting_table").stream()
                    .map(net.minecraft.resources.ResourceLocation::withDefaultNamespace).toList();
            require(crafted.equals(expectedCrafts) && unlocked.equals(expectedCrafts),
                    "Native crafted/unlock notice order differs from the four actual result takes");
            require(!crafted.contains(pressurePlate) && !unlocked.contains(pressurePlate),
                    "Derived native pressure-plate preview leaked a craft/unlock callback");
            for (var item : List.of(Items.OAK_PLANKS, Items.STICK, Items.CRAFTING_TABLE)) {
                var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
                int expected = item == Items.OAK_PLANKS ? 8 : item == Items.STICK ? 4 : 1;
                require(stats.getOrDefault(key, 0) == expected
                        && reference.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED, item) == expected
                        && detached.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED, item) == 0,
                        "Native crafted amount differs or escaped detached comparison");
                require(reference.player.getRecipeBook().contains(reference.server.getRecipeManager().byKey(key).orElseThrow()),
                        "Actual native reference did not unlock its crafted recipe");
            }
            for (int slot = 0; slot < 41; slot++) {
                ItemStack actual = prepared.after().inventory().get(slot).materialize(detached.level.registryAccess());
                if (slot == 1) require(actual.is(Items.OAK_PLANKS) && actual.getCount() == 2, "Expected two native remaining planks");
                else if (slot == 2) require(actual.is(Items.STICK) && actual.getCount() == 4, "Expected four native sticks");
                else if (slot == 3) require(actual.is(Items.CRAFTING_TABLE) && actual.getCount() == 1, "Expected one native crafting table");
                else require(actual.isEmpty(), "Unexpected native first-loop component output slot");
            }
            require(prepared.after().grid().stream().allMatch(NativeStackSnapshot::isEmpty)
                    && prepared.after().result().isEmpty() && prepared.after().carried().isEmpty(),
                    "Native reference script did not resolve all grid/result/cursor ownership");
            require(before.equals(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(detached.player))
                    && recipeBookBefore.equals(detached.player.getRecipeBook().toNbt())
                    && foodBefore.equals(io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot.capture(detached.player.getFoodData()))
                    && detached.player.totalExperience == totalXp && detached.player.experienceLevel == xpLevel
                    && Float.floatToIntBits(detached.player.experienceProgress) == Float.floatToIntBits(fraction)
                    && detached.world.commitSequence() == 0,
                    "Detached comparison changed the live native menu, metadata, food, XP or cubic state");
        }
        helper.succeed();
    }

    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativeInventoryMenuUnapprovedTakeAndClickRefusalsComponent(GameTestHelper helper) throws IOException {
        try (var fixture = NativeSurvivalTestFixture.create(helper.getLevel())) {
            fixture.player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
            var metadata = menuMetadata(fixture.player);
            var recipes = menuRecipeAdmission(fixture.player, metadata);
            var before = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(fixture.player);
            var bookBefore = fixture.player.getRecipeBook().toNbt();
            var pickup = net.minecraft.world.inventory.ClickType.PICKUP;
            var prefix = List.of(menuClick(36, 0), menuClick(1, 1), menuClick(2, 1));
            try (var action = fixture.begin()) {
                var preview = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.prepareScript(
                        action, fixture.player, metadata, recipes, prefix, menuPreparationLimits());
                require(preview.after().result().materialize(fixture.level.registryAccess()).is(Items.OAK_PRESSURE_PLATE)
                        && preview.notices().stream().allMatch(
                                io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.SlotPacket.class::isInstance),
                        "Unapproved pure native preview was refused or granted metadata");
            }
            var illegalScripts = new ArrayList<List<io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Click>>();
            var takePressurePlate = new ArrayList<>(prefix); takePressurePlate.add(menuClick(0, 0));
            illegalScripts.add(List.copyOf(takePressurePlate));
            for (var mode : net.minecraft.world.inventory.ClickType.values()) if (mode != pickup)
                illegalScripts.add(List.of(new io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Click(36, 0, mode)));
            for (int slot : new int[]{-999, -1, 5, 8, 45, 46}) illegalScripts.add(List.of(menuClick(slot, 0)));
            illegalScripts.add(List.of(menuClick(36, 2)));
            for (var script : illegalScripts) {
                try (var action = fixture.begin()) {
                    boolean refused = false;
                    try { io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.prepareScript(
                            action, fixture.player, metadata, recipes, script, menuPreparationLimits()); }
                    catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { refused = true; }
                    require(refused && action.state() == io.earthplatform.minecraft.survival.interaction.NativeWholeAction.State.ABORTED,
                            "Unsupported native take/click did not visibly refuse and poison");
                    boolean commitRefused = false;
                    try { action.commit(); } catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { commitRefused = true; }
                    require(commitRefused, "Caught menu refusal retained commit authority");
                }
                require(before.equals(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(fixture.player))
                        && bookBefore.equals(fixture.player.getRecipeBook().toNbt()) && fixture.world.commitSequence() == 0,
                        "Caught menu refusal changed live conservation/metadata state");
            }
        }
        helper.succeed();
    }

    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativeInventoryMenuWrongPlayerCapabilityRefusesComponent(GameTestHelper helper) throws IOException {
        try (var first = NativeSurvivalTestFixture.create(helper.getLevel());
                var second = NativeSurvivalTestFixture.create(helper.getLevel())) {
            first.player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            second.player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
            var firstMetadata = menuMetadata(first.player); var secondMetadata = menuMetadata(second.player);
            var firstRecipes = menuRecipeAdmission(first.player, firstMetadata);
            var secondRecipes = menuRecipeAdmission(second.player, secondMetadata);
            var firstBefore = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(first.player);
            var secondBefore = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(second.player);
            var firstBook = first.player.getRecipeBook().toNbt(); var secondBook = second.player.getRecipeBook().toNbt();
            drainNativeMenuPackets(first); drainNativeMenuPackets(second);
            for (int mismatch = 0; mismatch < 3; mismatch++) {
                try (var action = (mismatch == 2 ? second : first).begin()) {
                    boolean refused = false;
                    try { io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.prepareScript(
                            action, first.player, mismatch == 0 ? secondMetadata : firstMetadata,
                            mismatch == 1 ? secondRecipes : firstRecipes, List.of(menuClick(36, 0)), menuPreparationLimits()); }
                    catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) {
                        refused = true;
                        require(expected.getMessage().contains(mismatch == 2 ? "exact level/actor action binding" : "another player"),
                                "Wrong-player capability failed after its intended pre-construction identity boundary");
                    }
                    require(refused && action.state() == io.earthplatform.minecraft.survival.interaction.NativeWholeAction.State.ABORTED,
                            "Foreign menu capability/action was admitted or not poisoned");
                    boolean commitRefused = false;
                    try { action.commit(); } catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { commitRefused = true; }
                    require(commitRefused, "Foreign-capability refusal retained commit authority");
                }
            }
            require(firstBefore.equals(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(first.player))
                    && secondBefore.equals(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(second.player))
                    && firstBook.equals(first.player.getRecipeBook().toNbt()) && secondBook.equals(second.player.getRecipeBook().toNbt())
                    && first.world.commitSequence() == 0 && second.world.commitSequence() == 0
                    && drainNativeMenuPackets(first).isEmpty() && drainNativeMenuPackets(second).isEmpty(),
                    "Wrong-player capability changed either real player, menu, recipe book, packets or world");
        }
        helper.succeed();
    }

    private static io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata menuMetadata(ServerPlayer player) {
        return io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata.capture(player,
                new io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata.Limits(4096, 16384, 8192, 65536, 128, 128, 1));
    }
    private static io.earthplatform.minecraft.survival.interaction.host.NativeCraftingRecipeAdmission menuRecipeAdmission(ServerPlayer player,
            io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata metadata) {
        return io.earthplatform.minecraft.survival.interaction.host.NativeCraftingRecipeAdmission.capture(player, metadata,
                new io.earthplatform.minecraft.survival.interaction.host.NativeCraftingRecipeAdmission.Limits(1024, 16384, 16, 131072, 4*1024*1024, 131072));
    }
    private static io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Limits menuPreparationLimits() {
        return new io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Limits(64, 256, 256*1024, 4*1024*1024);
    }
    private static io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Click menuClick(int slot, int button) {
        return new io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Click(slot, button, net.minecraft.world.inventory.ClickType.PICKUP);
    }
    private static List<io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Click> nativeFirstLoop2x2Clicks() {
        return List.of(menuClick(36,0), menuClick(1,1), menuClick(36,0), menuClick(0,0), menuClick(37,0),
                menuClick(36,0), menuClick(1,0), menuClick(0,0), menuClick(37,0),
                menuClick(37,0), menuClick(1,1), menuClick(3,1), menuClick(37,0), menuClick(0,0), menuClick(38,0),
                menuClick(37,0), menuClick(1,1), menuClick(2,1), menuClick(3,1), menuClick(4,1),
                menuClick(37,0), menuClick(0,0), menuClick(39,0));
    }
    private static List<io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.SlotPacket>
            drainNativeMenuPackets(NativeSurvivalTestFixture fixture) {
        fixture.channel.runPendingTasks();
        var packets = new ArrayList<io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.SlotPacket>();
        for (Object packet; (packet = fixture.channel.readOutbound()) != null;) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket slot && slot.getContainerId() == 0 && slot.getSlot() == 0)
                packets.add(new io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.SlotPacket(
                        slot.getContainerId(), slot.getStateId(), slot.getSlot(), NativeStackSnapshot.capture(slot.getItem(), fixture.level.registryAccess())));
            io.netty.util.ReferenceCountUtil.release(packet);
        }
        return List.copyOf(packets);
    }

    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativeConsumedOwnerRetirementRequiresExactDurableCaptureComponent(GameTestHelper helper) throws IOException {
        try (var fixture = NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var location = fixture.nearestNaturalLog();
            fixture.beginNativeCarrierLoad(location); fixture.awaitNativeCarrierLoad();
            fixture.player.moveTo(location.x()+0.5, location.y(), location.z()+1.0, 180, 0);
            fixture.bindNativeDelivery();
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
            require(fixture.lastRetiredOwners == 0 && fixture.delivery.usage().retainedEntityOwners() == 0,
                    "Empty entering checkpoint fabricated a native retirement");
            // Two supplied zero-delay native drops are lifecycle component inputs, not earned loot.
            UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
            try (var action = fixture.begin()) {
                for (UUID id : List.of(firstId, secondId)) action.stage(new NativeEffectCodec.ItemSpawn(id,
                        NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG), fixture.level.registryAccess()),
                        location.x()+0.5, location.y()+0.5, location.z()+0.5, 0, 0, 0, 0, 0));
                action.commit();
            }
            var sources = fixture.delivery.captureNativeState().items();
            ItemEntity first = sources.stream().filter(item -> item.getUUID().equals(firstId)).findFirst().orElseThrow();
            ItemEntity second = sources.stream().filter(item -> item.getUUID().equals(secondId)).findFirst().orElseThrow();
            first.playerTouch(fixture.player);
            require(first.isRemoved() && !second.isRemoved() && fixture.delivery.usage().retainedEntityOwners() == 2,
                    "Native pickup did not retain its consumed owner until durable acknowledgement");
            long capturedCommit = fixture.world.commitSequence();
            fixture.delivery.captureCheckpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            var coordinator = fixture.checkpointCoordinator();
            var ticket = coordinator.pendingTicket().orElseThrow();
            require(ticket.commitSequence() == capturedCommit, "Host checkpoint froze a different native commit");
            // Genuine later native action while the exact earlier checkpoint is frozen.
            second.playerTouch(fixture.player);
            require(second.isRemoved() && fixture.world.commitSequence() == capturedCommit+1
                    && fixture.player.getInventory().getItem(0).getCount() == 2,
                    "Later native pickup was not conserved beyond the frozen boundary");
            var rejected = new java.util.concurrent.RejectedExecutionException("intentional fixture writer rejection");
            boolean submissionRejected = false;
            try { coordinator.submit(command -> { throw rejected; }); }
            catch (java.util.concurrent.RejectedExecutionException failure) {
                require(failure == rejected, "Checkpoint replaced its actual writer rejection"); submissionRejected = true;
            }
            require(submissionRejected && coordinator.state() == io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCoordinator.State.FAILED
                    && coordinator.pendingTicket().orElseThrow() == ticket,
                    "Failed native checkpoint did not retain its exact retry ticket");
            requireRetirementRefused(fixture);
            coordinator.retry(); coordinator.submit(Runnable::run);
            // A completed store write is still insufficient until the real owner-thread poll.
            requireRetirementRefused(fixture);
            require(coordinator.poll(), "Actual retry did not acknowledge its durable store receipt");
            require(fixture.delivery.retireDurableConsumedOwners() == 1
                    && fixture.delivery.usage().retainedEntityOwners() == 1,
                    "Earlier checkpoint retired a later consumed owner or lost its own candidate");
            var earlier = fixture.readDurableCheckpoint();
            require(earlier.commitSequence() == capturedCommit && earlier.boundary().items().size() == 1
                    && earlier.boundary().items().getFirst().id().equals(secondId)
                    && earlier.boundary().inventory().slots().getFirst().matches(new ItemStack(Items.OAK_LOG), fixture.level.registryAccess())
                    && fixture.world.hasUncheckpointedActions(),
                    "Durable earlier image mixed in the later pickup or acknowledged it prematurely");
            var finalImage = fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            require(fixture.lastRetiredOwners == 1 && fixture.delivery.usage().retainedEntityOwners() == 0
                    && finalImage.boundary().items().isEmpty()
                    && finalImage.boundary().inventory().slots().getFirst().matches(new ItemStack(Items.OAK_LOG,2), fixture.level.registryAccess())
                    && finalImage.commitSequence() == fixture.world.commitSequence(),
                    "Successor checkpoint did not conserve inventory and retire exactly the later native owner");
        }
        helper.succeed();
    }
    private static void requireRetirementRefused(NativeSurvivalTestFixture fixture) {
        boolean refused = false;
        try { fixture.delivery.retireDurableConsumedOwners(); }
        catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) { refused = true; }
        require(refused && fixture.delivery.usage().retainedEntityOwners() == 2,
                "Unacknowledged/failed checkpoint released native owner references");
    }

    /** Earned inputs use the existing native-loot preparation/transaction route. Client mining
     * packets and per-click UI dispatch are separate pending gates; this commits one resolved script. */
    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void earnedTwoLogsCommitNativeResolvedCraftingAndSave(GameTestHelper helper) throws IOException {
        String stage = "fixture initialization";
        try (var earned = NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel());
                var reference = NativeSurvivalTestFixture.create(helper.getLevel())) {
            var first = earned.nearestNaturalLog();
            var second = CubeCell.of(first.x(), first.y() + 1, first.z());
            earned.beginNativeCarrierLoadForDryItemTicks(first); earned.awaitNativeCarrierLoad(); earned.bindNativeDeliveryForEarnedMenu();
            require(earned.player.getInventory().isEmpty(), "Earned native crafting must start without supplied items");
            menuMetadata(earned.player); menuMetadata(reference.player); // Admit native initialization callbacks before invocation.
            earned.player.initInventoryMenu(); reference.player.initInventoryMenu();
            earned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
            earned.player.moveTo(first.x()+0.5, first.y(), first.z()+0.5, 180, 0);
            for (var log : List.of(first, second)) {
                stage = "natural log preparation at " + log;
                ItemEntity source = commitEarnedNativeLog(earned, log);
                stage = "native item delay/gravity at " + log;
                int ticks = 0; boolean fell = false; double y = source.getY();
                source.playerTouch(earned.player);
                require(!source.isRemoved(), "Natural default pickup delay was bypassed");
                while (ticks < 48 && (ticks < 10 || !source.onGround())) {
                    earned.level.tickNonPassenger(source); ticks++;
                    fell |= source.getY() < y; y = source.getY();
                }
                require(ticks >= 10 && ticks < 48 && fell && source.onGround() && source.mainSupportingBlockPos.isPresent()
                        && earned.level.getBlockState(source.mainSupportingBlockPos.get()).is(Blocks.GRASS_BLOCK),
                        "Earned crafting input did not use original native delay/gravity/grass landing");
                require(earned.player.getBoundingBox().inflate(1.0,0.5,1.0).intersects(source.getBoundingBox()),
                        "Original native drop escaped actual pickup range");
                stage = "native item pickup at " + log;
                source.playerTouch(earned.player);
                require(source.isRemoved(), "Actual native earned-log pickup failed");
                // Retire the consumed owner only through the actual acknowledged checkpoint.
                earned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
                require(earned.lastRetiredOwners == 1, "Earned log owner did not retire after exact durable capture");
            }
            require(earned.player.getInventory().getItem(0).is(Items.OAK_LOG)
                    && earned.player.getInventory().getItem(0).getCount() == 2, "Native earned route did not conserve exactly two logs");
            for (int slot=1;slot<41;slot++) require(earned.player.getInventory().getItem(slot).isEmpty(), "Earned route supplied unrelated inventory");
            require(earned.player.getStats().getValue(net.minecraft.stats.Stats.BLOCK_MINED, Blocks.OAK_LOG) == 2
                    && earned.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP, Items.OAK_LOG) == 2,
                    "Earned log route omitted native mined/pickup statistics");
            stage = "direct native component reference";
            // Explicit component setup for the direct-native semantic reference only.
            reference.player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG,2));
            int changes = earned.player.getInventory().getTimesChanged();
            require(changes >= 0 && changes <= 64, "Unexpected earned native inventory change count");
            for (int i=0;i<changes;i++) reference.player.getInventory().setChanged();
            require(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(earned.player).equals(
                    io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(reference.player)),
                    "Native earned/reference resolved menu inputs differ");
            drainNativeMenuPackets(earned); drainNativeMenuPackets(reference);
            var clicks = nativeFirstLoop2x2Clicks();
            for (var click : clicks) reference.player.inventoryMenu.clicked(click.slot(),click.button(),click.type(),reference.player);
            reference.player.inventoryMenu.broadcastChanges(); // Same single final native broadcast schedule.
            long before = earned.world.commitSequence();
            stage = "committed resolved native script";
            var receipt = earned.delivery.dispatchResolvedMenuScript(clicks,
                    new io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata.Limits(4096,16384,8192,65536,128,128,1),
                    new io.earthplatform.minecraft.survival.interaction.host.NativeCraftingRecipeAdmission.Limits(1024,16384,16,131072,4*1024*1024,131072),
                    menuPreparationLimits());
            stage = "native script/reference comparison";
            require(receipt == earned.delivery.lastAppliedReceipt() && receipt.commitSequence() == before + 1
                    && receipt.changedCubes().isEmpty() && !earned.actions.fenced(), "Resolved native script did not deliver exactly one effect-only receipt");
            require(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(earned.player).equals(
                    io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(reference.player)),
                    "Committed native inventory/grid/cursor/derived/observer state differs from direct native reference");
            require(drainAllNativeMenuOutput(earned).equals(drainAllNativeMenuOutput(reference)),
                    "Committed ordered native result/recipe/broadcast packets differ from direct native reference");
            require(earned.player.getRecipeBook().toNbt().equals(reference.player.getRecipeBook().toNbt())
                    && earned.nativeAdvancementState().equals(reference.nativeAdvancementState()),
                    "Committed native recipe unlocks or advancement criteria differ from direct native callbacks");
            for (var item : List.of(Items.OAK_PLANKS,Items.STICK,Items.CRAFTING_TABLE)) {
                int expected = item == Items.OAK_PLANKS ? 8 : item == Items.STICK ? 4 : 1;
                require(earned.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED,item) == expected
                        && reference.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED,item) == expected,
                        "Native crafted amount changed or callback was omitted");
            }
            stage = "native script checkpoint";
            var snapshot = earned.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ACTIVE);
            require(snapshot.commitSequence() == receipt.commitSequence() && snapshot.boundary().items().isEmpty()
                    && snapshot.boundary().inventory().equals(io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(earned.player)),
                    "Resolved earned crafting checkpoint lost native conserved ownership");
            var slots = snapshot.boundary().inventory().slots();
            require(slots.get(1).matches(new ItemStack(Items.OAK_PLANKS,2),earned.level.registryAccess())
                    && slots.get(2).matches(new ItemStack(Items.STICK,4),earned.level.registryAccess())
                    && slots.get(3).matches(new ItemStack(Items.CRAFTING_TABLE),earned.level.registryAccess()),
                    "Native earned crafting checkpoint does not contain exact planks/sticks/table output");
            stage = "native player/advancement/stat file proof";
            var files = earned.saveAndVerifyNativePlayerFiles();
            require(java.nio.file.Files.exists(files.playerData()) && java.nio.file.Files.exists(files.advancements())
                    && java.nio.file.Files.exists(files.statistics()), "Actual native save evidence disappeared");
        } catch (RuntimeException | Error | IOException failure) {
            traceNativeMenuFailure(stage,failure); throw failure;
        }
        helper.succeed();
    }

    private static ItemEntity commitEarnedNativeLog(NativeSurvivalTestFixture fixture, CubeCell log) {
        require(fixture.level.getBlockState(NativeVanillaPreparation.nativePos(log)).is(Blocks.OAK_LOG),
                "Earned script input must come from the next actual generated oak log");
        var inventoryBefore = io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player);
        long sequence = fixture.world.nextActionSequence(fixture.player.getUUID());
        var identity = fixture.world.actionIdentity(fixture.player.getUUID(),sequence);
        // Pinned ordinary oak-log harvest/loot/tool callbacks read only this exact target cube.
        // Any unexpected neighbor read still refuses; no wildcard or synthetic native readiness.
        var expected = Map.of(log.cube(),fixture.world.stamp(log.cube()));
        try (var action = fixture.delivery.begin(identity,expected)) {
            action.read(log);
            var mining = fixture.preparation.mining(fixture.player,log,action.random());
            require(mining.harvestable() && mining.loot().size() == 1 && !mining.tool().durabilityChanged(),
                    "Native first-loop log preparation changed its harvest/tool profile");
            require(mining.loot().getFirst().matches(new ItemStack(Items.OAK_LOG),fixture.level.registryAccess()),
                    "Native generated log loot differed from one oak log");
            // Pinned Block.playerDestroy exact inherited metadata, delivered through typed effects.
            action.stage(new NativeEffectCodec.PlayerStat(net.minecraft.resources.ResourceLocation.withDefaultNamespace("mined"),
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("oak_log"),1));
            action.stage(new NativeEffectCodec.PlayerExhaustion(0.005F));
            action.set(log,Blocks.AIR.defaultBlockState());
            var random = action.random();
            double x = log.x()+0.5+Mth.nextDouble(random,-0.25,0.25);
            double y = log.y()+0.5+Mth.nextDouble(random,-0.25,0.25)-EntityType.ITEM.getHeight()/2.0;
            double z = log.z()+0.5+Mth.nextDouble(random,-0.25,0.25);
            double vx = random.nextDouble()*0.2-0.1, vz = random.nextDouble()*0.2-0.1;
            UUID id = UUID.nameUUIDFromBytes(("native-earned-script-drop:"+identity).getBytes(StandardCharsets.UTF_8));
            action.stage(new NativeEffectCodec.ItemSpawn(id,mining.loot().getFirst(),x,y,z,vx,0.2,vz,0,10));
            action.commit();
        }
        require(inventoryBefore.equals(io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot.capture(fixture.player)),
                "Native log preparation granted inventory instead of spawning its actual drop");
        var items = fixture.delivery.captureNativeState().items();
        require(items.size() == 1, "Earned log fixture must own one actual native drop at a time");
        return items.getFirst();
    }

    private static List<String> drainAllNativeMenuOutput(NativeSurvivalTestFixture fixture) {
        fixture.channel.runPendingTasks(); var result = new ArrayList<String>();
        for (Object packet; (packet = fixture.channel.readOutbound()) != null;) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket slot)
                result.add("slot:"+slot.getContainerId()+":"+slot.getStateId()+":"+slot.getSlot()+":"
                        +java.util.Base64.getEncoder().encodeToString(NativeStackSnapshot.capture(slot.getItem(),fixture.level.registryAccess()).encoded()));
            else if (packet instanceof net.minecraft.network.protocol.game.ClientboundRecipePacket recipes)
                result.add("recipes:"+recipes.getState()+":"+recipes.getRecipes()+":"+recipes.getHighlights());
            else throw new AssertionError("Unexpected native script packet: "+packet.getClass().getName());
            io.netty.util.ReferenceCountUtil.release(packet);
        }
        return List.copyOf(result);
    }

    /** Explicitly supplied component inputs: capacity/observer/forged transcript refusal only. */
    @GameTest(template="native_survival_empty", timeoutTicks=400)
    public static void nativeResolvedMenuAuthorityAndCapacityRefusalsComponent(GameTestHelper helper) throws IOException {
        String stage = "component fixture initialization";
        try (var fixture = NativeSurvivalTestFixture.createDeliveryUnbound(helper.getLevel())) {
            var target = fixture.nearestNaturalLog(); fixture.beginNativeCarrierLoad(target);
            fixture.awaitNativeCarrierLoad(); fixture.bindNativeDelivery(); // Original32 metadata operations intentionally retained.
            fixture.player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,2));
            var before = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(fixture.player);
            stage = "uninitialized native observer refusal";
            boolean observerRefused = false;
            try { dispatchFixtureMenuScript(fixture); }
            catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) {
                observerRefused = expected.getMessage().contains("initialized native listener");
            }
            require(observerRefused && fixture.world.commitSequence() == 0 && before.equals(
                    io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(fixture.player)),
                    "Uninitialized native observer graph did not refuse before commit/mutation");
            menuMetadata(fixture.player); fixture.player.initInventoryMenu();
            before = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(fixture.player);
            var book = fixture.player.getRecipeBook().toNbt(); var progress = fixture.nativeAdvancementState();
            stage = "exact native metadata capacity refusal";
            boolean capacityRefused = false;
            try { dispatchFixtureMenuScript(fixture); }
            catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) {
                System.out.println("EARTH_NATIVE_EXPECTED_CAPACITY_REFUSAL " + expected.getMessage());
                capacityRefused = expected.getMessage().equals("Earth survival: Native menu notices and delivery closure exceed the metadata count cap")
                        || expected.getMessage().equals("Earth survival: native notification metadata budget exhausted, including retained/pending owners");
            }
            require(capacityRefused && fixture.world.commitSequence() == 0 && !fixture.actions.fenced()
                    && before.equals(io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(fixture.player))
                    && book.equals(fixture.player.getRecipeBook().toNbt()) && progress.equals(fixture.nativeAdvancementState()),
                    "Too-small native metadata capacity changed live menu/metadata or committed a prefix");
            stage = "forged native transcript authority refusal";
            var metadata = menuMetadata(fixture.player); var recipes = menuRecipeAdmission(fixture.player,metadata);
            io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.Result transcript;
            try (var comparison = fixture.delivery.begin(fixture.world.actionIdentity(fixture.player.getUUID(),
                    fixture.world.nextActionSequence(fixture.player.getUUID())),Map.of())) {
                transcript = io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation.prepareScript(
                        comparison,fixture.player,metadata,recipes,List.of(menuClick(9,0)),menuPreparationLimits());
            }
            try (var forged = fixture.delivery.begin(fixture.world.actionIdentity(fixture.player.getUUID(),
                    fixture.world.nextActionSequence(fixture.player.getUUID())),Map.of())) {
                var identity = forged.identity();
                forged.stage(new NativeEffectCodec.ResolvedMenuScript(fixture.world.world(),identity.ownerEpoch(),identity.actor(),
                        identity.sequence(),List.of(menuClick(9,0)),transcript));
                boolean rejected = false; try { forged.commit(); }
                catch (io.earthplatform.minecraft.survival.world.SurvivalRefusal expected) {
                    System.out.println("EARTH_NATIVE_EXPECTED_AUTHORITY_REFUSAL " + expected.getMessage());
                    rejected = expected.getMessage().equals("Earth survival: Native menu script lacks its exact effect-only single-use captured plan");
                }
                require(rejected && forged.state() == io.earthplatform.minecraft.survival.interaction.NativeWholeAction.State.ABORTED,
                        "Copied transcript obtained delivery authority without the host's private plan");
            }
            require(fixture.world.commitSequence() == 0 && before.equals(
                    io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot.captureLive(fixture.player)),
                    "Forged native menu transcript mutated conserved state");
            fixture.checkpoint(NativeSurvivalCheckpoint.SessionPhase.ENTERING);
        } catch (RuntimeException | Error | IOException failure) {
            traceNativeMenuFailure(stage,failure); throw failure;
        }
        helper.succeed();
    }
    private static NativeSurvivalWorld.CommitReceipt dispatchFixtureMenuScript(NativeSurvivalTestFixture fixture) {
        return fixture.delivery.dispatchResolvedMenuScript(nativeFirstLoop2x2Clicks(),
                new io.earthplatform.minecraft.survival.interaction.host.NativeFirstLoopMetadata.Limits(4096,16384,8192,65536,128,128,1),
                new io.earthplatform.minecraft.survival.interaction.host.NativeCraftingRecipeAdmission.Limits(1024,16384,16,131072,4*1024*1024,131072),
                menuPreparationLimits());
    }

    private static void traceNativeMenuFailure(String stage, Throwable failure) {
        System.err.println("EARTH_NATIVE_MENU_FAILURE stage=" + stage);
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        var queue = new java.util.ArrayDeque<Throwable>(); queue.add(failure);
        int causes = 0;
        while (!queue.isEmpty() && causes++ < 8) {
            Throwable current = queue.removeFirst(); if (!seen.add(current)) continue;
            System.err.println(current.getClass().getName() + ": " + current.getMessage());
            var frames = current.getStackTrace();
            for (int frame=0; frame<frames.length && frame<128; frame++) System.err.println("  at " + frames[frame]);
            if (frames.length > 128) System.err.println("  ... bounded stack remainder omitted");
            if (current.getCause() != null) queue.add(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) if (queue.size()<8) queue.add(suppressed);
        }
    }
}
