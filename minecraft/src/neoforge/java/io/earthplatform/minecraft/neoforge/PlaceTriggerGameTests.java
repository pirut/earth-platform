package io.earthplatform.minecraft.neoforge;

import com.mojang.authlib.GameProfile;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.play.PlaceTrigger;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Runs inside the game-test server, with the mod loaded: the {@code earthplatform:reached_place}
 * criterion is registered, and it fires for a real player's advancements.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class PlaceTriggerGameTests {
    private PlaceTriggerGameTests() {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(Cases.class);
    }

    public static final class Cases {
        private static AdvancementHolder advancement(String name, PlaceTrigger.Instance place) {
            Advancement advancement = new Advancement(Optional.empty(), Optional.empty(), AdvancementRewards.EMPTY,
                    Map.of("reached", new Criterion<>(PlaceTrigger.INSTANCE, place)), AdvancementRequirements.allOf(List.of("reached")), false);
            return new AdvancementHolder(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "gametest/" + name), advancement);
        }

        /**
         * A player with advancements and no connection. A mock player placed with the player list would log in and
         * be sent the mod's payloads, which a test connection has not agreed to.
         */
        private static ServerPlayer player(GameTestHelper helper) {
            return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "place-test"), ClientInformation.createDefault());
        }

        private static boolean done(ServerPlayer player, AdvancementHolder holder) {
            return player.getAdvancements().getOrStartProgress(holder).isDone();
        }

        /** Structure is {@code data/earthplatform/structure/empty.nbt}. The namespace keeps vanilla tests out. */
        @PrefixGameTestTemplate(false)
        @GameTest(templateNamespace = "earthplatform", template = "empty")
        public static void triggerIsRegistered(GameTestHelper helper) {
            if (BuiltInRegistries.TRIGGER_TYPES.get(PlaceTrigger.ID) != PlaceTrigger.INSTANCE) throw new AssertionError("earthplatform:reached_place is not registered");
            if (!"earthplatform:reached_place".equals(String.valueOf(BuiltInRegistries.TRIGGER_TYPES.getKey(PlaceTrigger.INSTANCE)))) throw new AssertionError("wrong key");
            helper.succeed();
        }

        /** The advancement in docs/DATAPACKS.md, read the way the server reads a datapack's, then fired. */
        @PrefixGameTestTemplate(false)
        @GameTest(templateNamespace = "earthplatform", template = "empty")
        public static void theDocumentedAdvancementLoadsAndFires(GameTestHelper helper) {
            String json = """
                    {"parent": "earthplatform:earth/root",
                     "criteria": {"half_dome": {"trigger": "earthplatform:reached_place",
                       "conditions": {"latitude": 37.7459, "longitude": -119.5332, "radius_m": 150, "elevation_m": {"min": 2500}}}},
                     "display": {"icon": {"id": "minecraft:spyglass"}, "title": "Half Dome", "description": "Stand on top of Half Dome"},
                     "requirements": [["half_dome"]]}""";
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
            Advancement advancement = Advancement.CODEC.parse(ops, JsonParser.parseString(json)).getOrThrow(AssertionError::new);
            Criterion<?> criterion = advancement.criteria().get("half_dome");
            if (criterion == null || criterion.trigger() != PlaceTrigger.INSTANCE) throw new AssertionError("the criterion is not reached_place: " + criterion);
            ServerPlayer player = player(helper);
            AdvancementHolder holder = new AdvancementHolder(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "gametest/documented"), advancement);
            PlaceTrigger.Instance place = (PlaceTrigger.Instance) criterion.triggerInstance();
            PlaceTrigger.INSTANCE.addPlayerListener(player.getAdvancements(), new CriterionTrigger.Listener<>(place, holder, "half_dome"));
            PlaceTrigger.INSTANCE.fire(player, 37.7459, -119.5332, 2693);
            if (!done(player, holder)) throw new AssertionError("not granted at Half Dome");
            helper.succeed();
        }

        @PrefixGameTestTemplate(false)
        @GameTest(templateNamespace = "earthplatform", template = "empty")
        public static void reachingThePlaceGrantsTheAdvancement(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            PlaceTrigger.Instance halfDome = new PlaceTrigger.Instance(Optional.empty(), 37.7459, -119.5332, 150, MinMaxBounds.Doubles.atLeast(2500));
            AdvancementHolder holder = advancement("half_dome", halfDome);
            PlaceTrigger.INSTANCE.addPlayerListener(player.getAdvancements(), new CriterionTrigger.Listener<>(halfDome, holder, "reached"));

            // Elsewhere, near but too far, and right place but at the foot of the dome: none of them.
            PlaceTrigger.INSTANCE.fire(player, 48.8584, 2.2945, 2700);
            PlaceTrigger.INSTANCE.fire(player, 37.7479, -119.5332, 2700);
            PlaceTrigger.INSTANCE.fire(player, 37.7459, -119.5332, 1200);
            if (done(player, holder)) throw new AssertionError("granted before the player reached the place");

            // At the top, a few metres off.
            PlaceTrigger.INSTANCE.fire(player, 37.74595, -119.53325, 2693);
            if (!done(player, holder)) throw new AssertionError("not granted at the place");
            helper.succeed();
        }

        @PrefixGameTestTemplate(false)
        @GameTest(templateNamespace = "earthplatform", template = "empty")
        public static void theDateLineIsNoEdge(GameTestHelper helper) {
            ServerPlayer player = player(helper);
            PlaceTrigger.Instance fiji = new PlaceTrigger.Instance(Optional.empty(), -16.85, 180, 3000, MinMaxBounds.Doubles.ANY);
            AdvancementHolder holder = advancement("date_line", fiji);
            PlaceTrigger.INSTANCE.addPlayerListener(player.getAdvancements(), new CriterionTrigger.Listener<>(fiji, holder, "reached"));
            PlaceTrigger.INSTANCE.fire(player, -16.85, -179.98, 0);
            if (!done(player, holder)) throw new AssertionError("not granted across the date line");
            helper.succeed();
        }
    }
}
