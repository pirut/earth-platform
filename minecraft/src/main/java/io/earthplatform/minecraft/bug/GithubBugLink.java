package io.earthplatform.minecraft.bug;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A prefilled {@code issues/new} link for pirut/earth-platform. Nothing is uploaded: the player's
 * browser opens GitHub, and the issue exists only after they press Submit.
 *
 * <p>The link names the markdown template {@code bug-courier.md}. This repo turns blank issues off,
 * and GitHub then drops {@code title} and {@code body} unless a template is selected. Bug Courier's
 * own {@code github:owner/repo} link does not add {@code template}, so when Bug Courier is installed
 * Earth Platform hands it {@link #TRACKER_TEMPLATE} instead.
 */
public final class GithubBugLink {
    public static final String MOD_ID = "bugcourier";
    public static final String REPO = "pirut/earth-platform";
    public static final String LABELS = "bug,bug-courier";
    /** Bug Courier replaces {@code {title}}, {@code {body}} and {@code {labels}} and encodes them. */
    public static final String TRACKER_NAME = "GitHub (pirut/earth-platform)";
    public static final String TRACKER_TEMPLATE =
            "https://github.com/" + REPO + "/issues/new?template=bug-courier.md&title={title}&body={body}&labels={labels}";
    /** Under the about-8 KB limit Bug Courier and browsers use for a new-issue link. */
    public static final int MAX_URL_LENGTH = 7500;

    private GithubBugLink() {}

    /**
     * What the link is filled with. Blank fields become {@code unknown}. The log and the summary
     * are scrubbed here so a caller cannot skip that step.
     */
    public record Facts(
            String earthVersion,
            String minecraftVersion,
            String loaderName,
            String loaderVersion,
            String side,
            String dimension,
            String coordinates,
            String latitudeLongitude,
            String biome,
            String logExcerpt,
            String summary) {
        public Facts {
            earthVersion = ReportText.orUnknown(earthVersion);
            minecraftVersion = ReportText.orUnknown(minecraftVersion);
            loaderName = ReportText.orUnknown(loaderName);
            loaderVersion = ReportText.orUnknown(loaderVersion);
            side = ReportText.orUnknown(side);
            dimension = ReportText.orUnknown(dimension);
            coordinates = ReportText.orUnknown(coordinates);
            latitudeLongitude = ReportText.orUnknown(latitudeLongitude);
            biome = ReportText.orUnknown(biome);
            logExcerpt = logExcerpt == null || logExcerpt.isBlank() ? "no log yet" : ReportText.scrub(logExcerpt).strip();
            summary = summary == null ? "" : ReportText.scrub(summary).strip();
        }

        Facts withLog(String logExcerpt) {
            return new Facts(earthVersion, minecraftVersion, loaderName, loaderVersion, side, dimension,
                    coordinates, latitudeLongitude, biome, logExcerpt, summary);
        }

        Facts withSummary(String summary) {
            return new Facts(earthVersion, minecraftVersion, loaderName, loaderVersion, side, dimension,
                    coordinates, latitudeLongitude, biome, logExcerpt, summary);
        }
    }

    public static String title(Facts facts) {
        String summary = facts.summary().isBlank() ? "Bug report" : facts.summary().replaceAll("\\s+", " ");
        if (summary.length() > 80) summary = summary.substring(0, 80).strip() + "...";
        return "[" + facts.earthVersion() + "] " + summary;
    }

    public static String body(Facts facts) {
        String what = facts.summary().isBlank()
                ? "Say what happened, what you expected, and what you did just before it."
                : facts.summary();
        StringBuilder out = new StringBuilder();
        out.append("### What happened\n\n").append(what).append("\n\n");
        out.append("### Environment\n\n| | |\n| --- | --- |\n");
        row(out, "Earth Platform", facts.earthVersion());
        row(out, "Minecraft", facts.minecraftVersion());
        row(out, "Loader", ReportText.loader(facts.loaderName(), facts.loaderVersion()));
        row(out, "Side", facts.side());
        row(out, "Dimension", facts.dimension());
        row(out, "Coordinates", facts.coordinates());
        row(out, "Latitude, longitude", facts.latitudeLongitude());
        row(out, "Biome", facts.biome());
        out.append('\n');
        if (!facts.logExcerpt().equals("no log yet")) {
            out.append("### Log (last lines)\n\n```\n").append(fence(facts.logExcerpt())).append("\n```\n\n");
        }
        out.append("Security problems belong on the Security tab, not in a public issue.\n\n");
        out.append("<sub>Prefilled by Earth Platform. Nothing is uploaded until you press Submit.</sub>\n");
        return out.toString();
    }

    /** The link, with the log and then the summary cut until it fits {@link #MAX_URL_LENGTH}. */
    public static URI url(Facts facts) {
        Facts fitted = facts;
        URI uri = assemble(title(fitted), body(fitted));
        if (uri.toString().length() <= MAX_URL_LENGTH) return uri;
        String log = fitted.logExcerpt();
        while (!log.isBlank() && !log.equals("no log yet")) {
            int next = Math.max(0, log.length() / 2);
            log = next < 40 ? "" : log.substring(0, next).strip() + "\n…(log cut to fit the link)";
            fitted = fitted.withLog(log.isBlank() ? "no log yet" : log);
            uri = assemble(title(fitted), body(fitted));
            if (uri.toString().length() <= MAX_URL_LENGTH) return uri;
            if (next < 40) break;
        }
        String summary = fitted.summary();
        while (uri.toString().length() > MAX_URL_LENGTH && summary.length() > 80) {
            summary = summary.substring(0, summary.length() / 2).strip();
            fitted = fitted.withSummary(summary + "\n\n_(description cut to fit the link)_");
            uri = assemble(title(fitted), body(fitted));
        }
        if (uri.toString().length() <= MAX_URL_LENGTH) return uri;
        fitted = fitted.withSummary("").withLog("no log yet");
        return assemble(title(fitted), body(fitted));
    }

    static URI assemble(String title, String body) {
        return URI.create("https://github.com/" + REPO + "/issues/new?template=bug-courier.md&title="
                + encode(title) + "&body=" + encode(body) + "&labels=" + encode(LABELS));
    }

    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void row(StringBuilder out, String label, String value) {
        out.append("| ").append(ReportText.cell(label)).append(" | ").append(ReportText.cell(value)).append(" |\n");
    }

    /** A fence cannot be closed by backticks inside a log line. */
    private static String fence(String text) {
        int longest = 0, run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        if (longest < 3) return text;
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R", -1)) lines.add(line.replace("`", "'"));
        return String.join("\n", lines);
    }
}
