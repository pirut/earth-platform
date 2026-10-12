package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** First resolved script's bounded recipe-only native output graph. Announcements remain refused. */
final class NativeMenuMetadataDeliveryAdmission {
    private final ServerPlayer player;
    private final NativeFirstLoopMetadata metadata;
    private final NativeCraftingRecipeAdmission recipes;
    private final List<ServerPlayer> audience;
    private final List<ServerGamePacketListenerImpl> connections;
    private final Object scoreboard;
    private final Set<ResourceLocation> items, crafted, initialUnlocks;
    private final NativeFirstLoopMetadata.Summary closure;
    private final Map<ResourceLocation, AdvancementHolder> holders;
    private final Map<ResourceLocation, byte[]> encodedAdvancements;
    private final int reservedBytes, reservedOperations;
    private NativeMenuMetadataDeliveryAdmission(ServerPlayer player, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, NativeInventoryMenuPreparation.Result transcript, NativeCraftingTablePreparation.Result tableTranscript, int allowance) {
        this.player = player; this.metadata = metadata; this.recipes = recipes;
        scoreboard = player.getScoreboard();
        if (!player.getScoreboard().getObjectives().isEmpty()) throw refused("Native crafted scoreboard objectives require separate delivery admission");
        if (player.server.getPlayerList().getPlayers().size() > 1)
            throw refused("Native resolved-menu audience exceeds its exact first-script bound");
        audience = List.copyOf(player.server.getPlayerList().getPlayers());
        if (audience.size() > 1 || audience.stream().anyMatch(p -> p != player || p.getClass() != ServerPlayer.class))
            throw refused("Native resolved-menu first profile allows only its exact player audience");
        connections = audience.stream().map(p -> p.connection).toList();
        var owned = new HashSet<ResourceLocation>(); var crafts = new HashSet<ResourceLocation>(); var unlocked = new HashSet<ResourceLocation>();
        if ((transcript==null)==(tableTranscript==null)) throw refused("Exactly one native menu shape transcript is required");
        List<NativeInventoryMenuPreparation.Notice> notices;
        int initialTriggers;
        if (transcript!=null) {
            collect(transcript.before().inventory(), owned); collect(transcript.after().inventory(), owned);
            for (var step:transcript.steps()) {
                collect(step.inventory(), owned); collect(step.grid(), owned); collect(List.of(step.carried()), owned);
            }
            notices=transcript.notices(); initialTriggers=46;
        } else {
            collect(tableTranscript.inventoryMenuBefore().inventory(),owned); collect(tableTranscript.after().inventory(),owned);
            for (var step:tableTranscript.steps()) {
                collect(step.inventory(),owned); collect(step.grid(),owned); collect(List.of(step.carried()),owned);
            }
            // Initial table listener sees up to36 inventory slots, then the final46-slot broadcast.
            notices=tableTranscript.notices(); initialTriggers=82;
        }
        for (var notice : notices) {
            if (notice instanceof NativeInventoryMenuPreparation.Crafted craft) { crafts.add(craft.recipe()); collect(craft.input(), owned); initialTriggers++; }
            else if (notice instanceof NativeInventoryMenuPreparation.Unlocked unlock) { unlocked.addAll(unlock.recipes()); initialTriggers += unlock.recipes().size(); }
        }
        items = Set.copyOf(owned); crafted = Set.copyOf(crafts); initialUnlocks = Set.copyOf(unlocked);
        closure = metadata.scriptClosure(items, crafted, initialUnlocks);
        long evaluations = (long)(initialTriggers + closure.unlockRecipes().size()) * closure.relevantCriteria();
        if (evaluations > 1_000_000L) throw refused("Native metadata criterion dispatch work exceeds the resolved-script bound");
        var retained = new LinkedHashMap<ResourceLocation, AdvancementHolder>();
        var bytesById = new LinkedHashMap<ResourceLocation, byte[]>();
        int bytes = 128; int criterionGrants = 0;
        for (ResourceLocation id : closure.reachableAdvancements()) {
            AdvancementHolder holder = player.server.getAdvancements().get(id);
            if (holder == null) throw refused("Native script advancement disappeared");
            criterionGrants = Math.addExact(criterionGrants, holder.value().criteria().size());
            requireDisplay(holder); byte[] encoded = encode(holder, allowance);
            retained.put(id, holder); bytesById.put(id, encoded);
            // Reserve native progress entries as well as all holder/display output, even though
            // this script does not flush advancement packets. All criterion names are bounded here.
            bytes = charge(bytes, encoded.length + 64, allowance);
            for (String criterion : holder.value().criteria().keySet())
                bytes = charge(bytes, criterion.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 64, allowance);
        }
        holders = Map.copyOf(retained); encodedAdvancements = Map.copyOf(bytesById);
        // Newly known recipe keys are monotone before recursive RECIPE_UNLOCKED dispatch. Charge
        // their complete ADD payload and a separate header/settings allowance for every key.
        var packet = new ClientboundRecipePacket(ClientboundRecipePacket.State.ADD, closure.unlockRecipes(), List.of(), player.getRecipeBook().getBookSettings());
        var raw = io.netty.buffer.Unpooled.buffer(128, Math.max(128, allowance));
        try {
            var buf = new FriendlyByteBuf(raw); ClientboundRecipePacket.STREAM_CODEC.encode(buf, packet);
            int size = buf.readableBytes(); var decoded = ClientboundRecipePacket.STREAM_CODEC.decode(buf);
            if (buf.isReadable() || !Set.copyOf(decoded.getRecipes()).equals(closure.unlockRecipes())) throw refused("Native recipe output codec changed closure");
            bytes = charge(bytes, size + closure.unlockRecipes().size() * 64, allowance);
        } finally { raw.release(); }
        reservedBytes = bytes; reservedOperations = Math.addExact(criterionGrants, Math.addExact(closure.reachableAdvancements().size(), closure.unlockRecipes().size())); require();
    }
    static NativeMenuMetadataDeliveryAdmission capture(ServerPlayer player, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, NativeInventoryMenuPreparation.Result transcript, int allowance) {
        if (allowance < 1) throw refused("No native metadata delivery allowance remains");
        return new NativeMenuMetadataDeliveryAdmission(player, metadata, recipes, transcript, null, allowance);
    }
    static NativeMenuMetadataDeliveryAdmission captureTable(ServerPlayer player, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, NativeCraftingTablePreparation.Result transcript,int allowance) {
        if (allowance<1) throw refused("No native table metadata delivery allowance remains");
        return new NativeMenuMetadataDeliveryAdmission(player,metadata,recipes,null,transcript,allowance);
    }
    int reservedBytes() { return reservedBytes; }
    int reservedOperations() { return reservedOperations; }
    void require() {
        if (!player.server.isSameThread() || player.getScoreboard() != scoreboard || !player.getScoreboard().getObjectives().isEmpty()
                || !player.server.getPlayerList().getPlayers().equals(audience)) throw refused("Native metadata scoreboard/audience changed");
        for (int i = 0; i < audience.size(); i++) if (audience.get(i).connection != connections.get(i))
            throw refused("Native metadata audience connection changed");
        if (!metadata.scriptClosure(items, crafted, initialUnlocks).equals(closure)) throw refused("Native script reward closure changed");
        for (var entry : holders.entrySet()) {
            if (player.server.getAdvancements().get(entry.getKey()) != entry.getValue()) throw refused("Native script advancement instance changed");
            requireDisplay(entry.getValue());
            if (!java.util.Arrays.equals(encodedAdvancements.get(entry.getKey()), encode(entry.getValue(), reservedBytes)))
                throw refused("Native advancement display/network payload changed");
        }
    }
    private void requireDisplay(AdvancementHolder holder) {
        holder.value().display().ifPresent(display -> {
            if (display.getClass() != DisplayInfo.class || display.shouldAnnounceChat())
                throw refused("Native script can reach an unadmitted advancement announcement: " + holder.id());
            requireSimpleText(display.getTitle()); requireSimpleText(display.getDescription());
            if (!display.getIcon().getComponentsPatch().isEmpty())
                throw refused("Native first-script advancement icon has an unadmitted component patch");
            metadata.requireItems(List.of(display.getIcon())); recipes.requirePreviewStack(display.getIcon());
        });
    }
    private static void requireSimpleText(Component component) {
        if (component.getClass() != MutableComponent.class || !component.getStyle().isEmpty() || !component.getSiblings().isEmpty())
            throw refused("Native first-script advancement display requires simple bounded native text");
        Object contents = component.getContents();
        if (contents.getClass() == TranslatableContents.class) {
            var translatable = (TranslatableContents) contents;
            if (translatable.getArgs().length != 0 || translatable.getKey().length() > 4096
                    || translatable.getFallback() != null && translatable.getFallback().length() > 4096)
                throw refused("Native advancement translation arguments/text exceed first-script admission");
        } else if (contents.getClass() == net.minecraft.network.chat.contents.PlainTextContents.LiteralContents.class) {
            if (((net.minecraft.network.chat.contents.PlainTextContents.LiteralContents) contents).text().length() > 4096)
                throw refused("Native advancement literal text exceeds first-script admission");
        } else throw refused("Custom native advancement text is not admitted");
    }
    private byte[] encode(AdvancementHolder holder, int allowance) {
        var raw = io.netty.buffer.Unpooled.buffer(128, Math.max(128, allowance));
        try {
            var buf = new RegistryFriendlyByteBuf(raw, player.registryAccess()); AdvancementHolder.STREAM_CODEC.encode(buf, holder);
            byte[] encoded = new byte[buf.readableBytes()]; buf.getBytes(buf.readerIndex(), encoded); return encoded;
        } finally { raw.release(); }
    }
    private void collect(List<NativeStackSnapshot> images, Set<ResourceLocation> output) {
        for (var image : images) if (!image.isEmpty()) output.add(BuiltInRegistries.ITEM.getKey(image.materialize(player.registryAccess()).getItem()));
    }
    private static int charge(int used, int amount, int limit) { return NativeInventoryMenuBroadcastAdmission.checkedCharge(used, amount, limit); }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
