package io.earthplatform.minecraft.bug;

import io.bugcourier.api.BugReports;
import io.bugcourier.core.Trackers;
import io.earthplatform.minecraft.EarthPlatformMod;

/**
 * The only class that names Bug Courier. {@link BugReporting} loads it with {@code Class.forName}
 * after it has checked that Bug Courier is installed and that this process is a client, so a
 * dedicated server and a game without the mod never resolve these types.
 */
public final class BugCourierBridge {
    private BugCourierBridge() {}

    public static void register() {
        BugReports.register(EarthPlatformMod.MOD_ID, config -> config
                .tracker(Trackers.template(GithubBugLink.TRACKER_NAME, GithubBugLink.TRACKER_TEMPLATE))
                .labels("bug", "bug-courier")
                .context("Coordinates", BugClientFacts::coordinates)
                .context("Latitude, longitude", BugClientFacts::latitudeLongitude)
                .context("Biome", BugClientFacts::biome)
                .context("Recent log", BugClientFacts::recentLog));
    }

    public static void open() {
        BugReports.open(EarthPlatformMod.MOD_ID);
    }
}
