package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.cubic.play.EarthPlaces;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * A globe to choose where an Earth world begins: drag to spin it, scroll to zoom from space down to single valleys,
 * click to put the pin down. Famous places are marked and can be clicked too. Close up, the real terrain loads in.
 * In a creative world the same globe is the travel map, and it is also what a trip flies over (see {@link EarthFlight}).
 */
public final class EarthGlobeScreen extends Screen {
    /** The generator only covers latitudes up to this far from the equator. */
    private static final double LIMIT = 80;
    private static final double MAX_ZOOM = 12.5;
    private static final int TOP = 24, BOTTOM = 56;
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("earthplatform", "dynamic/globe_picker");

    private final Screen parent;
    private final BiConsumer<Double, Double> picked;
    private GlobeImagery imagery;
    private DynamicTexture texture;
    private int textureWidth, textureHeight;
    /** The view: the point at the centre, and the zoom as powers of two over the whole globe fitting the screen. */
    private double centreLat, centreLon, zoom;
    private double pinLat, pinLon;
    private boolean pinned;
    private boolean dirty = true, dragging, moved;
    private long lastChange, lastDraw;
    private double pressX, pressY;
    private Component warning;
    private long warningUntil;
    private Hover hover;
    /** The trip this screen shows, with no controls; null when the globe is for choosing. */
    @Nullable private EarthFlight flight;
    /** Translation keys (title, button, hint and pin) of a globe that picks a spot for the server rather than a trip; null otherwise. */
    @Nullable private String keys;
    /** The travel map's "you are here", or NaN. */
    private double hereLat = Double.NaN, hereLon = Double.NaN;
    private int top = TOP, bottom = BOTTOM;
    /** Detail tiles a trip will need at both ends, asked for from the start so they are there when the globe gets to them. */
    private List<Long> ahead = List.of();

    EarthGlobeScreen(Screen parent, double lat, double lon, BiConsumer<Double, Double> picked) {
        this(Component.translatable("earthplatform.globe.title"), parent, lat, lon, picked);
    }

    private EarthGlobeScreen(Component title, @Nullable Screen parent, double lat, double lon, BiConsumer<Double, Double> picked) {
        super(title);
        this.parent = parent;
        this.picked = picked;
        centreLat = Mth.clamp(lat, -LIMIT, LIMIT);
        centreLon = lon;
        pinLat = lat;
        pinLon = lon;
        pinned = true;
        zoom = 1.5;
    }

    /** The creative travel map, opened where the player stands. */
    static EarthGlobeScreen travel(double lat, double lon) {
        EarthGlobeScreen screen = new EarthGlobeScreen(Component.translatable("earthplatform.map.title"), null, lat, lon,
                (toLat, toLon) -> EarthFlight.start(lat, lon, toLat, toLon));
        screen.hereLat = lat;
        screen.hereLon = lon;
        screen.pinned = false;
        screen.zoom = 4;
        return screen;
    }

    /** A globe for picking a spot on a server, such as its spawn, opened where the player stands; {@code keys} name its texts. */
    static EarthGlobeScreen pick(String keys, double lat, double lon, BiConsumer<Double, Double> picked) {
        EarthGlobeScreen screen = new EarthGlobeScreen(Component.translatable(keys + ".title"), null, lat, lon, picked);
        screen.keys = keys;
        screen.hereLat = lat;
        screen.hereLon = lon;
        screen.pinned = false;
        screen.zoom = 4;
        return screen;
    }

    /** The globe a trip flies over; it fades in over the world and back out again. */
    static EarthGlobeScreen flight(EarthFlight flight) {
        EarthGlobeScreen screen = new EarthGlobeScreen(Component.empty(), null, flight.toLatitude(), flight.toLongitude(), (a, b) -> {});
        screen.flight = flight;
        screen.top = 0;
        screen.bottom = 0;
        return screen;
    }

    @Nullable EarthFlight flying() { return flight; }

    @Override public boolean isPauseScreen() { return flight == null && super.isPauseScreen(); }

    @Override public boolean shouldCloseOnEsc() { return flight == null; }

    @Override protected void init() {
        if (imagery == null) imagery = new GlobeImagery();
        dirty = true;
        if (flight != null) return;
        int y = height - 28;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> zoomBy(-1, width / 2.0, centreY())).bounds(width / 2 - 155, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoomBy(1, width / 2.0, centreY())).bounds(width / 2 - 133, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(keys != null ? keys + ".button" : hereLat == hereLat ? "earthplatform.map.travel" : "earthplatform.globe.choose"), b -> finish()).bounds(width / 2 - 108, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 27, y, 128, 20).build());
        dirty = true;
    }

    private int globeHeight() { return height - top - bottom; }
    private double centreY() { return top + globeHeight() / 2.0; }
    /** Globe radius in GUI pixels. */
    private double radius() { return Math.min(width, globeHeight()) * 0.46 * Math.pow(2, zoom); }

    // Drawing ----------------------------------------------------------------------------------------

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        if (flight != null) {
            renderBackground(gui, mouseX, mouseY, partialTick);
            return;
        }
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        gui.drawCenteredString(font, status(), width / 2, height - 48, 0x9FD7FF);
        if (hover != null) gui.renderTooltip(font, Component.literal(hover.place.label()), hover.x, hover.y);
    }

    /** The globe, its markers and the bars behind the buttons; widgets draw over this. */
    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        if (flight != null) {
            renderFlight(gui);
            return;
        }
        gui.fill(0, 0, width, height, 0xFF05070D);
        if (imagery.takeChanged()) dirty = true;
        long now = Util.getMillis();
        boolean settled = !dragging && now - lastChange > 150;
        // Full resolution once the view rests; a coarser image keeps dragging smooth.
        if (dirty && (settled || now - lastDraw > 30)) redraw(settled);
        if (texture != null) gui.blit(TEXTURE, 0, top, width, globeHeight(), 0, 0, textureWidth, textureHeight, textureWidth, textureHeight);

        double cx = width / 2.0, cy = centreY(), r = radius();
        hover = null;
        gui.enableScissor(0, top, width, top + globeHeight());
        for (EarthPlaces.Place place : EarthPlaces.all()) {
            double[] at = project(place.latitude(), place.longitude(), cx, cy, r);
            if (at == null) continue;
            int px = (int) Math.round(at[0]), py = (int) Math.round(at[1]);
            boolean over = Math.abs(mouseX - px) <= 4 && Math.abs(mouseY - py) <= 4;
            gui.fill(px - 2, py - 2, px + 2, py + 2, 0xFF000000);
            gui.fill(px - 1, py - 1, px + 1, py + 1, over ? 0xFFFFFFFF : 0xFFFFD34D);
            if (over) hover = new Hover(place, mouseX, mouseY);
            else if (zoom >= 3) gui.drawString(font, place.name(), px + 4, py - 4, 0xFFFFE9A8);
        }
        if (hereLat == hereLat) {
            double[] at = project(hereLat, hereLon, cx, cy, r);
            if (at != null) drawHere(gui, (int) Math.round(at[0]), (int) Math.round(at[1]));
        }
        if (pinned) {
            double[] at = project(pinLat, pinLon, cx, cy, r);
            if (at != null) drawPin(gui, (int) Math.round(at[0]), (int) Math.round(at[1]));
        }
        drawCorners(gui, now);
        gui.disableScissor();

        gui.fill(0, 0, width, top, 0xC0000000);
        gui.fill(0, top + globeHeight(), width, height, 0xC0000000);
    }

    /** The globe of a trip in flight, over the world, with the destination pinned and a caption. */
    private void renderFlight(GuiGraphics gui) {
        flight.tick();
        if (flight == null || minecraft.screen != this) return;
        if (ahead.isEmpty()) {
            List<Long> keys = new ArrayList<>(around(flight.fromLatitude(), flight.fromLongitude(), EarthFlight.NEAR_ZOOM));
            keys.addAll(around(flight.toLatitude(), flight.toLongitude(), EarthFlight.NEAR_ZOOM));
            ahead = keys;
        }
        float alpha = flight.globeAlpha();
        if (alpha <= 0) {
            imagery.want(ahead);
            return;
        }
        double[] view = flight.view();
        if (view[0] != centreLat || view[1] != centreLon || view[2] != zoom) {
            centreLat = view[0];
            centreLon = view[1];
            zoom = view[2];
            changed();
        }
        if (imagery.takeChanged()) dirty = true;
        long now = Util.getMillis();
        // Coarse while moving, as when dragging; full detail once it rests on the destination.
        if (dirty) redraw(now - lastChange > 150);
        if (texture == null) return;
        RenderSystem.enableBlend();
        gui.setColor(1, 1, 1, alpha);
        gui.blit(TEXTURE, 0, 0, width, height, 0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
        double[] at = project(pinLat, pinLon, width / 2.0, centreY(), radius());
        if (at != null) drawPin(gui, (int) Math.round(at[0]), (int) Math.round(at[1]));
        Component caption = EarthPlaces.near(pinLat, pinLon).map(place -> Component.translatable("earthplatform.map.flying", place.label()))
                .orElse(Component.translatable("earthplatform.map.flying", String.format(Locale.ROOT, "%.4f, %.4f", pinLat, pinLon)));
        int w = font.width(caption) + 16;
        gui.fill((width - w) / 2, height - 34, (width + w) / 2, height - 18, 0x90000000);
        gui.drawCenteredString(font, caption, width / 2, height - 30, 0xFFFFFF);
        if (flight.waiting()) {
            Component note = Component.translatable("earthplatform.map.loading", ".".repeat((int) (now / 400 % 3) + 1));
            gui.drawCenteredString(font, note, width / 2, height - 14, 0xC0FFFFFF);
        }
        gui.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    private record Hover(EarthPlaces.Place place, int x, int y) {}

    private Component status() {
        if (warning != null && Util.getMillis() < warningUntil) return warning;
        boolean travel = hereLat == hereLat;
        if (keys != null) return pinned ? Component.translatable(keys + ".at", EarthPlaces.near(pinLat, pinLon).map(EarthPlaces.Place::label)
                .orElse(String.format(Locale.ROOT, "%.4f, %.4f", pinLat, pinLon))) : Component.translatable(keys + ".hint");
        if (!pinned) return Component.translatable(travel ? "earthplatform.map.hint" : "earthplatform.globe.hint");
        String point = String.format(Locale.ROOT, "%.4f, %.4f", pinLat, pinLon);
        if (travel) return Component.translatable("earthplatform.map.travelTo",
                EarthPlaces.near(pinLat, pinLon).map(EarthPlaces.Place::label).orElse(point));
        return EarthPlaces.near(pinLat, pinLon).map(place -> Component.translatable("earthplatform.setup.startAt", place.label()))
                .orElse(Component.translatable("earthplatform.globe.startAt", point));
    }

    private void drawCorners(GuiGraphics gui, long now) {
        int bottom = top + globeHeight();
        Component credit = Component.translatable("earthplatform.globe.credit");
        gui.drawString(font, credit, width - font.width(credit) - 4, top + 4, 0x80FFFFFF);
        Component note = null;
        if (imagery.unreachable()) note = Component.translatable("earthplatform.globe.offline");
        else if (imagery.pending() > 0) note = Component.translatable("earthplatform.globe.loading", ".".repeat((int) (now / 400 % 3) + 1));
        if (note != null) gui.drawString(font, note, 4, bottom - 10, 0xC0FFFFFF);
        else gui.drawString(font, Component.translatable("earthplatform.globe.controls"), 4, bottom - 10, 0x80FFFFFF);
    }

    /** A blue dot ringed in white where the player is. */
    private void drawHere(GuiGraphics gui, int x, int y) {
        gui.fill(x - 4, y - 4, x + 4, y + 4, 0xFF000000);
        gui.fill(x - 3, y - 3, x + 3, y + 3, 0xFFFFFFFF);
        gui.fill(x - 2, y - 2, x + 2, y + 2, 0xFF2F7DF6);
    }

    private void drawPin(GuiGraphics gui, int x, int y) {
        gui.fill(x - 1, y - 9, x + 2, y, 0xFF000000);
        gui.fill(x, y - 8, x + 1, y, 0xFFFFFFFF);
        gui.fill(x - 3, y - 13, x + 4, y - 6, 0xFF000000);
        gui.fill(x - 2, y - 12, x + 3, y - 7, 0xFFE8322E);
        gui.fill(x - 1, y - 11, x, y - 10, 0xFFFF9A96);
    }

    /** Renders the globe into the texture, one texel per screen pixel at rest. */
    private void redraw(boolean full) {
        lastDraw = Util.getMillis();
        dirty = !full;
        double guiScale = minecraft.getWindow().getGuiScale();
        double perGui = Math.min(guiScale, 1280.0 / width) * (full ? 1 : 0.5);
        int w = Math.max(1, (int) Math.round(width * perGui)), h = Math.max(1, (int) Math.round(globeHeight() * perGui));
        if (texture == null || textureWidth != w || textureHeight != h) {
            if (texture != null) texture.close();
            texture = new DynamicTexture(w, h, false);
            textureWidth = w;
            textureHeight = h;
            minecraft.getTextureManager().register(TEXTURE, texture);
        }
        NativeImage image = texture.getPixels();
        double r = radius() * perGui;
        double sinC = Math.sin(Math.toRadians(centreLat)), cosC = Math.cos(Math.toRadians(centreLat));
        // Detail zoom: the tile level whose pixels are about one texel at the centre.
        int detail = (int) Math.ceil(Math.log(2 * Math.PI * Math.max(0.05, cosC) * r / 256) / Math.log(2));
        detail = Math.min(GlobeImagery.MAX_DETAIL, detail);
        boolean useDetail = detail >= GlobeImagery.MIN_DETAIL;
        int tiles = useDetail ? 1 << detail : 0;
        LinkedHashSet<Long> wanted = new LinkedHashSet<>();
        long lastKey = -1;
        int[] lastTile = null;
        int lastLevel = 0;
        for (int ty = 0; ty < h; ty++) {
            double y = (h / 2.0 - ty - 0.5) / r;
            for (int tx = 0; tx < w; tx++) {
                double x = (tx + 0.5 - w / 2.0) / r;
                double rho2 = x * x + y * y;
                if (rho2 >= 1) {
                    image.setPixelRGBA(tx, ty, abgr(space(Math.sqrt(rho2))));
                    continue;
                }
                double cosAngle = Math.sqrt(1 - rho2);
                double lat = Math.toDegrees(Math.asin(Mth.clamp(cosAngle * sinC + y * cosC, -1, 1)));
                double lon = centreLon + Math.toDegrees(Math.atan2(x, cosAngle * cosC - y * sinC));
                lon = lon - 360 * Math.floor((lon + 180) / 360);
                int colour = -1;
                if (useDetail && Math.abs(lat) < 85) {
                    double mx = (lon + 180) / 360 * tiles * 256;
                    double my = (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * tiles * 256;
                    int px = (int) mx, py = Mth.clamp((int) my, 0, tiles * 256 - 1);
                    long key = GlobeImagery.key(detail, px >> 8, py >> 8);
                    if (key != lastKey) {
                        lastKey = key;
                        lastTile = null;
                        // The tile asked for, or the nearest coarser one already loaded.
                        for (int level = detail; level >= GlobeImagery.MIN_DETAIL && lastTile == null; level--) {
                            int shift = detail - level;
                            lastTile = imagery.tile(level, (px >> shift) >> 8, (py >> shift) >> 8);
                            lastLevel = level;
                        }
                        if (lastTile == null || lastLevel != detail) wanted.add(key);
                    }
                    if (lastTile != null) {
                        int shift = detail - lastLevel;
                        colour = lastTile[((py >> shift) & 255) * 256 + ((px >> shift) & 255)];
                    }
                }
                if (colour == -1) colour = imagery.base(lat, lon);
                // The edge of the globe darkens a little, and where worlds cannot start is dimmed.
                double light = 0.62 + 0.38 * Math.sqrt(cosAngle);
                if (Math.abs(lat) > LIMIT) light *= 0.55;
                image.setPixelRGBA(tx, ty, abgr(GlobeImagery.scale(colour, light)));
            }
        }
        texture.upload();
        List<Long> keys = new ArrayList<>(ahead);
        if (useDetail) keys.addAll(nearestFirst(wanted, detail));
        if (!keys.isEmpty()) imagery.want(keys);
    }

    /**
     * The detail tiles covering the screen around a point at a zoom, at the coarse level used while
     * the view moves and the full one used once it rests.
     */
    private List<Long> around(double lat, double lon, double atZoom) {
        double guiScale = minecraft.getWindow().getGuiScale();
        double cosLat = Math.max(0.05, Math.cos(Math.toRadians(lat)));
        double latR = Math.toRadians(Mth.clamp(lat, -85, 85));
        List<Long> keys = new ArrayList<>();
        // Coarse first: it fills the screen sooner. Each level nearest the destination first.
        for (double share : new double[]{0.5, 1}) {
            double perGui = Math.min(guiScale, 1280.0 / width) * share;
            double r = Math.min(width, globeHeight()) * 0.46 * Math.pow(2, atZoom) * perGui;
            int detail = Math.min(GlobeImagery.MAX_DETAIL, (int) Math.ceil(Math.log(2 * Math.PI * cosLat * r / 256) / Math.log(2)));
            if (detail < GlobeImagery.MIN_DETAIL) continue;
            int n = 1 << detail;
            int cx = (int) Math.floor((lon + 180) / 360 * n);
            int cy = (int) Math.floor((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n);
            int halfX = (int) Math.ceil(width * perGui / 512) + 1, halfY = (int) Math.ceil(globeHeight() * perGui / 512) + 1;
            List<int[]> steps = new ArrayList<>();
            for (int dy = -halfY; dy <= halfY; dy++) for (int dx = -halfX; dx <= halfX; dx++) steps.add(new int[]{dx, dy});
            steps.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
            for (int[] step : steps) {
                int y = cy + step[1];
                long key = GlobeImagery.key(detail, Math.floorMod(cx + step[0], n), y);
                if (y >= 0 && y < n && !keys.contains(key)) keys.add(key);
            }
        }
        return keys;
    }

    /** Detail tiles ordered by distance from the middle of the screen, so the centre fills in first. */
    private List<Long> nearestFirst(LinkedHashSet<Long> keys, int detail) {
        double n = 1 << detail;
        double cx = (centreLon + 180) / 360 * n;
        double latR = Math.toRadians(Mth.clamp(centreLat, -85, 85));
        double cy = (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n;
        List<Long> sorted = new ArrayList<>(keys);
        sorted.sort((a, b) -> Double.compare(distance(a, cx, cy, n), distance(b, cx, cy, n)));
        return sorted.size() > 96 ? sorted.subList(0, 96) : sorted;
    }

    private static double distance(long key, double cx, double cy, double n) {
        double x = (key >>> 29 & 0x1FFFFFFF) + 0.5, y = (key & 0x1FFFFFFF) + 0.5;
        double dx = Math.abs(x - cx);
        dx = Math.min(dx, n - dx);
        return dx * dx + (y - cy) * (y - cy);
    }

    /** Black space with a thin blue glow of atmosphere around the globe. */
    private static int space(double rho) {
        double glow = Math.max(0, 1 - (rho - 1) / 0.035);
        return GlobeImagery.mix(0xFF05070D, 0xFF5D8FD6, glow * glow * 0.8);
    }

    private static int abgr(int argb) {
        return (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
    }

    /** Where a point appears in GUI pixels, or null if it is on the far side of the globe. */
    private double[] project(double lat, double lon, double cx, double cy, double r) {
        double phi = Math.toRadians(lat), dl = Math.toRadians(lon - centreLon), phi0 = Math.toRadians(centreLat);
        double cosAngle = Math.sin(phi0) * Math.sin(phi) + Math.cos(phi0) * Math.cos(phi) * Math.cos(dl);
        if (cosAngle <= 0) return null;
        double x = r * Math.cos(phi) * Math.sin(dl), y = r * (Math.cos(phi0) * Math.sin(phi) - Math.sin(phi0) * Math.cos(phi) * Math.cos(dl));
        return new double[]{cx + x, cy - y};
    }

    /** The latitude and longitude under a GUI pixel, or null off the globe. */
    private double[] unproject(double mouseX, double mouseY) {
        double r = radius(), x = (mouseX - width / 2.0) / r, y = (centreY() - mouseY) / r;
        double rho2 = x * x + y * y;
        if (rho2 >= 1) return null;
        double cosAngle = Math.sqrt(1 - rho2), sinC = Math.sin(Math.toRadians(centreLat)), cosC = Math.cos(Math.toRadians(centreLat));
        double lat = Math.toDegrees(Math.asin(Mth.clamp(cosAngle * sinC + y * cosC, -1, 1)));
        double lon = centreLon + Math.toDegrees(Math.atan2(x, cosAngle * cosC - y * sinC));
        return new double[]{lat, lon - 360 * Math.floor((lon + 180) / 360)};
    }

    // Input ------------------------------------------------------------------------------------------

    private boolean overGlobe(double mouseY) { return flight == null && mouseY >= top && mouseY < top + globeHeight(); }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (flight != null) return true;
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (!overGlobe(mouseY)) return false;
        pressX = mouseX;
        pressY = mouseY;
        dragging = true;
        moved = false;
        return true;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        if (Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) > 3) moved = true;
        if (moved) {
            double r = radius();
            centreLat = Mth.clamp(centreLat + Math.toDegrees(dragY / r), -85, 85);
            centreLon -= Math.toDegrees(dragX / r) / Math.max(0.2, Math.cos(Math.toRadians(centreLat)));
            centreLon = centreLon - 360 * Math.floor((centreLon + 180) / 360);
            changed();
        }
        return true;
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!dragging) return super.mouseReleased(mouseX, mouseY, button);
        dragging = false;
        changed();
        if (!moved && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) click(mouseX, mouseY);
        return true;
    }

    private void click(double mouseX, double mouseY) {
        double cx = width / 2.0, cy = centreY(), r = radius();
        for (EarthPlaces.Place place : EarthPlaces.all()) {
            double[] at = project(place.latitude(), place.longitude(), cx, cy, r);
            if (at != null && Math.abs(mouseX - at[0]) <= 4 && Math.abs(mouseY - at[1]) <= 4) {
                pin(place.latitude(), place.longitude());
                return;
            }
        }
        double[] point = unproject(mouseX, mouseY);
        if (point != null) pin(point[0], point[1]);
    }

    private void pin(double lat, double lon) {
        if (Math.abs(lat) > LIMIT) {
            warning = Component.translatable("earthplatform.globe.polar");
            warningUntil = Util.getMillis() + 3000;
            return;
        }
        warning = null;
        pinLat = lat;
        pinLon = lon;
        pinned = true;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || !overGlobe(mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        zoomBy(scrollY > 0 ? 0.5 : -0.5, mouseX, mouseY);
        return true;
    }

    /** Zooms keeping the point under the mouse where it is, as far as the globe allows. */
    private void zoomBy(double steps, double mouseX, double mouseY) {
        double[] before = unproject(mouseX, mouseY);
        double target = Mth.clamp(zoom + steps, 0, MAX_ZOOM);
        if (target == zoom) return;
        zoom = target;
        // Nudge the centre until the point is back under the mouse; a few steps settle it to well under a pixel.
        for (int step = 0; step < 4 && before != null; step++) {
            double[] after = unproject(mouseX, mouseY);
            if (after == null) break;
            centreLat = Mth.clamp(centreLat + before[0] - after[0], -85, 85);
            double dl = before[1] - after[1];
            centreLon += dl - 360 * Math.floor((dl + 180) / 360);
            centreLon = centreLon - 360 * Math.floor((centreLon + 180) / 360);
        }
        changed();
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (flight != null) {
            // Escape comes straight back down; the trip itself carries on.
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) flight.skip();
            return true;
        }
        double step = Math.toDegrees(40 / radius());
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> centreLon -= step / Math.max(0.2, Math.cos(Math.toRadians(centreLat)));
            case GLFW.GLFW_KEY_RIGHT -> centreLon += step / Math.max(0.2, Math.cos(Math.toRadians(centreLat)));
            case GLFW.GLFW_KEY_UP -> centreLat = Math.min(85, centreLat + step);
            case GLFW.GLFW_KEY_DOWN -> centreLat = Math.max(-85, centreLat - step);
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoomBy(1, width / 2.0, centreY());
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> zoomBy(-1, width / 2.0, centreY());
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> finish();
            default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
        }
        centreLon = centreLon - 360 * Math.floor((centreLon + 180) / 360);
        changed();
        return true;
    }

    private void changed() {
        dirty = true;
        lastChange = Util.getMillis();
    }

    private void finish() {
        if (!pinned) return;
        picked.accept(pinLat, pinLon);
        // The travel map hands over to the trip's own screen.
        if (minecraft.screen == this) onClose();
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void removed() {
        if (flight != null) flight.closed();
        if (texture != null) minecraft.getTextureManager().release(TEXTURE);
        texture = null;
        if (imagery != null) imagery.close();
        imagery = null;
    }
}
