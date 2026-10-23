package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
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

/** Server-side lifecycle of cubic levels, arrivals and the /cubes debug commands. */
@EventBusSubscriber(modid = Cubic.MOD_ID)
public final class CubicEvents {
    private CubicEvents() {}

    @EventBusSubscriber(modid = Cubic.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}
        @SubscribeEvent public static void payloads(RegisterPayloadHandlersEvent event) { CubePayloads.register(event); }
    }

    @SubscribeEvent public static void load(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Cubic.isCubic(level.dimension())) return;
        var folder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
        CubeGenerator generator = Cubic.open(level, folder);
        ((CubicLevel) level).earthplatform$setCubes(new ServerCubeWorld(level, generator));
        // Fetch the data around the arrival point now, so the first visit does not wait on it.
        int[] home = generator.arrival();
        // On the IO pool: downloads wait on the network, which must not hold up world generation workers.
        // The cubes' data first, which the first arrival waits on, then the far levels'.
        Util.ioPool().execute(() -> {
            generator.prepareNear(home[0], home[1]);
            generator.prepare(home[0], home[1]);
        });
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
    private record Arrival(CommandSourceStack source, ServerPlayer player, int x, int z, int waited, @Nullable int[] view) {}

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
                ARRIVALS.set(i, new Arrival(arrival.source, arrival.player, arrival.x, arrival.z, arrival.waited + 1, arrival.view));
                continue;
            }
            ARRIVALS.remove(i);
            Vec3 stand = standOn(cubes, arrival.x, arrival.z);
            float yaw = lookOut(cubes, stand, facing(stand, arrival.view, arrival.player.getYRot()));
            arrival.player.teleportTo(level, stand.x, stand.y, stand.z, yaw, 0.0F);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new CubicArrivalEvent(arrival.player));
            tellPlace(arrival.source, cubes, BlockPos.containing(stand));
        }
    }

    /**
     * The way to face from a spot toward a block column (a peak or sight), level with the horizon;
     * {@code otherwise} when there is none or the spot is practically on it.
     */
    public static float facing(Vec3 from, @Nullable int[] view, float otherwise) {
        if (view == null) return otherwise;
        double dx = view[0] + 0.5 - from.x, dz = view[1] + 0.5 - from.z;
        if (dx * dx + dz * dz < 64 * 64) return otherwise;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** How far ahead, in blocks, a look-out checks for a clear view, and the run that keeps the way a place faces (within the cubes loaded around an arrival). */
    private static final int LOOK_REACH = 16, LOOK_CLEAR = 12;

    /**
     * Turns away from a tree trunk or wall right in front of an arriving player: keeps {@code yaw}
     * when the view that way runs clear for {@link #LOOK_CLEAR} blocks at eye height, else the clearest of
     * sixteen directions, nearest to it on a tie.
     */
    public static float lookOut(ServerCubeWorld cubes, Vec3 stand, float yaw) {
        float best = yaw;
        int bestClear = -1;
        for (int turn = 0; turn < 16; turn++) {
            // 0, +1, -1, +2, -2 ... steps of 22.5 degrees, so ties go to the nearest turn.
            int step = (turn + 1) / 2 * (turn % 2 == 1 ? 1 : -1);
            float candidate = yaw + step * 22.5F;
            int clear = clearAhead(cubes, stand, candidate);
            if (clear > bestClear) { bestClear = clear; best = candidate; }
            if (turn == 0 && clear >= LOOK_CLEAR) return yaw;
        }
        return best;
    }

    private static int clearAhead(ServerCubeWorld cubes, Vec3 stand, float yaw) {
        double dx = -Math.sin(Math.toRadians(yaw)), dz = Math.cos(Math.toRadians(yaw));
        for (int i = 1; i <= LOOK_REACH; i++) {
            BlockPos eye = BlockPos.containing(stand.x + dx * i, stand.y + 1.6, stand.z + dz * i);
            if (!cubes.getBlockState(eye).isAir() && !cubes.getBlockState(eye).canBeReplaced()) return i - 1;
        }
        return LOOK_REACH;
    }

    /** Vanilla's default spawn radius, in blocks. */
    private static final int DEFAULT_SPAWN_RADIUS = 10;

    /**
     * Spreads arrivals over the spawn radius, as vanilla does around its world spawn, so players
     * joining a server don't all land on one block inside each other: a random dry column within
     * the {@code spawnRadius} game rule (vanilla's 10 when it is 0) that is not much higher or lower than the spot itself.
     */
    private static int[] scatter(ServerCubeWorld cubes, int[] spot) {
        if (!(cubes.level() instanceof ServerLevel level)) return spot;
        // An Earth world sets the rule to 0 so vanilla never searches the stand-in overworld for a
        // spawn; the Earth then scatters over vanilla's default instead.
        int rule = level.getServer().getSpawnRadius(level), radius = rule > 0 ? rule : DEFAULT_SPAWN_RADIUS;
        CubeGenerator generator = cubes.generator();
        int ground = generator.surfaceY(spot[0], spot[1]);
        for (int attempt = 0; attempt < 16; attempt++) {
            int x = spot[0] + level.random.nextInt(radius * 2 + 1) - radius, z = spot[1] + level.random.nextInt(radius * 2 + 1) - radius;
            int y = generator.surfaceY(x, z);
            if (generator.waterY(x, z) < y && Math.abs(y - ground) <= Math.max(2, radius / 2)) return new int[]{x, z};
        }
        return spot;
    }

    /** The world's arrival point, with its cubes loaded so the player lands on solid ground. */
    public static Vec3 arrival(ServerCubeWorld cubes) { return arrival(cubes, null); }

    /** The arrival point, drawn within sight of {@code view} (a landmark column) when the start is too far off to see it. */
    public static Vec3 arrival(ServerCubeWorld cubes, @Nullable int[] view) {
        return arrivalAt(cubes, landing(cubes, view));
    }

    /** The start's landing spot, before scattering; slow where the data is new, so best found off the server thread. */
    public static int[] landing(ServerCubeWorld cubes, @Nullable int[] view) {
        return landing(cubes, cubes.generator().arrival(), view);
    }

    /** The landing spot about a start of the server's choosing, rather than the world's own. */
    public static int[] landing(ServerCubeWorld cubes, int[] start, @Nullable int[] view) {
        return landing(cubes.generator(), withinSight(start, view), 4096, true);
    }

    /** The arrival point about a landing spot found before, scattered as {@link #arrival} does. */
    public static Vec3 arrivalAt(ServerCubeWorld cubes, int[] landing) {
        int[] spot = scatter(cubes, landing);
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
        // First somewhere dry and roomy, so nobody arrives in a one-block pit facing a wall, then dry
        // with headroom; swimming only where there is no dry ground (a start on a sea stack or islet).
        for (int pass = 0; pass < 4; pass++)
            for (int radius = 0; radius <= 15; radius++) for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                boolean roomy = pass % 2 == 0, dry = generator.waterY(x + dx, z + dz) < generator.surfaceY(x + dx, z + dz);
                if (pass < 2 && !dry) continue;
                int y = Math.max(generator.surfaceY(x + dx, z + dz), generator.waterY(x + dx, z + dz)) + 1;
                if (open(cubes, x + dx, y, z + dz) && open(cubes, x + dx, y + 1, z + dz)
                        && !cubes.getBlockState(new BlockPos(x + dx, y - 1, z + dz)).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                        && (!roomy || roomy(cubes, x + dx, y, z + dz))) {
                    if (!roomy) clearLeaves(cubes, x + dx, y, z + dz);
                    return new Vec3(x + dx + 0.5, y, z + dz + 0.5);
                }
            }
        int y = ground + 1;
        // Under a closed canopy, clear the leaves where the player stands rather than land on the treetops.
        if (cubes.getBlockState(new BlockPos(x, y - 1, z)).isSolid() && leafy(cubes, x, y, z) && leafy(cubes, x, y + 1, z)) {
            clearLeaves(cubes, x, y, z);
            return new Vec3(x + 0.5, y, z + 0.5);
        }
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

    /** Clears leaves (never logs) beside and above a cramped spot, a small clearing to look out of. */
    private static void clearLeaves(ServerCubeWorld cubes, int x, int y, int z) {
        for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            BlockPos at = new BlockPos(x + dx, y + dy, z + dz);
            if (cubes.getBlockState(at).is(net.minecraft.tags.BlockTags.LEAVES))
                cubes.level().setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }

    /** Whether a block is open or only leaves, which can be cleared for a player to stand in. */
    private static boolean leafy(ServerCubeWorld cubes, int x, int y, int z) {
        return open(cubes, x, y, z) || cubes.getBlockState(new BlockPos(x, y, z)).is(net.minecraft.tags.BlockTags.LEAVES);
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
            ServerLevel level = home(context.getSource());
            String text = level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes
                    ? "Cubic world: " + cubes.describe() : "Cubic world is not loaded.";
            context.getSource().sendSuccess(() -> Component.literal(text), false);
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    /** The home cubic level, or null when none is registered or loaded. */
    private static @Nullable ServerLevel home(CommandSourceStack source) {
        var key = Cubic.home();
        return key == null ? null : source.getServer().getLevel(key);
    }

    private static int enter(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = home(source);
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

    /** Says where a position is, in the generator's own terms when it has them, else just its Y. */
    private static void tellPlace(CommandSourceStack source, ServerCubeWorld cubes, BlockPos feet) {
        String place = cubes.generator().describe(feet);
        String text = place.isEmpty() ? "Cubic world: standing at Y " + feet.getY() + "." : place;
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
     * Moves a player to a block column once the workers have its cubes, landing on the ground there
     * facing {@code view} (a block column, or null). Replies go to {@code source}; a newer request for
     * the same player replaces this one. A {@link CubicArrivalEvent} follows the move. Server thread.
     */
    public static void arriveWhenReady(CommandSourceStack source, ServerPlayer player, int x, int z, @Nullable int[] view) {
        if (player.isRemoved()) return;
        ARRIVALS.removeIf(waiting -> waiting.player == player);
        ARRIVALS.add(new Arrival(source, player, x, z, 0, view));
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

    /** How far from the named spot a coast counts as near, so the start goes down to it. */
    private static final int COAST_REACH = 2048;
    /** Highest ground, in metres above the sea, that counts as a beach or shore flat... */
    private static final int BEACH = 12;
    /** ...and as low ground by a coast, the next choice. */
    private static final int LOW_GROUND = 40;
    /** How far a start may move, in blocks, to stand in the open rather than under trees. */
    private static final int OPEN_REACH = 512;

    /**
     * Spirals out from a point to the first dry, gentle spot within a radius, skipping ground too
     * cold or high to start on when {@code welcoming}; the point itself when there is none. By a
     * island (such as Bora Bora) the first choice is low ground near the
     * water rather than a ledge on the mountain above it.
     */
    /** Farthest a start may be from its landmark, in blocks, and still show it through the far terrain. */
    private static final int SIGHT = 12_000;
    /** Where a start too far from its landmark moves to: this far from it, on the same side. */
    private static final int SIGHT_STANDOFF = 8_000;

    /**
     * A start brought within sight of its landmark: a place's coordinates can be the park or region
     * the landmark is known by (Kilimanjaro's from Amboseli, 45 km off), so a start farther than
     * {@link #SIGHT} moves toward the landmark until it is {@link #SIGHT_STANDOFF} away.
     */
    public static int[] withinSight(int[] start, @Nullable int[] view) {
        if (view == null) return start;
        double dx = start[0] - view[0], dz = start[1] - view[1], distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= SIGHT) return start;
        return new int[]{view[0] + (int) Math.round(dx / distance * SIGHT_STANDOFF), view[1] + (int) Math.round(dz / distance * SIGHT_STANDOFF)};
    }

    /**
     * The landing spot nearest a block column: a dry, gentle spot within {@code maxRadius} blocks (low ground
     * near a coast, open ground over forest when {@code welcoming}), or the column itself. Slow; call off-thread.
     */
    public static int[] landing(CubeGenerator generator, int[] centre, int maxRadius, boolean welcoming) {
        // Open ground (a beach, a meadow, a clearing) beats a start inside a forest with no view.
        SurfaceGenerator unwooded = welcoming && generator instanceof SurfaceGenerator surface ? surface : null;
        if (nearSea(generator, centre))
            for (SurfaceGenerator open : unwooded != null ? new SurfaceGenerator[]{unwooded, null} : new SurfaceGenerator[]{null})
                for (int highest : new int[]{BEACH, LOW_GROUND}) {
                    int[] low = search(generator, centre, Math.min(maxRadius, COAST_REACH), welcoming, generator.seaLevel() + highest, open);
                    if (low != null) return low;
                }
        if (unwooded != null) {
            int[] open = search(generator, centre, Math.min(maxRadius, OPEN_REACH), true, Integer.MAX_VALUE, unwooded);
            if (open != null) return open;
        }
        int[] spot = search(generator, centre, maxRadius, welcoming, Integer.MAX_VALUE, null);
        return spot != null ? spot : centre;
    }

    private static int[] search(CubeGenerator generator, int[] centre, int maxRadius, boolean welcoming, int highest) {
        return search(generator, centre, maxRadius, welcoming, highest, null);
    }

    /** Spots of the spiral judged together, and how many each thread judges in turn. */
    private static final int SEARCH_BATCH = 96, SEARCH_SHARE = 8;

    /**
     * The spiral search; with {@code unwooded}, only columns that generator grows no trees on. Spots are
     * judged a batch of rings at a time, side by side, and the first good one in spiral order wins, as
     * one at a time: in a new place each reads terrain not yet loaded, which one after another kept a
     * player waiting seconds before a /cubes goto landed.
     */
    private static int[] search(CubeGenerator generator, int[] centre, int maxRadius, boolean welcoming, int highest, @Nullable SurfaceGenerator unwooded) {
        List<int[]> spots = new ArrayList<>();
        for (int radius = 0; radius < maxRadius; radius += 16) {
            for (int step = 0; step < Math.max(1, radius / 4); step++) {
                double angle = step * Math.PI * 2 / Math.max(1, radius / 4);
                spots.add(new int[]{centre[0] + (int) Math.round(Math.cos(angle) * radius), centre[1] + (int) Math.round(Math.sin(angle) * radius)});
            }
            if (spots.size() < SEARCH_BATCH && radius + 16 < maxRadius) continue;
            boolean[] good = new boolean[spots.size()];
            List<java.util.concurrent.Callable<Void>> shares = new ArrayList<>();
            for (int from = 0; from < spots.size(); from += SEARCH_SHARE) {
                int start = from, end = Math.min(spots.size(), from + SEARCH_SHARE);
                shares.add(() -> {
                    for (int i = start; i < end; i++) good[i] = suits(generator, spots.get(i)[0], spots.get(i)[1], welcoming, highest, unwooded);
                    return null;
                });
            }
            try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                for (var share : threads.invokeAll(shares)) share.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } catch (java.util.concurrent.ExecutionException failed) {
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failed.getCause());
            }
            for (int i = 0; i < spots.size(); i++) if (good[i]) return spots.get(i);
            spots.clear();
        }
        return null;
    }

    /** Whether a spot is dry, gentle ground to land on, as {@link #search} wants it. */
    private static boolean suits(CubeGenerator generator, int x, int z, boolean welcoming, int highest, @Nullable SurfaceGenerator unwooded) {
        int y = generator.surfaceY(x, z);
        if (y <= generator.seaLevel() + 2 || y > highest || generator.waterY(x, z) >= y) return false;
        int slope = Math.abs(generator.surfaceY(x + 2, z) - generator.surfaceY(x - 2, z))
                + Math.abs(generator.surfaceY(x, z + 2) - generator.surfaceY(x, z - 2));
        return slope <= 3 && !(welcoming && generator.barren(x, z, y)) && (unwooded == null || !unwooded.wooded(x, z));
    }

    /**
     * Whether a point is on an island: open sea (water at sea level over a seabed) within
     * {@link #COAST_REACH} in at least three quarters of the directions around it. A mainland coast
     * (a loch shore, a fjord) has sea on one side only and keeps its upland start.
     */
    private static boolean nearSea(CubeGenerator generator, int[] centre) {
        int sea = generator.seaLevel();
        // The directions look out side by side, as each reads terrain a new place has not loaded yet.
        List<java.util.concurrent.Callable<Boolean>> directions = new ArrayList<>();
        for (int step = 0; step < 24; step++) {
            double angle = step * Math.PI * 2 / 24;
            directions.add(() -> {
                for (int radius = 128; radius <= COAST_REACH; radius += 128) {
                    int x = centre[0] + (int) Math.round(Math.cos(angle) * radius), z = centre[1] + (int) Math.round(Math.sin(angle) * radius);
                    if (generator.waterY(x, z) == sea && generator.surfaceY(x, z) < sea - 3) return true;
                }
                return false;
            });
        }
        int seaward = 0;
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (var direction : threads.invokeAll(directions)) if (direction.get()) seaward++;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failed.getCause());
        }
        return seaward >= 18;
    }

    static boolean isCubic(Level level) { return CubicLevel.of(level) != null; }
}
