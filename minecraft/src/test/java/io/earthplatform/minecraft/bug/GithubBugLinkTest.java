package io.earthplatform.minecraft.bug;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GithubBugLinkTest {
    @Test void prefilledLinkCarriesTheReportAndDropsPersonalDetails() {
        GithubBugLink.Facts facts = new GithubBugLink.Facts(
                "0.1.0-alpha.75", "1.21.1", "neoforge", "21.1.259", "client", "earthplatform:earth",
                "100 64 -200", "36.05440, -112.14010", "minecraft:plains",
                "Loading C:\\Users\\alice\\AppData\\Roaming\\minecraft\\logs\\latest.log\n"
                        + "session for bob@example.com and --accessToken secret-token-value\n"
                        + "player Steve stood at /home/bob/.minecraft\n",
                "The river is too high");
        URI uri = GithubBugLink.url(facts);
        String url = uri.toString();
        assertTrue(url.startsWith("https://github.com/pirut/earth-platform/issues/new?"));
        assertTrue(url.contains("template=bug-courier.md"));
        assertTrue(url.contains("labels=bug%2Cbug-courier"));
        assertTrue(url.length() <= GithubBugLink.MAX_URL_LENGTH);
        String body = query(uri, "body");
        String title = query(uri, "title");
        assertTrue(title.contains("[0.1.0-alpha.75]"));
        assertTrue(title.contains("The river is too high"));
        assertTrue(body.contains("0.1.0-alpha.75"));
        assertTrue(body.contains("1.21.1"));
        assertTrue(body.contains("NeoForge 21.1.259"));
        assertTrue(body.contains("100 64 -200"));
        assertTrue(body.contains("36.05440, -112.14010"));
        assertTrue(body.contains("minecraft:plains"));
        assertTrue(body.contains("earthplatform:earth"));
        assertTrue(body.contains("<user>"));
        assertTrue(body.contains("<email>"));
        assertTrue(body.contains("<hidden>"));
        assertFalse(body.contains("alice"));
        assertFalse(body.contains("bob"));
        assertFalse(body.contains("secret-token-value"));
        assertFalse(url.contains("alice"));
        assertFalse(url.contains("secret-token-value"));
        assertFalse(body.contains("ghp_"));
    }

    @Test void playerNameIsHiddenOnlyAsAWholeWord() {
        String scrubbed = ReportText.scrub("Steve fell from device", "Steve");
        assertTrue(scrubbed.contains("<hidden> fell"));
        assertTrue(scrubbed.contains("device"));
        assertFalse(scrubbed.contains("Steve"));
        assertEquals("Ed", ReportText.scrub("Ed", "Ed"));
    }

    @Test void longLogIsCutSoTheLinkStillFits() {
        String log = ("C:\\Users\\carol\\AppData\\warning " + "x".repeat(200) + "\n").repeat(400);
        URI uri = GithubBugLink.url(new GithubBugLink.Facts(
                "0.1.0-alpha.75", "1.21.1", "fabric", "0.16.14", "dedicated server", "minecraft:overworld",
                "1 2 3", "not an Earth world", "minecraft:ocean", log, "y".repeat(4000)));
        String url = uri.toString();
        assertTrue(url.length() <= GithubBugLink.MAX_URL_LENGTH, "length " + url.length());
        assertTrue(url.contains("template=bug-courier.md"));
        String body = query(uri, "body");
        assertTrue(body.contains("Fabric Loader 0.16.14"));
        assertTrue(body.contains("1 2 3"));
        assertFalse(body.contains("carol"));
        assertFalse(url.contains("carol"));
    }

    @Test void blankFactsStayUnknownAndStillNameTheRepository() {
        URI uri = GithubBugLink.url(new GithubBugLink.Facts(null, " ", null, null, null, null, null, null, null, null, null));
        String body = query(uri, "body");
        assertTrue(body.contains("| Earth Platform | unknown |"));
        assertTrue(body.contains("| Coordinates | unknown |"));
        assertFalse(body.contains("### Log"));
        assertTrue(uri.toString().contains("github.com/pirut/earth-platform/issues/new"));
    }

    @Test void logTailScrubsAndLimits(@TempDir Path dir) throws Exception {
        Path logs = Files.createDirectories(dir.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), "start\n".repeat(30) + "token --accessToken abcdefghijklmnop\n"
                + "saved /home/dave/instance\n");
        String excerpt = LogExcerpt.tail(dir, 4, 200, "dave");
        assertFalse(excerpt.contains("start\n".repeat(5)));
        assertFalse(excerpt.contains("dave"));
        assertFalse(excerpt.contains("abcdefghijklmnop"));
        assertTrue(excerpt.contains("<user>") || excerpt.contains("<hidden>"));
        assertTrue(excerpt.length() <= 200);
        assertEquals("no log yet", LogExcerpt.tail(dir.resolve("missing"), 4, 200));
    }

    @Test void onlyTheBridgeClassNamesBugCourier() throws Exception {
        String reporting = new String(classBytes("io/earthplatform/minecraft/bug/BugReporting.class"), StandardCharsets.ISO_8859_1);
        String place = new String(classBytes("io/earthplatform/minecraft/bug/BugPlace.class"), StandardCharsets.ISO_8859_1);
        String link = new String(classBytes("io/earthplatform/minecraft/bug/GithubBugLink.class"), StandardCharsets.ISO_8859_1);
        String bridge = new String(classBytes("io/earthplatform/minecraft/bug/BugCourierBridge.class"), StandardCharsets.ISO_8859_1);
        assertFalse(reporting.contains("io/bugcourier"));
        assertFalse(reporting.contains("io.bugcourier"));
        assertFalse(place.contains("io/bugcourier"));
        assertFalse(link.contains("io/bugcourier"));
        assertTrue(bridge.contains("io/bugcourier/api/BugReports"));
        assertTrue(bridge.contains("io/bugcourier/core/Trackers"));
        assertTrue(reporting.contains("BugCourierBridge"));
    }

    @Test void issueTemplateAndMetadataKeepTheDependencyOptional() throws Exception {
        Path project = Path.of(System.getProperty("earthplatform.test.projectDirectory"));
        String template = Files.readString(project.resolve("../.github/ISSUE_TEMPLATE/bug-courier.md"));
        assertTrue(template.contains("labels: bug, bug-courier"));
        assertTrue(template.contains("name: Bug Courier report"));
        String toml = Files.readString(project.resolve("src/main/templates/META-INF/neoforge.mods.toml"));
        assertTrue(toml.contains("modId=\"bugcourier\""));
        assertTrue(toml.contains("type=\"optional\""));
        assertFalse(toml.contains("modId=\"bugcourier\"\n    type=\"required\""));
        String fabric = Files.readString(project.resolve("../fabric/src/main/resources/fabric.mod.json"));
        assertTrue(fabric.contains("\"bugcourier\": \">=0.1.0\""));
        assertFalse(fabric.substring(fabric.indexOf("\"depends\""), fabric.indexOf("\"suggests\"")).contains("bugcourier"));
    }

    private static byte[] classBytes(String path) throws Exception {
        try (var in = GithubBugLinkTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return in.readAllBytes();
        }
    }

    private static String query(URI uri, String key) {
        for (String part : uri.getRawQuery().split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        fail("missing " + key);
        return "";
    }
}
