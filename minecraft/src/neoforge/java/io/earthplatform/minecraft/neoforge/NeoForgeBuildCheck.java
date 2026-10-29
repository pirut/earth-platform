package io.earthplatform.minecraft.neoforge;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import java.util.function.Consumer;
import io.netty.buffer.ByteBuf;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Turns a client away at connect, with a plain message, when its Earth Platform build differs
 * from the server's. The cube and far terrain payloads change between builds without their
 * channel versions changing, so a mismatched pair used to join and then fail to decode, or quietly
 * drew wrong.
 *
 * <p>The server sends its build while configuring the connection; the client answers with its
 * own and the server lets it in only on a match. Builds before 68 have no such channel, so the
 * server turns those away too. The channel is optional, so it never changes how NeoForge
 * negotiates the rest.
 */
final class NeoForgeBuildCheck {
    private static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "build_check"));

    /** The connection on which this client last heard a server's build. */
    @Nullable private static volatile Connection heard;

    private NeoForgeBuildCheck() {}

    record Build(String version) implements CustomPacketPayload {
        static final Type<Build> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "build"));
        static final StreamCodec<ByteBuf, Build> CODEC = ByteBufCodecs.STRING_UTF8.map(Build::new, Build::version);
        @Override public Type<Build> type() { return TYPE; }
    }

    static String version() {
        return ModList.get().getModContainerById(EarthPlatformMod.MOD_ID).map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown");
    }

    static void register(PayloadRegistrar registrar) {
        registrar.optional().configurationBidirectional(Build.TYPE, Build.CODEC, new DirectionalPayloadHandler<>(NeoForgeBuildCheck::fromServer, NeoForgeBuildCheck::fromClient));
    }

    static void tasks(RegisterConfigurationTasksEvent event) { event.register(new Task(event.getListener())); }

    private record Task(ServerConfigurationPacketListener listener) implements ICustomConfigurationTask {
        @Override public Type type() { return TASK; }

        @Override public void run(Consumer<CustomPacketPayload> sender) {
            if (listener.hasChannel(Build.TYPE)) {
                sender.accept(new Build(version()));
                return;
            }
            EarthPlatformMod.LOGGER.info("Turned away {}: their Earth Platform is older than build 68, this server runs {}", name(listener), version());
            listener.disconnect(mismatch(version(), null));
        }
    }

    /** Client: answers with its own build. */
    private static void fromServer(Build server, IPayloadContext context) {
        heard = context.connection();
        context.reply(new Build(version()));
    }

    /** Server: lets the client in only on a match. */
    private static void fromClient(Build client, IPayloadContext context) {
        String ours = version();
        if (ours.equals(client.version())) {
            context.finishCurrentTask(TASK);
            return;
        }
        ServerConfigurationPacketListener listener = (ServerConfigurationPacketListener) context.listener();
        EarthPlatformMod.LOGGER.info("Turned away {}: they run Earth Platform {}, this server runs {}", name(listener), client.version(), ours);
        context.disconnect(mismatch(ours, client.version()));
    }

    /**
     * Client, once in the world: why to leave a server too old to have sent its build, or null.
     * Only for a remote server that has Earth Platform's channels.
     */
    @Nullable static Component tooOld(Connection connection, boolean earthServer) {
        boolean heardBuild = heard == connection;
        heard = null;
        if (heardBuild || !earthServer) return null;
        EarthPlatformMod.LOGGER.warn("Leaving the server: it runs an Earth Platform build older than 68, this game runs {}", version());
        return Component.literal("This server runs an older Earth Platform build than yours (" + version() + ").\n\n"
                + "Install the same Earth Platform build as the server to join.");
    }

    static ResourceLocation earthChannel() { return CubePayloads.CubeData.TYPE.id(); }

    private static String name(ServerConfigurationPacketListener listener) {
        return listener instanceof ServerConfigurationPacketListenerImpl impl ? impl.getOwner().getName() : "a player";
    }

    private static Component mismatch(String server, @Nullable String client) {
        String yours = client == null ? "an Earth Platform build older than 68" : "Earth Platform " + client;
        return Component.literal("This server runs Earth Platform " + server + ", and you have " + yours + ".\n\n"
                + "Install the same Earth Platform build as the server to join.");
    }
}
