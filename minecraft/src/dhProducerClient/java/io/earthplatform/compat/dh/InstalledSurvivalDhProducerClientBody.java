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
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import static io.earthplatform.minecraft.survival.dhfixture.DhProducerFixtureAssertions.*;

/**
 * Actual integrated-client real-source/managed-helper ownership component with modeled GL only.
 * Launch-only source set. Does not establish client readiness, real GL or frame publication.
 */
public final class InstalledSurvivalDhProducerClientBody {
    // Static test support keeps source aliases; this charge is deliberately never released before process exit.
    private static io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation retainedSourceAssets;
    private static final String ARTIFACT = "DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar";
    private static final List<InstalledDhTerrainRuntime> RETAINED = new ArrayList<>();
    // At most four wrappers from the sole required case. Uncertain ownership survives until JVM exit.
    private static final List<Object> RETAINED_OWNERS = new ArrayList<>(4);
    private InstalledSurvivalDhProducerClientBody() {}

    private static InstalledDhTerrainRuntime runtime(InstalledIrisRuntime installed) throws Exception {
        var mods = ModList.get();
        assertTrue(mods != null && mods.isLoaded(InstalledDhResolver.MOD_ID));
        var mod = new NeoForgeDhCatalogue(installed).installedDh().orElseThrow();
        assertEquals(InstalledDhResolver.VERSION, mod.version());
        assertTrue(!mod.moduleName().isEmpty());
        var installedPath = FMLPaths.GAMEDIR.get().resolve("mods").resolve(ARTIFACT).toRealPath();
        assertTrue(mod.path().toRealPath().equals(installedPath));
        InstalledDhResolver.verifyArtifact(mod.path());
        Class<?> api = Class.forName("com.seibel.distanthorizons.api.DhApi", false, mod.gameLoader());
        InstalledDhResolver.verifyOrigin(mod, api);
        assertTrue(api.getModule().isNamed());
        assertEquals(mod.moduleName(), api.getModule().getName());
        System.out.printf("EARTH_DH_PRODUCER_INSTALLED version=%s sha256=%s module=%s location=%s path=%s%n",
                mod.version(), InstalledDhResolver.SHA256, mod.moduleName(), mod.moduleLocation(), installedPath);
        // This explicitly borrows the real installed helper, without manufacturing client READY status.
        var result = new InstalledDhTerrainRuntime(new InstalledDhResolver.Runtime(mod, api),
                InstalledDhTerrainRuntime.HelperCalls.DIRECT);
        assertTrue(RETAINED.isEmpty());
        RETAINED.add(result);
        return result;
    }

    private static void requireSingleSupportIdentity() throws Exception {
        var type = SurvivalDhTestSupport.class;
        assertTrue(type.getClassLoader() == InstalledSurvivalDhProducerClientBody.class.getClassLoader());
        assertTrue(type.getModule() == InstalledSurvivalDhProducerClientBody.class.getModule());
        var resources = type.getClassLoader().getResources(type.getName().replace('.', '/') + ".class");
        int count = 0;
        while (resources.hasMoreElements()) {
            System.out.println("EARTH_DH_PRODUCER_SUPPORT_ORIGIN " + resources.nextElement());
            count++;
        }
        assertEquals(1, count);
    }

    public static void run(Path work, InstalledIrisRuntime installed, Runnable current) throws Exception {
        requireSingleSupportIdentity();
        Path source = Files.createDirectory(work.resolve("source-root"));
        Path root = Files.createDirectory(work.resolve("owner-root"));
        if(retainedSourceAssets!=null || SurvivalDhTestSupport.source!=null)
            throw new IllegalStateException("Producer source already initialized in this process");
        retainedSourceAssets=io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.global().reserve(
                io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.SOURCE_ASSETS,64L<<20);
        SurvivalDhTestSupport.setup(source);
        final long begin = System.nanoTime();
        Runnable deadline = () -> {
            current.run();
            if (System.nanoTime() - begin >= TimeUnit.SECONDS.toNanos(90))
                throw new AssertionError("Integrated producer exceeded its checked 90-second wall deadline");
        };
        var grass = Config.Client.Advanced.Graphics.Quality.grassSideRendering;
        var debug = Config.Client.Advanced.Debugging.debugRenderingColors;
        var witness = SurvivalDhConfiguration.capture(com.seibel.distanthorizons.core.config.types.ConfigEntry.class, grass, debug);
        if (witness == null) throw new IllegalStateException("Actual transformed DH config generations required");
        witness.requireCurrent(grass, debug);
        assertEquals("AS_GRASS", grass.get().name());
        assertEquals("OFF", debug.get().name());
        witness.requireCurrent(grass, debug);
        System.out.println("EARTH_DH_PRODUCER_BEGIN wallLimitSeconds=90 operationCpuBudgetBytes=134217728 retainedGlobalSourceAssetsBytes=67108864 gl=MODELED_ONLY configGeneration=ACTUAL_TRANSFORMED");
        ownership(root, installed, () -> { deadline.run(); witness.requireCurrent(grass, debug); });
        witness.requireCurrent(grass, debug); deadline.run();
        System.out.printf("EARTH_DH_PRODUCER_BODY_PASS elapsedNanos=%d gl=MODELED_ONLY liveRenderClaim=false%n", System.nanoTime()-begin);
    }

    private static void ownership(Path root, InstalledIrisRuntime installed, Runnable deadline) throws Exception {
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(24);
                var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                var palette=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget)){
            long baseline=f.budget.evidence().bytes();var runtime=runtime(installed);RETAINED_OWNERS.add(f);
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
        }
    }
}
