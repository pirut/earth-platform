package io.earthplatform.minecraft.cubic.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.earthplatform.geography.globe.ClimateSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

class PMWeatherCompatTest {
    // Representative CHELSA-like climatologies (Koppen, mean C, warmest-quarter C, annual mm).
    private static final ClimateSource.Climate OKLAHOMA = new ClimateSource.Climate(ClimateSource.CFA, 15.5, 26.5, 920);
    private static final ClimateSource.Climate LOUISIANA = new ClimateSource.Climate(ClimateSource.CFA, 20.5, 28.0, 1580);
    private static final ClimateSource.Climate SCOTLAND = new ClimateSource.Climate(ClimateSource.CFB, 7.5, 13.5, 1950);
    private static final ClimateSource.Climate CAMBODIA = new ClimateSource.Climate(ClimateSource.AW, 27.5, 29.5, 1650);
    private static final ClimateSource.Climate SAHARA = new ClimateSource.Climate(ClimateSource.BWH, 25.0, 34.0, 25);

    private static final double JAN_MID = 0.5 / 12.0;
    private static final double MAY_MID = 4.5 / 12.0;
    private static final double JUL_MID = 6.5 / 12.0;
    private static final double SEP_START = 8.0 / 12.0;

    @Test void oklahomaSpringFavorsSupercellsAndTornadoesNotHurricanes() {
        EarthWeatherProfile ok = EarthWeatherProfile.of(35.5, -97.5, 360, MAY_MID, OKLAHOMA);
        EarthWeatherProfile scotland = EarthWeatherProfile.of(57.0, -5.0, 220, JUL_MID, SCOTLAND);
        EarthWeatherProfile sahara = EarthWeatherProfile.of(23.5, 11.0, 500, JUL_MID, SAHARA);
        EarthWeatherProfile cambodia = EarthWeatherProfile.of(11.5, 105.0, 15, JUL_MID, CAMBODIA);

        assertTrue(ok.tornadoPotential() > 0.45, "Oklahoma spring tornado potential should be strong: " + ok);
        assertTrue(ok.stormEnergy() > 0.45, "Oklahoma spring storm energy should be strong: " + ok);
        assertTrue(ok.windShear() > 0.55, "Oklahoma spring wind shear should be strong: " + ok);
        assertTrue(ok.tornadoPotential() > scotland.tornadoPotential() * 10.0, "Oklahoma >> Scotland tornadoes");
        assertTrue(ok.tornadoPotential() > sahara.tornadoPotential() * 10.0, "Oklahoma >> Sahara tornadoes");
        assertTrue(ok.tornadoPotential() > cambodia.tornadoPotential() * 4.0, "Oklahoma >> Cambodia tornadoes");

        assertTrue(ok.hurricanePotential() < 0.12, "Inland Oklahoma should not spawn hurricanes: " + ok);
        assertEquals(Integer.MAX_VALUE, PMWeatherCompat.cycloneChanceInOne(64, ok));
        assertTrue(PMWeatherCompat.soundingRisk(2.0f, ok) > PMWeatherCompat.soundingRisk(2.0f, scotland) * 5.0f);
    }

    @Test void louisianaLateSummerSupportsHurricanesWhileWinterAndScotlandDoNot() {
        EarthWeatherProfile laSep = EarthWeatherProfile.of(29.9, -90.1, 5, SEP_START, LOUISIANA);
        EarthWeatherProfile laJan = EarthWeatherProfile.of(29.9, -90.1, 5, JAN_MID, LOUISIANA);
        EarthWeatherProfile scotland = EarthWeatherProfile.of(57.0, -5.0, 20, SEP_START, SCOTLAND);
        EarthWeatherProfile okSep = EarthWeatherProfile.of(35.5, -97.5, 360, SEP_START, OKLAHOMA);

        assertTrue(laSep.seaSurfaceTempC() >= 26.5, "Gulf late-summer SST should exceed 26.5 C: " + laSep);
        assertTrue(laSep.hurricanePotential() > 0.40, "Louisiana late summer should favor cyclones: " + laSep);
        assertTrue(laSep.hurricanePotential() > okSep.hurricanePotential() * 4.0, "Louisiana >> Oklahoma hurricanes");
        assertTrue(laJan.hurricanePotential() < 0.05, "Louisiana winter should suppress hurricanes: " + laJan);
        assertTrue(scotland.hurricanePotential() < 0.01, "Scotland should never spawn tropical cyclones: " + scotland);
        assertTrue(scotland.seaSurfaceTempC() < 16.0, "Scotland North Atlantic SST stays cool: " + scotland);

        int laRoll = PMWeatherCompat.cycloneChanceInOne(64, laSep);
        assertTrue(laRoll >= 1 && laRoll < 64, "Active hurricane season should tighten cyclone roll: " + laRoll);
        assertEquals(Integer.MAX_VALUE, PMWeatherCompat.cycloneChanceInOne(64, laJan));
        assertEquals(Integer.MAX_VALUE, PMWeatherCompat.cycloneChanceInOne(64, scotland));
    }

    @Test void scotlandIsOvercastAndRainyWithoutSevereConvection() {
        EarthWeatherProfile scotland = EarthWeatherProfile.of(57.0, -5.0, 220, JUL_MID, SCOTLAND);
        EarthWeatherProfile sahara = EarthWeatherProfile.of(23.5, 11.0, 500, JUL_MID, SAHARA);

        assertTrue(scotland.cloudiness() > 0.75, "Scottish Highlands should be heavily overcast: " + scotland);
        assertTrue(scotland.rainChance() > 0.70, "Scottish Highlands should rain frequently: " + scotland);
        assertTrue(scotland.tornadoPotential() < 0.05, "Scottish Highlands should rarely have tornadoes: " + scotland);
        assertTrue(PMWeatherCompat.overcastPercent(1.0, scotland) > 0.90);
        assertTrue(PMWeatherCompat.overcastPercent(1.0, sahara) < 0.15);
    }

    @Test void cambodiaMonsoonSwingsDramaticallyAndFlipsInSouthernHemisphere() {
        EarthWeatherProfile wetNH = EarthWeatherProfile.of(11.5, 105.0, 15, JUL_MID, CAMBODIA);
        EarthWeatherProfile dryNH = EarthWeatherProfile.of(11.5, 105.0, 15, JAN_MID, CAMBODIA);

        assertTrue(wetNH.rainChance() > dryNH.rainChance() * 2.5, "Wet monsoon >> dry season rain chance");
        assertTrue(wetNH.rainIntensity() > dryNH.rainIntensity() * 2.0, "Wet monsoon >> dry season rain intensity");
        assertTrue(wetNH.cloudiness() > dryNH.cloudiness() * 2.0, "Wet monsoon >> dry season cloudiness");
        assertTrue(wetNH.moisture() > dryNH.moisture() * 2.0, "Wet monsoon >> dry season moisture");
        assertTrue(PMWeatherCompat.precipitation(0.5f, wetNH) > PMWeatherCompat.precipitation(0.5f, dryNH) * 4.0f);
        assertTrue(PMWeatherCompat.stormSpawnChancePerMinute(0.2, wetNH)
                > PMWeatherCompat.stormSpawnChancePerMinute(0.2, dryNH) * 3.0);

        // Southern Hemisphere tropical wet-winter/monsoon (e.g. northern Australia) has exact opposite phase.
        EarthWeatherProfile wetSH = EarthWeatherProfile.of(-12.5, 131.0, 15, JAN_MID, CAMBODIA);
        EarthWeatherProfile drySH = EarthWeatherProfile.of(-12.5, 131.0, 15, JUL_MID, CAMBODIA);
        assertEquals(wetNH.rainChance(), wetSH.rainChance(), 0.03);
        assertEquals(dryNH.rainChance(), drySH.rainChance(), 0.03);
        assertTrue(wetSH.rainChance() > drySH.rainChance() * 2.5, "Southern Hemisphere monsoon peaks in Jan");
    }

    @Test void saharaRemainsClearAndDry() {
        EarthWeatherProfile sahara = EarthWeatherProfile.of(23.5, 11.0, 500, JUL_MID, SAHARA);
        assertTrue(sahara.cloudiness() < 0.10, "Sahara skies stay clear: " + sahara);
        assertTrue(sahara.rainChance() < 0.05, "Sahara rain chance stays minimal: " + sahara);
        assertTrue(sahara.moisture() < 0.15, "Sahara moisture stays very low: " + sahara);
        assertTrue(sahara.tornadoPotential() < 0.02);
        assertTrue(sahara.hurricanePotential() < 0.05);
        assertTrue(PMWeatherCompat.stormSpawnChancePerMinute(0.2, sahara) < 0.03);
        assertEquals(0.0f, PMWeatherCompat.precipitation(0.0f, sahara), "Never fabricate rain from zero");
    }

    @Test void highElevationSuppressesConvectionAndFallbackWithoutClimateIsFinite() {
        ClimateSource.Climate lowland = new ClimateSource.Climate(ClimateSource.CFA, 16.0, 26.5, 900);
        ClimateSource.Climate mountain = new ClimateSource.Climate(ClimateSource.CFA, 3.0, 13.5, 900);
        EarthWeatherProfile low = EarthWeatherProfile.of(35.5, -100.0, 300, MAY_MID, lowland);
        EarthWeatherProfile high = EarthWeatherProfile.of(35.5, -105.0, 2400, MAY_MID, mountain);
        assertTrue(high.tornadoPotential() < low.tornadoPotential() * 0.25);

        for (double lat : new double[]{-75.0, -30.0, 0.0, 35.0, 75.0}) {
            EarthWeatherProfile fallback = EarthWeatherProfile.of(lat, 0.0, 100.0, MAY_MID, null);
            assertTrue(Double.isFinite(fallback.temperatureC()));
            assertTrue(fallback.dewpointC() <= fallback.temperatureC());
            assertTrue(fallback.cloudiness() >= 0.0 && fallback.cloudiness() <= 1.0);
            assertTrue(fallback.rainChance() >= 0.0 && fallback.rainChance() <= 1.0);
            assertTrue(fallback.tornadoPotential() >= 0.0 && fallback.tornadoPotential() <= 1.0);
            assertTrue(fallback.hurricanePotential() >= 0.0 && fallback.hurricanePotential() <= 1.0);
            assertTrue(PMWeatherCompat.cycloneChanceInOne(64, fallback) >= 1);
        }
    }

    @Test void calendarAlignmentAndBiomeTemperatureMonotonicity() {
        assertEquals(4.0 / 12.0, PMWeatherCompat.yearFraction(0L, 8), 1e-9, "Day 0 aligns with May 1");
        assertEquals(5.0 / 12.0, PMWeatherCompat.yearFraction(8L * 24000L, 8), 1e-9, "Month 1 step aligns with Jun 1");
        assertEquals(4.0 / 12.0, PMWeatherCompat.yearFraction(96L * 24000L, 8), 1e-9, "Full 12-month cycle wraps");

        EarthWeatherProfile cold = EarthWeatherProfile.of(65.0, 10.0, 100.0, JUL_MID, null);
        EarthWeatherProfile mild = EarthWeatherProfile.of(40.0, 10.0, 100.0, JUL_MID, null);
        EarthWeatherProfile hot = EarthWeatherProfile.of(10.0, 10.0, 100.0, JUL_MID, null);
        assertTrue(PMWeatherCompat.biomeTemperature(cold) < PMWeatherCompat.biomeTemperature(mild));
        assertTrue(PMWeatherCompat.biomeTemperature(mild) < PMWeatherCompat.biomeTemperature(hot));
    }

    @Test void optionalMixinsGatesServerLevelMixinAndConfigListsAllPMWeatherMixins() throws Exception {
        OptionalMixins plugin = new OptionalMixins();
        assertFalse(plugin.shouldApplyMixin(
                "net.minecraft.server.level.ServerLevel",
                "io.earthplatform.minecraft.cubic.mixin.compat.PMWeatherServerLevelMixin"),
                "PMWeatherServerLevelMixin must not apply when PM Weather is absent");

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("earthplatform.cubic.mixins.json")) {
            assertNotNull(in, "earthplatform.cubic.mixins.json");
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> common = strings(root.getAsJsonArray("mixins"));
            Set<String> client = strings(root.getAsJsonArray("client"));

            for (String expected : List.of(
                    "compat.PMWeatherServerConfigMixin",
                    "compat.PMWeatherLevelUpdaterMixin",
                    "compat.PMWeatherSeasonalUpdaterMixin",
                    "compat.PMWeatherServerLevelMixin",
                    "compat.PMWeatherStormBoundsMixin",
                    "compat.PMWeatherStormPosMixin",
                    "compat.PMWeatherSupercellMixin",
                    "compat.PMWeatherGameBusMixin",
                    "compat.PMWeatherMoistureMixin",
                    "compat.PMWeatherThermodynamicsMixin",
                    "compat.PMWeatherSoundingMixin",
                    "compat.PMWeatherCloudsMixin",
                    "compat.PMWeatherWeatherHandlerMixin"
            )) {
                assertTrue(common.contains(expected), "Missing common mixin " + expected);
            }
            assertTrue(client.contains("compat.PMWeatherVolumeShaderMixin"), "Missing client mixin PMWeatherVolumeShaderMixin");
        }
    }

    @Test void verifyAllMixinBytecodeTargetsAgainstPMWeatherJarIfPresent() throws Exception {
        Path jar = findPMWeatherJar();
        Assumptions.assumeTrue(jar != null, "PM Weather jar not present in environment; skipping ASM target check");

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // 1. ServerConfig
            ClassNode serverConfig = readClass(zip, "dev/protomanly/pmweather/config/ServerConfig");
            assertHasField(serverConfig, "validDimensions", "Ljava/util/List;");
            assertHasField(serverConfig, "monthLength", "I");
            assertHasMethod(serverConfig, "onLoad", "(Lnet/neoforged/fml/event/config/ModConfigEvent;)V");

            // 2. LevelUpdater
            ClassNode updater = readClass(zip, "dev/protomanly/pmweather/level/LevelUpdater");
            assertHasMethod(updater, "isValidDimension", "(Lnet/minecraft/world/level/Level;)Z");
            MethodNode updaterTick = assertHasMethod(updater, "tick", "(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z");
            assertHasInvoke(updaterTick, "net/minecraft/world/level/Level", "isLoaded", "(Lnet/minecraft/core/BlockPos;)Z", 1);
            assertHasMethod(updater, "onSurface", "(Lnet/minecraft/world/level/chunk/ChunkAccess;ZLjava/util/function/BiConsumer;)V");

            // 3. SeasonalLevelUpdater
            ClassNode seasonal = readClass(zip, "dev/protomanly/pmweather/level/updaters/SeasonalLevelUpdater");
            MethodNode seasonalUpdate = assertHasMethod(seasonal, "update", "(Lnet/minecraft/world/level/chunk/ChunkAccess;J)V");
            assertHasInvoke(seasonalUpdate, "net/minecraft/world/level/chunk/ChunkAccess", "getHeight",
                    "(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", 1);
            MethodNode seasonalLambda = assertHasMethod(seasonal, "lambda$update$1",
                    "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/ChunkAccess;FLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
            assertHasInvoke(seasonalLambda, "net/minecraft/world/level/Level", "isOutsideBuildHeight", "(Lnet/minecraft/core/BlockPos;)Z", 1);
            assertHasInvoke(seasonalLambda, "net/minecraft/world/level/chunk/ChunkAccess", "getBlockState",
                    "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", 1);

            // 4. ServerLevelMixin merged methods
            ClassNode serverLevelMixin = readClass(zip, "dev/protomanly/pmweather/mixin/ServerLevelMixin");
            for (String m : List.of("shouldFreeze", "shouldSnow", "shouldIce", "shouldSleet")) {
                MethodNode mn = findMethodByName(serverLevelMixin, m);
                assertNotNull(mn, "ServerLevelMixin." + m);
                assertHasInvoke(mn, "net/minecraft/server/level/ServerLevel", "getMinBuildHeight", "()I", 1);
                assertHasInvoke(mn, "net/minecraft/server/level/ServerLevel", "getMaxBuildHeight", "()I", 1);
            }

            // 5. Storm / Cyclone / FireWhirl / Supercell
            ClassNode storm = readClass(zip, "dev/protomanly/pmweather/weather/Storm");
            assertHasField(storm, "level", "Lnet/minecraft/world/level/Level;");
            assertHasMethod(storm, "getBlockPos", "()Lnet/minecraft/core/BlockPos;");
            assertHasInvoke(assertHasMethod(storm, "move", "()V"), "net/minecraft/world/level/Level", "getMaxBuildHeight", "()I", 1);

            ClassNode cyclone = readClass(zip, "dev/protomanly/pmweather/weather/storms/Cyclone");
            assertHasInvoke(assertHasMethod(cyclone, "move", "()V"), "net/minecraft/world/level/Level", "getMaxBuildHeight", "()I", 1);

            ClassNode fireWhirl = readClass(zip, "dev/protomanly/pmweather/weather/storms/FireWhirl");
            assertHasInvoke(assertHasMethod(fireWhirl, "move", "()V"), "net/minecraft/world/level/Level", "getMaxBuildHeight", "()I", 1);
            assertHasInvoke(assertHasMethod(fireWhirl, "getBlockPos", "()Lnet/minecraft/core/BlockPos;"),
                    "net/minecraft/world/level/Level", "isLoaded", "(Lnet/minecraft/core/BlockPos;)Z", 1);
            assertHasInvoke(assertHasMethod(fireWhirl, "tickServer", "(Lnet/minecraft/core/BlockPos;)V"),
                    "net/minecraft/world/level/Level", "isInWorldBounds", "(Lnet/minecraft/core/BlockPos;)Z", 1);

            ClassNode supercell = readClass(zip, "dev/protomanly/pmweather/weather/storms/Supercell");
            assertHasInvoke(assertHasMethod(supercell, "shouldLoadChunk", "(Lnet/minecraft/world/level/ChunkPos;D)Z"),
                    "net/minecraft/server/level/ServerLevel", "isInWorldBounds", "(Lnet/minecraft/core/BlockPos;)Z", 1);
            assertHasInvoke(assertHasMethod(supercell, "getBlockPos", "()Lnet/minecraft/core/BlockPos;"),
                    "net/minecraft/world/level/Level", "isLoaded", "(Lnet/minecraft/core/BlockPos;)Z", 1);
            MethodNode scTick = assertHasMethod(supercell, "tickServer", "(Lnet/minecraft/core/BlockPos;)V");
            assertHasInvoke(scTick, "net/minecraft/world/level/Level", "isInWorldBounds", "(Lnet/minecraft/core/BlockPos;)Z", 1);
            assertHasInvoke(scTick, "net/minecraft/world/level/Level", "isLoaded", "(Lnet/minecraft/core/BlockPos;)Z", 1);
            assertHasLdc(assertHasMethod(supercell, "changeY", "(Lnet/minecraft/core/BlockPos;)V"), 69.0d, 1);

            // 6. GameBusEvents
            ClassNode gameBus = readClass(zip, "dev/protomanly/pmweather/event/GameBusEvents");
            MethodNode levelTick = assertHasMethod(gameBus, "onLevelTick", "(Lnet/neoforged/neoforge/event/tick/LevelTickEvent$Post;)V");
            assertHasInvoke(levelTick, "net/neoforged/neoforge/event/tick/LevelTickEvent$Post", "getLevel", "()Lnet/minecraft/world/level/Level;", 1);
            assertHasFieldInsn(levelTick, Opcodes.GETSTATIC, "net/minecraft/world/level/Level", "OVERWORLD", "Lnet/minecraft/resources/ResourceKey;", 2);
            assertHasFieldInsn(levelTick, Opcodes.GETSTATIC, "dev/protomanly/pmweather/event/GameBusEvents", "lastTick", "J", 2);
            assertHasFieldInsn(levelTick, Opcodes.PUTSTATIC, "dev/protomanly/pmweather/event/GameBusEvents", "lastTick", "J", 2);
            assertHasInvoke(levelTick, "net/minecraft/world/level/Level", "isLoaded", "(Lnet/minecraft/core/BlockPos;)Z", 2);
            assertHasInvoke(levelTick, "net/minecraft/server/level/ServerPlayer", "getX", "()D", 2);
            assertHasInvoke(levelTick, "net/minecraft/world/level/Level", "getMaxBuildHeight", "()I", 2);
            assertHasFieldInsn(levelTick, Opcodes.GETSTATIC, "dev/protomanly/pmweather/config/ServerConfig", "chanceInOneCyclone", "I", 1);
            assertHasFieldInsn(levelTick, Opcodes.GETSTATIC, "dev/protomanly/pmweather/config/ServerConfig", "stormSpawnChancePerMinute", "D", 1);

            MethodNode chunkSent = assertHasMethod(gameBus, "onChunkSent", "(Lnet/neoforged/neoforge/event/level/ChunkWatchEvent$Sent;)V");
            assertHasInvoke(chunkSent, "net/neoforged/neoforge/event/level/ChunkWatchEvent$Sent", "getLevel", "()Lnet/minecraft/server/level/ServerLevel;", 1);
            assertHasFieldInsn(chunkSent, Opcodes.GETSTATIC, "net/minecraft/server/level/ServerLevel", "OVERWORLD", "Lnet/minecraft/resources/ResourceKey;", 1);

            // 7. MoistureHandler
            ClassNode moisture = readClass(zip, "dev/protomanly/pmweather/seasons/MoistureHandler");
            assertHasFieldInsn(assertHasMethod(moisture, "setMoistureOnChunk",
                    "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/lang/Float;)V"),
                    Opcodes.GETSTATIC, "net/minecraft/world/level/Level", "OVERWORLD", "Lnet/minecraft/resources/ResourceKey;", 1);
            assertHasFieldInsn(assertHasMethod(moisture, "HandleChunkData", "(Lnet/minecraft/nbt/CompoundTag;)V"),
                    Opcodes.GETSTATIC, "net/minecraft/world/level/Level", "OVERWORLD", "Lnet/minecraft/resources/ResourceKey;", 1);
            assertHasMethod(moisture, "getStartMoisture", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/ChunkAccess;)F");

            // 8. ThermodynamicEngine
            ClassNode thermo = readClass(zip, "dev/protomanly/pmweather/weather/ThermodynamicEngine");
            MethodNode samplePoint = assertHasMethod(thermo, "samplePoint",
                    "(Ldev/protomanly/pmweather/weather/WeatherHandler;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;Ldev/protomanly/pmweather/block/entity/RadarBlockEntity;ILjava/lang/Integer;ZZ)Ldev/protomanly/pmweather/weather/ThermodynamicEngine$AtmosphericDataPoint;");
            assertHasLdc(samplePoint, 20.0f, 2);
            assertHasInvoke(samplePoint, "net/minecraft/world/level/biome/Biome", "getBaseTemperature", "()F", 2);
            assertHasInvoke(samplePoint, "net/minecraft/world/level/biome/Biome$ClimateSettings", "downfall", "()F", 2);
            assertHasInvoke(samplePoint, "dev/protomanly/pmweather/seasons/SeasonHandler", "getSeasonEffectSine",
                    "(Lnet/minecraft/world/level/Level;F)F", 3);

            MethodNode getSst = assertHasMethod(thermo, "GetSST",
                    "(Ldev/protomanly/pmweather/weather/WeatherHandler;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;Ldev/protomanly/pmweather/block/entity/RadarBlockEntity;I)Ljava/lang/Float;");
            assertHasInvoke(getSst, "dev/protomanly/pmweather/seasons/SeasonHandler", "getSeasonEffectSine",
                    "(Lnet/minecraft/world/level/Level;F)F", 1);
            assertHasInvoke(getSst, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", 1);

            // 9. Sounding
            ClassNode sounding = readClass(zip, "dev/protomanly/pmweather/weather/Sounding");
            assertHasField(sounding, "position", "Lnet/minecraft/world/phys/Vec3;");
            MethodNode getRisk = assertHasMethod(sounding, "getRisk", "(I)F");
            assertHasInvoke(getRisk, "dev/protomanly/pmweather/weather/WeatherHandler", "getWorld", "()Lnet/minecraft/world/level/Level;", 1);

            // 10. Clouds
            ClassNode clouds = readClass(zip, "dev/protomanly/pmweather/weather/Clouds");
            MethodNode cloudDensity = assertHasMethod(clouds, "getCloudDensity",
                    "(Ldev/protomanly/pmweather/weather/WeatherHandler;Lorg/joml/Vector2f;FZI)F");
            assertHasInvoke(cloudDensity, "dev/protomanly/pmweather/weather/WeatherHandler", "getWorld", "()Lnet/minecraft/world/level/Level;", 3);
            assertHasInvoke(cloudDensity, "dev/protomanly/pmweather/seasons/SeasonHandler", "getSeasonEffectSine",
                    "(Lnet/minecraft/world/level/Level;F)F", 1);
            assertHasFieldInsn(cloudDensity, Opcodes.GETSTATIC, "dev/protomanly/pmweather/config/ServerConfig", "overcastPercent", "D", 2);

            // 11. WeatherHandler
            ClassNode handler = readClass(zip, "dev/protomanly/pmweather/weather/WeatherHandler");
            assertHasMethod(handler, "getWorld", "()Lnet/minecraft/world/level/Level;");
            assertHasMethod(handler, "_getPrecipitation", "(Lnet/minecraft/world/phys/Vec3;I)F");

            // 12. VolumeShader
            ClassNode shader = readClass(zip, "dev/protomanly/pmweather/shaders/post/VolumeShader");
            MethodNode pre = assertHasMethod(shader, "pre",
                    "(Lfoundry/veil/api/client/render/post/PostPipeline;Lfoundry/veil/api/client/render/post/PostPipeline$Context;)V");
            assertHasFieldInsn(pre, Opcodes.GETSTATIC, "dev/protomanly/pmweather/config/ServerConfig", "layer0Height", "D", 2);
            assertHasFieldInsn(pre, Opcodes.GETSTATIC, "dev/protomanly/pmweather/config/ServerConfig", "layerCHeight", "D", 1);
            assertHasFieldInsn(pre, Opcodes.GETSTATIC, "dev/protomanly/pmweather/config/ServerConfig", "overcastPercent", "D", 1);
            assertHasInvoke(pre, "dev/protomanly/pmweather/seasons/SeasonHandler", "getSeasonEffectSine",
                    "(Lnet/minecraft/world/level/Level;F)F", 1);
        }
    }

    private static Path findPMWeatherJar() {
        String env = System.getenv("PMWEATHER_JAR");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> out = new HashSet<>();
        for (JsonElement el : array) out.add(el.getAsString());
        return out;
    }

    private static ClassNode readClass(ZipFile zip, String internalName) throws Exception {
        ZipEntry entry = zip.getEntry(internalName + ".class");
        assertNotNull(entry, "Missing class " + internalName);
        try (InputStream in = zip.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static void assertHasField(ClassNode cls, String name, String desc) {
        for (FieldNode f : cls.fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) return;
        }
        fail("Missing field " + cls.name + "." + name + ":" + desc);
    }

    private static MethodNode assertHasMethod(ClassNode cls, String name, String desc) {
        for (MethodNode m : cls.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        fail("Missing method " + cls.name + "." + name + desc);
        return null;
    }

    private static MethodNode findMethodByName(ClassNode cls, String name) {
        for (MethodNode m : cls.methods) if (m.name.equals(name)) return m;
        return null;
    }

    private static void assertHasInvoke(MethodNode m, String owner, String name, String desc, int expectedCount) {
        int count = 0;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)) {
                count++;
            }
        }
        assertEquals(expectedCount, count, "Invoke count mismatch in " + m.name + " for " + owner + "." + name + desc);
    }

    private static void assertHasFieldInsn(MethodNode m, int opcode, String owner, String name, String desc, int expectedCount) {
        int count = 0;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == opcode
                    && fi.owner.equals(owner) && fi.name.equals(name) && fi.desc.equals(desc)) {
                count++;
            }
        }
        assertEquals(expectedCount, count, "Field insn count mismatch in " + m.name + " for " + owner + "." + name + ":" + desc);
    }

    private static void assertHasLdc(MethodNode m, Object constant, int expectedCount) {
        int count = 0;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof LdcInsnNode ldc && constant.equals(ldc.cst)) {
                count++;
            }
        }
        assertEquals(expectedCount, count, "LDC constant count mismatch in " + m.name + " for " + constant);
    }
}
