package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.cubic.play.EarthPlaces;
import io.earthplatform.minecraft.cubic.play.EarthWorlds;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/** The Earth world type's "Customize" screen: where new players start, a famous place or any latitude and longitude. */
public final class EarthSetupScreen extends Screen {
    private final Screen parent;
    private PlaceList list;
    private EditBox latitude, longitude;
    private Button done;
    private CycleButton<Boolean> imagery;
    /** True while fields are filled from the list, so typing in them can tell the two apart. */
    private boolean filling;
    /** What was on screen when the globe opened, so coming back keeps it. */
    private double[] kept;
    private Boolean keptImagery;

    EarthSetupScreen(Screen parent) {
        super(Component.translatable("earthplatform.setup.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        int listTop = 26 + 10 * font.split(Component.translatable("earthplatform.setup.hint"), width - 40).size() + 4, listBottom = height - 108;
        list = new PlaceList(minecraft, Math.min(340, width - 20), listBottom - listTop, listTop);
        list.setX(width / 2 - list.getWidth() / 2);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(Component.translatable("earthplatform.setup.globe"), button -> openGlobe())
                .tooltip(Tooltip.create(Component.translatable("earthplatform.setup.globe.tooltip")))
                .bounds(width / 2 - 155, height - 100, 150, 20).build());
        imagery = addRenderableWidget(CycleButton.onOffBuilder(keptImagery != null ? keptImagery : EarthConfig.imagery())
                .withTooltip(on -> Tooltip.create(Component.translatable("earthplatform.setup.imagery.tooltip")))
                .create(width / 2 + 5, height - 100, 150, 20, Component.translatable("earthplatform.setup.imagery")));

        int y = height - 74;
        latitude = new EditBox(font, width / 2 - 155, y, 100, 20, Component.translatable("earthplatform.setup.latitude"));
        longitude = new EditBox(font, width / 2 - 50, y, 100, 20, Component.translatable("earthplatform.setup.longitude"));
        latitude.setHint(Component.translatable("earthplatform.setup.latitude"));
        longitude.setHint(Component.translatable("earthplatform.setup.longitude"));
        latitude.setResponder(text -> typed());
        longitude.setResponder(text -> typed());
        addRenderableWidget(latitude);
        addRenderableWidget(longitude);
        addRenderableWidget(Button.builder(Component.translatable("earthplatform.setup.random"), button -> {
            var places = EarthPlaces.all();
            select(places.get(RandomSource.create().nextInt(places.size())));
        }).bounds(width / 2 + 55, y, 100, 20).build());

        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> finish()).bounds(width / 2 - 155, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose()).bounds(width / 2 + 5, height - 28, 150, 20).build());

        EarthWorlds.Choice chosen = EarthWorlds.chosen();
        if (kept != null) {
            fill(kept[0], kept[1]);
            EarthPlaces.near(kept[0], kept[1]).ifPresent(this::select);
        } else if (chosen == null) select(EarthPlaces.YOSEMITE);
        else {
            fill(chosen.settings().latitude(), chosen.settings().longitude());
            EarthPlaces.near(chosen.settings().latitude(), chosen.settings().longitude()).ifPresent(this::select);
        }
    }

    private void openGlobe() {
        double[] at = point().orElse(new double[]{EarthPlaces.YOSEMITE.latitude(), EarthPlaces.YOSEMITE.longitude()});
        kept = at;
        keptImagery = imagery.getValue();
        minecraft.setScreen(new EarthGlobeScreen(this, at[0], at[1], (lat, lon) -> kept = new double[]{lat, lon}));
    }

    private void select(EarthPlaces.Place place) {
        list.setSelected(list.entry(place));
        list.scrollTo(list.entry(place));
        fill(place.latitude(), place.longitude());
    }

    private void fill(double lat, double lon) {
        filling = true;
        latitude.setValue(String.format(Locale.ROOT, "%.4f", lat));
        longitude.setValue(String.format(Locale.ROOT, "%.4f", lon));
        filling = false;
        typed();
    }

    private void typed() {
        if (done == null) return;
        Optional<double[]> point = point();
        done.active = point.isPresent();
        if (filling || point.isEmpty()) return;
        var selected = list.getSelected();
        if (selected != null && (Math.abs(selected.place.latitude() - point.get()[0]) > 1e-4 || Math.abs(selected.place.longitude() - point.get()[1]) > 1e-4))
            list.setSelected(null);
    }

    /** The typed latitude and longitude, when both are numbers the Earth can start at. */
    private Optional<double[]> point() {
        try {
            double lat = Double.parseDouble(latitude.getValue().trim()), lon = Double.parseDouble(longitude.getValue().trim());
            if (Math.abs(lat) > 80 || Math.abs(lon) > 180 || !Double.isFinite(lat) || !Double.isFinite(lon)) return Optional.empty();
            return Optional.of(new double[]{lat, lon});
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private void finish() {
        point().ifPresent(point -> {
            var selected = list.getSelected();
            String name = selected != null ? selected.place.label() : EarthPlaces.near(point[0], point[1]).map(EarthPlaces.Place::label).orElse("");
            EarthWorlds.choose(new EarthWorlds.Choice(EarthSettings.at(point[0], point[1]), name));
            if (imagery.getValue() != EarthConfig.imagery()) EarthConfig.setImagery(imagery.getValue());
            onClose();
        });
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        int lineY = 26;
        for (var line : font.split(Component.translatable("earthplatform.setup.hint"), width - 40)) {
            gui.drawCenteredString(font, line, width / 2, lineY, 0xA0A0A0);
            lineY += 10;
        }
        if (!done.active) gui.drawCenteredString(font, Component.translatable("earthplatform.setup.invalid"), width / 2, height - 48, 0xFF7777);
        else {
            var selected = list.getSelected();
            Component where = selected != null ? Component.translatable("earthplatform.setup.startAt", selected.place.label())
                    : Component.translatable("earthplatform.setup.startAtPoint", latitude.getValue().trim(), longitude.getValue().trim());
            gui.drawCenteredString(font, where, width / 2, height - 48, 0x9FD7FF);
        }
    }

    private final class PlaceList extends ObjectSelectionList<PlaceEntry> {
        PlaceList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 16);
            for (EarthPlaces.Place place : EarthPlaces.all()) addEntry(new PlaceEntry(place));
        }

        PlaceEntry entry(EarthPlaces.Place place) {
            return children().stream().filter(entry -> entry.place == place).findFirst().orElse(null);
        }

        void scrollTo(PlaceEntry entry) { if (entry != null) centerScrollOn(entry); }

        @Override public int getRowWidth() { return getWidth() - 20; }
    }

    private final class PlaceEntry extends ObjectSelectionList.Entry<PlaceEntry> {
        final EarthPlaces.Place place;

        PlaceEntry(EarthPlaces.Place place) { this.place = place; }

        @Override public void render(GuiGraphics gui, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            gui.drawString(font, place.name(), left + 4, top + 3, 0xFFFFFF);
            gui.drawString(font, place.region(), left + 8 + font.width(place.name()), top + 3, 0x9A9A9A);
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            select(place);
            return true;
        }

        @Override public Component getNarration() { return Component.literal(place.label()); }
    }
}
