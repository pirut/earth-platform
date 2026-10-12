package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Stage A read-only actual-player diagnostic. It never creates an installed-session capability. */
@EventBusSubscriber(modid="earthplatform")
public final class InstalledSessionProbeCommands {
    public static final String FLAG="earthplatform.installedSessionProbe";
    private static final int MAX_REPORTS=4;
    // One process-lifetime charge: four <=1 MiB UTF-16 reports, <=1 MiB identity/roster
    // scratch, <=4 MiB per-event copied chains across a capture, <=2 MiB reflective/string
    // transients, <=1 MiB streaming file/hash scratch, plus 4 MiB conservative slack.
    // This is diagnostic accounting, not approval of runtime/session reservations.
    private static NativeDistanceCpuBudget.Reservation charge;
    private static final InstalledSessionCallbackProbe.Report[] reports=new InstalledSessionCallbackProbe.Report[MAX_REPORTS];
    private static IntegratedServer owner;
    private static Path output;
    private static int attempts;
    private static boolean observing;
    private InstalledSessionProbeCommands() {}

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if(!Boolean.getBoolean(FLAG))return;
        event.getDispatcher().register(Commands.literal("earthsurvival_probe")
                .requires(source->source.getEntity() instanceof ServerPlayer)
                .executes(context->{
                    ServerPlayer player=context.getSource().getPlayerOrException();
                    try {
                        Path path=capture(player);
                        context.getSource().sendSuccess(()->Component.literal("Installed diagnostic saved: "+path.getFileName()+". Observation only; no entry approval."),false);
                        return 1;
                    } catch(java.io.IOException | RuntimeException failure){
                        context.getSource().sendFailure(Component.literal("Installed diagnostic refused: "+failure.getClass().getSimpleName()+". Ordinary play is unchanged."));
                        return 0;
                    }
                }));
    }
    private static Path capture(ServerPlayer player) throws java.io.IOException {
        if(!Boolean.getBoolean(FLAG) || player==null || player.getClass()!=ServerPlayer.class
                || player.server.getClass()!=IntegratedServer.class || !player.server.isSameThread()
                || player.server.isPublished() || NativeSessionExecutionGate.blocksNative(player))
            throw new IllegalStateException("Actual ordinary integrated player required");
        IntegratedServer server=(IntegratedServer)player.server;
        if(observing || attempts>=MAX_REPORTS || (owner!=null && owner!=server))
            throw new IllegalStateException("One bounded diagnostic owner is retained for process lifetime");
        if(charge==null)charge=NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,16L<<20);
        // Retain ownership before filesystem or observation work. A failed write is never retried
        // over the same file or silently discarded from the attempt budget.
        owner=server;int index=attempts++;observing=true;
        try {
            if(output==null){
                Path root=server.getServerDirectory().toAbsolutePath().normalize();
                for(Path ancestor=root;ancestor!=null;ancestor=ancestor.getParent())
                    if(Files.isSymbolicLink(ancestor))throw new java.io.IOException("Diagnostic root has symbolic-link ancestor");
                output=Files.createTempDirectory(root,"earthplatform-installed-probe-");
                if(!Files.isDirectory(output,LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("Diagnostic directory differs");
            }
            reports[index]=InstalledSessionCallbackProbe.observe(player);
            Path file=output.resolve("observation-"+(index+1)+".txt");
            try(BufferedWriter writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                writer.write("STAGE_A_ORDINARY_DIAGNOSTIC version=1 completeDiagnostic="+reports[index].completeDiagnostic());writer.newLine();
                for(String line:reports[index].lines()){writer.write(line);writer.newLine();}
            }
            return file;
        } finally {observing=false;}
    }
}
