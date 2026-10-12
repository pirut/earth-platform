package io.earthplatform.minecraft.survival.persistence;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import net.minecraft.client.server.IntegratedPlayerList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Mixin-only facade: no scope factory, token mint, replay, success or failure-clear API. */
public final class NativeInstalledOrdinarySaveHooks {
    private NativeInstalledOrdinarySaveHooks(){}
    public static CompoundTag cache(ServerPlayer receiver,CompoundTag input,IntegratedPlayerList list,ServerPlayer invocation){return InstalledOrdinarySaveEnvelope.cache(receiver,input,list,invocation);}
    public static void statsEnter(ServerStatsCounter source){InstalledOrdinarySaveEnvelope.statsEnter(source);}
    public static void statsWrite(ServerStatsCounter source,File file,String text)throws IOException{InstalledOrdinarySaveEnvelope.statsWrite(source,file,text);}
    public static void statsExit(ServerStatsCounter source){InstalledOrdinarySaveEnvelope.statsExit(source);}
    public static void advancementsEnter(PlayerAdvancements source){InstalledOrdinarySaveEnvelope.advancementsEnter(source);}
    public static void advancementDirectory(PlayerAdvancements source,Path directory)throws IOException{InstalledOrdinarySaveEnvelope.advancementDirectory(source,directory);}
    public static BufferedWriter advancementsWriter(PlayerAdvancements source,Path path,Charset charset,OpenOption[] options)throws IOException{return InstalledOrdinarySaveEnvelope.advancementsWriter(source,path,charset,options);}
    public static void advancementsExit(PlayerAdvancements source){InstalledOrdinarySaveEnvelope.advancementsExit(source);}
    public static void levelCodec(net.minecraft.world.level.storage.PrimaryLevelData source,com.mojang.serialization.DataResult<?> result){InstalledOrdinarySaveEnvelope.levelCodec(source,result);}
    public static void levelEnter(LevelStorageSource.LevelStorageAccess source,CompoundTag tag){InstalledOrdinarySaveEnvelope.levelEnter(source,tag);}
    public static void levelWrite(LevelStorageSource.LevelStorageAccess source,CompoundTag tag,Path temporary)throws IOException{InstalledOrdinarySaveEnvelope.levelWrite(source,tag,temporary);}
    public static void levelReplace(LevelStorageSource.LevelStorageAccess source,Path current,Path temporary,Path backup){InstalledOrdinarySaveEnvelope.levelReplace(source,current,temporary,backup);}
    public static void levelExit(LevelStorageSource.LevelStorageAccess source){InstalledOrdinarySaveEnvelope.levelExit(source);}
    public static void caught(Object source){InstalledOrdinarySaveEnvelope.caught(source);}
}
