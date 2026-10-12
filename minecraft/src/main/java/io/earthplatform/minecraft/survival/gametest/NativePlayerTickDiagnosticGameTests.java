package io.earthplatform.minecraft.survival.gametest;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.gametest.GameTestHolder;
import static io.earthplatform.minecraft.survival.gametest.NativeSurvivalTestFixture.require;

/** Direct native method diagnosis only. No tests register when the explicit diagnostic is off. */
@GameTestHolder("earthplatform")
public final class NativePlayerTickDiagnosticGameTests {
    private NativePlayerTickDiagnosticGameTests() {}
    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        if(!NativePlayerTickProbeScope.enabled())return List.of();
        return List.of(test("refuses_unknown_missing_foreign_and_high_alias",NativePlayerTickDiagnosticGameTests::negativeAdmission),
                test("refuses_returning_player_500xp_before_native_entry",NativePlayerTickDiagnosticGameTests::returningRewardRefusal),
                test("refuses_legacy_environment_identity",NativePlayerTickDiagnosticGameTests::legacyIdentityRefusal),
                test("direct_tick_and_dotick_dry_marin",helper->directDryTick(helper,NativePlayerTickProbeScope.Invocation.TICK_THEN_DO_TICK)),
                test("isolated_dotick_only_dry_marin",helper->directDryTick(helper,NativePlayerTickProbeScope.Invocation.DO_TICK_ONLY)));
    }
    private static TestFunction test(String name,java.util.function.Consumer<GameTestHelper> body) {
        return new TestFunction("nativePlayerTickDiagnostic","nativeplayertickdiagnosticgametests."+name,
                "earthplatform:native_survival_empty",200,0,true,helper -> {
                    try { body.accept(helper); }
                    catch (AssertionError failure) {
                        // Native GameTestInfo catches Exception, not Error. Keep the assertion
                        // and exact cause as a failed case so later cases still execute.
                        throw new IllegalStateException("Native player diagnostic assertion failed: "+name,failure);
                    }
                });
    }
    private static void placeAtSourceAnchor(NativeSurvivalTestFixture fixture) {
        fixture.player.moveTo(MarinSurvivalDefinition.ANCHOR_X+0.5,MarinSurvivalDefinition.GROUND_Y+1,
                MarinSurvivalDefinition.ANCHOR_Z+0.5,0,0);
        // Initial spawn placement, not packet movement, teleport acknowledgement or settling proof.
        // Never write velocity, disable collision, switch game mode or edit a native tick counter.
    }
    private static void admitFootprint(NativeSurvivalTestFixture fixture,CubePos skip) {
        var bounds=NativePlayerTickProbeScope.bounds(fixture.player.getBoundingBox());
        var min=CubePos.fromBlock(bounds.x1(),bounds.y1(),bounds.z1());
        var max=CubePos.fromBlock(bounds.x2(),bounds.y2(),bounds.z2());
        for(int x=min.x();x<=max.x();x++)for(int y=min.y();y<=max.y();y++)for(int z=min.z();z<=max.z();z++) {
            var cube=new CubePos(x,y,z);if(!cube.equals(skip))fixture.admit(cube);
        }
    }
    private static void negativeAdmission(GameTestHelper helper) {
        try(var fixture=NativeSurvivalTestFixture.createPlayerEnvironment(helper.getLevel());
                var foreign=NativeSurvivalTestFixture.createPlayerEnvironment(helper.getLevel())) {
            placeAtSourceAnchor(fixture);placeAtSourceAnchor(foreign);
            expectBeforeEntryRefusal(fixture,()->NativePlayerTickProbeScope.prepare(fixture.player,fixture.binding),"unknown");
            var bounds=NativePlayerTickProbeScope.bounds(fixture.player.getBoundingBox());
            var missing=CubePos.fromBlock(bounds.x2(),bounds.y2(),bounds.z2());
            admitFootprint(fixture,missing);
            expectBeforeEntryRefusal(fixture,()->NativePlayerTickProbeScope.prepare(fixture.player,fixture.binding),"missing");
            fixture.admit(missing);
            expectBeforeEntryRefusal(fixture,()->NativePlayerTickProbeScope.prepare(fixture.player,foreign.binding),"foreign binding");
            // All actual low-Y support is resident. A +4096 probe still refuses before native entry.
            fixture.player.moveTo(fixture.player.getX(),MarinSurvivalDefinition.GROUND_Y+1+4096,
                    fixture.player.getZ(),0,0);
            expectBeforeEntryRefusal(fixture,()->NativePlayerTickProbeScope.prepare(fixture.player,fixture.binding),"high/low alias");
            placeAtSourceAnchor(fixture);
            var prepared=NativePlayerTickProbeScope.prepare(fixture.player,fixture.binding);
            fixture.player.moveTo(fixture.player.getX(),fixture.player.getY()+4096,fixture.player.getZ(),0,0);
            expectBeforeEntryRefusal(fixture,prepared::runOnce,"changed after admission");
            require(!NativePlayerTickProbeScope.active(),"Refused admission leaked a diagnostic capability");
        }catch(IOException failure){throw new RuntimeException(failure);}
        helper.succeed();
    }
    private static void expectBeforeEntryRefusal(NativeSurvivalTestFixture fixture,Runnable operation,String label) {
        var before=NativePlayerTickProbeScope.State.capture(fixture.player);
        boolean refused=false;
        try{operation.run();}catch(SurvivalRefusal expected){refused=true;}
        require(refused,"Expected before-entry refusal for "+label);
        require(before.equals(NativePlayerTickProbeScope.State.capture(fixture.player)),"Refused "+label+" changed native state");
        require(!NativePlayerTickProbeScope.active(),"Refused "+label+" entered/leaked diagnostic scope");
    }
    private static void returningRewardRefusal(GameTestHelper helper) {
        try(var fixture=NativeSurvivalTestFixture.createReturningPlayerEnvironment(helper.getLevel())) {
            placeAtSourceAnchor(fixture);admitFootprint(fixture,null);
            var holder=fixture.server.getAdvancements().get(net.minecraft.resources.ResourceLocation.withDefaultNamespace("adventure/adventuring_time"));
            var progress=fixture.player.getAdvancements().getOrStartProgress(holder);
            require(!progress.isDone()&&!progress.getCriterion("minecraft:forest").isDone(),"Returning native progress must be one forest criterion short");
            int completed=0;for(String ignored:progress.getCompletedCriteria())completed++;
            require(completed==52,"Native constructor did not load all52 prior biome criteria");
            var book=fixture.player.getRecipeBook().toNbt();
            var before=NativePlayerTickProbeScope.State.capture(fixture.player);
            boolean refused=false;
            try {NativePlayerTickProbeScope.prepare(fixture.player,fixture.binding);}
            catch(SurvivalRefusal expected) {
                refused=expected.getMessage().contains("unsupported conserved/function reward")&&expected.getMessage().contains("adventuring_time");
                Files.writeString(fixture.directory.resolve("returning-player-preflight-refusal.txt"),expected.toString()+"\nbefore="+before+"\nafter="+NativePlayerTickProbeScope.State.capture(fixture.player));
            }
            require(refused,"Returning500XP completion must refuse before native tick entry");
            require(before.equals(NativePlayerTickProbeScope.State.capture(fixture.player)),"Reward preflight refusal mutated native player state");
            require(book.equals(fixture.player.getRecipeBook().toNbt())&&!progress.getCriterion("minecraft:forest").isDone(),"Reward preflight changed recipes/progress");
            require(!NativePlayerTickProbeScope.active(),"Reward refusal leaked environment/tick authority");
        }catch(IOException failure){throw new RuntimeException(failure);}
        helper.succeed();
    }
    private static void legacyIdentityRefusal(GameTestHelper helper) {
        try(var fixture=NativeSurvivalTestFixture.create(helper.getLevel())) {
            placeAtSourceAnchor(fixture);admitFootprint(fixture,null);
            var legacy=fixture.world.world();
            var environment=io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV1.worldIdentity(legacy.blockPaletteVersion());
            require(!legacy.equals(environment),"Legacy terrain-only world identity was silently reinterpreted");
            expectBeforeEntryRefusal(fixture,()->NativePlayerTickProbeScope.prepare(fixture.player,fixture.binding),"legacy persisted environment identity");
        }catch(IOException failure){throw new RuntimeException(failure);}
        helper.succeed();
    }
    private static void directDryTick(GameTestHelper helper,NativePlayerTickProbeScope.Invocation invocation) {
        try(var fixture=NativeSurvivalTestFixture.createPlayerEnvironment(helper.getLevel())) {
            placeAtSourceAnchor(fixture);admitFootprint(fixture,null);
            var anchor=BlockPos.containing(fixture.player.position()).below();
            require(anchor.getY()==134 && fixture.level.getBlockState(anchor).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK),
                    "Ground must be source-generated grass at exact Earth Y134");
            require(fixture.player.touchingUnloadedChunk(),"The full-column legacy residency guard was unexpectedly weakened");
            var before=NativePlayerTickProbeScope.State.capture(fixture.player);
            var probe=NativePlayerTickProbeScope.prepare(fixture.player,fixture.binding,invocation);
            Throwable failure=null;
            try {
                probe.runOnce();
                var after=NativePlayerTickProbeScope.State.capture(fixture.player);
                require(probe.metadata.actualAwards().equals(probe.metadata.expectedAwards()),"Native award sequence differed from preflight");
                if(invocation==NativePlayerTickProbeScope.Invocation.TICK_THEN_DO_TICK) {
                    require(probe.metadata.actualAwards().stream().anyMatch(award->award.holder().id().toString().equals("minecraft:recipes/decorations/crafting_table")
                            &&award.criterion().equals("unlock_right_away")&&award.newGrant()),"Original native TICK crafting-table award never ran");
                    require(fixture.player.getRecipeBook().contains(net.minecraft.resources.ResourceLocation.withDefaultNamespace("crafting_table")),"Native recipe reward was not unlocked");
                }
                require(probe.metadata.actualAwards().stream().anyMatch(award->award.holder().id().toString().equals("minecraft:adventure/adventuring_time")
                        &&award.criterion().equals("minecraft:forest")&&award.newGrant()),"Original native LOCATION did not award procedural forest criterion");
                require(!probe.environment.quartReads().isEmpty()&&probe.environment.structureQueries()>0,"Native LOCATION skipped biome/structure environment seams");
                require(probe.environment.quartReads().stream().allMatch(q->q.y()>=32&&q.y()<=35),"Biome source lost actual native quart Y");
                boolean outsideRefused=false;
                try{fixture.level.getBiome(fixture.player.blockPosition());}catch(SurvivalRefusal expected){outsideRefused=true;}
                require(outsideRefused,"Native return leaked environment biome authority");
                require(probe.fluidResidencyCalls>0,"Native fluid residency seam was not exercised");
                require(probe.readCount("fluid")>0,"Native fluid loop never read actual cubic state");
                require(probe.readCount("inside-block")>0,"Native inside-block loop never read actual cubic state");
                require(probe.readCount("collision")>0,"Native collision iterator never read actual cubic state");
                require(probe.reads.stream().anyMatch(read->read.y()==134 && read.actualState().contains("grass_block")),
                        "Tick did not read genuine cubic ground at Earth Y134");
                require(after.health()==before.health()&&after.absorption()==before.absorption()&&after.air()==before.air(),
                        "Unadmitted native health/absorption/air change; inspect diagnostic state report");
                require(after.inventory().equals(before.inventory()),"Fresh native tick mutated inventory");
                require(after.x()==before.x()&&after.y()==before.y()&&after.z()==before.z()&&after.pose()==Pose.STANDING,
                        "Fresh zero-motion native tick changed position or standing pose unexpectedly");
                require(after.tickCount()==before.tickCount(),"Direct invocation unexpectedly claimed the outer scheduler tick counter");
                require(!after.noPhysics(),"Native tick disabled collision");
                float expectedSaturation=before.food().saturation();
                if(fixture.level.getDifficulty()==Difficulty.PEACEFUL
                        &&fixture.level.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION))expectedSaturation+=1;
                require(after.food().foodLevel()==20&&after.food().tickTimer()==0&&after.food().exhaustion()==0
                        &&after.food().saturation()==expectedSaturation,
                        "Unexpected FoodData change in the pinned fresh tick0 profile");
                // Native first movement is zero; native travel then applies gravity for the next tick.
                // This is an assertion, never a replacement integrator or a write to player motion.
                require(after.vx()==0&&after.vz()==0&&Double.doubleToLongBits(after.vy())==Double.doubleToLongBits(-0.08D*0.98F),
                        "Native dry zero-input gravity result changed; inspect actual state report");
                require(!NativePlayerTickProbeScope.active(),"Successful native return retained tick authority");
                require(fixture.player.touchingUnloadedChunk(),"Diagnostic leaked a full-column availability claim");
                boolean reused=false;try{probe.runOnce();}catch(SurvivalRefusal expected){reused=true;}
                require(reused,"Single-use diagnostic accepted a second native call");
            }catch(RuntimeException|Error error){failure=error;throw error;}
            finally {
                NativePlayerTickProbeScope.State after=null;
                try { after=NativePlayerTickProbeScope.State.capture(fixture.player); }
                catch(RuntimeException|Error captureFailure) {
                    if(failure==null)throw captureFailure;
                    failure.addSuppressed(captureFailure);
                }
                var report=fixture.directory.resolve("native-player-tick-diagnostic.txt");
                try {
                    Files.writeString(report,probe.report(before,after,failure));
                    com.mojang.logging.LogUtils.getLogger().info("NATIVE_PLAYER_TICK_DIAGNOSTIC invocation={} stage={} reads={} fluid={} inside={} collision={} report={}",
                            probe.invocation,probe.stage,probe.reads.size(),probe.readCount("fluid"),probe.readCount("inside-block"),probe.readCount("collision"),report);
                }catch(IOException error){if(failure!=null)failure.addSuppressed(error);else throw error;}
            }
        }catch(IOException failure){throw new RuntimeException(failure);}
        helper.succeed();
    }
}
