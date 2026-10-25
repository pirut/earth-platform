package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.diagnostics.PerfExport;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /earthperf export}: saves the local performance log as one zip and links its folder in
 * chat. A client command, so it works in singleplayer and on any server, and sends nothing.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class PerfExportCommand {
    private PerfExportCommand() {}

    @SubscribeEvent public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("earthperf").then(Commands.literal("export").executes(context -> {
            Minecraft mc = Minecraft.getInstance();
            var perf = mc.gameDirectory.toPath().resolve("earthplatform/perf");
            // Off the render thread: the log can be up to 12 MiB.
            Thread.ofVirtual().name("Earth performance export").start(() -> {
                Component message;
                try {
                    var zip = PerfExport.export(perf, Instant.now());
                    String folder = zip.getParent().toAbsolutePath().toString();
                    message = Component.literal("Saved performance report " + zip.getFileName() + ". ")
                            .append(Component.literal("[Open folder]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, folder))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(folder)))))
                            .append(Component.literal(" Attach it to a bug report. It holds no names, places or addresses."));
                } catch (NoSuchFileException none) {
                    message = Component.literal("No performance log yet. Play for a minute and try again.").withStyle(ChatFormatting.YELLOW);
                } catch (Exception failure) {
                    EarthPlatformMod.LOGGER.warn("Performance export failed", failure);
                    message = Component.literal("Could not save the performance report: " + failure.getClass().getSimpleName()).withStyle(ChatFormatting.RED);
                }
                Component shown = message;
                mc.execute(() -> mc.gui.getChat().addMessage(shown));
            });
            return 1;
        })));
    }
}
