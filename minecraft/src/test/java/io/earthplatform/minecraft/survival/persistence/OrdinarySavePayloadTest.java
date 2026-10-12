package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

class OrdinarySavePayloadTest {
    static HolderLookup.Provider registries;
    static final UUID PLAYER=new UUID(1,2);
    @BeforeAll static void setup(){registries=VanillaRegistries.createLookup();}
    Session session(){
        List<NativeStackSnapshot> slots=new ArrayList<>();
        for(int i=0;i<41;i++)slots.add(NativeStackSnapshot.capture(ItemStack.EMPTY,registries));
        slots.set(0,NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG,11),registries));
        slots.set(36,NativeStackSnapshot.capture(new ItemStack(Items.LEATHER_BOOTS),registries));
        slots.set(40,NativeStackSnapshot.capture(new ItemStack(Items.STICK,3),registries));
        var pose=new NativePlayerPoseSnapshot(ResourceLocation.parse("minecraft:overworld"),-123.5,7314.875,-456.25,
                -127.25F,42.5F,0.125,-0.25,0.375,3.5F,false,GameType.SURVIVAL,null,
                new NativePlayerPoseSnapshot.AbilityState(false,false,false,false,true,0.05F,0.1F));
        return new Session(new UUID(3,4),SessionPhase.RETURNING,new NativeInventorySnapshot(slots,8),new NativeFoodSnapshot(17,31,3.5F,0.125F),pose);
    }
    CompoundTag payload(Session s){
        CompoundTag tag=new CompoundTag();tag.putUUID("UUID",PLAYER);tag.putInt("SelectedItemSlot",s.ordinaryEscrow().selected());
        ListTag inventory=new ListTag();List<ItemStack> stacks=s.ordinaryEscrow().materialize(registries);
        for(int i=0;i<stacks.size();i++)if(!stacks.get(i).isEmpty()){
            CompoundTag entry=new CompoundTag();entry.putByte("Slot",(byte)(i<36?i:i<40?i+64:150));inventory.add(stacks.get(i).save(registries,entry));
        }
        tag.put("Inventory",inventory);tag.merge(s.ordinaryFood().tagCopy());var p=s.ordinaryPose();
        tag.putString("Dimension",p.dimension().toString());
        ListTag pos=new ListTag();for(double d:new double[]{p.x(),p.y(),p.z()})pos.add(DoubleTag.valueOf(d));tag.put("Pos",pos);
        ListTag motion=new ListTag();for(double d:new double[]{p.velocityX(),p.velocityY(),p.velocityZ()})motion.add(DoubleTag.valueOf(d));tag.put("Motion",motion);
        ListTag rot=new ListTag();rot.add(FloatTag.valueOf(p.yaw()));rot.add(FloatTag.valueOf(p.pitch()));tag.put("Rotation",rot);
        tag.putFloat("FallDistance",p.fallDistance());tag.putBoolean("OnGround",p.onGround());tag.putInt("playerGameType",p.gameMode().getId());
        if(p.previousGameMode()!=null)tag.putInt("previousPlayerGameType",p.previousGameMode().getId());
        var a=p.abilities();CompoundTag ab=new CompoundTag();ab.putBoolean("invulnerable",a.invulnerable());ab.putBoolean("flying",a.flying());
        ab.putBoolean("mayfly",a.mayFly());ab.putBoolean("instabuild",a.instantBuild());ab.putBoolean("mayBuild",a.mayBuild());
        ab.putFloat("flySpeed",a.flyingSpeed());ab.putFloat("walkSpeed",a.walkingSpeed());tag.put("abilities",ab);return tag;
    }
    @Test void all41SlotsFoodAndFullPoseAreComparedSemantically(){var s=session();assertDoesNotThrow(()->OrdinarySavePayload.requireEscrow(payload(s),PLAYER,s,registries));}
    @Test void wrongPlayerSelectionInventoryFoodOrPoseNeverDefaults(){
        List<Consumer<CompoundTag>> corruptions=List.of(t->t.putUUID("UUID",UUID.randomUUID()),t->t.putInt("SelectedItemSlot",0),
                t->t.remove("foodTickTimer"),t->t.putDouble("foodSaturationLevel",3.5),t->t.putInt("foodLevel",18),
                t->t.putString("Dimension","minecraft:the_nether"),t->t.getList("Pos",Tag.TAG_DOUBLE).set(1,DoubleTag.valueOf(134.875)),
                t->t.putByte("OnGround",(byte)2),t->t.putInt("previousPlayerGameType",0),
                t->t.getCompound("abilities").putFloat("walkSpeed",0.2F),t->t.putInt("playerGameType",1),
                t->t.getList("Inventory",Tag.TAG_COMPOUND).remove(2));
        for(var mutate:corruptions){var s=session();CompoundTag tag=payload(s);mutate.accept(tag);assertThrows(RuntimeException.class,()->OrdinarySavePayload.requireEscrow(tag,PLAYER,s,registries));}
    }
    @Test void duplicateAndUnknownNativeSlotIdsAreRefused(){
        var s=session();CompoundTag tag=payload(s);ListTag items=tag.getList("Inventory",Tag.TAG_COMPOUND);items.add(items.getCompound(0).copy());
        assertThrows(SurvivalRefusal.class,()->OrdinarySavePayload.requireEscrow(tag,PLAYER,s,registries));
        CompoundTag wrong=payload(s);wrong.getList("Inventory",Tag.TAG_COMPOUND).getCompound(0).putByte("Slot",(byte)99);
        assertThrows(SurvivalRefusal.class,()->OrdinarySavePayload.requireEscrow(wrong,PLAYER,s,registries));
    }
    @Test void nonEscrowFieldsAreRetainedByFullPayloadEquality() throws Exception {
        var s=session();CompoundTag tag=payload(s);tag.putInt("XpTotal",53);CompoundTag frozen=BoundedOrdinarySaveNbt.freeze(tag);
        tag.putInt("XpTotal",54);assertNotEquals(frozen,tag);
        // Escrow admission alone intentionally does not claim all native fields equal; the observer also compares full NBT.
        assertDoesNotThrow(()->OrdinarySavePayload.requireEscrow(tag,PLAYER,s,registries));
    }
}
