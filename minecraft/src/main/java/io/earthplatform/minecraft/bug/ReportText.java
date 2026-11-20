package io.earthplatform.minecraft.bug;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scrubs paths, addresses and secrets out of text that might land in a public issue. */
public final class ReportText {
    static final String HIDDEN = "<hidden>";
    private static final Pattern WINDOWS_HOME = Pattern.compile(
            "([A-Za-z]:[\\\\/](?:Users|Documents and Settings)[\\\\/])([^\\\\/\\s:*?\"<>|]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIX_HOME = Pattern.compile("(/(?:Users|home)/)([^/\\s:]+)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern TOKEN_ARG = Pattern.compile("(?i)(--(?:accessToken|session|xuid|clientId)\\s+)(\\S+)");
    private static final Pattern TOKEN_ASSIGN = Pattern.compile(
            "(?i)((?:access_?token|session_?id|auth_?token|refresh_?token|api_?key|password)\\s*[=:]\\s*\"?)([^\\s\",;]+)");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    private ReportText() {}

    /** A blank field reads as {@code unknown} so the issue table stays complete. */
    public static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.strip();
    }

    /** Hides home-folder names, emails, tokens, and the extra whole words in {@code literals} (a player name, a UUID). */
    public static String scrub(String text, String... literals) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = text;
        if (literals != null) {
            for (String literal : literals) {
                if (literal == null || literal.length() < 3) continue;
                out = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(literal) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
                        .matcher(out).replaceAll(Matcher.quoteReplacement(HIDDEN));
            }
        }
        out = TOKEN_ARG.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(HIDDEN));
        out = TOKEN_ASSIGN.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(HIDDEN));
        out = JWT.matcher(out).replaceAll(Matcher.quoteReplacement(HIDDEN));
        out = WINDOWS_HOME.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "<user>"));
        out = UNIX_HOME.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "<user>"));
        out = EMAIL.matcher(out).replaceAll("<email>");
        return out;
    }

    /** One table cell: no newlines, and not so long that it crowds the link. */
    public static String oneLine(String text, int max) {
        String flat = scrub(text).replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
        if (flat.length() <= max) return flat;
        return flat.substring(0, max).strip() + "…";
    }

    static String cell(String value) {
        return orUnknown(value).replace("|", "\\|").replace("\n", " ");
    }

    /** Lower-case side name used in the issue table. */
    public static String side(boolean client) {
        return client ? "client" : "dedicated server";
    }

    public static String loader(String name, String version) {
        String who = switch (orUnknown(name)) {
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric Loader";
            default -> orUnknown(name);
        };
        String ver = orUnknown(version);
        return "unknown".equals(ver) ? who : who + " " + ver;
    }
}
