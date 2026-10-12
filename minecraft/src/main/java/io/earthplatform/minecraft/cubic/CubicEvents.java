package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.gen.HighlandsGenerator;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Server-side lifecycle of cubic levels, respawning and the travel commands. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class CubicEvents {
    private CubicEvents() {}

    @EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}
        @SubscribeEvent public static void payloads(RegisterPayloadHandlersEvent event) { CubePayloads.register(event); }
    }

    @SubscribeEvent public static void load(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Cubic.isCubic(level.dimension())) return;
        CubeGenerator generator = new HighlandsGenerator(level.getSeed(), level.registryAccess().registryOrThrow(Registries.BIOME));
        ((CubicLevel) level).earthplatform$setCubes(new ServerCubeWorld(level, generator));
    }

    @SubscribeEvent public static void save(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && CubicLevel.of(level) instanceof ServerCubeWorld cubes) cubes.save();
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && CubicLevel.of(level) instanceof ServerCubeWorld cubes) {
            cubes.close();
            ((CubicLevel) level).earthplatform$setCubes(null);
        }
    }

    @SubscribeEvent public static void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && CubicLevel.of(level) instanceof ServerCubeWorld cubes) cubes.tick();
    }

    /** Dying in the cubic world without a bed brings the player back to its arrival point, not the overworld. */
    @SubscribeEvent public static void respawn(PlayerRespawnPositionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes)) return;
        DimensionTransition target = event.getDimensionTransition();
        if (Cubic.isCubic(target.newLevel().dimension())) return;
        if (player.getRespawnPosition() != null && !target.missingRespawnBlock()) return;
        event.setDimensionTransition(new DimensionTransition(player.serverLevel(), arrival(cubes), Vec3.ZERO,
                target.yRot(), 0.0F, target.missingRespawnBlock(), target.postDimensionTransition()));
    }

    /** The world's arrival point, with its cubes loaded so the player lands on solid ground. */
    private static Vec3 arrival(ServerCubeWorld cubes) {
        int[] spot = landing(cubes.generator());
        int x = spot[0], z = spot[1], ground = cubes.generator().surfaceY(x, z);
        cubes.loadAround(new net.minecraft.core.BlockPos(x, ground, z));
        int y = ground + 1;
        while (y < ground + 48 && !(cubes.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).isAir()
                && cubes.getBlockState(new net.minecraft.core.BlockPos(x, y + 1, z)).isAir())) y++;
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("cubes");
        root.then(Commands.literal("enter").executes(context -> enter(context.getSource())));
        root.then(Commands.literal("leave").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            ServerLevel overworld = player.server.overworld();
            var spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
            return 1;
        }));
        root.then(Commands.literal("goto").then(Commands.argument("block", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .executes(context -> approach(context.getSource(), net.minecraft.commands.arguments.ResourceLocationArgument.getId(context, "block")))));
        root.then(Commands.literal("light").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            var pos = player.blockPosition();
            var level = player.serverLevel();
            String text = "Light at " + pos.toShortString() + ": sky " + level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos)
                    + ", block " + level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos)
                    + (CubicLevel.of(level) instanceof ServerCubeWorld cubes && !cubes.lightReady(pos) ? " (estimate, lighting)" : "");
            context.getSource().sendSuccess(() -> Component.literal(text), false);
            return 1;
        }));
        root.then(Commands.literal("status").executes(context -> {
            ServerLevel level = context.getSource().getServer().getLevel(Cubic.LEVEL);
            String text = level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes
                    ? "Cubic world: " + cubes.describe() : "Cubic world is not loaded.";
            context.getSource().sendSuccess(() -> Component.literal(text), false);
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    private static int enter(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        if (level == null || !(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) {
            source.sendFailure(Component.literal("The cubic world is missing from this save."));
            return 0;
        }
        Vec3 spot = arrival(cubes);
        player.teleportTo(level, spot.x, spot.y, spot.z, player.getYRot(), player.getXRot());
        int ground = (int) spot.y - 1;
        source.sendSuccess(() -> Component.literal("Cubic world: ground at Y " + ground + ", far above vanilla's build limit."), false);
        return 1;
    }

    /** Test helper: stand next to the nearest loaded block of a kind, facing it. */
    private static int approach(CommandSourceStack source, net.minecraft.resources.ResourceLocation id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
        if (!(CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes)) { source.sendFailure(Component.literal("Not in the cubic world.")); return 0; }
        net.minecraft.core.BlockPos origin = player.blockPosition(), best = null;
        double bestDistance = Double.MAX_VALUE;
        for (var it = cubes.loaded(); it.hasNext(); ) {
            Cube cube = it.next();
            if (!cube.section.maybeHas(state -> state.is(block))) continue;
            for (int i = 0; i < 4096; i++) {
                int x = (cube.x << 4) + (i & 15), y = (cube.y << 4) + (i >> 8), z = (cube.z << 4) + (i >> 4 & 15);
                if (!cube.get(x, y, z).is(block)) continue;
                double d = origin.distSqr(new net.minecraft.core.BlockPos(x, y, z));
                if (d < bestDistance && standing(cubes, x, y, z) != null) { bestDistance = d; best = new net.minecraft.core.BlockPos(x, y, z); }
            }
        }
        if (best == null) { source.sendFailure(Component.literal("No reachable " + id + " in loaded cubes.")); return 0; }
        net.minecraft.core.BlockPos stand = standing(cubes, best.getX(), best.getY(), best.getZ());
        double dx = best.getX() - stand.getX(), dz = best.getZ() - stand.getZ(), dy = best.getY() + 0.5 - (stand.getY() + 1.62);
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        player.teleportTo(player.serverLevel(), stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, yaw, pitch);
        net.minecraft.core.BlockPos target = best;
        source.sendSuccess(() -> Component.literal(id + " at " + target.toShortString()), false);
        return 1;
    }

    private static net.minecraft.core.BlockPos standing(ServerCubeWorld cubes, int x, int y, int z) {
        int[][] around = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        for (int dy = 0; dy >= -1; dy--) for (int[] d : around) {
            net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(x + d[0], y + dy, z + d[1]);
            if (cubes.getBlockState(feet.below()).isSolid() && cubes.getBlockState(feet).isAir() && cubes.getBlockState(feet.above()).isAir()) return feet;
        }
        return null;
    }

    /** Spiral out from the origin to the first dry, gentle spot. */
    private static int[] landing(CubeGenerator generator) {
        for (int radius = 0; radius < 4096; radius += 16) {
            for (int step = 0; step < Math.max(1, radius / 4); step++) {
                double angle = step * Math.PI * 2 / Math.max(1, radius / 4);
                int x = (int) Math.round(Math.cos(angle) * radius), z = (int) Math.round(Math.sin(angle) * radius);
                int y = generator.surfaceY(x, z);
                if (y <= HighlandsGenerator.SEA + 2) continue;
                int slope = Math.abs(generator.surfaceY(x + 2, z) - generator.surfaceY(x - 2, z))
                        + Math.abs(generator.surfaceY(x, z + 2) - generator.surfaceY(x, z - 2));
                if (slope <= 3 && y < HighlandsGenerator.TREELINE) return new int[]{x, z};
            }
        }
        return new int[]{0, 0};
    }

    static boolean isCubic(Level level) { return CubicLevel.of(level) != null; }
}
