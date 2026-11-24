package io.earthplatform.minecraft.cubic;

import com.mojang.brigadier.CommandDispatcher;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.geography.globe.Downloads;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Server-side lifecycle of cubic levels, respawning and the travel commands. */
public final class CubicEvents {
    private CubicEvents() {}

    public static void load(LevelAccessor levelAccessor) {
        if (!(levelAccessor instanceof ServerLevel level) || !Cubic.isCubic(level.dimension())) return;
        var folder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
        CubeGenerator generator = CubicSettings.open(folder, level.getSeed(), level.registryAccess().registryOrThrow(Registries.BIOME));
        ((CubicLevel) level).earthplatform$setCubes(new ServerCubeWorld(level, generator));
        // Fetch the data around the arrival point now, so the first visit does not wait on it.
        int[] home = generator.arrival();
        // On the IO pool: downloads wait on the network, which must not hold up world generation workers.
        // The cubes' data first, which the first arrival waits on, then the far levels'.
        Util.ioPool().execute(() -> {
            generator.prepareNear(home[0], home[1]);
            generator.prepareFar(home[0], home[1]);
        });
        // Beside them, the little the horizon's quick stand-ins read, and the stand-ins themselves, ready for the first player.
        if (CubicLevel.of(level) instanceof ServerCubeWorld cubes) standIns(generator, cubes, home[0], home[1]);
    }

    /** Fetches the data of the far stand-ins round a place and makes them ahead of a player arriving there, off-thread. */
    private static void standIns(CubeGenerator generator, ServerCubeWorld cubes, int x, int z) {
        if (!generator.farPreviews(io.earthplatform.minecraft.cubic.far.Far.MAX_LEVEL)) return;
        Util.ioPool().execute(() -> {
            try {
                generator.preparePreview(x, z, io.earthplatform.minecraft.cubic.far.Far.BACKDROP_LEVEL);
                cubes.far().warmPreviews(x, z);
            } catch (RuntimeException failure) {
                EarthPlatformMod.LOGGER.warn("Could not make the far stand-ins ahead", failure);
            }
        });
    }

    public static void save(LevelAccessor levelAccessor) {
        if (levelAccessor instanceof ServerLevel level && CubicLevel.of(level) instanceof ServerCubeWorld cubes) cubes.save();
    }

    public static void unload(LevelAccessor levelAccessor) {
        if (levelAccessor instanceof ServerLevel level && CubicLevel.of(level) instanceof ServerCubeWorld cubes) {
            ARRIVALS.clear(); // trips waiting on this world's cubes, whose holds close with it
            TRIPS.clear();
            cubes.close();
            ((CubicLevel) level).earthplatform$setCubes(null);
        }
    }

    public static void tick(LevelAccessor levelAccessor) {
        if (levelAccessor instanceof ServerLevel level && CubicLevel.of(level) instanceof ServerCubeWorld cubes) {
            cubes.tick();
            arrive(level, cubes);
        }
    }

    /**
     * A travel waiting for the cubes at its landing spot; the player stays where they are meanwhile.
     * {@code token} is its trip's (see {@link Trips}); {@code from} the dimension it set off from;
     * {@code fare} what it costs, charged only once it lands.
     */
    private record Arrival(CommandSourceStack source, ServerPlayer player, long token, ResourceKey<Level> from, double latitude, double longitude,
                           int x, int z, ArrivalWait.Wait waited, @Nullable int[] view, @Nullable io.earthplatform.minecraft.cubic.play.EarthRules.Fare fare) {
        /** What keeps the cubes at the landing loaded while it waits (see {@link ServerCubeWorld#keepAround}). */
        String holder() { return "trip:" + player.getUUID(); }

        Arrival waiting(ArrivalWait.Wait then) { return new Arrival(source, player, token, from, latitude, longitude, x, z, then, view, fare); }
    }

    private static final List<Arrival> ARRIVALS = new ArrayList<>();
    /** The trip each player is on now. */
    private static final Trips TRIPS = new Trips();

    /** Why a trip a player set off from {@code from} is called off now, or null (see {@link Trips#whyCalledOff}). */
    @Nullable private static String whyCalledOff(ServerPlayer player, ResourceKey<Level> from) {
        return Trips.whyCalledOff(player.isRemoved(), player.isDeadOrDying(), from, player.level().dimension());
    }

    /** A trip as it set off: its token and the dimension it left from, taken before its first step (a random trip's search for land). */
    public record TripStart(long token, ResourceKey<Level> from) {}

    /**
     * Server thread. Whether a trip's step may go on: false, and silently, when a newer trip has
     * taken its place; false, telling the player in one line and ending the trip, when they died or
     * went to another dimension since it set off.
     */
    private static boolean stillOn(CommandSourceStack source, ServerPlayer player, TripStart start) {
        UUID id = player.getUUID();
        if (!TRIPS.current(id, start.token)) return false;
        String why = whyCalledOff(player, start.from);
        if (why == null) return true;
        TRIPS.cancel(id);
        if (!player.isRemoved()) {
            source.sendFailure(Component.literal(why));
            io.earthplatform.minecraft.cubic.play.EarthMap.failed(player);
        }
        return false;
    }

    /** Whether a teleport put the player in {@code level} at {@code at}: false when something (a mod) stopped the move. */
    public static boolean arrivedAt(ServerPlayer player, ServerLevel level, Vec3 at) {
        return !player.isRemoved() && player.level() == level && player.position().distanceToSqr(at) < 1.0;
    }

    /**
     * Moves each waiting traveller as {@link ArrivalWait} allows, onto loaded ground: the server thread
     * never makes those cubes, so never waits behind a structure start or on map data for them, and the
     * first lookups where they land answer right. A trip whose cubes cannot be had is given up; one
     * superseded by a newer trip, or whose player died or left for another dimension, is dropped. Its
     * fare is charged only as it lands.
     */
    private static void arrive(ServerLevel level, ServerCubeWorld cubes) {
        for (int i = ARRIVALS.size() - 1; i >= 0; i--) {
            Arrival arrival = ARRIVALS.get(i);
            UUID id = arrival.player.getUUID();
            if (!TRIPS.current(id, arrival.token)) {
                // A newer trip took its place: its wait goes to that one if it is to the same place.
                ARRIVALS.remove(i);
                cubes.release(arrival.holder());
                TRIPS.remember(id, arrival.latitude, arrival.longitude, arrival.waited);
                continue;
            }
            if (whyCalledOff(arrival.player, arrival.from) != null) {
                ARRIVALS.remove(i);
                cubes.release(arrival.holder());
                stillOn(arrival.source, arrival.player, new TripStart(arrival.token, arrival.from)); // ends it, telling the player why
                continue;
            }
            CubeGenerator generator = cubes.generator();
            int ground = Math.max(generator.surfaceY(arrival.x, arrival.z), generator.waterY(arrival.x, arrival.z));
            BlockPos at = new BlockPos(arrival.x, ground, arrival.z);
            // Nobody may be there yet: the cubes in first must not go again while the rest, or the structures, are made.
            cubes.keepAround(arrival.holder(), at);
            ArrivalWait.Next next = ArrivalWait.step(arrival.waited, ArrivalWait.around(cubes, at));
            Vec3 stand = next.step() == ArrivalWait.Step.GO ? standOn(cubes, arrival.x, arrival.z, false) : null;
            if (stand == null) {
                if (next.step() == ArrivalWait.Step.GIVE_UP || arrival.waited.ticks() >= ArrivalWait.TOTAL) {
                    ARRIVALS.remove(i);
                    cubes.release(arrival.holder());
                    TRIPS.end(id, arrival.token);
                    TRIPS.remember(id, arrival.latitude, arrival.longitude, arrival.waited); // a retry of the same place keeps the clock
                    arrival.source.sendFailure(Component.literal("Could not load the terrain there in time. Try again in a minute."));
                    io.earthplatform.minecraft.cubic.play.EarthMap.failed(arrival.player);
                    continue;
                }
                ARRIVALS.set(i, arrival.waiting(next.then()));
                continue;
            }
            ARRIVALS.remove(i);
            // The hold goes however landing ends (the player stands there now, which keeps the cubes).
            cubes.landHeld(arrival.holder(), () -> {
                TRIPS.end(id, arrival.token);
                TRIPS.landed(id);
                float yaw = lookOut(cubes, stand, facing(stand, arrival.view, arrival.player.getYRot()));
                // Charged only once the move is confirmed: a mod vetoing the teleport leaves them where they were, unpaid.
                boolean landed = io.earthplatform.minecraft.cubic.play.EarthRules.land(arrival.source, arrival.player, arrival.fare, () -> {
                    arrival.player.teleportTo(level, stand.x, stand.y, stand.z, yaw, 0.0F);
                    return arrivedAt(arrival.player, level, stand);
                });
                if (!landed) {
                    io.earthplatform.minecraft.cubic.play.EarthMap.failed(arrival.player);
                    return;
                }
                io.earthplatform.minecraft.cubic.play.EarthMap.arrived(arrival.player);
                tellPlace(arrival.source, cubes, BlockPos.containing(stand));
            });
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
    public static int[] scatter(ServerCubeWorld cubes, int[] spot) {
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

    /** The world's arrival point, with its cubes loaded so the player lands on solid ground; null where they could not be. */
    @Nullable public static Vec3 arrival(ServerCubeWorld cubes) { return arrival(cubes, null); }

    /** The arrival point, drawn within sight of {@code view} (a landmark column) when the start is too far off to see it. */
    @Nullable public static Vec3 arrival(ServerCubeWorld cubes, @Nullable int[] view) {
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

    /** The arrival point about a landing spot found before, scattered as {@link #arrival} does; null where its cubes could not be loaded. */
    @Nullable public static Vec3 arrivalAt(ServerCubeWorld cubes, int[] landing) {
        int[] spot = scatter(cubes, landing);
        return standOn(cubes, spot[0], spot[1], true);
    }

    /**
     * Server thread. Calls off whatever trip a player is on, still fetching or waiting to land (a
     * personal spawn given up for the server's, or another way there taken at once): its later steps
     * are dropped, and its cubes let go. Nothing was charged for it.
     */
    public static void cancelTrip(ServerCubeWorld cubes, ServerPlayer player) {
        TRIPS.cancel(player.getUUID());
        for (int i = ARRIVALS.size() - 1; i >= 0; i--) {
            Arrival arrival = ARRIVALS.get(i);
            if (arrival.player != player) continue;
            ARRIVALS.remove(i);
            cubes.release(arrival.holder());
        }
    }

    /** Server thread. Whether a player is on a trip still fetching or waiting to land. */
    public static boolean tripPending(ServerPlayer player) { return TRIPS.pending(player.getUUID()); }

    /**
     * Server thread. A trip sets off whose first step (a random trip's search for land) is the
     * caller's: its token, which supersedes any trip the player was on, and the dimension it leaves
     * from, both taken now, before that step.
     */
    public static TripStart beginTrip(ServerPlayer player) { return new TripStart(TRIPS.begin(player.getUUID()), player.level().dimension()); }

    /**
     * Server thread. Whether a trip begun with {@link #beginTrip} may go on: false when a newer
     * trip took its place, or (telling the player, and ending it) when they died or went to another
     * dimension during its first step.
     */
    public static boolean tripStillOn(CommandSourceStack source, ServerPlayer player, TripStart start) { return stillOn(source, player, start); }

    /** Server thread. A trip begun with {@link #beginTrip} ended before travelling (no land was found). */
    public static void endTrip(ServerPlayer player, TripStart start) { TRIPS.end(player.getUUID(), start.token); }

    /**
     * The world's arrival point, as {@link #arrival}, or null where it cannot be had now: its cubes
     * failed, or the landing search or scatter found the terrain unavailable (a respawn into the
     * Earth then takes vanilla's, rather than failing).
     */
    @Nullable public static Vec3 arrivalIfAvailable(ServerCubeWorld cubes, @Nullable int[] view) {
        return ArrivalWait.orNone(() -> arrival(cubes, view));
    }

    /** As {@link #arrivalAt}, or null where the terrain there is unavailable. */
    @Nullable public static Vec3 arrivalAtIfAvailable(ServerCubeWorld cubes, int[] landing) {
        return ArrivalWait.orNone(() -> arrivalAt(cubes, landing));
    }

    /**
     * A place to stand at a column already scattered (see {@link #scatter}) and waited for, in loaded
     * cubes only, never making one here; null while there is nowhere loaded to stand yet.
     */
    @Nullable public static Vec3 standAt(ServerCubeWorld cubes, int[] spot) { return standOn(cubes, spot[0], spot[1], false); }

    /**
     * A place to stand near a column, on the ground or swimming on water, with the cubes there
     * loaded: the nearest spot within a few blocks with room above the ground (grass and a dusting
     * of snow leave room) and not on powder snow, which a player sinks into and freezes in; else on
     * top of whatever stands there, such as a tree, with a drift underfoot packed firm. Never in a
     * cube not loaded, which looks open and would put the player inside the ground once it loads.
     * Unless {@code loadAll}, the cubes there are the workers' to make, and null is returned while
     * nowhere loaded will do; with it, the cubes needed are loaded at once, and null is returned
     * where one could not be (its terrain failed): never a spot whose feet, head or footing is not loaded.
     */
    @Nullable private static Vec3 standOn(ServerCubeWorld cubes, int x, int z, boolean loadAll) {
        CubeGenerator generator = cubes.generator();
        int ground = Math.max(generator.surfaceY(x, z), generator.waterY(x, z));
        if (loadAll) cubes.loadAround(new BlockPos(x, ground, z));
        else cubes.readyAround(new BlockPos(x, ground, z)); // asked of the workers, never made here
        // First somewhere dry and roomy, so nobody arrives in a one-block pit facing a wall, then dry
        // with headroom; swimming only where there is no dry ground (a start on a sea stack or islet).
        for (int pass = 0; pass < 4; pass++)
            for (int radius = 0; radius <= 15; radius++) for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                boolean roomy = pass % 2 == 0, dry = generator.waterY(x + dx, z + dz) < generator.surfaceY(x + dx, z + dz);
                if (pass < 2 && !dry) continue;
                int y = Math.max(generator.surfaceY(x + dx, z + dz), generator.waterY(x + dx, z + dz)) + 1;
                if (open(cubes, x + dx, y, z + dz) && open(cubes, x + dx, y + 1, z + dz) && cubes.isLoaded(new BlockPos(x + dx, y - 1, z + dz))
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
        for (; y < ground + 48; y++) {
            boolean in = loaded(cubes, x, y - 1, z, loadAll) && loaded(cubes, x, y, z, loadAll) && loaded(cubes, x, y + 1, z, loadAll);
            if (!in) {
                // The first open spot may lie in a cube not loaded: wait for it rather than pass it by.
                if (!loadAll) cubes.readyAround(new BlockPos(x, y, z));
                return null;
            }
            if (open(cubes, x, y, z) && open(cubes, x, y + 1, z)) break;
        }
        BlockPos under = new BlockPos(x, y - 1, z);
        if (!(open(cubes, x, y, z) && open(cubes, x, y + 1, z) && cubes.isLoaded(under))) return null;
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
    /** Whether a block is open to stand in: never one in a cube not loaded, which only looks like air. */
    private static boolean open(ServerCubeWorld cubes, int x, int y, int z) {
        var state = cubes.getBlockState(new BlockPos(x, y, z));
        if (state == CubeWorld.UNLOADED) return false;
        return state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    /** Whether the cube holding a block is loaded; with {@code load}, loading it at once first. */
    private static boolean loaded(ServerCubeWorld cubes, int x, int y, int z, boolean load) {
        if (load && !cubes.isLoaded(new BlockPos(x, y, z))) cubes.loadNow(x >> 4, y >> 4, z >> 4);
        return cubes.isLoaded(new BlockPos(x, y, z));
    }

    public static void commands(CommandDispatcher<CommandSourceStack> dispatcher) {
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
        dispatcher.register(root);
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
        if (generator instanceof io.earthplatform.minecraft.cubic.earth.EarthGenerator earth) {
            var grid = earth.grid();
            String stretch;
            if (grid.isWebMercator()) {
                stretch = io.earthplatform.minecraft.cubic.play.EarthStretch.where(grid.metresPerBlock(), grid.geographic(feet.getX(), feet.getZ()).latitude());
            } else {
                stretch = io.earthplatform.minecraft.cubic.play.EarthStretch.whereLocal(grid.groundMetresPerBlock(feet.getX(), feet.getZ()));
            }
            source.sendSuccess(() -> Component.literal(stretch).withStyle(ChatFormatting.GRAY), false);
        }
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
        return travel(source, player, latitude, longitude, Double.NaN, Double.NaN);
    }

    /** As below, free. */
    public static int travel(CommandSourceStack source, ServerPlayer player, double latitude, double longitude, double viewLatitude, double viewLongitude) {
        return travel(source, player, latitude, longitude, viewLatitude, viewLongitude, null, null);
    }

    /**
     * Travels there and faces a sight, such as the peak a famous place is known for (NaN for none).
     * A famous place may land farther from its point than a bare latitude and longitude does, as a
     * new world's start does, to find good ground.
     * {@code fare} (null for free) is charged only once it lands. {@code begun} is the trip as its
     * caller began it (a random trip, before its search for land), or null to begin it here.
     */
    public static int travel(CommandSourceStack source, ServerPlayer player, double latitude, double longitude, double viewLatitude, double viewLongitude,
                             @Nullable io.earthplatform.minecraft.cubic.play.EarthRules.Fare fare, @Nullable TripStart begun) {
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        if (level == null || !(CubicLevel.of(level) instanceof ServerCubeWorld cubes) || !(cubes.generator() instanceof EarthGenerator earth)) {
            source.sendFailure(Component.literal("This save's cubic world is not the Earth."));
            return 0;
        }
        // The server's rules, such as a region border, may refuse the trip.
        String refused = io.earthplatform.minecraft.cubic.play.EarthRules.refuse(player, latitude, longitude);
        if (refused != null) {
            if (begun != null) TRIPS.end(player.getUUID(), begun.token);
            source.sendFailure(Component.literal(refused));
            io.earthplatform.minecraft.cubic.play.EarthMap.failed(player);
            return 0;
        }
        // This trip supersedes any the player was on: their older fetches and arrival are dropped from now.
        TripStart setOff = begun != null ? begun : beginTrip(player);
        long trip = setOff.token;
        ResourceKey<Level> from = setOff.from;
        int[] target = earth.block(latitude, longitude);
        int[] view = Double.isNaN(viewLatitude) ? null : earth.block(viewLatitude, viewLongitude);
        source.sendSuccess(() -> Component.literal("Fetching terrain around " + earth.place(target[0], target[1]) + "...").withStyle(ChatFormatting.GRAY), false);
        CompletableFuture.supplyAsync(() -> {
            // How long each part takes and what it downloads, so a slow first visit shows whether the network is why.
            Map<String, long[]> before = Downloads.totals();
            long start = System.nanoTime();
            // Only the data about the landing first; the far levels' data comes in after the player arrives.
            // A famous place lands within sight of its view, which may stand kilometres off the point: fetch about there.
            boolean famous = !Double.isNaN(viewLatitude);
            int[] centre = famous ? withinSight(target, view) : target;
            // The horizon's stand-ins meanwhile, so they are made by the time the player lands.
            standIns(earth, cubes, centre[0], centre[1]);
            earth.prepareNear(centre[0], centre[1]);
            long prepared = System.nanoTime();
            String fetched = Downloads.since(before);
            before = Downloads.totals();
            int[] spot = landing(earth, centre, famous ? 2048 : 256, famous);
            long landed = System.nanoTime();
            EarthPlatformMod.LOGGER.info("Travel to {}, {}: data {} ms ({}); landing {} ms ({})", latitude, longitude,
                    (prepared - start) / 1_000_000, fetched, (landed - prepared) / 1_000_000, Downloads.since(before));
            Map<String, long[]> far = Downloads.totals();
            Util.ioPool().execute(() -> {
                earth.prepareFar(centre[0], centre[1]);
                EarthPlatformMod.LOGGER.info("Travel to {}, {}: far data {} ms after landing ({})", latitude, longitude,
                        (System.nanoTime() - landed) / 1_000_000, Downloads.since(far));
            });
            return spot;
        }, Util.ioPool()).whenComplete((spot, failure) -> source.getServer().execute(() -> {
            UUID id = player.getUUID();
            // A fetch for a trip since superseded or called off (a personal spawn given up, a newer trip) is dropped.
            if (!stillOn(source, player, setOff)) return; // told in one line when a death or another dimension called it off
            if (failure != null) {
                TRIPS.end(id, trip);
                // The data's own words, without the exception's class names: most often a data server can't be reached.
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                source.sendFailure(Component.literal(cause instanceof io.earthplatform.geography.MissingTerrainException
                        ? "Could not load terrain there yet: " + cause.getMessage() + ". Try again in a minute."
                        : "Could not load terrain there: " + cause));
                io.earthplatform.minecraft.cubic.play.EarthMap.failed(player);
                return;
            }
            // A new try of the same place (a personal spawn's) keeps the wait a dropped try had, so its deadline still comes.
            ARRIVALS.removeIf(waiting -> {
                if (waiting.player != player) return false;
                cubes.release(waiting.holder());
                if (waiting.latitude == latitude && waiting.longitude == longitude) TRIPS.remember(id, latitude, longitude, waiting.waited);
                return true;
            });
            ARRIVALS.add(new Arrival(source, player, trip, from, latitude, longitude, spot[0], spot[1], TRIPS.resume(id, latitude, longitude), view, fare));
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
    private static int[] withinSight(int[] start, @Nullable int[] view) {
        if (view == null) return start;
        double dx = start[0] - view[0], dz = start[1] - view[1], distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= SIGHT) return start;
        return new int[]{view[0] + (int) Math.round(dx / distance * SIGHT_STANDOFF), view[1] + (int) Math.round(dz / distance * SIGHT_STANDOFF)};
    }

    private static int[] landing(CubeGenerator generator, int[] centre, int maxRadius, boolean welcoming) {
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
