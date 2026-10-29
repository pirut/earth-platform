package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The far terrain's distance on the pause menu, in kilometers and miles, through every detail
 * setting from 4 (37 km) to 48 (397 km), past 24 (201 km) marked as extreme; a narrow slider
 * leaves out the miles, then the words, so the label never runs past its edges. The reach is fixed
 * when the game starts (see {@link Far#DETAIL}), so a new distance is saved at once and says it
 * takes a restart.
 */
final class FarDistanceSlider extends AbstractSliderButton {
    private static final int MIN = 4, MAX = Far.MAX_DETAIL;

    FarDistanceSlider(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(), (EarthConfig.FAR_DETAIL.get() - MIN) / (double) (MAX - MIN));
        setTooltip(Tooltip.create(Component.translatable("earthplatform.pause.distance.tooltip")));
        updateMessage();
    }

    private int detail() { return MIN + (int) Math.round(value * (MAX - MIN)); }

    /** The longest label that fits the slider: with miles, then without, then the distance alone. */
    @Override protected void updateMessage() {
        int detail = detail(), km = EarthConfig.distanceKm(detail);
        String key = detail > Far.NORMAL_DETAIL ? "earthplatform.pause.distance.extreme" : "earthplatform.pause.distance";
        Component[] labels = {
                Component.translatable(key, km, Math.round(km * 0.621371)),
                Component.translatable(key + ".km", km),
                Component.translatable(key + ".short", km)};
        Font font = Minecraft.getInstance().font;
        Component message = null;
        for (Component label : labels) {
            message = detail == Far.DETAIL ? label : Component.translatable("earthplatform.pause.distance.restart", label);
            if (font.width(message) <= getWidth() - 8) break;
        }
        setMessage(message);
    }

    @Override protected void applyValue() { EarthConfig.setDetail(detail()); }
}
