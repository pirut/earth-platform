package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.EarthPlatformMod;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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
        var folder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
        CubeGenerator generator = CubicSettings.open(folder, level.getSeed(), level.registryAccess().registryOrThrow(Registries.BIOME));
        ((CubicLevel) level).earthplatform$setCubes(new ServerCubeWorld(level, generator));
        // Fetch the data around the arrival point now, so the first visit does not wait on it.
        int[] home = generator.arrival();
        // On the IO pool: downloads wait on the network, which must not hold up world generation workers.
        Util.ioPool().execute(() -> generator.prepare(home[0], home[1]));
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
        if (event.getLevel() instanceof ServerLevel level && CubicLevel.of(level) instanceof ServerCubeWorld cubes) {
            cubes.tick();
            arrive(level, cubes);
        }
    }

    /** A travel waiting for the cubes at its landing spot; the player stays where they are meanwhile. */
    private record Arrival(CommandSourceStack source, ServerPlayer player, int x, int z, int waited) {}

    /** At most this many ticks of waiting before the rest of the cubes are loaded on the spot. */
    private static final int ARRIVAL_PATIENCE = 200;
    private static final List<Arrival> ARRIVALS = new ArrayList<>();

    /**
     * Moves each waiting traveller once the workers have the cubes around their landing spot, so
     * several players travelling at once never generate those cubes on the server thread.
     */
    private static void arrive(ServerLevel level, ServerCubeWorld cubes) {
        for (int i = ARRIVALS.size() - 1; i >= 0; i--) {
            Arrival arrival = ARRIVALS.get(i);
            if (arrival.player.isRemoved()) { ARRIVALS.remove(i); continue; }
            CubeGenerator generator = cubes.generator();
            int ground = Math.max(generator.surfaceY(arrival.x, arrival.z), generator.waterY(arrival.x, arrival.z));
            if (!cubes.readyAround(new BlockPos(arrival.x, ground, arrival.z)) && arrival.waited < ARRIVAL_PATIENCE) {
                ARRIVALS.set(i, new Arrival(arrival.source, arrival.player, arrival.x, arrival.z, arrival.waited + 1));
                continue;
            }
            ARRIVALS.remove(i);
            Vec3 stand = standOn(cubes, arrival.x, arrival.z);
            arrival.player.teleportTo(level, stand.x, stand.y, stand.z, arrival.player.getYRot(), arrival.player.getXRot());
            tellPlace(arrival.source, cubes, BlockPos.containing(stand));
        }
    }

    /** The world's arrival point, with its cubes loaded so the player lands on solid ground. */
    public static Vec3 arrival(ServerCubeWorld cubes) {
        int[] spot = landing(cubes.generator(), cubes.generator().arrival(), 4096, true);
        return standOn(cubes, spot[0], spot[1]);
    }

    /**
     * A place to stand near a column, on the ground or swimming on water, with the cubes there
     * loaded: the nearest spot within a few blocks with room above the ground (grass and a dusting
     * of snow leave room) and not on powder snow, which a player sinks into and freezes in; else on
     * top of whatever stands there, such as a tree, with a drift underfoot packed firm.
     */
    private static Vec3 standOn(ServerCubeWorld cubes, int x, int z) {
        CubeGenerator generator = cubes.generator();
        int ground = Math.max(generator.surfaceY(x, z), generator.waterY(x, z));
        cubes.loadAround(new BlockPos(x, ground, z));
        // First somewhere roomy, so nobody arrives in a one-block pit facing a wall; then anywhere with headroom.
        for (boolean roomy : new boolean[]{true, false})
            for (int radius = 0; radius <= 15; radius++) for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                int y = Math.max(generator.surfaceY(x + dx, z + dz), generator.waterY(x + dx, z + dz)) + 1;
                if (open(cubes, x + dx, y, z + dz) && open(cubes, x + dx, y + 1, z + dz)
                        && !cubes.getBlockState(new BlockPos(x + dx, y - 1, z + dz)).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                        && (!roomy || roomy(cubes, x + dx, y, z + dz)))
                    return new Vec3(x + dx + 0.5, y, z + dz + 0.5);
            }
        int y = ground + 1;
        while (y < ground + 48 && !(open(cubes, x, y, z) && open(cubes, x, y + 1, z))) y++;
        BlockPos under = new BlockPos(x, y - 1, z);
        if (cubes.getBlockState(under).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW))
            cubes.level().setBlockAndUpdate(under, net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState());
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    /**
     * Whether a spot has room around it: at least six of the eight blocks beside the player's feet
     * and head are open, and none of those open neighbours is a sheer drop, so they can look around
     * and walk off.
     */
    private static boolean roomy(ServerCubeWorld cubes, int x, int y, int z) {
        int room = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dz == 0) continue;
            if (!open(cubes, x + dx, y, z + dz) || !open(cubes, x + dx, y + 1, z + dz)) continue;
            if (open(cubes, x + dx, y - 1, z + dz) && open(cubes, x + dx, y - 2, z + dz) && open(cubes, x + dx, y - 3, z + dz)) continue;
            room++;
        }
        return room >= 6;
    }

    /** Whether a player fits in a block: air, or a dry one they walk through, such as grass or a thin layer of snow. */
    private static boolean open(ServerCubeWorld cubes, int x, int y, int z) {
        var state = cubes.getBlockState(new BlockPos(x, y, z));
        return state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty();
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
        root.then(Commands.literal("find").then(Commands.argument("block", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .executes(context -> approach(context.getSource(), net.minecraft.commands.arguments.ResourceLocationArgument.getId(context, "block")))));
        root.then(Commands.literal("goto").then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-85, 85))
                .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180)).executes(context -> travel(context.getSource(),
                        DoubleArgumentType.getDouble(context, "latitude"), DoubleArgumentType.getDouble(context, "longitude"))))));
        root.then(Commands.literal("locate").then(Commands.argument("structure", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .executes(context -> locate(context.getSource(), net.minecraft.commands.arguments.ResourceLocationArgument.getId(context, "structure")))));
        root.then(Commands.literal("structures").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!(CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes)) return 0;
            var pos = player.blockPosition();
            java.util.concurrent.CompletableFuture.supplyAsync(() -> cubes.generator().structuresAt(pos.getX(), pos.getY(), pos.getZ()), net.minecraft.Util.backgroundExecutor())
                    .thenAcceptAsync(text -> context.getSource().sendSuccess(() -> Component.literal(text), false), context.getSource().getServer());
            return 1;
        }));
        root.then(Commands.literal("where").executes(context -> where(context.getSource())));
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
        tellPlace(source, cubes, BlockPos.containing(spot));
        String credits = cubes.generator().credits();
        if (!credits.isEmpty()) source.sendSuccess(() -> Component.literal(credits).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** Says where a position is: latitude and longitude with real height on the Earth, else just its Y. */
    private static void tellPlace(CommandSourceStack source, ServerCubeWorld cubes, BlockPos feet) {
        CubeGenerator generator = cubes.generator();
        String place = generator.place(feet.getX(), feet.getZ());
        String text = place.isEmpty() ? "Cubic world: standing at Y " + feet.getY() + "."
                : "Earth " + place + ": ground " + (generator.surfaceY(feet.getX(), feet.getZ()) - generator.seaLevel()) + " m, standing at Y "
                        + feet.getY() + " (" + (feet.getY() - generator.seaLevel() - 1) + " m).";
        source.sendSuccess(() -> Component.literal(text), false);
    }

    /** Finds the nearest vanilla structure of a kind in the cubic world, off the server thread, as /locate does elsewhere. */
    private static int locate(CommandSourceStack source, net.minecraft.resources.ResourceLocation structure) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!(CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes)) { source.sendFailure(Component.literal("Not in the cubic world.")); return 0; }
        var from = player.blockPosition();
        source.sendSuccess(() -> Component.literal("Looking for " + structure + "...").withStyle(ChatFormatting.GRAY), false);
        var wanted = source.getServer().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).get(structure);
        if (wanted == null) { source.sendFailure(Component.literal("No structure " + structure)); return 0; }
        java.util.concurrent.CompletableFuture.supplyAsync(() -> cubes.generator().locate(s -> s == wanted, from.getX(), from.getZ(), 100), net.minecraft.Util.backgroundExecutor())
                .thenAcceptAsync(found -> {
                    if (found == null) source.sendFailure(Component.literal("No " + structure + " within 1600 blocks."));
                    else source.sendSuccess(() -> Component.literal(structure + " at " + found.toShortString() + ", "
                            + (int) Math.sqrt(found.distSqr(new net.minecraft.core.BlockPos(from.getX(), found.getY(), from.getZ()))) + " blocks away"), false);
                }, source.getServer());
        return 1;
    }

    public static int where(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!(CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes)) { source.sendFailure(Component.literal("Not in the cubic world.")); return 0; }
        tellPlace(source, cubes, player.blockPosition());
        return 1;
    }

    /**
     * Travels to a latitude and longitude on the Earth: terrain there is fetched off the server
     * thread, then the player lands on the nearest gentle dry spot, or on the water when there is
     * none close by.
     */
    public static int travel(CommandSourceStack source, double latitude, double longitude) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return travel(source, source.getPlayerOrException(), latitude, longitude);
    }

    /** Sends {@code player} there; the replies go to {@code source}, which may be the console. */
    public static int travel(CommandSourceStack source, ServerPlayer player, double latitude, double longitude) {
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        if (level == null || !(CubicLevel.of(level) instanceof ServerCubeWorld cubes) || !(cubes.generator() instanceof EarthGenerator earth)) {
            source.sendFailure(Component.literal("This save's cubic world is not the Earth."));
            return 0;
        }
        int[] target = earth.block(latitude, longitude);
        source.sendSuccess(() -> Component.literal("Fetching terrain around " + earth.place(target[0], target[1]) + "...").withStyle(ChatFormatting.GRAY), false);
        CompletableFuture.supplyAsync(() -> {
            earth.prepare(target[0], target[1]);
            return landing(earth, target, 256, false);
        }, Util.ioPool()).whenComplete((spot, failure) -> source.getServer().execute(() -> {
            if (failure != null) {
                source.sendFailure(Component.literal("Could not load terrain there: " + failure.getMessage()));
                return;
            }
            if (player.isRemoved()) return;
            ARRIVALS.removeIf(waiting -> waiting.player == player);
            ARRIVALS.add(new Arrival(source, player, spot[0], spot[1], 0));
        }));
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

    /**
     * Spirals out from a point to the first dry, gentle spot within a radius, skipping ground too
     * cold or high to start on when {@code welcoming}; the point itself when there is none.
     */
    private static int[] landing(CubeGenerator generator, int[] centre, int maxRadius, boolean welcoming) {
        for (int radius = 0; radius < maxRadius; radius += 16) {
            for (int step = 0; step < Math.max(1, radius / 4); step++) {
                double angle = step * Math.PI * 2 / Math.max(1, radius / 4);
                int x = centre[0] + (int) Math.round(Math.cos(angle) * radius), z = centre[1] + (int) Math.round(Math.sin(angle) * radius);
                int y = generator.surfaceY(x, z);
                if (y <= generator.seaLevel() + 2 || generator.waterY(x, z) >= y) continue;
                int slope = Math.abs(generator.surfaceY(x + 2, z) - generator.surfaceY(x - 2, z))
                        + Math.abs(generator.surfaceY(x, z + 2) - generator.surfaceY(x, z - 2));
                if (slope <= 3 && !(welcoming && generator.barren(x, z, y))) return new int[]{x, z};
            }
        }
        return centre;
    }

    static boolean isCubic(Level level) { return CubicLevel.of(level) != null; }
}
