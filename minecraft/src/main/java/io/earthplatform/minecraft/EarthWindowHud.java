package io.earthplatform.minecraft;

import java.util.function.ToIntFunction;

/** Width-aware two-line diagnostics, independent of rendering so layout can be tested headlessly. */
public final class EarthWindowHud {
    private static final int MARGIN = 5;
    private static final int PADDING = 5;
    // The vanilla movement/tutorial toast occupies the first 32 GUI pixels.
    private static final int TOP = 42;

    private EarthWindowHud() {}

    public record Layout(int x, int y, int width, int height, int lineHeight, String coordinates, String source) {}

    public static Layout layout(int guiWidth, int guiHeight, int lineHeight, ToIntFunction<String> textWidth,
            long engineY, int originY, boolean realTerrain) {
        return layout(guiWidth, guiHeight, lineHeight, textWidth, engineY, originY, realTerrain, ProofTerrain.NOAA);
    }
    public static Layout layout(int guiWidth, int guiHeight, int lineHeight, ToIntFunction<String> textWidth,
            long engineY, int originY, boolean realTerrain, String dataset) {
        int available = Math.max(0, guiWidth - 2 * (MARGIN + PADDING));
        String earth = "Earth Y " + signed(engineY + originY) + " m";
        String coordinates = fit(available, textWidth,
                earth + " | engine " + engineY + " | origin " + signed(originY),
                earth + " | engine " + engineY,
                earth);
        String label = ProofTerrain.shortLabel(dataset);
        String compactLabel = ProofTerrain.COPERNICUS.equals(dataset) ? "Copernicus" : "NOAA";
        String source = realTerrain
                ? fit(available, textWidth, label + " | heuristic cover | 256 m window",
                        label + " | heuristic | 256 m", compactLabel + " | heuristic | 256 m")
                : fit(available, textWidth, "Synthetic calibration | 256 m window", "Synthetic | 256 m");
        int width = Math.min(Math.max(0, guiWidth - 2 * MARGIN),
                Math.max(textWidth.applyAsInt(coordinates), textWidth.applyAsInt(source)) + 2 * PADDING);
        int height = Math.min(Math.max(0, guiHeight - 2 * MARGIN), 2 * lineHeight + 2 * PADDING + 3);
        int y = Math.max(MARGIN, Math.min(TOP, guiHeight - height - MARGIN));
        return new Layout(MARGIN, y, width, height, lineHeight, coordinates, source);
    }

    private static String signed(long value) { return value >= 0 ? "+" + value : Long.toString(value); }

    private static String fit(int available, ToIntFunction<String> textWidth, String... alternatives) {
        for (String text : alternatives) if (textWidth.applyAsInt(text) <= available) return text;
        String text = alternatives[alternatives.length - 1];
        while (!text.isEmpty() && textWidth.applyAsInt(text + "...") > available) {
            text = text.substring(0, text.length() - 1);
        }
        return textWidth.applyAsInt(text + "...") <= available ? text + "..." : "";
    }
}
