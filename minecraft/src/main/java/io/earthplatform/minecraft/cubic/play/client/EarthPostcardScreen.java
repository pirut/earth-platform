package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.play.EarthCountries;
import io.earthplatform.minecraft.cubic.play.EarthPlaces;
import io.earthplatform.minecraft.cubic.play.EarthPostcard;
import io.earthplatform.minecraft.cubic.play.EarthTowns;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.lwjgl.glfw.GLFW;

/**
 * A postcard: a screenshot with no hotbar, hand or chat, and along its bottom the name of the
 * place, where it is, how high, and the place code that brings anyone else to the same spot
 * (see {@link EarthPostcard}). The key asks the server where the player stands; when it answers,
 * this screen goes up for a few frames, drawing only that line over the world, and the picture is
 * taken with the line in it and saved beside the game's own screenshots. A screen is used because
 * the game still draws one while its own interface is hidden, with its own font, at any window
 * size and under any shader pack.
 */
public final class EarthPostcardScreen extends Screen {
    public static final KeyMapping KEY = new KeyMapping("key.earthplatform.postcard", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.earthplatform");
    /** Frames drawn before the picture is taken, so the hotbar and hand are gone from it and far terrain has had a moment to settle. */
    private static final int FRAMES = 4;

    private final String place, detail, code;
    private final boolean hidGui;
    private int frames;
    private boolean taken;

    private EarthPostcardScreen(String place, String detail, String code) {
        super(Component.translatable("key.earthplatform.postcard"));
        this.place = place;
        this.detail = detail;
        this.code = code;
        Minecraft minecraft = Minecraft.getInstance();
        hidGui = minecraft.options.hideGui;
        minecraft.options.hideGui = true;
    }

    /** Called every client tick: the key asks the server where we are. */
    public static void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        while (KEY.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) minecraft.player.connection.sendCommand("earth postcard");
        }
    }

    /** The server's answer: take the postcard. */
    public static void received(EarthPostcard.Payload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player == null || minecraft.screen != null) return;
            double latitude = payload.latitude(), longitude = payload.longitude();
            String code = EarthPostcard.code(latitude, longitude, EarthPostcard.bearing(minecraft.player.getYRot()));
            String detail = String.format(Locale.ROOT, "%.4f° %s, %.4f° %s  ·  %,d m  ·  /earth code %s",
                    Math.abs(latitude), latitude < 0 ? "S" : "N", Math.abs(longitude), longitude < 0 ? "W" : "E", payload.meters(), code);
            minecraft.setScreen(new EarthPostcardScreen(placeName(latitude, longitude), detail, code));
        });
    }

    /** What to call a spot: a famous place there, else the nearest town within 40 km, else the country, else nothing. */
    static String placeName(double latitude, double longitude) {
        var famous = EarthPlaces.near(latitude, longitude);
        if (famous.isPresent()) return famous.get().label();
        EarthTowns.Town town = EarthTowns.nearest(latitude, longitude, 40);
        if (town != null) {
            double km = Math.hypot(town.latitude() - latitude, (town.longitude() - longitude) * Math.cos(Math.toRadians(latitude))) * 111.2;
            return km < 3 ? town.label() : String.format(Locale.ROOT, "%d km from %s", Math.round(km), town.label());
        }
        EarthCountries.Country country = EarthCountries.at(latitude, longitude);
        return country == null ? "" : country.name();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    // No blur, no dimming: the world is the picture.
    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        int lines = place.isEmpty() ? 1 : 2, band = 12 * lines + 12;
        gui.fillGradient(0, height - band - 16, width, height - band, 0x00000000, 0x90000000);
        gui.fill(0, height - band, width, height, 0x90000000);
        int y = height - band + 4;
        if (!place.isEmpty()) {
            gui.drawString(font, place, 10, y, 0xFFFFFF);
            y += 12;
        }
        gui.drawString(font, detail, 10, y, 0xB8E2FF);
        String mark = "Earth Platform";
        gui.drawString(font, mark, width - 10 - font.width(mark), height - 16, 0xFFFFFF);
        gui.flush();
        if (++frames >= FRAMES && !taken) {
            taken = true;
            save(Screenshot.takeScreenshot(minecraft.getMainRenderTarget()));
            minecraft.setScreen(null);
        }
    }

    @Override public void removed() { minecraft.options.hideGui = hidGui; }

    private void save(NativeImage image) {
        File folder = new File(minecraft.gameDirectory, "screenshots");
        File file = new File(folder, "earth-postcard-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".png");
        String code = this.code;
        Util.ioPool().execute(() -> {
            Component told;
            try (image) {
                folder.mkdirs();
                image.writeToFile(file);
                told = Component.translatable("earthplatform.postcard.saved",
                        Component.literal(file.getName()).withStyle(style -> style.withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath()))),
                        Component.literal(code).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("earthplatform.postcard.copy")))));
            } catch (Exception failure) {
                EarthPlatformMod.LOGGER.warn("Postcard could not be saved: {}", failure.toString());
                told = Component.translatable("earthplatform.postcard.failed", failure.getMessage()).withStyle(ChatFormatting.RED);
            }
            Component line = told;
            minecraft.execute(() -> minecraft.gui.getChat().addMessage(line));
        });
    }
}
