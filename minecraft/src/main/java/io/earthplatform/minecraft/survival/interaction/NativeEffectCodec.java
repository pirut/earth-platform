package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation;
import io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot;
import io.earthplatform.minecraft.survival.interaction.host.NativeCraftingTableSnapshot;
import io.earthplatform.minecraft.survival.interaction.host.NativeCraftingTablePreparation;
import io.earthplatform.minecraft.survival.world.NativeActionEffect;
import io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.inventory.ClickType;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

/** Data-only effect boundary. Decoding/prevalidation must finish before committing any cube. */
public final class NativeEffectCodec {
    private static final int VERSION = 1;
    private NativeEffectCodec() {}

    public sealed interface Effect permits MiningControl, InventorySlot, ItemSpawn, ItemPickup, ItemTick, PlayerMove, ExperienceSpawn, ScheduledBlockTick,
            PlayerStat, PlayerExhaustion, Sound, GameEvent, PlacementSound, BlockPlaceEvent, BlockBreakMetadata, RandomSeed, BlockNotification, ResolvedMenuScript, ResolvedTableScript, SessionMenuPacket, SessionMenuClose, SessionMenuOpen {
        String type();
    }

    /** Data-only native control transcript; the exact retained private Plan is still required. */
    public record MiningControl(WorldIdentity world, UUID ownerEpoch, UUID actor, long sequence,
            io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlTranscript journal) implements Effect {
        public MiningControl {
            Objects.requireNonNull(world); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(actor); Objects.requireNonNull(journal);
            if(sequence <= 0) throw new IllegalArgumentException("Positive native mining action sequence required");
        }
        public String type() { return "native_mining_control_v1"; }
    }

    public record SessionMenuOpen(WorldIdentity world, UUID ownerEpoch, UUID actor, long sequence,
            io.earthplatform.minecraft.survival.interaction.host.NativeMenuOpenJournal journal) implements Effect {
        public SessionMenuOpen {
            Objects.requireNonNull(world); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(actor); Objects.requireNonNull(journal);
            if (sequence <= 0) throw new IllegalArgumentException("Positive native menu open sequence required");
        }
        public String type() { return "session_menu_open_v1"; }
    }
    /** Complete retained per-packet evidence; construction grants no menu ownership or callback authority. */
    public record SessionMenuPacket(WorldIdentity world, UUID ownerEpoch, UUID actor, long sequence,
            io.earthplatform.minecraft.survival.interaction.host.NativeMenuPacketJournal journal) implements Effect {
        public SessionMenuPacket {
            Objects.requireNonNull(world); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(actor); Objects.requireNonNull(journal);
            if (sequence <= 0) throw new IllegalArgumentException("Positive native menu action sequence required");
        }
        public String type() { return "session_menu_packet_v1"; }
    }
    /** Complete fitting native-close evidence; unresolved crash recovery remains outside this profile. */
    public record SessionMenuClose(WorldIdentity world, UUID ownerEpoch, UUID actor, long sequence,
            io.earthplatform.minecraft.survival.interaction.host.NativeMenuCloseJournal journal) implements Effect {
        public SessionMenuClose {
            Objects.requireNonNull(world); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(actor); Objects.requireNonNull(journal);
            if (sequence <= 0) throw new IllegalArgumentException("Positive native menu close sequence required");
        }
        public String type() { return "session_menu_close_v1"; }
    }

    /** Binds the one detached random stream to the exact native action and generation source. */
    public record RandomSeed(WorldIdentity world, UUID ownerEpoch, UUID actor, long sequence, long seed) implements Effect {
        public RandomSeed {
            Objects.requireNonNull(world); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(actor);
            if (sequence <= 0) throw new IllegalArgumentException("Positive action sequence required");
        }
        public String type() { return "random_seed_v1"; }
    }

    /**
     * Complete bounded native2x2 script evidence. It grants no live hydration, callback or retry
     * authority: only the host's private plan for the exact current action may consume it.
     * Intermediate grid/cursor images are evidence, never independently owned/persisted items.
     */
    public record ResolvedMenuScript(WorldIdentity world, UUID ownerEpoch, UUID actor, long sequence,
            List<NativeInventoryMenuPreparation.Click> clicks,
            NativeInventoryMenuPreparation.Result transcript) implements Effect {
        public static final int MAX_CLICKS = 128;
        public static final int MAX_NOTICES = 1024;
        public ResolvedMenuScript {
            Objects.requireNonNull(world); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(actor);
            Objects.requireNonNull(clicks); Objects.requireNonNull(transcript);
            if (sequence <= 0 || clicks.isEmpty() || clicks.size() > MAX_CLICKS
                    || transcript.steps().size() != clicks.size() || transcript.notices().size() > MAX_NOTICES)
                throw new IllegalArgumentException("Exact bounded native menu script shape required");
            clicks = List.copyOf(clicks);
            for (var click : clicks) {
                if (click.type() != ClickType.PICKUP || (click.button() != 0 && click.button() != 1)
                        || !(click.slot() >= 0 && click.slot() < 5 || click.slot() >= 9 && click.slot() < 45))
                    throw new IllegalArgumentException("Only native2x2 PICKUP result/grid/main/hotbar clicks are admitted");
            }
            requireMenuImage(transcript.before()); requireMenuImage(transcript.after());
            requireResolvedMenuImage(transcript.before()); requireResolvedMenuImage(transcript.after());
            if (!transcript.after().equals(transcript.steps().getLast()))
                throw new IllegalArgumentException("Native menu final image must equal its final captured step");
            for (var step : transcript.steps()) {
                requireMenuImage(step);
                if (step.selected() != transcript.before().selected()
                        || step.quickcraftType() != transcript.before().quickcraftType())
                    throw new IllegalArgumentException("Native PICKUP script changed selection or drag metadata");
            }
            for (var notice : transcript.notices()) requireMenuNotice(notice);
        }
        public String type() { return "resolved_menu_script_v1"; }
    }

    /** Resolved native3x3 transcript; data alone grants no live menu or effect authority. */
    public record ResolvedTableScript(WorldIdentity world, UUID ownerEpoch, UUID actor, long sequence,
            CubeCell table, int tableStateId, int containerCounterBefore,
            List<NativeInventoryMenuPreparation.Click> clicks,
            NativeCraftingTablePreparation.Result transcript) implements Effect {
        public ResolvedTableScript {
            Objects.requireNonNull(world); Objects.requireNonNull(ownerEpoch); Objects.requireNonNull(actor);
            Objects.requireNonNull(table); Objects.requireNonNull(clicks); Objects.requireNonNull(transcript);
            if (sequence <= 0 || tableStateId < 0 || containerCounterBefore < 0 || containerCounterBefore > 100
                    || clicks.isEmpty() || clicks.size() > ResolvedMenuScript.MAX_CLICKS
                    || transcript.steps().size() != clicks.size()
                    || transcript.notices().size() > ResolvedMenuScript.MAX_NOTICES)
                throw new IllegalArgumentException("Exact bounded native table script shape required");
            clicks = List.copyOf(clicks);
            for (var click : clicks)
                if (click.type() != ClickType.PICKUP || click.button() < 0 || click.button() > 1
                        || click.slot() < 0 || click.slot() >= 46)
                    throw new IllegalArgumentException("Only native3x3 PICKUP result/grid/main/hotbar clicks are admitted");
            var before = transcript.before(); var after = transcript.after();
            requireMenuImage(transcript.inventoryMenuBefore()); requireResolvedMenuImage(transcript.inventoryMenuBefore());
            requireTableImage(before); requireTableImage(after); requireResolvedTableImage(before); requireResolvedTableImage(after);
            if (before.containerId() != containerCounterBefore % 100 + 1
                    || !before.inventory().equals(transcript.inventoryMenuBefore().inventory())
                    || before.selected() != transcript.inventoryMenuBefore().selected()
                    || before.inventoryChanges() != transcript.inventoryMenuBefore().inventoryChanges()
                    || !after.equals(transcript.steps().getLast()))
                throw new IllegalArgumentException("Native table open/final image identity differs from its source");
            for (var step : transcript.steps()) {
                requireTableImage(step);
                if (step.containerId() != before.containerId() || step.selected() != before.selected()
                        || step.quickcraftType() != before.quickcraftType())
                    throw new IllegalArgumentException("Native table PICKUP script changed container/selection/drag identity");
            }
            for (var notice : transcript.notices()) requireTableNotice(notice, before.containerId());
        }
        public String type() { return "resolved_table_script_v1"; }
    }

    /**
     * Publication metadata after native neighbor/shape/state-change work was already captured.
     * This never authorizes rerunning markAndNotifyBlock or any native mutating callback after
     * commit. Delivery must first validate this exact world/palette and both pinned state IDs.
     */
    public record BlockNotification(WorldIdentity world, CubeCell cell, int beforeStateId, int afterStateId,
                                    int flags, int recursionDepth) implements Effect {
        public BlockNotification {
            Objects.requireNonNull(world); Objects.requireNonNull(cell);
            if (beforeStateId < 0 || afterStateId < 0 || flags < 0 || flags > 127
                    || recursionDepth < 0 || recursionDepth > 512)
                throw new IllegalArgumentException("Invalid bounded native block notification metadata");
        }
        public String type() { return "block_notification_v1"; }
    }

    /** Exact old-state destruction metadata. Data alone never authorizes this callback. */
    public record BlockBreakMetadata(WorldIdentity world, CubeCell cell, UUID actor, int beforeStateId,
            float observedDestroyProgress) implements Effect {
        public BlockBreakMetadata {
            Objects.requireNonNull(world); Objects.requireNonNull(cell); Objects.requireNonNull(actor);
            if (beforeStateId < 0 || !Float.isFinite(observedDestroyProgress) || observedDestroyProgress <= 0)
                throw new IllegalArgumentException("Native break metadata requires exact old state and observed positive progress");
        }
        public String type() { return "block_break_metadata_v1"; }
    }

    /** Inventory indices refer to native Inventory, never a client-supplied menu slot number. */
    public record InventorySlot(int slot, NativeStackSnapshot before, NativeStackSnapshot after) implements Effect {
        public InventorySlot {
            if (slot < 0 || slot >= 41) throw new IllegalArgumentException("Native inventory slot is out of bounds");
            Objects.requireNonNull(before); Objects.requireNonNull(after);
        }
        public String type() { return "inventory_slot_v1"; }
    }

    /** State is preserved exactly, including negative coordinates, components and pickup delay. */
    public record ItemSpawn(UUID id, NativeStackSnapshot stack, double x, double y, double z,
                            double velocityX, double velocityY, double velocityZ,
                            int age, int pickupDelay) implements Effect {
        public ItemSpawn {
            Objects.requireNonNull(id); Objects.requireNonNull(stack);
            requireFinite(x, y, z, velocityX, velocityY, velocityZ);
            if (stack.isEmpty() || age < -32768 || age > 32767 || pickupDelay < 0 || pickupDelay > 32767)
                throw new IllegalArgumentException("Invalid native item entity state");
        }
        public String type() { return "item_spawn_v1"; }
    }

    /**
     * Exact native playerTouch result. Null after means the detached native entity was consumed.
     * Native Inventory.add may transfer a partial stack yet return false: takeAmount is then zero
     * and no take/stat callback is invented for that conserved transfer.
     */
    public record ItemPickup(UUID id, int entityId, NativeItemEntitySnapshot before,
                             NativeItemEntitySnapshot after, int amount, int takeAmount) implements Effect {
        public ItemPickup {
            Objects.requireNonNull(id); Objects.requireNonNull(before);
            if (entityId <= 0 || amount <= 0 || takeAmount != 0 && takeAmount != amount
                    || !id.equals(before.id()) || after != null && !id.equals(after.id()))
                throw new IllegalArgumentException("Invalid exact native pickup identity/result");
        }
        public String type() { return "item_pickup_v1"; }
    }

    /** Accepted source-admitted native tick command. Outcome is never replayed from this journal. */
    public record ItemTick(UUID id, int entityId, NativeItemEntitySnapshot before, int tickCount) implements Effect {
        public ItemTick {
            Objects.requireNonNull(id); Objects.requireNonNull(before);
            if (!id.equals(before.id()) || entityId <= 0 || tickCount < 0 || tickCount == Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid exact native tick identity/source");
        }
        public String type() { return "item_tick_v1"; }
    }

    /** Data-only accepted original MOVE_PACKET command. It never reconstructs a live packet or permits replay. */
    public record PlayerMove(WorldIdentity world,UUID ownerEpoch,UUID actor,long sequence,int entityId,
            io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot beforePose,
            io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot beforeFood,
            int playerTick,int connectionTick,int received,int known,int packetKind,
            double x,double y,double z,float yaw,float pitch,boolean onGround) implements Effect {
        public PlayerMove {
            Objects.requireNonNull(world);Objects.requireNonNull(ownerEpoch);Objects.requireNonNull(actor);
            Objects.requireNonNull(beforePose);Objects.requireNonNull(beforeFood);
            if(sequence<=0||entityId<=0||playerTick<0||connectionTick<0||received<0||known<0||received<known
                    ||packetKind<0||packetKind>3||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                    ||Math.abs(x)>29999984||Math.abs(y)>29999984||Math.abs(z)>29999984
                    ||!Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(yaw)>360000||Math.abs(pitch)>90
                    ||x!=beforePose.x()||z!=beforePose.z()||y>beforePose.y()||y<beforePose.y()-1)
                throw new IllegalArgumentException("Invalid bounded native player-move command");
        }
        public String type(){return "player_move_v1";}
    }

    public record ExperienceSpawn(UUID id, int value, double x, double y, double z,
                                  double velocityX, double velocityY, double velocityZ, int age) implements Effect {
        public ExperienceSpawn {
            Objects.requireNonNull(id); requireFinite(x, y, z, velocityX, velocityY, velocityZ);
            if (value <= 0 || age < 0 || age > 6000) throw new IllegalArgumentException("Invalid experience orb state");
        }
        public String type() { return "experience_spawn_v1"; }
    }

    /** The host must explicitly admit this tick type and bound its queue before accepting it. */
    public record ScheduledBlockTick(CubeCell cell, ResourceLocation block, long triggerTick,
                                     int priority, long subTickOrder) implements Effect {
        public ScheduledBlockTick {
            Objects.requireNonNull(cell); Objects.requireNonNull(block);
            if (triggerTick < 0 || priority < -3 || priority > 3 || subTickOrder < 0)
                throw new IllegalArgumentException("Invalid scheduled native block tick");
        }
        public String type() { return "scheduled_block_tick_v1"; }
    }

    public record PlayerStat(ResourceLocation category, ResourceLocation value, int amount) implements Effect {
        public PlayerStat {
            Objects.requireNonNull(category); Objects.requireNonNull(value);
            if (amount <= 0) throw new IllegalArgumentException("Positive statistic increment required");
        }
        public String type() { return "player_stat_v1"; }
    }

    /** Ordered native food exhaustion increment; the host prepares/checkpoints complete FoodData. */
    public record PlayerExhaustion(float amount) implements Effect {
        public PlayerExhaustion {
            if (!Float.isFinite(amount) || amount <= 0.0F || amount > 40.0F)
                throw new IllegalArgumentException("Positive bounded native exhaustion increment required");
        }
        public String type() { return "player_exhaustion_v1"; }
    }

    public record Sound(ResourceLocation sound, String category, double x, double y, double z,
                        float volume, float pitch, long seed) implements Effect {
        public Sound {
            Objects.requireNonNull(sound); Objects.requireNonNull(category); requireFinite(x, y, z);
            if (!category.matches("[a-z_]{1,32}") || !Float.isFinite(volume) || volume < 0
                    || !Float.isFinite(pitch) || pitch < 0)
                throw new IllegalArgumentException("Invalid native sound effect");
        }
        public String type() { return "sound_v1"; }
    }

    public record GameEvent(ResourceLocation event, CubeCell cell, UUID source) implements Effect {
        public GameEvent { Objects.requireNonNull(event); Objects.requireNonNull(cell); }
        public String type() { return "game_event_v1"; }
    }

    /** Exact native placement sound arguments, including the excluded acting player. */
    public record PlacementSound(ResourceLocation sound, String category, double x, double y, double z,
            float volume, float pitch, long seed, UUID excludedActor) implements Effect {
        public PlacementSound {
            Objects.requireNonNull(excludedActor);
            // Reuse the unchanged finite/category sound data contract without rewriting v1 bytes.
            new Sound(sound, category, x, y, z, volume, pitch, seed);
        }
        public String type() { return "placement_sound_v1"; }
    }

    /** BLOCK_PLACE includes an affected native state; plain game_event_v1 remains source-only. */
    public record BlockPlaceEvent(WorldIdentity world, CubeCell cell, UUID actor, int affectedStateId) implements Effect {
        public BlockPlaceEvent {
            Objects.requireNonNull(world); Objects.requireNonNull(cell); Objects.requireNonNull(actor);
            if (affectedStateId < 0) throw new IllegalArgumentException("Native placement state ID must be nonnegative");
        }
        public String type() { return "block_place_event_v1"; }
    }

    public static NativeActionEffect encode(Effect effect) {
        Objects.requireNonNull(effect);
        try {
            var bytes = new BoundedEffectBytes();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION);
                if (effect instanceof InventorySlot slot) {
                    out.writeInt(slot.slot()); writeStack(out, slot.before()); writeStack(out, slot.after());
                } else if (effect instanceof ItemSpawn item) {
                    writeUuid(out, item.id()); writeStack(out, item.stack());
                    writePosition(out, item.x(), item.y(), item.z());
                    writePosition(out, item.velocityX(), item.velocityY(), item.velocityZ());
                    out.writeInt(item.age()); out.writeInt(item.pickupDelay());
                } else if (effect instanceof ItemPickup pickup) {
                    writeUuid(out, pickup.id()); out.writeInt(pickup.entityId());
                    writeEntity(out, pickup.before()); out.writeBoolean(pickup.after() != null);
                    if (pickup.after() != null) writeEntity(out, pickup.after());
                    out.writeInt(pickup.amount()); out.writeInt(pickup.takeAmount());
                } else if (effect instanceof ItemTick tick) {
                    writeUuid(out, tick.id()); out.writeInt(tick.entityId()); writeEntity(out, tick.before()); out.writeInt(tick.tickCount());
                } else if (effect instanceof PlayerMove move) {
                    writeWorld(out,move.world());writeUuid(out,move.ownerEpoch());writeUuid(out,move.actor());out.writeLong(move.sequence());out.writeInt(move.entityId());
                    move.beforePose().write(out);out.writeInt(move.beforeFood().foodLevel());out.writeInt(move.beforeFood().tickTimer());
                    out.writeFloat(move.beforeFood().saturation());out.writeFloat(move.beforeFood().exhaustion());
                    out.writeInt(move.playerTick());out.writeInt(move.connectionTick());out.writeInt(move.received());out.writeInt(move.known());out.writeInt(move.packetKind());
                    out.writeDouble(move.x());out.writeDouble(move.y());out.writeDouble(move.z());out.writeFloat(move.yaw());out.writeFloat(move.pitch());out.writeBoolean(move.onGround());
                } else if (effect instanceof ExperienceSpawn orb) {
                    writeUuid(out, orb.id()); out.writeInt(orb.value());
                    writePosition(out, orb.x(), orb.y(), orb.z());
                    writePosition(out, orb.velocityX(), orb.velocityY(), orb.velocityZ()); out.writeInt(orb.age());
                } else if (effect instanceof ScheduledBlockTick tick) {
                    writeCell(out, tick.cell()); out.writeUTF(tick.block().toString());
                    out.writeLong(tick.triggerTick()); out.writeInt(tick.priority()); out.writeLong(tick.subTickOrder());
                } else if (effect instanceof PlayerStat stat) {
                    out.writeUTF(stat.category().toString()); out.writeUTF(stat.value().toString()); out.writeInt(stat.amount());
                } else if (effect instanceof PlayerExhaustion exhaustion) {
                    out.writeFloat(exhaustion.amount());
                } else if (effect instanceof Sound sound) {
                    out.writeUTF(sound.sound().toString()); out.writeUTF(sound.category());
                    writePosition(out, sound.x(), sound.y(), sound.z());
                    out.writeFloat(sound.volume()); out.writeFloat(sound.pitch()); out.writeLong(sound.seed());
                } else if (effect instanceof PlacementSound sound) {
                    out.writeUTF(sound.sound().toString()); out.writeUTF(sound.category());
                    writePosition(out, sound.x(), sound.y(), sound.z());
                    out.writeFloat(sound.volume()); out.writeFloat(sound.pitch()); out.writeLong(sound.seed());
                    writeUuid(out, sound.excludedActor());
                } else if (effect instanceof BlockBreakMetadata broken) {
                    writeWorld(out, broken.world()); writeCell(out, broken.cell()); writeUuid(out, broken.actor());
                    out.writeInt(broken.beforeStateId()); out.writeFloat(broken.observedDestroyProgress());
                } else if (effect instanceof BlockPlaceEvent event) {
                    writeWorld(out, event.world()); writeCell(out, event.cell());
                    writeUuid(out, event.actor()); out.writeInt(event.affectedStateId());
                } else if (effect instanceof GameEvent event) {
                    out.writeUTF(event.event().toString()); writeCell(out, event.cell());
                    out.writeBoolean(event.source() != null); if (event.source() != null) writeUuid(out, event.source());
                } else if (effect instanceof RandomSeed random) {
                    writeWorld(out, random.world()); writeUuid(out, random.ownerEpoch()); writeUuid(out, random.actor());
                    out.writeLong(random.sequence()); out.writeLong(random.seed());
                } else if (effect instanceof BlockNotification notification) {
                    writeWorld(out, notification.world()); writeCell(out, notification.cell());
                    out.writeInt(notification.beforeStateId()); out.writeInt(notification.afterStateId());
                    out.writeInt(notification.flags()); out.writeInt(notification.recursionDepth());
                } else if (effect instanceof MiningControl control) {
                    writeWorld(out, control.world()); writeUuid(out, control.ownerEpoch()); writeUuid(out, control.actor());
                    out.writeLong(control.sequence()); control.journal().write(out);
                } else if (effect instanceof SessionMenuOpen open) {
                    writeWorld(out, open.world()); writeUuid(out, open.ownerEpoch()); writeUuid(out, open.actor());
                    out.writeLong(open.sequence()); open.journal().write(out);
                } else if (effect instanceof SessionMenuPacket packet) {
                    writeWorld(out, packet.world()); writeUuid(out, packet.ownerEpoch()); writeUuid(out, packet.actor());
                    out.writeLong(packet.sequence()); packet.journal().write(out);
                } else if (effect instanceof SessionMenuClose close) {
                    writeWorld(out, close.world()); writeUuid(out, close.ownerEpoch()); writeUuid(out, close.actor());
                    out.writeLong(close.sequence()); close.journal().write(out);
                } else if (effect instanceof ResolvedMenuScript script) {
                    writeWorld(out, script.world()); writeUuid(out, script.ownerEpoch()); writeUuid(out, script.actor());
                    out.writeLong(script.sequence()); out.writeInt(script.clicks().size());
                    for (var click : script.clicks()) { out.writeInt(click.slot()); out.writeInt(click.button()); }
                    writeMenuImage(out, script.transcript().before()); writeMenuImage(out, script.transcript().after());
                    out.writeInt(script.transcript().steps().size());
                    for (var step : script.transcript().steps()) writeMenuImage(out, step);
                    out.writeInt(script.transcript().notices().size());
                    for (var notice : script.transcript().notices()) writeMenuNotice(out, notice);
                } else if (effect instanceof ResolvedTableScript script) {
                    writeWorld(out, script.world()); writeUuid(out, script.ownerEpoch()); writeUuid(out, script.actor());
                    out.writeLong(script.sequence()); writeCell(out, script.table());
                    out.writeInt(script.tableStateId()); out.writeInt(script.containerCounterBefore());
                    out.writeInt(script.clicks().size());
                    for (var click : script.clicks()) { out.writeInt(click.slot()); out.writeInt(click.button()); }
                    writeMenuImage(out, script.transcript().inventoryMenuBefore());
                    writeTableImage(out, script.transcript().before()); writeTableImage(out, script.transcript().after());
                    out.writeInt(script.transcript().steps().size());
                    for (var step : script.transcript().steps()) writeTableImage(out, step);
                    out.writeInt(script.transcript().notices().size());
                    for (var notice : script.transcript().notices()) writeMenuNotice(out, notice);
                } else throw new IllegalArgumentException("Unsupported native effect type");
            }
            return new NativeActionEffect(effect.type(), bytes.toByteArray());
        } catch (IOException invalid) { throw new IllegalArgumentException("Unable to encode native effect", invalid); }
    }

    public static Effect decode(NativeActionEffect effect, HolderLookup.Provider registries) {
        Objects.requireNonNull(effect); Objects.requireNonNull(registries);
        try (var input = new DataInputStream(new ByteArrayInputStream(effect.payload()))) {
            if (input.readInt() != VERSION) throw new IllegalArgumentException("Unsupported native effect version");
            Effect value = switch (effect.type()) {
                case "inventory_slot_v1" -> new InventorySlot(input.readInt(), readStack(input, registries), readStack(input, registries));
                case "item_spawn_v1" -> new ItemSpawn(readUuid(input), readStack(input, registries),
                        input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(),
                        input.readDouble(), input.readDouble(), input.readInt(), input.readInt());
                case "item_pickup_v1" -> new ItemPickup(readUuid(input), input.readInt(), readEntity(input, registries),
                        input.readBoolean() ? readEntity(input, registries) : null, input.readInt(), input.readInt());
                case "player_move_v1" -> readPlayerMove(input);
                case "item_tick_v1" -> new ItemTick(readUuid(input), input.readInt(), readEntity(input, registries), input.readInt());
                case "experience_spawn_v1" -> new ExperienceSpawn(readUuid(input), input.readInt(),
                        input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(),
                        input.readDouble(), input.readDouble(), input.readInt());
                case "scheduled_block_tick_v1" -> new ScheduledBlockTick(readCell(input), readId(input),
                        input.readLong(), input.readInt(), input.readLong());
                case "player_stat_v1" -> new PlayerStat(readId(input), readId(input), input.readInt());
                case "player_exhaustion_v1" -> new PlayerExhaustion(input.readFloat());
                case "sound_v1" -> new Sound(readId(input), input.readUTF(), input.readDouble(), input.readDouble(),
                        input.readDouble(), input.readFloat(), input.readFloat(), input.readLong());
                case "placement_sound_v1" -> new PlacementSound(readId(input), input.readUTF(), input.readDouble(),
                        input.readDouble(), input.readDouble(), input.readFloat(), input.readFloat(), input.readLong(), readUuid(input));
                case "block_break_metadata_v1" -> new BlockBreakMetadata(readWorld(input), readCell(input), readUuid(input), input.readInt(), input.readFloat());
                case "block_place_event_v1" -> new BlockPlaceEvent(readWorld(input), readCell(input), readUuid(input), input.readInt());
                case "game_event_v1" -> new GameEvent(readId(input), readCell(input), input.readBoolean() ? readUuid(input) : null);
                case "random_seed_v1" -> new RandomSeed(readWorld(input), readUuid(input), readUuid(input), input.readLong(), input.readLong());
                case "block_notification_v1" -> new BlockNotification(readWorld(input), readCell(input),
                        input.readInt(), input.readInt(), input.readInt(), input.readInt());
                case "native_mining_control_v1" -> new MiningControl(readWorld(input), readUuid(input), readUuid(input), input.readLong(),
                        io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlTranscript.read(input, registries));
                case "session_menu_open_v1" -> new SessionMenuOpen(readWorld(input), readUuid(input), readUuid(input), input.readLong(),
                        io.earthplatform.minecraft.survival.interaction.host.NativeMenuOpenJournal.read(input, registries));
                case "session_menu_packet_v1" -> new SessionMenuPacket(readWorld(input), readUuid(input), readUuid(input), input.readLong(),
                        io.earthplatform.minecraft.survival.interaction.host.NativeMenuPacketJournal.read(input, registries));
                case "session_menu_close_v1" -> new SessionMenuClose(readWorld(input), readUuid(input), readUuid(input), input.readLong(),
                        io.earthplatform.minecraft.survival.interaction.host.NativeMenuCloseJournal.read(input, registries));
                case "resolved_menu_script_v1" -> readMenuScript(input, registries);
                case "resolved_table_script_v1" -> readTableScript(input, registries);
                default -> throw new IllegalArgumentException("Unsupported native effect type: " + effect.type());
            };
            if (input.available() != 0) throw new IllegalArgumentException("Trailing native effect bytes");
            return value;
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid native effect payload", invalid); }
    }

    /** Bound allocation while encoding, before any large journal can be handed to a transaction. */
    private static final class BoundedEffectBytes extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            requireRoom(1); super.write(value);
        }
        @Override public synchronized void write(byte[] value, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, value.length);
            requireRoom(length); super.write(value, offset, length);
        }
        private void requireRoom(int length) {
            if (length < 0 || length > NativeActionEffect.MAX_PAYLOAD_BYTES - count)
                throw new IllegalArgumentException("Native effect exceeds its exact encoded byte cap");
        }
    }
    private static void requireMenuImage(NativeInventoryMenuSnapshot.Data value) {
        Objects.requireNonNull(value); Objects.requireNonNull(value.result()); Objects.requireNonNull(value.carried());
        Objects.requireNonNull(value.remoteCarried());
        if (value.inventory().size() != 41 || value.grid().size() != 4 || value.lastSlots().size() != 46
                || value.remoteSlots().size() != 46 || value.suppressRemoteUpdates()
                || value.quickcraftType() < -1 || value.quickcraftType() > 2)
            throw new IllegalArgumentException("Unsupported exact native menu image");
    }
    private static void requireResolvedMenuImage(NativeInventoryMenuSnapshot.Data value) {
        if (!value.result().isEmpty() || !value.carried().isEmpty() || value.grid().stream().anyMatch(stack -> !stack.isEmpty()))
            throw new IllegalArgumentException("Native committed script boundary must be fully resolved");
    }
    private static void requireMenuNotice(NativeInventoryMenuPreparation.Notice notice) {
        Objects.requireNonNull(notice);
        if (notice instanceof NativeInventoryMenuPreparation.Statistic statistic) Objects.requireNonNull(statistic.stat());
        else if (notice instanceof NativeInventoryMenuPreparation.Crafted crafted) {
            Objects.requireNonNull(crafted.recipe());
            if (crafted.input().isEmpty() || crafted.input().size() > 4)
                throw new IllegalArgumentException("Native2x2 crafted input is out of bounds");
        } else if (notice instanceof NativeInventoryMenuPreparation.Unlocked unlocked) {
            if (unlocked.recipes().size() != 1)
                throw new IllegalArgumentException("Native result removal unlock must name one exact recipe");
        } else if (notice instanceof NativeInventoryMenuPreparation.SlotPacket packet) {
            Objects.requireNonNull(packet.stack());
            if (packet.container() != 0 || packet.slot() != 0 || packet.state() < 0 || packet.state() > 32767)
                throw new IllegalArgumentException("Only bounded native2x2 result packet notices are admitted");
        } else throw new IllegalArgumentException("Unknown native menu notice");
    }
    private static void requireTableImage(NativeCraftingTableSnapshot.Data value) {
        Objects.requireNonNull(value); Objects.requireNonNull(value.result()); Objects.requireNonNull(value.carried());
        Objects.requireNonNull(value.remoteCarried());
        if (value.containerId() < 1 || value.containerId() > 100 || value.inventory().size() != 41
                || value.grid().size() != 9 || value.lastSlots().size() != 46 || value.remoteSlots().size() != 46
                || value.selected() < 0 || value.selected() > 8 || value.stateId() < 0 || value.stateId() > 32767
                || value.removeCount() < 0 || value.suppressRemoteUpdates()
                || value.quickcraftType() < -1 || value.quickcraftType() > 2)
            throw new IllegalArgumentException("Unsupported exact native table image");
    }
    private static void requireResolvedTableImage(NativeCraftingTableSnapshot.Data value) {
        if (!value.result().isEmpty() || !value.carried().isEmpty() || value.grid().stream().anyMatch(stack -> !stack.isEmpty()))
            throw new IllegalArgumentException("Native table committed boundary must be fully resolved");
    }
    private static void requireTableNotice(NativeInventoryMenuPreparation.Notice notice, int containerId) {
        Objects.requireNonNull(notice);
        if (notice instanceof NativeInventoryMenuPreparation.Crafted crafted) {
            Objects.requireNonNull(crafted.recipe());
            if (crafted.input().isEmpty() || crafted.input().size() > 9)
                throw new IllegalArgumentException("Native3x3 crafted input is out of bounds");
        } else if (notice instanceof NativeInventoryMenuPreparation.SlotPacket packet) {
            Objects.requireNonNull(packet.stack());
            if (packet.container() != containerId || packet.slot() != 0 || packet.state() < 0 || packet.state() > 32767)
                throw new IllegalArgumentException("Native3x3 result packet differs from the opened container");
        } else requireMenuNotice(notice);
    }
    private static void writeTableImage(DataOutputStream out, NativeCraftingTableSnapshot.Data value) throws IOException {
        out.writeInt(value.containerId());
        writeStacks(out, value.inventory()); out.writeInt(value.selected()); out.writeInt(value.inventoryChanges());
        writeStacks(out, value.grid()); writeStack(out, value.result()); writeStack(out, value.carried());
        out.writeBoolean(value.recipeUsed() != null); if (value.recipeUsed() != null) out.writeUTF(value.recipeUsed().toString());
        out.writeInt(value.removeCount()); out.writeInt(value.stateId());
        writeStacks(out, value.lastSlots()); writeStacks(out, value.remoteSlots()); writeStack(out, value.remoteCarried());
        out.writeBoolean(value.suppressRemoteUpdates()); out.writeInt(value.quickcraftType());
    }
    private static NativeCraftingTableSnapshot.Data readTableImage(DataInputStream in, HolderLookup.Provider registries) throws IOException {
        return new NativeCraftingTableSnapshot.Data(in.readInt(), readStacks(in, registries, 41, 41), in.readInt(), in.readInt(),
                readStacks(in, registries, 9, 9), readStack(in, registries), readStack(in, registries),
                in.readBoolean() ? readId(in) : null, in.readInt(), in.readInt(),
                readStacks(in, registries, 46, 46), readStacks(in, registries, 46, 46), readStack(in, registries),
                in.readBoolean(), in.readInt());
    }
    private static void writeMenuImage(DataOutputStream out, NativeInventoryMenuSnapshot.Data value) throws IOException {
        writeStacks(out, value.inventory()); out.writeInt(value.selected()); out.writeInt(value.inventoryChanges());
        writeStacks(out, value.grid()); writeStack(out, value.result()); writeStack(out, value.carried());
        out.writeBoolean(value.recipeUsed() != null); if (value.recipeUsed() != null) out.writeUTF(value.recipeUsed().toString());
        out.writeInt(value.removeCount()); out.writeInt(value.stateId());
        writeStacks(out, value.lastSlots()); writeStacks(out, value.remoteSlots()); writeStack(out, value.remoteCarried());
        out.writeBoolean(value.suppressRemoteUpdates()); out.writeInt(value.quickcraftType());
    }
    private static NativeInventoryMenuSnapshot.Data readMenuImage(DataInputStream in, HolderLookup.Provider registries) throws IOException {
        return new NativeInventoryMenuSnapshot.Data(readStacks(in, registries, 41, 41), in.readInt(), in.readInt(),
                readStacks(in, registries, 4, 4), readStack(in, registries), readStack(in, registries),
                in.readBoolean() ? readId(in) : null, in.readInt(), in.readInt(),
                readStacks(in, registries, 46, 46), readStacks(in, registries, 46, 46), readStack(in, registries),
                in.readBoolean(), in.readInt());
    }
    private static void writeStacks(DataOutputStream out, List<NativeStackSnapshot> values) throws IOException {
        out.writeInt(values.size()); for (var value : values) writeStack(out, value);
    }
    private static List<NativeStackSnapshot> readStacks(DataInputStream in, HolderLookup.Provider registries, int min, int max) throws IOException {
        int count = readCount(in, min, max, "native menu stack vector");
        var values = new ArrayList<NativeStackSnapshot>(count);
        for (int i = 0; i < count; i++) values.add(readStack(in, registries));
        return List.copyOf(values);
    }
    private static void writeMenuNotice(DataOutputStream out, NativeInventoryMenuPreparation.Notice notice) throws IOException {
        if (notice instanceof NativeInventoryMenuPreparation.Statistic statistic) {
            out.writeByte(0); out.writeUTF(statistic.stat().category().toString()); out.writeUTF(statistic.stat().value().toString());
            out.writeInt(statistic.stat().amount());
        } else if (notice instanceof NativeInventoryMenuPreparation.Crafted crafted) {
            out.writeByte(1); out.writeUTF(crafted.recipe().toString()); writeStacks(out, crafted.input());
        } else if (notice instanceof NativeInventoryMenuPreparation.Unlocked unlocked) {
            out.writeByte(2); out.writeInt(unlocked.recipes().size());
            for (var recipe : unlocked.recipes()) out.writeUTF(recipe.toString());
        } else if (notice instanceof NativeInventoryMenuPreparation.SlotPacket packet) {
            out.writeByte(3); out.writeInt(packet.container()); out.writeInt(packet.state()); out.writeInt(packet.slot());
            writeStack(out, packet.stack());
        } else throw new IllegalArgumentException("Unknown native menu notice");
    }
    private static NativeInventoryMenuPreparation.Notice readMenuNotice(DataInputStream in, HolderLookup.Provider registries) throws IOException {
        return readMenuNotice(in, registries, 4);
    }
    private static NativeInventoryMenuPreparation.Notice readMenuNotice(DataInputStream in, HolderLookup.Provider registries, int gridSize) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> new NativeInventoryMenuPreparation.Statistic(new PlayerStat(readId(in), readId(in), in.readInt()));
            case 1 -> new NativeInventoryMenuPreparation.Crafted(readId(in), readStacks(in, registries, 1, gridSize));
            case 2 -> {
                int count = readCount(in, 1, 1, "native recipe unlock");
                var recipes = new ArrayList<ResourceLocation>(count);
                for (int i = 0; i < count; i++) recipes.add(readId(in));
                yield new NativeInventoryMenuPreparation.Unlocked(recipes);
            }
            case 3 -> new NativeInventoryMenuPreparation.SlotPacket(in.readInt(), in.readInt(), in.readInt(), readStack(in, registries));
            default -> throw new IllegalArgumentException("Unknown native menu notice tag");
        };
    }
    private static ResolvedMenuScript readMenuScript(DataInputStream in, HolderLookup.Provider registries) throws IOException {
        var world = readWorld(in); var owner = readUuid(in); var actor = readUuid(in); long sequence = in.readLong();
        int clicks = readCount(in, 1, ResolvedMenuScript.MAX_CLICKS, "native clicks");
        var script = new ArrayList<NativeInventoryMenuPreparation.Click>(clicks);
        for (int i = 0; i < clicks; i++) script.add(new NativeInventoryMenuPreparation.Click(in.readInt(), in.readInt(), ClickType.PICKUP));
        var before = readMenuImage(in, registries); var after = readMenuImage(in, registries);
        int count = readCount(in, clicks, clicks, "native click steps");
        var steps = new ArrayList<NativeInventoryMenuSnapshot.Data>(count);
        for (int i = 0; i < count; i++) steps.add(readMenuImage(in, registries));
        count = readCount(in, 0, ResolvedMenuScript.MAX_NOTICES, "native menu notices");
        var notices = new ArrayList<NativeInventoryMenuPreparation.Notice>(count);
        for (int i = 0; i < count; i++) notices.add(readMenuNotice(in, registries));
        return new ResolvedMenuScript(world, owner, actor, sequence, script,
                new NativeInventoryMenuPreparation.Result(before, after, notices, steps));
    }
    private static ResolvedTableScript readTableScript(DataInputStream in, HolderLookup.Provider registries) throws IOException {
        var world = readWorld(in); var owner = readUuid(in); var actor = readUuid(in); long sequence = in.readLong();
        var table = readCell(in); int state = in.readInt(), counter = in.readInt();
        int clicks = readCount(in, 1, ResolvedMenuScript.MAX_CLICKS, "native table clicks");
        var script = new ArrayList<NativeInventoryMenuPreparation.Click>(clicks);
        for (int i = 0; i < clicks; i++) script.add(new NativeInventoryMenuPreparation.Click(in.readInt(), in.readInt(), ClickType.PICKUP));
        var inventoryBefore = readMenuImage(in, registries);
        var before = readTableImage(in, registries); var after = readTableImage(in, registries);
        int count = readCount(in, clicks, clicks, "native table steps");
        var steps = new ArrayList<NativeCraftingTableSnapshot.Data>(count);
        for (int i = 0; i < count; i++) steps.add(readTableImage(in, registries));
        count = readCount(in, 0, ResolvedMenuScript.MAX_NOTICES, "native table notices");
        var notices = new ArrayList<NativeInventoryMenuPreparation.Notice>(count);
        for (int i = 0; i < count; i++) notices.add(readMenuNotice(in, registries, 9));
        return new ResolvedTableScript(world, owner, actor, sequence, table, state, counter, script,
                new NativeCraftingTablePreparation.Result(inventoryBefore, before, after, notices, steps));
    }

    private static int readCount(DataInputStream in, int min, int max, String kind) throws IOException {
        int count = in.readInt();
        if (count < min || count > max || count > in.available())
            throw new IllegalArgumentException("Invalid bounded " + kind + " count");
        return count;
    }

    private static void writeEntity(DataOutputStream out, NativeItemEntitySnapshot entity) throws IOException {
        byte[] bytes = entity.encoded(); out.writeInt(bytes.length); out.write(bytes);
    }
    private static NativeItemEntitySnapshot readEntity(DataInputStream input, HolderLookup.Provider registries) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > NativeItemEntitySnapshot.MAX_BYTES || length > input.available())
            throw new IllegalArgumentException("Invalid native pickup entity payload length");
        return NativeItemEntitySnapshot.decode(input.readNBytes(length), registries);
    }
    private static void writeStack(DataOutputStream out, NativeStackSnapshot stack) throws IOException {
        byte[] bytes = stack.encoded(); out.writeInt(bytes.length); out.write(bytes);
    }
    private static NativeStackSnapshot readStack(DataInputStream in, HolderLookup.Provider registries) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > NativeStackSnapshot.MAX_BYTES || length > in.available())
            throw new IllegalArgumentException("Invalid native stack payload length");
        return NativeStackSnapshot.decode(in.readNBytes(length), registries);
    }
    private static PlayerMove readPlayerMove(DataInputStream in) throws IOException {
        var world=readWorld(in);var epoch=readUuid(in);var actor=readUuid(in);long sequence=in.readLong();int entity=in.readInt();
        var pose=io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot.read(in);
        var food=new io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot(in.readInt(),in.readInt(),in.readFloat(),in.readFloat());
        int playerTick=in.readInt(),connectionTick=in.readInt(),received=in.readInt(),known=in.readInt(),kind=in.readInt();
        double x=in.readDouble(),y=in.readDouble(),z=in.readDouble();float yaw=in.readFloat(),pitch=in.readFloat();int ground=in.readUnsignedByte();
        if(ground>1)throw new IllegalArgumentException("Noncanonical native player-move ground flag");
        return new PlayerMove(world,epoch,actor,sequence,entity,pose,food,playerTick,connectionTick,received,known,kind,x,y,z,yaw,pitch,ground==1);
    }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void writeWorld(DataOutputStream out, WorldIdentity world) throws IOException {
        out.writeLong(world.seed()); out.writeUTF(world.generatorId()); out.writeUTF(world.generatorVersion());
        out.writeUTF(world.datasetVersion()); out.writeUTF(world.blockPaletteVersion());
    }
    private static WorldIdentity readWorld(DataInputStream in) throws IOException {
        return new WorldIdentity(in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
    }
    private static void writePosition(DataOutputStream out, double x, double y, double z) throws IOException {
        out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
    }
    private static void writeCell(DataOutputStream out, CubeCell cell) throws IOException {
        // Do not replace with BlockPos.asLong or a native packed block-position codec.
        out.writeLong(cell.x()); out.writeLong(cell.y()); out.writeLong(cell.z());
    }
    private static CubeCell readCell(DataInputStream in) throws IOException {
        return CubeCell.of(in.readLong(), in.readLong(), in.readLong());
    }
    private static ResourceLocation readId(DataInputStream in) throws IOException { return ResourceLocation.parse(in.readUTF()); }
    private static void requireFinite(double... numbers) {
        for (double number : numbers) if (!Double.isFinite(number)) throw new IllegalArgumentException("Finite entity coordinates required");
    }
}
