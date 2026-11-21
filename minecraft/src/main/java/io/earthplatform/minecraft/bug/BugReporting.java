package io.earthplatform.minecraft.bug;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.platform.Loader;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/**
 * {@code /earth bug}, and the startup check that wires Bug Courier when it is installed.
 * This class does not name Bug Courier's types. The bridge is loaded only on a client that has it.
 */
public final class BugReporting {
    private static final String BRIDGE = "io.earthplatform.minecraft.bug.BugCourierBridge";

    private BugReporting() {}

    /** Call from each loader's startup. Safe on a dedicated server and when Bug Courier is absent. */
    public static void registerIfPresent() {
        if (!Loader.INSTANCE.hasMod(GithubBugLink.MOD_ID)) {
            EarthPlatformMod.LOGGER.info("Bug Courier is not installed. /earth bug opens a prefilled GitHub issue.");
            return;
        }
        if (!Loader.INSTANCE.isClient()) {
            EarthPlatformMod.LOGGER.info("Bug Courier is installed. Reports are filed on the client; this process will not load it.");
            return;
        }
        if (bridge("register")) {
            EarthPlatformMod.LOGGER.info("Bug Courier is installed. Earth Platform reports open at https://github.com/{}/issues.", GithubBugLink.REPO);
        }
    }

    public static int file(CommandSourceStack source, String summary) {
        if (Loader.INSTANCE.isClient() && Loader.INSTANCE.hasMod(GithubBugLink.MOD_ID) && bridge("open")) {
            String extra = summary == null || summary.isBlank() ? "" : " Add this to the description: " + ReportText.oneLine(summary, 120);
            source.sendSuccess(() -> Component.literal("Opening a bug report for Earth Platform. You'll see everything before it goes anywhere." + extra), false);
            return 1;
        }
        URI uri = GithubBugLink.url(BugPlace.capture(source, summary == null ? "" : summary));
        if (Loader.INSTANCE.isClient()) Util.getPlatform().openUri(uri);
        Component link = Component.literal("[Open the prefilled issue]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, uri.toString()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Nothing is sent until you press Submit on GitHub"))));
        String lead = Loader.INSTANCE.hasMod(GithubBugLink.MOD_ID)
                ? "Bug Courier runs on your own computer. Use the pause menu's Report a mod bug button, or /bugreport earthplatform. "
                : "Bug Courier isn't installed, so this opens a prefilled GitHub issue. Nothing is sent until you press Submit. ";
        source.sendSuccess(() -> Component.literal(lead).append(link), false);
        return 1;
    }

    private static boolean bridge(String method) {
        try {
            Class.forName(BRIDGE).getDeclaredMethod(method).invoke(null);
            return true;
        } catch (InvocationTargetException e) {
            EarthPlatformMod.LOGGER.warn("Bug Courier is installed, but Earth Platform could not use it ({})", e.getCause() == null ? e.toString() : e.getCause().toString());
        } catch (ReflectiveOperationException | LinkageError e) {
            EarthPlatformMod.LOGGER.warn("Bug Courier is installed, but Earth Platform could not use it ({})", e.toString());
        }
        return false;
    }
}
