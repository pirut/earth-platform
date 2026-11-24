package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.bug.BugReporting;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.ArrivalWait;
import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.CubicSettings;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.platform.Loader;
import io.earthplatform.minecraft.platform.Payloads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Earth worlds: saves made with the "Earth" world type, where the cubic Earth is home. Their
 * overworld is an empty stand-in nobody stays in; players who would land there (on first join,
 * after a respawn, through a portal) go to the Earth instead. The first arrival waits, with a
 * message on screen, until the data around the world's home is downloaded.
 */
public final class EarthWorlds {
    public static final ResourceKey<WorldPreset> PRESET =
            ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth"));
    /** The empty structure set tag that marks an Earth world's stand-in overworld. */
    public static final TagKey<StructureSet> MARKER =
            TagKey.create(Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_world"));
    private static final String WELCOMED = "earthplatform.welcomed";
    /** Where players wait in the stand-in overworld, above everything. */
    private static final Vec3 HOLD = new Vec3(0.5, 300, 0.5);

    /** Version of the Earth payloads; NeoForge refuses a connection whose two sides differ. */
    public static final String PAYLOADS_VERSION = "earth-2";

    public static void payloads(Payloads registrar) {
        registrar.toClient(EarthStatus.Payload.TYPE, EarthStatus.Payload.CODEC,
                        payload -> { if (Loader.INSTANCE.isClient()) io.earthplatform.minecraft.cubic.play.client.EarthPlayClient.status(payload); })
                .toClient(EarthMap.Payload.TYPE, EarthMap.Payload.CODEC,
                        payload -> { if (Loader.INSTANCE.isClient()) io.earthplatform.minecraft.cubic.play.client.EarthFlight.received(payload); })
                .toClient(EarthMap.Players.TYPE, EarthMap.Players.CODEC,
                        payload -> { if (Loader.INSTANCE.isClient()) io.earthplatform.minecraft.cubic.play.client.EarthFlight.others(payload); })
                .toClient(EarthPostcard.Payload.TYPE, EarthPostcard.Payload.CODEC,
                        payload -> { if (Loader.INSTANCE.isClient()) io.earthplatform.minecraft.cubic.play.client.EarthPostcardScreen.received(payload); })
                .toClient(EarthSolar.Payload.TYPE, EarthSolar.Payload.CODEC,
                        payload -> { if (Loader.INSTANCE.isClient()) io.earthplatform.minecraft.cubic.play.client.EarthSolarClient.received(payload); });
    }

    /** A home picked on the Create World screen, for the next new Earth world this game makes. */
    public record Choice(EarthSettings settings, String name) {}
    private static volatile Choice chosen;

    /** The running Earth world, or null when the running world is not one. */
    private static Session session;

    private EarthWorlds() {}

    public static void choose(@Nullable Choice choice) { chosen = choice; }
    @Nullable public static Choice chosen() { return chosen; }

    /** Whether a server's world was made with the Earth world type. */
    public static boolean isEarth(MinecraftServer server) {
        LevelStem overworld = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
        return overworld != null && overworld.generator() instanceof FlatLevelSource flat
                && flat.settings().structureOverrides().flatMap(HolderSet::unwrapKey).filter(MARKER::equals).isPresent();
    }

    private static Path cubicFolder(MinecraftServer server) {
        return DimensionType.getStorageFolder(Cubic.LEVEL, server.getWorldPath(LevelResource.ROOT));
    }

    /** A new Earth world gets its home pinned before its levels load, and loads no overworld spawn area. */
    public static void aboutToStart(MinecraftServer server) {
        Choice choice = chosen;
        chosen = null;
        if (!isEarth(server) || server.getWorldData().overworldData().isInitialized()) return;
        if (choice == null || server.isDedicatedServer()) {
            String text = EarthConfig.DEFAULT_HOME.get();
            String projection = EarthConfig.DEFAULT_PROJECTION.get();
            EarthSettings settings = EarthConfig.home(text, projection);
            if (settings == null) settings = EarthSettings.at(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE, projection);
            String name = EarthPlaces.byId(text.trim()).map(EarthPlaces.Place::label).orElse("");
            choice = new Choice(settings, name);
        }
        CubicSettings.pinEarth(cubicFolder(server), choice.settings, choice.name);
        // Nobody stays in the stand-in overworld: keep no spawn area loaded, and don't search one for a spawn spot.
        server.getWorldData().getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
        server.getWorldData().getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0, null);
        EarthPlatformMod.LOGGER.info("New Earth world, home {} {}, {}", choice.settings.latitude(), choice.settings.longitude(), choice.name);
    }

    public static void started(MinecraftServer server) {
        session = isEarth(server) ? new Session(server) : null;
    }

    public static void stopped() { session = null; chosen = null; }

    public static void loggedIn(Player entity) {
        if (entity instanceof ServerPlayer player) {
            EarthSolar.sync(player);
            if (session != null) session.check(player);
        }
    }

    public static void respawned(Player entity) {
        if (session != null && entity instanceof ServerPlayer player) session.check(player);
    }

    public static void changedDimension(Player entity) {
        if (session != null && entity instanceof ServerPlayer player) session.check(player);
    }

    public static void loggedOut(Player entity) {
        if (session == null) return;
        if (entity instanceof ServerPlayer player) session.release(player);
        session.forget(entity.getUUID());
    }

    public static void tick(MinecraftServer server) {
        if (session != null && session.server == server) session.tick();
    }

    /** One running Earth world: players waiting to arrive, the readiness of its home, download status. */
    private static final class Session {
        final MinecraftServer server;
        final String homeName;
        final Set<UUID> waiting = new LinkedHashSet<>();
        /** Waiting players allowed to fly so a dedicated server does not kick them for floating. */
        final Set<UUID> floating = new HashSet<>();
        final EarthStatus status = new EarthStatus();
        CompletableFuture<Void> preparing;
        /** Where the start's landing search found to stand, found off the server thread with the data. */
        @Nullable volatile int[] landing;
        boolean ready;
        long retryAt, lastSample, firstWait;
        /** Counts server spawn moves, so a landing search for an old spawn is thrown away. */
        volatile int round;
        /** Waiting players sent to their own spawn, and when they may be sent again if that trip failed. */
        final java.util.Map<UUID, Long> sent = new java.util.HashMap<>();

        Session(MinecraftServer server) {
            this.server = server;
            this.homeName = CubicSettings.homeName(cubicFolder(server));
        }

        @Nullable ServerCubeWorld cubes() {
            ServerLevel level = server.getLevel(Cubic.LEVEL);
            return level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes ? cubes : null;
        }

        /** The column a new arrival faces: the server spawn's sight, else the home place's sight, or the home point itself. */
        @Nullable private static int[] view(ServerCubeWorld cubes) {
            if (!(cubes.generator() instanceof EarthGenerator earth)) return null;
            Optional<EarthSpawns.Spot> spawn = spawn(cubes);
            if (spawn.isPresent()) return Double.isNaN(spawn.get().viewLatitude()) ? null
                    : earth.block(spawn.get().viewLatitude(), spawn.get().viewLongitude());
            EarthSettings home = earth.settings();
            return EarthPlaces.near(home.latitude(), home.longitude())
                    .map(place -> earth.block(place.viewLatitude(), place.viewLongitude()))
                    .orElseGet(() -> earth.block(home.latitude(), home.longitude()));
        }

        /** The server spawn its operators set, if any. */
        private static Optional<EarthSpawns.Spot> spawn(ServerCubeWorld cubes) {
            return cubes.level() instanceof ServerLevel level ? EarthSpawns.of(level.getServer()).spawn() : Optional.empty();
        }

        /** Where arrivals start from: the server spawn, else the world's home. */
        private static int[] start(ServerCubeWorld cubes) {
            Optional<EarthSpawns.Spot> spawn = spawn(cubes);
            if (spawn.isPresent() && cubes.generator() instanceof EarthGenerator earth) return earth.block(spawn.get().latitude(), spawn.get().longitude());
            return cubes.generator().arrival();
        }

        /** The server spawn moved: arrivals wait while the landing there is found. */
        void moved() {
            ready = false;
            preparing = null;
            landing = null;
            ServerCubeWorld spawnCubes = cubes();
            if (spawnCubes != null) for (UUID id : spots.keySet()) spawnCubes.release(holder(id));
            spots.clear();
            retryAt = 0;
            round++;
        }

        String place() {
            ServerCubeWorld spawnCubes = cubes();
            if (spawnCubes != null) { Optional<EarthSpawns.Spot> spawn = spawn(spawnCubes); if (spawn.isPresent()) return spawn.get().label(); }
            if (!homeName.isEmpty()) return homeName;
            ServerCubeWorld cubes = cubes();
            if (cubes != null && cubes.generator() instanceof EarthGenerator earth) {
                EarthSettings home = earth.settings();
                return EarthPlaces.near(home.latitude(), home.longitude()).map(EarthPlaces.Place::label)
                        .orElse(String.format(java.util.Locale.ROOT, "%.4f, %.4f", home.latitude(), home.longitude()));
            }
            return "";
        }

        void check(ServerPlayer player) {
            if (player.level().dimension() != Level.OVERWORLD) return;
            if (waiting.isEmpty()) firstWait = Util.getMillis();
            waiting.add(player.getUUID());
        }

        void forget(UUID player) {
            waiting.remove(player);
            sent.remove(player);
            ownSince.remove(player);
            ownGivenUp.remove(player);
            status.forget(player);
            // Left while waiting: the cubes held at their landing go too, so nothing stays resident.
            ServerCubeWorld spawnCubes = cubes();
            forgetSpot(spots, player, holder -> { if (spawnCubes != null) spawnCubes.release(holder); });
        }

        void tick() {
            long now = Util.getMillis();
            ServerCubeWorld cubes = cubes();
            if (cubes == null) return;
            if (!ready) prepare(cubes, now);
            if (now - lastSample >= 1000 && cubes.generator() instanceof EarthGenerator) {
                lastSample = now;
                status.sample(now);
                List<ServerPlayer> told = new ArrayList<>();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (waiting.contains(player.getUUID())) continue;
                    if (Cubic.isCubic(player.level().dimension())) told.add(player);
                }
                byte problem = status.unreachable() ? EarthStatus.UNREACHABLE : EarthStatus.OK;
                for (ServerPlayer player : told) status.send(player, status.playing(problem, cubes.far().waiting(player.getUUID()) > 0), now);
            }
            if (waiting.isEmpty()) return;
            for (UUID id : List.copyOf(waiting)) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null || player.level().dimension() != Level.OVERWORLD) {
                    waiting.remove(id);
                    sent.remove(id);
                    ownSince.remove(id);
                    ownGivenUp.remove(id);
                    if (spots.remove(id) != null) cubes.release(holder(id));
                    if (player != null) release(player);
                    continue;
                }
                Optional<EarthSpawns.Spot> own = EarthSpawns.of(server).personal(player);
                // A spawn of their own that cannot be landed on in time, tried every 30 s, would hold them for ever:
                // past the arrival's whole bound it is given up, and they land at the server's spawn instead.
                if (own.isPresent() && !ownGivenUp.contains(id) && ArrivalWait.overdue(ownSince.computeIfAbsent(id, k -> now), now)) {
                    ownGivenUp.add(id);
                    CubicEvents.cancelTrip(cubes, player);
                    player.sendSystemMessage(Component.literal("Your own spawn could not be loaded in time; landing at the server's spawn instead.")
                            .withStyle(ChatFormatting.YELLOW));
                }
                if (own.isPresent() && !ownGivenUp.contains(id)) {
                    // A spawn of their own: travel there, held up here until the trip lands; tried again if it fails.
                    hold(player);
                    if (now >= sent.getOrDefault(id, 0L) && !CubicEvents.tripPending(player)) {
                        sent.put(id, now + 30_000);
                        EarthSpawns.Spot spot = own.get();
                        CubicEvents.travel(server.createCommandSourceStack().withSuppressedOutput(), player,
                                spot.latitude(), spot.longitude(), spot.viewLatitude(), spot.viewLongitude());
                    }
                    status.send(player, status.payload(EarthStatus.PREPARING, EarthStatus.OK, (int) ((now - firstWait) / 1000), own.get().label()), now);
                    continue;
                }
                Vec3 stand = ready ? landed(id, cubes) : null;
                if (stand != null) {
                    // The hold goes however landing ends, before the entries that track it (the player stands there now).
                    cubes.landHeld(holder(id), () -> {
                        waiting.remove(id);
                        spots.remove(id);
                        arrive(player, cubes, stand);
                    });
                } else {
                    hold(player);
                    byte problem = EarthStatus.offlineMode() && retryAt > 0 ? EarthStatus.OFFLINE_MODE
                            : status.unreachable() ? EarthStatus.UNREACHABLE : EarthStatus.OK;
                    status.send(player, status.payload(EarthStatus.PREPARING, problem, (int) ((now - firstWait) / 1000), place()), now);
                }
            }
        }

        /** A waiting player's own spot, scattered about the landing, and how many ticks they have waited for it. */
        private record Spot(int[] at, ArrivalWait.Wait waited) {}
        final java.util.Map<UUID, Spot> spots = new java.util.HashMap<>();
        /** When each waiting player was first sent toward a spawn of their own, and those whose own spawn was given up. */
        final java.util.Map<UUID, Long> ownSince = new java.util.HashMap<>();
        final Set<UUID> ownGivenUp = new HashSet<>();

        /** What keeps a waiting player's landing cubes loaded (see {@link ServerCubeWorld#keepAround}). */
        static String holder(UUID id) { return "join:" + id; }

        /**
         * Where a waiting player may stand now, or null while they wait: about their own spot,
         * scattered about the landing once, as {@link ArrivalWait} allows, waited for round that
         * spot (a wide spawn radius scatters past the cubes round the landing) and never in a cube
         * not loaded. The cubes there are kept loaded meanwhile, as nobody may be near. The player is
         * held, so waiting on is safe; a spot whose cubes cannot be had by the arrival's bound is
         * scattered again, so another column round the landing is tried.
         */
        @Nullable private Vec3 landed(UUID id, ServerCubeWorld cubes) {
            if (landing == null) landing = ArrivalWait.orNone(() -> CubicEvents.landing(cubes, view(cubes))); // found here when not found ahead
            int[] found = landing;
            if (found == null) return null; // its terrain is not to be had yet: they wait, held
            Spot spot = spots.get(id);
            if (spot == null) {
                // A failed elevation sample keeps them waiting, held, rather than throwing out of the server tick.
                int[] scattered = scatterOrWait(() -> CubicEvents.scatter(cubes, found));
                if (scattered == null) return null;
                spots.put(id, spot = new Spot(scattered, ArrivalWait.Wait.NEW));
            }
            var generator = cubes.generator();
            int x = spot.at[0], z = spot.at[1];
            Integer ground = ArrivalWait.orNone(() -> Math.max(generator.surfaceY(x, z), generator.waterY(x, z)));
            if (ground == null) return null;
            var at = new net.minecraft.core.BlockPos(x, ground, z);
            cubes.keepAround(holder(id), at);
            ArrivalWait.Next next = ArrivalWait.step(spot.waited, ArrivalWait.around(cubes, at));
            Vec3 stand = next.step() == ArrivalWait.Step.GO ? CubicEvents.standAt(cubes, spot.at) : null;
            if (stand != null) return stand;
            if (next.step() == ArrivalWait.Step.GIVE_UP || spot.waited.ticks() >= ArrivalWait.TOTAL) {
                spots.remove(id);
                cubes.release(holder(id));
            } else spots.put(id, new Spot(spot.at, next.then()));
            return null;
        }

        /** Fetches the data around the home off the server thread, and checks it is there; retries until it is. */
        private void prepare(ServerCubeWorld cubes, long now) {
            if (preparing == null) {
                if (now < retryAt) return;
                var generator = cubes.generator();
                int[] home = start(cubes);
                int[] view = view(cubes);
                int asked = round;
                preparing = CompletableFuture.runAsync(() -> {
                    // Only what the cubes about the home read: the far levels' data loads on without holding up the arrival.
                    generator.prepareNear(home[0], home[1]);
                    // Throws when the home's terrain is still missing.
                    generator.surfaceY(home[0], home[1]);
                    // The search reads ground kilometres out, from the network where it is new: never on the server thread.
                    int[] found = CubicEvents.landing(cubes, home, view);
                    if (asked == round) landing = found;
                }, Util.ioPool());
            } else if (preparing.isDone()) {
                if (preparing.isCompletedExceptionally()) {
                    preparing.exceptionally(failure -> {
                        EarthPlatformMod.LOGGER.warn("The Earth's home area is not available yet: {}", failure.toString());
                        return null;
                    });
                    preparing = null;
                    retryAt = now + 5_000;
                    status.suspect(now);
                } else {
                    ready = true;
                }
            }
        }

        /**
         * Keeps a waiting player in the air above the stand-in overworld. Its client may hold still
         * there for as long as the Earth takes to get ready, which a dedicated server without
         * allow-flight would kick as floating, so the player may fly until it arrives.
         */
        private void hold(ServerPlayer player) {
            if (!player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
                floating.add(player.getUUID());
            }
            if (player.position().distanceToSqr(HOLD) > 4) player.teleportTo(player.serverLevel(), HOLD.x, HOLD.y, HOLD.z, player.getYRot(), player.getXRot());
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
        }

        /** Takes back the flight a waiting player was lent, leaving what its game mode allows. */
        void release(ServerPlayer player) {
            if (!floating.remove(player.getUUID())) return;
            player.gameMode.getGameModeForPlayer().updatePlayerAbilities(player.getAbilities());
            player.onUpdateAbilities();
        }

        private void arrive(ServerPlayer player, ServerCubeWorld cubes, Vec3 spot) {
            release(player);
            int[] view = view(cubes);
            ServerLevel level = server.getLevel(Cubic.LEVEL);
            player.teleportTo(level, spot.x, spot.y, spot.z, CubicEvents.lookOut(cubes, spot, CubicEvents.facing(spot, view, player.getYRot())), 0.0F);
            player.resetFallDistance();
            status.send(player, status.payload(EarthStatus.PLAYING, EarthStatus.OK, 0, ""), Util.getMillis());
            if (player.getTags().contains(WELCOMED)) { EarthBasics.bonusChest(level, cubicFolder(server), spot, player); return; }
            player.addTag(WELCOMED);
            String place = place();
            player.sendSystemMessage(Component.literal("Welcome to the Earth" + (place.isEmpty() ? "." : ": " + place + "."))
                    .withStyle(ChatFormatting.GREEN));
            player.sendSystemMessage(Component.literal("Explore the real world at true scale. Travel with ")
                    .append(command("/earth visit <place>", "/earth visit "))
                    .append(Component.literal(" or "))
                    .append(command("/earth goto <latitude> <longitude>", "/earth goto "))
                    .append(Component.literal("; "))
                    .append(command("/earth places", "/earth places"))
                    .append(Component.literal(" lists famous spots.")));
            String credits = cubes.generator().credits();
            if (!credits.isEmpty()) player.sendSystemMessage(Component.literal(credits).withStyle(ChatFormatting.DARK_GRAY));
            // After the credits, so it is the last line in chat and not scrolled away.
            if (EarthCommands.mayChooseStart(player))
                player.sendSystemMessage(Component.literal("You can choose where you start, once: ")
                        .append(Component.literal("[Choose on the map]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/earth start"))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Opens the globe"))))));
            EarthBasics.bonusChest(level, cubicFolder(server), spot, player);
        }
    }

    private static Component command(String text, String suggestion) {
        return Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggestion))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to type it"))));
    }

    // Commands ------------------------------------------------------------------------------------

    /** The player's /earth. */
    public static void commands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("earth");
        root.then(Commands.literal("visit").requires(EarthWorlds::mayTravel)
                .then(Commands.argument("place", StringArgumentType.greedyString())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(EarthPlaces.all().stream().map(EarthPlaces.Place::id), builder))
                        .executes(context -> visit(context.getSource(), StringArgumentType.getString(context, "place")))));
        root.then(Commands.literal("find").then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(context -> find(context.getSource(), StringArgumentType.getString(context, "name")))));
        root.then(Commands.literal("goto").requires(EarthWorlds::mayTravel)
                .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-80, 80))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180)).executes(context -> {
                                    double latitude = DoubleArgumentType.getDouble(context, "latitude"), longitude = DoubleArgumentType.getDouble(context, "longitude");
                                    ServerPlayer traveller = context.getSource().getPlayerOrException();
                                    EarthRules.Fare fare = EarthRules.quote(context.getSource(), traveller, latitude, longitude);
                                    if (fare == null) return 0;
                                    return CubicEvents.travel(context.getSource(), traveller, latitude, longitude, Double.NaN, Double.NaN, fare, null);
                                })
                                // Operators and the console can send someone else.
                                .then(Commands.argument("player", EntityArgument.player()).requires(source -> source.hasPermission(2))
                                        .executes(context -> CubicEvents.travel(context.getSource(), EntityArgument.getPlayer(context, "player"),
                                                DoubleArgumentType.getDouble(context, "latitude"), DoubleArgumentType.getDouble(context, "longitude")))))));
        // The travel map in creative, the survival map otherwise: the client opens it with a key, through this command.
        root.then(Commands.literal("map").requires(EarthMap::mayUse).executes(context -> EarthMap.open(context.getSource())));
        EarthCommands.register(root);
        EarthCompass.register(root);
        EarthTeleports.register(root);
        EarthRace.register(root);
        EarthGuess.register(root);
        EarthPostcard.register(root);
        EarthAdmin.register(root);
        EarthToggles.register(root);
        root.then(Commands.literal("where").executes(context -> where(context.getSource())));
        root.then(Commands.literal("bug").executes(context -> BugReporting.file(context.getSource(), ""))
                .then(Commands.argument("what", StringArgumentType.greedyString())
                        .executes(context -> BugReporting.file(context.getSource(), StringArgumentType.getString(context, "what")))));
        root.then(Commands.literal("places").executes(context -> places(context.getSource())));
        dispatcher.register(root);
    }


    /** Travel is for operators, the singleplayer owner, or everyone when the server allows it. */
    static boolean mayTravel(CommandSourceStack source) {
        if (source.hasPermission(2) || EarthConfig.ANYONE_CAN_TRAVEL.get()) return true;
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    private static int visit(CommandSourceStack source, String query) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Optional<EarthPlaces.Place> place = EarthPlaces.find(query);
        if (place.isPresent()) {
            EarthRules.Fare fare = EarthRules.quote(source, source.getPlayerOrException(), place.get().latitude(), place.get().longitude());
            if (fare == null) return 0;
            source.sendSuccess(() -> Component.literal("Traveling to " + place.get().label() + ".").withStyle(ChatFormatting.GREEN), false);
            return CubicEvents.travel(source, source.getPlayerOrException(), place.get().latitude(), place.get().longitude(),
                    place.get().viewLatitude(), place.get().viewLongitude(), fare, null);
        }
        // Not a famous place: the biggest town of that name ("Paris", or "Paris, Texas").
        List<EarthTowns.Town> towns = EarthTowns.search(query, 1);
        if (towns.isEmpty()) {
            source.sendFailure(Component.literal("No place called '" + query + "'. Try /earth find <name>, /earth places, or /earth goto <latitude> <longitude>."));
            return 0;
        }
        EarthTowns.Town town = towns.get(0);
        EarthRules.Fare fare = EarthRules.quote(source, source.getPlayerOrException(), town.latitude(), town.longitude());
        if (fare == null) return 0;
        source.sendSuccess(() -> Component.literal("Traveling to where " + town.label() + " is. The Earth here is wild land, so there is no town.")
                .withStyle(ChatFormatting.GREEN), false);
        return CubicEvents.travel(source, source.getPlayerOrException(), town.latitude(), town.longitude(), Double.NaN, Double.NaN, fare, null);
    }

    /** Lists the places a name could mean, each a click away for those who may travel. */
    private static int find(CommandSourceStack source, String query) {
        List<Component> lines = new ArrayList<>();
        boolean travel = mayTravel(source);
        EarthPlaces.find(query).ifPresent(place -> lines.add(found(place.label(), place.latitude(), place.longitude(), travel)));
        for (EarthTowns.Town town : EarthTowns.search(query, 8)) lines.add(found(town.label(), town.latitude(), town.longitude(), travel));
        if (lines.isEmpty()) {
            source.sendFailure(Component.literal("No place called '" + query + "'. Towns of 1,000 people or more are listed; add a state or country after a comma to narrow it."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Places called '" + query + "':").withStyle(ChatFormatting.GOLD), false);
        for (Component line : lines) source.sendSuccess(() -> line, false);
        return lines.size();
    }

    private static Component found(String label, double latitude, double longitude, boolean travel) {
        String spot = String.format(java.util.Locale.ROOT, "%.3f %.3f", latitude, longitude);
        var line = Component.literal("  " + label + " ").append(Component.literal("(" + spot + ")").withStyle(ChatFormatting.GRAY));
        if (!travel) return line;
        return line.withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/earth goto " + spot))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("Click to travel here"))));
    }

    /** Sends a player to the server spawn (the world's start unless operators moved it); replies go to {@code source}. */
    static int toSpawn(CommandSourceStack source, ServerPlayer player) { return toSpawn(source, player, null); }

    /** As above, charging {@code fare} (null for free) only as the player lands. */
    static int toSpawn(CommandSourceStack source, ServerPlayer player, @Nullable EarthRules.Fare fare) {
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        if (level == null || !(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) {
            source.sendFailure(Component.literal("This world has no Earth."));
            return 0;
        }
        Session running = session;
        int[] found = running != null && running.ready ? running.landing : null;
        Optional<EarthSpawns.Spot> spawn = Session.spawn(cubes);
        // A moved spawn whose landing is still being found: travel there like any trip.
        if (found == null && spawn.isPresent())
            return CubicEvents.travel(source, player, spawn.get().latitude(), spawn.get().longitude(), spawn.get().viewLatitude(), spawn.get().viewLongitude(), fare, null);
        int[] view = Session.view(cubes);
        Vec3 spot = found != null ? CubicEvents.arrivalAtIfAvailable(cubes, found) : CubicEvents.arrivalIfAvailable(cubes, view);
        if (spot == null) {
            source.sendFailure(Component.literal("Could not load the ground at the server spawn yet. Try again in a minute."));
            return 0;
        }
        float yaw = CubicEvents.lookOut(cubes, spot, CubicEvents.facing(spot, view, player.getYRot()));
        // Charged only once the move is confirmed: a mod vetoing the teleport leaves them where they were, unpaid.
        if (!EarthRules.land(source, player, fare, () -> {
            player.teleportTo(level, spot.x, spot.y, spot.z, yaw, 0.0F);
            return CubicEvents.arrivedAt(player, level, spot);
        })) return 0;
        CubicEvents.cancelTrip(cubes, player); // landed another way: a trip still pending must not pull them off
        if (source.getPlayer() == player) {
            try { return CubicEvents.where(source); } catch (com.mojang.brigadier.exceptions.CommandSyntaxException notAPlayer) { return 1; }
        }
        source.sendSuccess(() -> Component.literal("Sent " + player.getGameProfile().getName() + " to the server spawn."), true);
        return 1;
    }

    /** A waiting player's scattered spot, or null (they wait) where the elevation it samples cannot be had now. */
    @Nullable static int[] scatterOrWait(java.util.function.Supplier<int[]> scatter) { return ArrivalWait.orNone(scatter); }

    /** A waiting player is gone: their spot is forgotten and the cubes held there let go. */
    static void forgetSpot(java.util.Map<UUID, ?> spots, UUID player, java.util.function.Consumer<Object> release) {
        spots.remove(player);
        release.accept(Session.holder(player));
    }

    /** The server spawn moved: players arriving from now on land there. */
    static void spawnMoved() {
        Session running = session;
        if (running != null) running.moved();
    }

    /** Where the player is, with the country, unless that would give a Where on Earth round away. */
    private static int where(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (EarthGuess.playing(player)) { source.sendFailure(Component.literal("Nice try! Make your guess with /earth guess.")); return 0; }
        int result = CubicEvents.where(source);
        String country = EarthRules.country(player);
        if (result > 0 && !country.isEmpty()) source.sendSuccess(() -> Component.literal("In " + country + "."), false);
        return result;
    }

    private static int places(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Famous places (click one to travel there):").withStyle(ChatFormatting.GREEN), false);
        for (EarthPlaces.Place place : EarthPlaces.all()) {
            String coordinates = String.format(java.util.Locale.ROOT, "%.4f, %.4f", place.latitude(), place.longitude());
            Component line = Component.literal("  " + place.label()).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/earth visit " + place.id()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(coordinates))));
            source.sendSuccess(() -> line, false);
        }
        return EarthPlaces.all().size();
    }
}
