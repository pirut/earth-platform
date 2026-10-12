package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;

/** Immutable untrusted client prediction metadata. No field grants inventory or menu ownership. */
public record NativeMenuPacketInput(int containerId, int stateId, int slot, int button,
        List<Prediction> changedSlots, NativeStackSnapshot carried) {
    public static final int MAX_PREDICTIONS = 46;
    public static final int MAX_PACKET_BYTES = 1024 * 1024;
    public record Prediction(int slot, NativeStackSnapshot stack) {
        public Prediction {
            if (slot < 0 || slot >= 46 || stack == null) throw new IllegalArgumentException("Bounded native prediction slot required");
        }
    }
    public NativeMenuPacketInput {
        if (containerId < 0 || containerId > 100 || stateId < 0 || stateId > 32767 || slot < 0 || slot >= 46
                || button < 0 || button > 1 || changedSlots == null || changedSlots.size() > MAX_PREDICTIONS || carried == null)
            throw new IllegalArgumentException("Bounded native PICKUP packet required");
        boolean[] seen = new boolean[46];
        for (var prediction : changedSlots) {
            if (prediction == null || seen[prediction.slot()]) throw new IllegalArgumentException("Duplicate native prediction slot");
            seen[prediction.slot()] = true;
        }
        changedSlots = List.copyOf(changedSlots);
    }
    static NativeMenuPacketInput capture(ServerboundContainerClickPacket packet, ServerPlayer player,
            NativeMenuBudget budget) {
        if (packet == null || packet.getClass() != ServerboundContainerClickPacket.class) throw new SurvivalRefusal("Exact native click packet required");
        var map = packet.getChangedSlots();
        if (map.getClass() != it.unimi.dsi.fastutil.ints.Int2ObjectMaps.UnmodifiableMap.class)
            throw new SurvivalRefusal("Foreign native prediction map wrapper");
        var backing = NativeSessionMenuBoundary.read(it.unimi.dsi.fastutil.ints.Int2ObjectMaps.UnmodifiableMap.class, map, "map", it.unimi.dsi.fastutil.ints.Int2ObjectMap.class);
        if (backing.getClass() != it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap.class)
            throw new SurvivalRefusal("Foreign native prediction map delegate before traversal");
        if (packet.getClickType() != ClickType.PICKUP || packet.getContainerId() < 0
                || packet.getContainerId() > 100 || packet.getStateId() < 0 || packet.getStateId() > 32767
                || packet.getSlotNum() < 0 || packet.getSlotNum() >= 46 || packet.getButtonNum() < 0 || packet.getButtonNum() > 1
                || packet.getChangedSlots().size() > MAX_PREDICTIONS)
            throw new SurvivalRefusal("Only bounded native result/grid/main/hotbar PICKUP packets are admitted");
        for (var entry : packet.getChangedSlots().int2ObjectEntrySet())
            if (entry.getIntKey() < 0 || entry.getIntKey() >= 46 || entry.getValue() == null)
                throw new SurvivalRefusal("Client native prediction slot is outside the retained menu");
        // Refuse custom item/component codec graphs before the network round trip or stack capture.
        for (var stack : packet.getChangedSlots().values()) NativeMenuBudget.requireComponents(stack);
        NativeMenuBudget.requireComponents(packet.getCarriedItem());
        // Prediction caches may contain audited derived previews outside the owned item universe.
        // These never become authoritative grid/cursor/inventory stacks.
        budget.reserve(MAX_PACKET_BYTES * 2); // encoder storage plus immutable copied/decoded prediction allowance
        var bytes = io.netty.buffer.Unpooled.buffer(256, MAX_PACKET_BYTES);
        try {
            var buffer = new RegistryFriendlyByteBuf(bytes, player.registryAccess());
            ServerboundContainerClickPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = ServerboundContainerClickPacket.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable() || decoded.getContainerId() != packet.getContainerId()
                    || decoded.getStateId() != packet.getStateId() || decoded.getSlotNum() != packet.getSlotNum()
                    || decoded.getButtonNum() != packet.getButtonNum() || decoded.getClickType() != ClickType.PICKUP
                    || decoded.getChangedSlots().size() != packet.getChangedSlots().size())
                throw new SurvivalRefusal("Native click packet changed during bounded codec round trip");
            budget.reserve(64 + MAX_PREDICTIONS * 32);
            var predictions = new ArrayList<Prediction>(packet.getChangedSlots().size());
            for (var entry : packet.getChangedSlots().int2ObjectEntrySet()) {
                var image = budget.image(entry.getValue(), player);
                if (!decoded.getChangedSlots().containsKey(entry.getIntKey())
                        || !image.matches(decoded.getChangedSlots().get(entry.getIntKey()), player.registryAccess()))
                    throw new SurvivalRefusal("Native prediction stack changed during packet round trip");
                predictions.add(new Prediction(entry.getIntKey(), image));
            }
            var carried = budget.image(packet.getCarriedItem(), player);
            if (!carried.matches(decoded.getCarriedItem(), player.registryAccess()))
                throw new SurvivalRefusal("Native carried prediction changed during packet round trip");
            return new NativeMenuPacketInput(packet.getContainerId(), packet.getStateId(), packet.getSlotNum(),
                    packet.getButtonNum(), predictions, carried);
        } finally { bytes.release(); }
    }
    public void requireShape(boolean table) {
        if (table ? containerId == 0 : containerId != 0 || !(slot < 5 || slot >= 9 && slot < 45))
            throw new SurvivalRefusal("Native PICKUP does not belong to its exact admitted menu shape");
    }
}
