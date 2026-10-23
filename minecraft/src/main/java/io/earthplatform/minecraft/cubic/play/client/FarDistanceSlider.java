package io.earthplatform.minecraft.cubic.play.client;

import io.github.pirut.chunks3.far.Far;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The far terrain's distance on the pause menu, in kilometers and miles, through every detail
 * setting from 4 (37 km) to 48 (397 km), past 24 (201 km) marked as extreme. The reach is fixed
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

    @Override protected void updateMessage() {
        int detail = detail(), km = EarthConfig.distanceKm(detail);
        Component distance = Component.translatable(detail > Far.NORMAL_DETAIL ? "earthplatform.pause.distance.extreme" : "earthplatform.pause.distance",
                km, Math.round(km * 0.621371));
        setMessage(detail == Far.DETAIL ? distance
                : Component.translatable("earthplatform.pause.distance.restart", distance));
    }

    @Override protected void applyValue() { EarthConfig.setDetail(detail()); }
}
