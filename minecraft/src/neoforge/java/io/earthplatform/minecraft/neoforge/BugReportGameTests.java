package io.earthplatform.minecraft.neoforge;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.bug.BugReporting;
import io.earthplatform.minecraft.bug.GithubBugLink;
import java.net.URI;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runs inside the game-test server. The same link builder the command uses, with the mod loaded. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BugReportGameTests {
    private BugReportGameTests() {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(Cases.class);
    }

    public static final class Cases {
        /** Structure is {@code data/earthplatform/structure/empty.nbt}. The namespace keeps vanilla tests out. */
        @PrefixGameTestTemplate(false)
        @GameTest(templateNamespace = "earthplatform", template = "empty")
        public static void prefilledIssueLink(GameTestHelper helper) {
            BugReporting.registerIfPresent();
            URI uri = GithubBugLink.url(new GithubBugLink.Facts(
                    "test", "1.21.1", "neoforge", "21.1.259", "dedicated server", "minecraft:overworld",
                    "0 64 0", "0.00000, 0.00000", "minecraft:plains", "game test log", ""));
            String url = uri.toString();
            if (!url.startsWith("https://github.com/pirut/earth-platform/issues/new?template=bug-courier.md")) {
                throw new AssertionError(url);
            }
            if (url.length() > GithubBugLink.MAX_URL_LENGTH) throw new AssertionError("url length " + url.length());
            helper.succeed();
        }
    }
}
