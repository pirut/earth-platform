package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/** Reads only the actual emitted tag; never serializes a second live player or loads a player. */
final class OrdinarySavePayload {
    private OrdinarySavePayload() {}
    static void requireEscrow(CompoundTag tag, UUID player, Session session, HolderLookup.Provider registries) {
        if (!tag.hasUUID("UUID") || !player.equals(tag.getUUID("UUID"))) refuse();
        NativeInventorySnapshot expected = session.ordinaryEscrow();
        requireType(tag, "Inventory", Tag.TAG_LIST); requireType(tag, "SelectedItemSlot", Tag.TAG_INT);
        ListTag list = tag.getList("Inventory", Tag.TAG_COMPOUND);
        if (list.size() > NativeInventorySnapshot.SLOTS || ((ListTag)tag.get("Inventory")).size() != list.size()
                || tag.getInt("SelectedItemSlot") != expected.selected()) refuse();
        boolean[] seen = new boolean[NativeInventorySnapshot.SLOTS];
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i); requireType(entry, "Slot", Tag.TAG_BYTE);
            int raw = entry.getByte("Slot") & 255;
            int slot = raw < 36 ? raw : raw >= 100 && raw <= 103 ? raw - 64 : raw == 150 ? 40 : -1;
            if (slot < 0 || seen[slot]) refuse();
            seen[slot] = true;
            ItemStack stack = ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), entry).getOrThrow();
            if (stack.isEmpty() || !expected.slots().get(slot).equals(NativeStackSnapshot.capture(stack, registries))) refuse();
        }
        for (int i = 0; i < seen.length; i++) if (!seen[i] && !expected.slots().get(i).isEmpty()) refuse();
        var food = session.ordinaryFood();
        requireType(tag, "foodLevel", Tag.TAG_INT); requireType(tag, "foodTickTimer", Tag.TAG_INT);
        requireType(tag, "foodSaturationLevel", Tag.TAG_FLOAT); requireType(tag, "foodExhaustionLevel", Tag.TAG_FLOAT);
        if (!food.equals(new NativeFoodSnapshot(tag.getInt("foodLevel"), tag.getInt("foodTickTimer"),
                tag.getFloat("foodSaturationLevel"), tag.getFloat("foodExhaustionLevel")))) refuse();
        var pose = session.ordinaryPose();
        requireType(tag, "Dimension", Tag.TAG_STRING);
        if (!tag.getString("Dimension").equals(pose.dimension().toString())) refuse();
        doubles(tag, "Pos", pose.x(), pose.y(), pose.z());
        doubles(tag, "Motion", pose.velocityX(), pose.velocityY(), pose.velocityZ());
        ListTag rotation = list(tag, "Rotation", Tag.TAG_FLOAT, 2);
        if (Float.compare(rotation.getFloat(0), pose.yaw()) != 0 || Float.compare(rotation.getFloat(1), pose.pitch()) != 0) refuse();
        requireType(tag, "FallDistance", Tag.TAG_FLOAT); bool(tag, "OnGround", pose.onGround());
        if (Float.compare(tag.getFloat("FallDistance"), pose.fallDistance()) != 0) refuse();
        requireType(tag, "playerGameType", Tag.TAG_INT);
        if (tag.getInt("playerGameType") != pose.gameMode().getId()) refuse();
        if (pose.previousGameMode() == null) { if (tag.contains("previousPlayerGameType")) refuse(); }
        else {
            requireType(tag, "previousPlayerGameType", Tag.TAG_INT);
            if (tag.getInt("previousPlayerGameType") != pose.previousGameMode().getId()) refuse();
        }
        requireType(tag, "abilities", Tag.TAG_COMPOUND); CompoundTag abilities = tag.getCompound("abilities");
        var a = pose.abilities();
        bool(abilities,"invulnerable",a.invulnerable()); bool(abilities,"flying",a.flying());
        bool(abilities,"mayfly",a.mayFly()); bool(abilities,"instabuild",a.instantBuild()); bool(abilities,"mayBuild",a.mayBuild());
        requireType(abilities,"flySpeed",Tag.TAG_FLOAT); requireType(abilities,"walkSpeed",Tag.TAG_FLOAT);
        if (Float.compare(abilities.getFloat("flySpeed"),a.flyingSpeed()) != 0
                || Float.compare(abilities.getFloat("walkSpeed"),a.walkingSpeed()) != 0) refuse();
    }
    private static void requireType(CompoundTag tag, String key, int type) { if (!tag.contains(key, type)) refuse(); }
    private static ListTag list(CompoundTag tag, String key, int type, int size) {
        requireType(tag,key,Tag.TAG_LIST); ListTag list = tag.getList(key,type);
        if (list.size() != size || list.getElementType() != type) refuse(); return list;
    }
    private static void doubles(CompoundTag tag, String key, double... values) {
        ListTag list = list(tag,key,Tag.TAG_DOUBLE,values.length);
        for (int i=0;i<values.length;i++) if (Double.compare(list.getDouble(i), values[i]) != 0) refuse();
    }
    private static void bool(CompoundTag tag, String key, boolean value) {
        requireType(tag,key,Tag.TAG_BYTE); if (tag.getByte(key) != (value ? 1 : 0)) refuse();
    }
    private static void refuse() { throw new SurvivalRefusal("Emitted ordinary native payload does not equal session escrow"); }
}
