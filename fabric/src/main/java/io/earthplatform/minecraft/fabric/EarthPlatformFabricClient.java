package io.earthplatform.minecraft.fabric;

import io.earthplatform.minecraft.cubic.client.CubicClient;
import io.earthplatform.minecraft.cubic.client.Flawless;
import io.earthplatform.minecraft.cubic.client.PerfExportCommand;
import io.earthplatform.minecraft.cubic.compat.CubicCompat;
import io.earthplatform.minecraft.cubic.jet.JetHud;
import io.earthplatform.minecraft.cubic.play.client.EarthCompassHud;
import io.earthplatform.minecraft.cubic.play.client.EarthFlight;
import io.earthplatform.minecraft.cubic.play.client.EarthPlayClient;
import io.earthplatform.minecraft.fabric.axiom.AxiomCubes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The Fabric client entry point. Level loading, frames, the level's draw stages, fog and the
 * debug screen have no Fabric API events with NeoForge's timing, so mixins in
 * {@code io.earthplatform.minecraft.fabric.mixin} call the game code at those points.
 */
public final class EarthPlatformFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricPayloads.registerClient();
        KeyBindingHelper.registerKeyBinding(EarthFlight.KEY);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Flawless.offer());

        ClientTickEvents.START_CLIENT_TICK.register(client -> CubicClient.tickStart());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CubicClient.tick();
            CubicCompat.tick();
            EarthFlight.clientTick();
            EarthCompassHud.tick();
            JetHud.tick();
        });
        if (FabricLoader.getInstance().isModLoaded("axiom")) ClientTickEvents.END_CLIENT_TICK.register(client -> AxiomCubes.flush(true));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EarthPlayClient.loggedOut();
            EarthCompassHud.loggedOut();
            CubicCompat.logout();
        });

        HudRenderCallback.EVENT.register((graphics, delta) -> {
            CubicClient.waiting(graphics);
            EarthPlayClient.hud(graphics);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            EarthPlayClient.createWorld(screen);
            EarthPlayClient.pauseMenu(screen, Screens.getButtons(screen), Screens.getButtons(screen)::add);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> PerfExportCommand.register(dispatcher));
    }
}
