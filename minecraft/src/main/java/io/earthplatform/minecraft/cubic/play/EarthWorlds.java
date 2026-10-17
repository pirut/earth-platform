package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.CubicSettings;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
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
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Earth worlds: saves made with the "Earth" world type, where the cubic Earth is home. Their
 * overworld is an empty stand-in nobody stays in; players who would land there (on first join,
 * after a respawn, through a portal) go to the Earth instead. The first arrival waits, with a
 * message on screen, until the data around the world's home is downloaded.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class EarthWorlds {
    public static final ResourceKey<WorldPreset> PRESET =
            ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth"));
    /** The empty structure set tag that marks an Earth world's stand-in overworld. */
    public static final TagKey<StructureSet> MARKER =
            TagKey.create(Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_world"));
    private static final String WELCOMED = "earthplatform.welcomed";
    /** Where players wait in the stand-in overworld, above everything. */
    private static final Vec3 HOLD = new Vec3(0.5, 300, 0.5);

    @EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}
        @SubscribeEvent public static void payloads(RegisterPayloadHandlersEvent event) {
            event.registrar("earth-1").playToClient(EarthStatus.Payload.TYPE, EarthStatus.Payload.CODEC,
                    (payload, context) -> { if (FMLEnvironment.dist.isClient()) io.earthplatform.minecraft.cubic.play.client.EarthPlayClient.status(payload); })
                    .playToClient(EarthMap.Payload.TYPE, EarthMap.Payload.CODEC,
                    (payload, context) -> { if (FMLEnvironment.dist.isClient()) io.earthplatform.minecraft.cubic.play.client.EarthFlight.received(payload); });
        }
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
    @SubscribeEvent public static void aboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        Choice choice = chosen;
        chosen = null;
        if (!isEarth(server) || server.getWorldData().overworldData().isInitialized()) return;
        if (choice == null || server.isDedicatedServer()) {
            String text = EarthConfig.DEFAULT_HOME.get();
            EarthSettings settings = EarthConfig.home(text);
            if (settings == null) settings = EarthSettings.defaults();
            String name = EarthPlaces.byId(text.trim()).map(EarthPlaces.Place::label).orElse("");
            choice = new Choice(settings, name);
        }
        CubicSettings.pinEarth(cubicFolder(server), choice.settings, choice.name);
        // Nobody stays in the stand-in overworld: keep no spawn area loaded, and don't search one for a spawn spot.
        server.getWorldData().getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
        server.getWorldData().getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0, null);
        EarthPlatformMod.LOGGER.info("New Earth world, home {} {}, {}", choice.settings.latitude(), choice.settings.longitude(), choice.name);
    }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        session = isEarth(server) ? new Session(server) : null;
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { session = null; chosen = null; }

    @SubscribeEvent public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (session != null && event.getEntity() instanceof ServerPlayer player) session.check(player);
    }

    @SubscribeEvent public static void respawned(PlayerEvent.PlayerRespawnEvent event) {
        if (session != null && event.getEntity() instanceof ServerPlayer player) session.check(player);
    }

    @SubscribeEvent public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (session != null && event.getEntity() instanceof ServerPlayer player) session.check(player);
    }

    @SubscribeEvent public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session == null) return;
        if (event.getEntity() instanceof ServerPlayer player) session.release(player);
        session.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (session != null && session.server == event.getServer()) session.tick();
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
        static final int LANDING_PATIENCE = 200;
        boolean ready;
        long retryAt, lastSample, firstWait;

        Session(MinecraftServer server) {
            this.server = server;
            this.homeName = CubicSettings.homeName(cubicFolder(server));
        }

        @Nullable ServerCubeWorld cubes() {
            ServerLevel level = server.getLevel(Cubic.LEVEL);
            return level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes ? cubes : null;
        }

        /** The column a new arrival faces: the home place's sight, or the home point itself. */
        @Nullable private static int[] view(ServerCubeWorld cubes) {
            if (!(cubes.generator() instanceof EarthGenerator earth)) return null;
            EarthSettings home = earth.settings();
            return EarthPlaces.near(home.latitude(), home.longitude())
                    .map(place -> earth.block(place.viewLatitude(), place.viewLongitude()))
                    .orElseGet(() -> earth.block(home.latitude(), home.longitude()));
        }

        String place() {
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

        void forget(UUID player) { waiting.remove(player); status.forget(player); }

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
                for (ServerPlayer player : told) status.send(player, status.payload(EarthStatus.PLAYING, problem, 0, ""), now);
            }
            if (waiting.isEmpty()) return;
            for (UUID id : List.copyOf(waiting)) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null || player.level().dimension() != Level.OVERWORLD) {
                    waiting.remove(id);
                    if (player != null) release(player);
                    continue;
                }
                if (ready && landingLoaded(cubes)) {
                    waiting.remove(id);
                    arrive(player, cubes);
                } else {
                    hold(player);
                    byte problem = EarthStatus.offlineMode() && retryAt > 0 ? EarthStatus.OFFLINE_MODE
                            : status.unreachable() ? EarthStatus.UNREACHABLE : EarthStatus.OK;
                    status.send(player, status.payload(EarthStatus.PREPARING, problem, (int) ((now - firstWait) / 1000), place()), now);
                }
            }
        }

        /** Ticks spent waiting for the cubes at the landing, which the workers make; after that they load on the spot. */
        int landingTicks;

        /**
         * Whether the cubes about the start's landing spot are made: asked of the workers and waited
         * for, rather than generated on the server thread when the first player arrives.
         */
        private boolean landingLoaded(ServerCubeWorld cubes) {
            int[] spot = landing;
            if (spot == null || landingTicks >= LANDING_PATIENCE) return true;
            var generator = cubes.generator();
            int ground = Math.max(generator.surfaceY(spot[0], spot[1]), generator.waterY(spot[0], spot[1]));
            if (cubes.readyAround(new net.minecraft.core.BlockPos(spot[0], ground, spot[1]))) return true;
            landingTicks++;
            return false;
        }

        /** Fetches the data around the home off the server thread, and checks it is there; retries until it is. */
        private void prepare(ServerCubeWorld cubes, long now) {
            if (preparing == null) {
                if (now < retryAt) return;
                var generator = cubes.generator();
                int[] home = generator.arrival();
                preparing = CompletableFuture.runAsync(() -> {
                    // Only what the cubes about the home read: the far levels' data loads on without holding up the arrival.
                    generator.prepareNear(home[0], home[1]);
                    // Throws when the home's terrain is still missing.
                    generator.surfaceY(home[0], home[1]);
                    // The search reads ground kilometres out, from the network where it is new: never on the server thread.
                    landing = CubicEvents.landing(cubes, view(cubes));
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

        private void arrive(ServerPlayer player, ServerCubeWorld cubes) {
            release(player);
            int[] view = view(cubes);
            int[] found = landing;
            Vec3 spot = found != null ? CubicEvents.arrivalAt(cubes, found) : CubicEvents.arrival(cubes, view);
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
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void commands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        var root = Commands.literal("earth");
        root.then(Commands.literal("visit").requires(EarthWorlds::mayTravel)
                .then(Commands.argument("place", StringArgumentType.greedyString())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(EarthPlaces.all().stream().map(EarthPlaces.Place::id), builder))
                        .executes(context -> visit(context.getSource(), StringArgumentType.getString(context, "place")))));
        root.then(Commands.literal("goto").requires(EarthWorlds::mayTravel)
                .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-80, 80))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180)).executes(context -> CubicEvents.travel(context.getSource(),
                                        DoubleArgumentType.getDouble(context, "latitude"), DoubleArgumentType.getDouble(context, "longitude")))
                                // Operators and the console can send someone else.
                                .then(Commands.argument("player", EntityArgument.player()).requires(source -> source.hasPermission(2))
                                        .executes(context -> CubicEvents.travel(context.getSource(), EntityArgument.getPlayer(context, "player"),
                                                DoubleArgumentType.getDouble(context, "latitude"), DoubleArgumentType.getDouble(context, "longitude")))))));
        // The creative travel map: the client opens it with a key, through this command.
        root.then(Commands.literal("map").requires(EarthWorlds::mayTravel).executes(context -> EarthMap.open(context.getSource())));
        root.then(Commands.literal("home").requires(EarthWorlds::mayTravel).executes(context -> home(context.getSource())));
        root.then(Commands.literal("where").executes(context -> CubicEvents.where(context.getSource())));
        root.then(Commands.literal("places").executes(context -> places(context.getSource())));
        dispatcher.register(root);
    }


    /** Travel is for operators, the singleplayer owner, or everyone when the server allows it. */
    private static boolean mayTravel(CommandSourceStack source) {
        if (source.hasPermission(2) || EarthConfig.ANYONE_CAN_TRAVEL.get()) return true;
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    private static int visit(CommandSourceStack source, String query) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Optional<EarthPlaces.Place> place = EarthPlaces.find(query);
        if (place.isEmpty()) {
            source.sendFailure(Component.literal("No famous place called '" + query + "'. Try /earth places, or /earth goto <latitude> <longitude>."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Travelling to " + place.get().label() + ".").withStyle(ChatFormatting.GREEN), false);
        return CubicEvents.travel(source, source.getPlayerOrException(), place.get().latitude(), place.get().longitude(),
                place.get().viewLatitude(), place.get().viewLongitude());
    }

    private static int home(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        if (level == null || !(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) {
            source.sendFailure(Component.literal("This world has no Earth."));
            return 0;
        }
        int[] view = Session.view(cubes);
        Session running = session;
        int[] found = running != null ? running.landing : null;
        Vec3 spot = found != null ? CubicEvents.arrivalAt(cubes, found) : CubicEvents.arrival(cubes, view);
        player.teleportTo(level, spot.x, spot.y, spot.z, CubicEvents.lookOut(cubes, spot, CubicEvents.facing(spot, view, player.getYRot())), 0.0F);
        return CubicEvents.where(source);
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
