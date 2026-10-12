package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.api.enums.config.EDhApiGrassSideRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import io.earthplatform.minecraft.lod.SurvivalDhPageCompiler;
import io.earthplatform.minecraft.lod.SurvivalDhProducerGpuFixture;
import io.earthplatform.minecraft.lod.SurvivalDhTerrainData;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.render.SurvivalDhMaterialPalette;
import io.earthplatform.minecraft.survival.render.SurvivalDhTestSupport;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.earthplatform.minecraft.survival.dhfixture.DhProducerFixtureAssertions.*;

/**
 * Official FML-bootstrapped real-source/managed-helper ownership component with modeled GL only.
 * Launch-only source set and namespace. Does not establish client readiness, real GL or frame publication.
 */
@GameTestHolder("earthplatform_dh_producer_fixture")
@PrefixGameTestTemplate(false)
public final class InstalledSurvivalDhProducerGameTests {
    private static final String ARTIFACT = "DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar";
    private static final List<InstalledDhTerrainRuntime> RETAINED = new ArrayList<>();
    // At most four wrappers from the sole required case. Uncertain ownership survives until JVM exit.
    private static final List<Object> RETAINED_OWNERS = new ArrayList<>(4);
    private InstalledSurvivalDhProducerGameTests() {}

    private static InstalledDhTerrainRuntime runtime() throws Exception {
        var mods = ModList.get();
        assertTrue(mods != null && mods.isLoaded(InstalledDhResolver.MOD_ID));
        var mod = new NeoForgeDhCatalogue().installedDh().orElseThrow();
        assertEquals(InstalledDhResolver.VERSION, mod.version());
        assertTrue(!mod.moduleName().isEmpty());
        var installed = FMLPaths.GAMEDIR.get().resolve("mods").resolve(ARTIFACT).toRealPath();
        assertTrue(mod.path().toRealPath().equals(installed));
        InstalledDhResolver.verifyArtifact(mod.path());
        Class<?> api = Class.forName("com.seibel.distanthorizons.api.DhApi", false, mod.gameLoader());
        InstalledDhResolver.verifyOrigin(mod, api);
        assertTrue(api.getModule().isNamed());
        assertEquals(mod.moduleName(), api.getModule().getName());
        System.out.printf("EARTH_DH_PRODUCER_INSTALLED version=%s sha256=%s module=%s location=%s path=%s%n",
                mod.version(), InstalledDhResolver.SHA256, mod.moduleName(), mod.moduleLocation(), installed);
        // This explicitly borrows the real installed helper, without manufacturing client READY status.
        var result = new InstalledDhTerrainRuntime(new InstalledDhResolver.Runtime(mod, api),
                InstalledDhTerrainRuntime.HelperCalls.DIRECT);
        assertTrue(RETAINED.isEmpty());
        RETAINED.add(result);
        return result;
    }

    private static void requireSingleSupportIdentity() throws Exception {
        var type = SurvivalDhTestSupport.class;
        assertTrue(type.getClassLoader() == InstalledSurvivalDhProducerGameTests.class.getClassLoader());
        assertTrue(type.getModule() == InstalledSurvivalDhProducerGameTests.class.getModule());
        var resources = type.getClassLoader().getResources(type.getName().replace('.', '/') + ".class");
        int count = 0;
        while (resources.hasMoreElements()) {
            System.out.println("EARTH_DH_PRODUCER_SUPPORT_ORIGIN " + resources.nextElement());
            count++;
        }
        assertEquals(1, count);
    }

    @GameTest(template="native_survival_empty", timeoutTicks=1800)
    public static void actualResultProducesAnExclusiveBoundedCompleteSetAndDropsEveryCpuPayload(GameTestHelper helper)
            throws Exception {
        try {
            assertTrue(Boolean.getBoolean("earthplatform.dhProducerFixture"));
            assertTrue(Boolean.getBoolean("neoforge.gameTestServer"));
            assertEquals("earthplatform_dh_producer_fixture", System.getProperty("neoforge.enabledGameTestNamespaces"));
            requireSingleSupportIdentity();
            Path work = Files.createDirectory(FMLPaths.GAMEDIR.get().resolve("dh-producer-ownership"));
            Path source = Files.createDirectory(work.resolve("source-root"));
            Path root = Files.createDirectory(work.resolve("owner-root"));
            // Minecraft/NeoForge already completed bootstrap. Never invoke Bootstrap from a plain JVM.
            SurvivalDhTestSupport.setup(source);
            System.out.println("EARTH_DH_PRODUCER_BEGIN wallLimitSeconds=90 cpuBudgetBytes=134217728 gl=MODELED_ONLY");
            final long begin = System.nanoTime();
            Runnable deadline = () -> {
                if (System.nanoTime() - begin >= TimeUnit.SECONDS.toNanos(90))
                    throw new AssertionError("Migrated producer exceeded the original 90-second wall limit");
            };
            ownership(root, deadline);
            deadline.run();
            System.out.printf("EARTH_DH_PRODUCER_PASS elapsedNanos=%d gl=MODELED_ONLY liveRenderClaim=false%n",
                    System.nanoTime() - begin);
            helper.succeed();
        } catch (AssertionError failure) {
            // GameTestInfo catches Exception rather than Error. Preserve the original assertion as cause.
            throw new IllegalStateException("Installed DH producer ownership assertion failed", failure);
        }
    }

    private static void ownership(Path root, Runnable deadline) throws Exception {
        var originalGrass=Config.Client.Advanced.Graphics.Quality.grassSideRendering.getTrueValue();var originalDebug=Config.Client.Advanced.Debugging.debugRenderingColors.getTrueValue();
        Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(EDhApiGrassSideRendering.AS_GRASS);
        Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(EDhApiDebugRendering.OFF);
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(24);
                var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                var palette=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget)){
            long baseline=f.budget.evidence().bytes();var runtime=runtime();RETAINED_OWNERS.add(f);
            var input=SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,f.centerX(),f.centerZ(),SurvivalDhTerrainData.Limits.standard(),f.budget,deadline);
            RETAINED_OWNERS.add(input);int count=input.tileCount();var compiler=new SurvivalDhPageCompiler(runtime,f.budget);RETAINED_OWNERS.add(compiler);compiler.start(input);assertThrows(SurvivalRefusal.class,input::tileCount);
            int steps=0;while(!compiler.step(4,Long.MAX_VALUE,deadline).complete())assertTrue(++steps<=64);
            var output=compiler.finish();RETAINED_OWNERS.add(output);assertEquals(count,output.tileCount());for(int i=0;i<count;i++){assertTrue(output.vertexBytes(i)>0);assertEquals(0,output.vertexBytes(i)%64);assertTrue(output.vertexBytes(i)<=4096*64);}
            assertThrows(SurvivalRefusal.class,compiler::finish);assertThrows(SurvivalRefusal.class,compiler::close);
            try(var equalValues=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                    var lease=f.capture.authorize(plan);var frame=lease.latch(plan)){
                SurvivalDhProducerGpuFixture.uploadAndCheck(output,f.budget,cover,equalValues,frame,count,deadline);
            }
            output.close();output.close();assertThrows(SurvivalRefusal.class,output::tileCount);
            assertEquals(baseline+InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_BYTES+InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_METADATA_BYTES,f.budget.evidence().bytes());
            assertFalse(runtime.physicalDrain());assertTrue(runtime.managedSurvivalWorkspace(f.budget).operationReady());
        }finally{Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(originalGrass);Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(originalDebug);}
    }
}
