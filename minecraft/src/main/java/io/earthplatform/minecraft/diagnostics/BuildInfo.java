package io.earthplatform.minecraft.diagnostics;

import java.io.InputStream;
import java.util.Properties;

/** Baked current-build identity. Players install one JAR; no runtime switch is required. */
public final class BuildInfo {
    private static final Properties VALUES = load();
    private BuildInfo() {}
    private static Properties load() {
        Properties result = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/META-INF/earthplatform-build.properties")) {
            if (in != null) result.load(in);
        } catch (java.io.IOException ignored) { result.clear(); }
        return result;
    }
    public static String commit() { return commit(VALUES.getProperty("commit", "unknown")); }
    static String commit(String value) { return value.matches("[a-f0-9]{40}") ? value : "unknown"; }
    public static String version() {
        String version=VALUES.getProperty("version", "development");
        return version.matches("[A-Za-z0-9.+_-]{1,96}") ? version : "development";
    }
    /** Bounded local performance logs are on unless {@code -Dearthplatform.perfLog=false}. */
    private static final boolean DIAGNOSTICS = Boolean.parseBoolean(System.getProperty("earthplatform.perfLog", "true"));
    public static boolean diagnostics() { return DIAGNOSTICS; }
}
