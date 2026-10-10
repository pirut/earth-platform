package io.earthplatform.minecraft.coregate;

import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/** Small player-owned recovery record, written before carrier travel and cleared after return. */
record CoreReturnPoint(ResourceKey<Level> dimension,double x,double y,double z,float yaw,float pitch,GameType mode) {
    static final String KEY="earthplatform:core_return_v1";
    CoreReturnPoint {
        if(dimension==null||dimension.equals(io.earthplatform.minecraft.scene.SceneCommands.CARRIER)||mode==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000||Math.abs(y)>20_000_000
                ||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("Invalid core return point");
    }
    CompoundTag encode(){var t=new CompoundTag();t.putInt("version",1);t.putString("dimension",dimension.location().toString());t.putDouble("x",x);t.putDouble("y",y);t.putDouble("z",z);t.putFloat("yaw",yaw);t.putFloat("pitch",pitch);t.putString("mode",mode.getName());return t;}
    static Optional<CoreReturnPoint> decode(CompoundTag tag){try{
        if(tag.getInt("version")!=1)return Optional.empty();
        for(String name:new String[]{"x","y","z","yaw","pitch"})if(!tag.contains(name,net.minecraft.nbt.Tag.TAG_ANY_NUMERIC))return Optional.empty();
        var id=ResourceLocation.tryParse(tag.getString("dimension"));if(id==null)return Optional.empty();
        GameType mode=null;for(var value:GameType.values())if(value.getName().equals(tag.getString("mode")))mode=value;if(mode==null)return Optional.empty();
        return Optional.of(new CoreReturnPoint(ResourceKey.create(Registries.DIMENSION,id),tag.getDouble("x"),tag.getDouble("y"),tag.getDouble("z"),tag.getFloat("yaw"),tag.getFloat("pitch"),mode));
    }catch(RuntimeException invalid){return Optional.empty();}}
    void remember(ServerPlayer p){p.getPersistentData().put(KEY,encode());}
    static Optional<CoreReturnPoint> remembered(ServerPlayer p){return decode(p.getPersistentData().getCompound(KEY));}
    static void clear(ServerPlayer p){p.getPersistentData().remove(KEY);}
}
