package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.mixin.InstalledSessionPlayerListSaveAccess;
import io.earthplatform.minecraft.mixin.InstalledSessionServerSaveAccess;
import io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.server.IntegratedPlayerList;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.level.storage.*;
import org.apache.commons.io.FileUtils;

/** Private-token installed extension of the existing ordinary player.dat observer.
 * Owns real cached player tag, stats, advancements and level.dat effects. No begin/success API is public. */
final class InstalledOrdinarySaveEnvelope {
    private static final int MAX_PLAYER_BYTES=256*1024, MAX_LEVEL_BYTES=1024*1024, MAX_TEXT_CHARS=256*1024;
    private static final AtomicReference<InstalledOrdinarySaveEnvelope> ACTIVE=new AtomicReference<>();
    private final Thread owner=Thread.currentThread();
    private final InstalledMarinSessionAdmission.Token token;
    private final NativeSessionExecutionGate gate;
    private final ServerPlayer player;
    private final IntegratedPlayerList list;
    private final LevelStorageSource.LevelStorageAccess storage;
    private final PrimaryLevelData worldData;
    private final net.minecraft.core.RegistryAccess registries;
    private final ServerStatsCounter stats;
    private final PlayerAdvancements advancements;
    private final Consumer<Throwable> failOwner;
    private final Runnable requireAuthority;
    private final NativeSurvivalCheckpoint.Session session;
    private final InstalledOrdinarySaveTrace trace=new InstalledOrdinarySaveTrace();
    private final Path root, statsFile, advancementsFile, levelFile, levelBackup;
    private final Object rootKey;
    private Object statsRootKey, advancementsRootKey;
    private CompoundTag cached, levelTag;
    private String cachedDigest, levelDigest;
    private byte[] statsBytes, advancementsBytes;
    private Path levelTemporary;
    private BoundedWriter advancementWriter;
    private boolean checking;
    private int levelCodecResults;

    private InstalledOrdinarySaveEnvelope(InstalledMarinSessionAdmission.Token token,NativeSessionExecutionGate gate,
            ServerPlayer player,PlayerDataStorage playerStorage,NativeSurvivalCheckpoint.Session session,Runnable requireAuthority,Consumer<Throwable> failOwner) throws IOException {
        this.token=token;this.gate=gate;this.player=player;this.session=session;this.requireAuthority=requireAuthority;this.failOwner=failOwner;
        token.requireOrdinarySave(gate,player);
        if(player.getClass()!=ServerPlayer.class || player.server.getClass()!=IntegratedServer.class
                || player.server.getPlayerList().getClass()!=IntegratedPlayerList.class
                || !Charset.defaultCharset().equals(StandardCharsets.UTF_8))throw refuse("Exact UTF-8 installed native save profile required");
        pinSources();list=(IntegratedPlayerList)player.server.getPlayerList();
        if(raw(PlayerList.class,list,"playerIo")!=playerStorage)throw refuse("Native player storage owner differs");
        stats=ownedEntry(raw(PlayerList.class,list,"stats"),ServerStatsCounter.class);
        advancements=ownedEntry(raw(PlayerList.class,list,"advancements"),PlayerAdvancements.class);
        if(raw(ServerStatsCounter.class,stats,"server")!=player.server
                || raw(PlayerAdvancements.class,advancements,"player")!=player
                || raw(PlayerAdvancements.class,advancements,"playerList")!=list)throw refuse("Native stats/advancement owner differs");
        storage=((InstalledSessionServerSaveAccess)player.server).earthplatform$storageSource();
        if(storage==null || storage.getClass()!=LevelStorageSource.LevelStorageAccess.class
                || player.server.getWorldData().getClass()!=PrimaryLevelData.class)throw refuse("Exact actual saved world owner required");
        worldData=(PrimaryLevelData)player.server.getWorldData();registries=player.server.registryAccess();
        if(registries!=player.registryAccess())throw refuse("Original server/player registry owner differs");
        root=player.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();rootKey=directoryKey(root);
        statsFile=root.resolve("stats").resolve(player.getStringUUID()+".json");
        advancementsFile=root.resolve("advancements").resolve(player.getStringUUID()+".json");
        if(!((File)raw(ServerStatsCounter.class,stats,"file")).toPath().toAbsolutePath().normalize().equals(statsFile)
                || !((Path)raw(PlayerAdvancements.class,advancements,"playerSavePath")).toAbsolutePath().normalize().equals(advancementsFile)
                || !storage.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root))throw refuse("Native save destinations differ");
        statsRootKey=optionalDirectoryKey(statsFile.getParent());advancementsRootKey=optionalDirectoryKey(advancementsFile.getParent());
        levelFile=root.resolve("level.dat");levelBackup=root.resolve("level.dat_old");
        requirePlainTarget(statsFile);requirePlainTarget(advancementsFile);requirePlainTarget(levelFile);requirePlainTarget(levelBackup);
    }
    static InstalledOrdinarySaveEnvelope open(InstalledMarinSessionAdmission.Token token,NativeSessionExecutionGate gate,
            ServerPlayer player,PlayerDataStorage playerStorage,NativeSurvivalCheckpoint.Session session,Runnable requireAuthority,Consumer<Throwable> failOwner) throws IOException {
        var envelope=new InstalledOrdinarySaveEnvelope(token,gate,player,playerStorage,session,requireAuthority,failOwner);
        if(!ACTIVE.compareAndSet(null,envelope)){
            var prior=ACTIVE.get();var failure=new SurvivalRefusal("Nested installed ordinary save envelope");
            if(prior!=null)prior.poison(failure);envelope.poison(failure);throw failure;
        }
        return envelope;
    }
    void saveNativePlayerList(){check();((InstalledSessionPlayerListSaveAccess)list).earthplatform$savePlayer(player);check();trace.listDone();requireCached();}
    void completeLevelSave(CompoundTag playerDat) throws IOException {
        check();requirePlayerPayload(playerDat);
        storage.saveDataTag(registries,worldData,cached); // Original actual native method, exactly once.
        check();if(trace.step()!=InstalledOrdinarySaveTrace.Step.LEVEL_DONE)throw refuse("Missing native level save exit");
        CompoundTag read=readNbt(levelFile,MAX_LEVEL_BYTES,4L<<20);
        if(levelTag==null || !Objects.equals(levelDigest,digest(levelTag,MAX_LEVEL_BYTES)) || !levelTag.equals(read) || !cached.equals(read.getCompound("Data").getCompound("Player")))
            throw refuse("Actual level.dat readback does not contain exact ordinary cached player tag");
        requireText(statsFile,statsBytes);requireText(advancementsFile,advancementsBytes);requireCached();check();trace.verified();
        // Readback success is bound to this attempt. Large text arrays/writer aliases are no longer
        // needed and are discarded synchronously after close; immutable digests/trace remain.
        statsBytes=null;advancementsBytes=null;advancementWriter=null;levelTag=null;
    }
    void requireVerified(){check();trace.requireVerified();requireCached();}
    void close(){if(!ACTIVE.compareAndSet(this,null))poison(new SurvivalRefusal("Installed save envelope owner changed"));}
    void poison(Throwable cause){trace.poison(cause);failOwner.accept(cause);}
    void requirePlayerPayload(CompoundTag emitted){check();digest(emitted,MAX_PLAYER_BYTES);if(cached==null || !cached.equals(emitted))throw refuse("Cached ordinary and player.dat payload differ");}
    CompoundTag readPlayerTarget(Path path) throws IOException {check();return readNbt(path,MAX_PLAYER_BYTES,2L<<20);}
    CompoundTag freezePlayer(CompoundTag tag) throws IOException {
        requirePlayerPayload(tag);
        var bytes=new ByteArrayOutputStream(4096){
            private void admit(int length){if(length<0 || (long)count+length>MAX_PLAYER_BYTES)throw refuse("Installed player encoding bound changed");}
            @Override public synchronized void write(int value){admit(1);super.write(value);}
            @Override public synchronized void write(byte[] value,int start,int length){admit(length);super.write(value,start,length);}
        };
        try(var output=new DataOutputStream(bytes)){NbtIo.write(tag,output);}
        if(bytes.size()>MAX_PLAYER_BYTES)throw refuse("Installed player encoding bound changed");
        CompoundTag copy;
        try(var input=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))){copy=NbtIo.read(input,new NbtAccounter(2L<<20,32));if(input.read()!=-1)throw refuse("Trailing installed player NBT");}
        digest(copy,MAX_PLAYER_BYTES);if(!tag.equals(copy))throw refuse("Installed player NBT encoding changed");return copy;
    }
    void playerEnter(){check();trace.playerEnter();}
    void playerDone(){check();trace.playerDone();}

    static CompoundTag cache(ServerPlayer receiver,CompoundTag input,IntegratedPlayerList list,ServerPlayer invocation){
        var a=ACTIVE.get();if(a==null)return receiver.saveWithoutId(input);
        a.check();if(a.list!=list || a.player!=invocation || receiver!=invocation || input.getClass()!=CompoundTag.class || !input.isEmpty())throw a.refuse("Foreign native cached-player serialization");
        a.trace.cacheEnter();CompoundTag actual=receiver.saveWithoutId(input);
        a.cachedDigest=digest(actual,MAX_PLAYER_BYTES);a.cached=actual;
        OrdinarySavePayload.requireEscrow(actual,a.player.getUUID(),a.session,a.player.registryAccess());
        a.check();a.trace.cacheDone();return actual;
    }
    static void statsEnter(ServerStatsCounter source){var a=ACTIVE.get();if(a!=null){a.check();if(source!=a.stats)throw a.refuse("Foreign stats save");a.trace.statsEnter();}}
    static void statsWrite(ServerStatsCounter source,File file,String text) throws IOException {
        var a=ACTIVE.get();if(a==null){FileUtils.writeStringToFile(file,text);return;}
        a.check();if(source!=a.stats || !file.toPath().toAbsolutePath().normalize().equals(a.statsFile) || text.length()>MAX_TEXT_CHARS || a.statsBytes!=null)throw a.refuse("Foreign/unbounded/duplicate stats write");
        requirePlainTarget(a.statsFile);a.statsBytes=text.getBytes(StandardCharsets.UTF_8);
        try{FileUtils.writeStringToFile(file,text);if(a.statsRootKey==null)a.statsRootKey=directoryKey(a.statsFile.getParent());requireText(a.statsFile,a.statsBytes);a.check();a.trace.statsWritten();}
        catch(IOException | RuntimeException failure){a.poison(failure);throw failure;}
    }
    static void statsExit(ServerStatsCounter source){var a=ACTIVE.get();if(a!=null){a.check();if(source!=a.stats)throw a.refuse("Foreign stats exit");a.trace.statsDone();}}
    static void advancementsEnter(PlayerAdvancements source){var a=ACTIVE.get();if(a!=null){a.check();if(source!=a.advancements)throw a.refuse("Foreign advancement save");a.trace.advancementsEnter();}}
    static void advancementDirectory(PlayerAdvancements source,Path directory) throws IOException {
        var a=ACTIVE.get();if(a==null){net.minecraft.FileUtil.createDirectoriesSafe(directory);return;}
        try {a.check();if(source!=a.advancements || !directory.toAbsolutePath().normalize().equals(a.advancementsFile.getParent()))throw a.refuse("Foreign advancement directory");
            net.minecraft.FileUtil.createDirectoriesSafe(directory);
            if(a.advancementsRootKey==null)a.advancementsRootKey=directoryKey(a.advancementsFile.getParent());a.check();
        }catch(IOException | RuntimeException failure){a.poison(failure);throw failure;}
    }
    static BufferedWriter advancementsWriter(PlayerAdvancements source,Path path,Charset charset,OpenOption[] options) throws IOException {
        var a=ACTIVE.get();if(a==null)return Files.newBufferedWriter(path,charset,options);
        a.check();if(source!=a.advancements || !path.toAbsolutePath().normalize().equals(a.advancementsFile)
                || !charset.equals(StandardCharsets.UTF_8) || options.length!=0 || a.advancementWriter!=null)throw a.refuse("Foreign/duplicate advancement writer");
        requirePlainTarget(a.advancementsFile);
        a.advancementWriter=new BoundedWriter(a,Files.newBufferedWriter(path,charset,options));return a.advancementWriter;
    }
    static void advancementsExit(PlayerAdvancements source){var a=ACTIVE.get();if(a!=null){a.check();if(source!=a.advancements || a.advancementWriter==null || !a.advancementWriter.closed)throw a.refuse("Missing original advancement close");a.trace.advancementsDone();}}
    static void levelCodec(PrimaryLevelData source,com.mojang.serialization.DataResult<?> result){
        var a=ACTIVE.get();if(a==null)return;a.check();
        if(source!=a.worldData || a.trace.step()!=InstalledOrdinarySaveTrace.Step.LIST_DONE || ++a.levelCodecResults>3)throw a.refuse("Foreign/repeated native level codec result");
        // Do not invoke Error.message() suppliers or turn partial/error values into successful data.
        // Preserve the exact native result so its original error/log branch remains original.
        if(result==null || result.getClass().getClassLoader()!=com.mojang.serialization.DataResult.class.getClassLoader()
                || !result.getClass().getName().equals("com.mojang.serialization.DataResult$Success"))
            a.poison(new SurvivalRefusal("Native level generation codec returned error/partial/foreign result"));
    }
    static void levelEnter(LevelStorageSource.LevelStorageAccess source,CompoundTag tag){
        var a=ACTIVE.get();if(a==null)return;a.check();if(source!=a.storage || a.levelDigest!=null || a.levelCodecResults!=3)throw a.refuse("Foreign/duplicate native level save or missing codec evidence");
        a.requireCached();if(!tag.getCompound("Data").contains("WorldGenSettings",Tag.TAG_COMPOUND)
                || !tag.getCompound("Data").getCompound("Player").equals(a.cached))throw a.refuse("Native level tag lacks ordinary cached player");
        a.levelDigest=digest(tag,MAX_LEVEL_BYTES);a.levelTag=tag;a.trace.levelEnter();
    }
    static void levelWrite(LevelStorageSource.LevelStorageAccess source,CompoundTag tag,Path temporary) throws IOException {
        var a=ACTIVE.get();if(a==null){NbtIo.writeCompressed(tag,temporary);return;}
        try { a.check();if(source!=a.storage || a.levelTemporary!=null || !temporary.toAbsolutePath().normalize().getParent().equals(a.root)
                || !temporary.getFileName().toString().startsWith("level") || !temporary.getFileName().toString().endsWith(".dat")
                || !Files.isRegularFile(temporary,LinkOption.NOFOLLOW_LINKS) || !Objects.equals(a.levelDigest,digest(tag,MAX_LEVEL_BYTES)))throw a.refuse("Foreign level temporary/payload");
        a.levelTemporary=temporary;NbtIo.writeCompressed(tag,temporary);a.check();a.trace.levelWritten();
        } catch(IOException | RuntimeException failure){a.poison(failure);throw failure;}
    }
    static void levelReplace(LevelStorageSource.LevelStorageAccess source,Path current,Path temporary,Path backup){
        var a=ACTIVE.get();if(a==null){Util.safeReplaceFile(current,temporary,backup);return;}
        try { a.check();if(source!=a.storage || !current.toAbsolutePath().normalize().equals(a.levelFile) || !temporary.equals(a.levelTemporary) || !backup.toAbsolutePath().normalize().equals(a.levelBackup))throw a.refuse("Foreign native level replacement paths");
        requirePlainTarget(current);requirePlainTarget(temporary);requirePlainTarget(backup);
        boolean success=Util.safeReplaceOrMoveFile(current,temporary,backup,false);a.trace.levelReplaced(success);a.check();
        } catch(RuntimeException failure){a.poison(failure);throw failure;}
    }
    static void levelExit(LevelStorageSource.LevelStorageAccess source){var a=ACTIVE.get();if(a!=null){a.check();if(source!=a.storage)throw a.refuse("Foreign level save exit");a.trace.levelDone();}}
    static void caught(Object source){var a=ACTIVE.get();if(a!=null)a.poison(new SurvivalRefusal("Native installed save caught an exception; original owner="+(source==a.stats?"stats":source==a.advancements?"advancements":source==a.storage?"level.dat":"foreign")));}

    private void requireCached(){if(cached==null || raw(IntegratedPlayerList.class,list,"playerData")!=cached || !Objects.equals(cachedDigest,digest(cached,MAX_PLAYER_BYTES)))throw refuse("Actual integrated cached tag changed");}
    private void check(){
        if(Thread.currentThread()!=owner || checking || trace.failure()!=null
                || ACTIVE.get()!=this && (trace.step()!=InstalledOrdinarySaveTrace.Step.VERIFIED || ACTIVE.get()!=null))throw refuse("Installed native save ownership/reentry failure");
        checking=true;
        try {
            requireAuthority.run();token.requireOrdinarySave(gate,player);
            if(player.server.getPlayerList()!=list || player.server.getWorldData()!=worldData || player.server.registryAccess()!=registries || player.registryAccess()!=registries
                    || ((InstalledSessionServerSaveAccess)player.server).earthplatform$storageSource()!=storage
                    || ownedEntry(raw(PlayerList.class,list,"stats"),ServerStatsCounter.class)!=stats
                    || ownedEntry(raw(PlayerList.class,list,"advancements"),PlayerAdvancements.class)!=advancements)
                throw refuse("Installed save backing owners changed");
            if(!rootKey.equals(directoryKey(root)))throw refuse("Installed save root changed");
            requireDirectory(statsFile.getParent(),statsRootKey);requireDirectory(advancementsFile.getParent(),advancementsRootKey);
            requireAuthority.run();if(trace.failure()!=null)throw refuse("Installed save authority was reentered");
        }catch(IOException failure){poison(failure);throw withCause("Installed save directory verification failed",failure);}
        finally{checking=false;}
    }
    private <T> T ownedEntry(Object raw,Class<T> type){
        if(raw==null || raw.getClass()!=HashMap.class)throw refuse("Exact native save owner map required before collection calls");
        var map=(HashMap<?,?>)raw;if(map.size()!=1)throw refuse("Sole native save owner required");
        Object value=map.get(player.getUUID());if(value==null || value.getClass()!=type)throw refuse("Exact native save receiver required");return type.cast(value);
    }
    private static Object raw(Class<?> type,Object receiver,String name){try{var f=type.getDeclaredField(name);if(!f.trySetAccessible())throw new ReflectiveOperationException();return f.get(receiver);}catch(ReflectiveOperationException failure){throw withCause("Native installed save field unavailable: "+type.getName()+"#"+name,failure);}}
    private static Object optionalDirectoryKey(Path path) throws IOException {return Files.exists(path,LinkOption.NOFOLLOW_LINKS)?directoryKey(path):null;}
    private static void requireDirectory(Path path,Object key) throws IOException {
        if(key==null){if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected native save directory creation");}
        else if(!key.equals(directoryKey(path)))throw new IOException("Native save directory replaced");
    }
    private static Object directoryKey(Path path) throws IOException {
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath()))throw new IOException("Canonical native save directory required");
        return Objects.requireNonNull(Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey());
    }
    private static void requirePlainTarget(Path path){if(Files.exists(path,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new SurvivalRefusal("Native save target is linked or non-regular");}
    private static void requireText(Path path,byte[] expected) throws IOException {
        if(expected==null || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=expected.length)throw new IOException("Missing/unbounded native text save");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] chunk=new byte[8192];int offset=0,n;while((n=input.read(chunk))!=-1){if(offset+n>expected.length)throw new IOException("Native text grew during readback");for(int i=0;i<n;i++)if(chunk[i]!=expected[offset+i])throw new IOException("Native text readback differs");offset+=n;}if(offset!=expected.length)throw new IOException("Native text shrank during readback");}
    }
    private static CompoundTag readNbt(Path path,int maxBytes,long maxHeap) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>maxBytes+65536L)throw new IOException("Installed readback compressed bound");
        try(var raw=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);
            var gzip=new java.util.zip.GZIPInputStream(new LimitedInput(raw,maxBytes+65536L));
            var input=new DataInputStream(new LimitedInput(gzip,maxBytes))){
            CompoundTag tag=NbtIo.read(input,new NbtAccounter(maxHeap,32));
            if(input.read()!=-1)throw new IOException("Trailing installed NBT");digest(tag,maxBytes);return tag;
        }
    }
    private static final class LimitedInput extends FilterInputStream {
        private long remaining;LimitedInput(InputStream input,long limit){super(input);remaining=limit;}
        @Override public int read()throws IOException{int value=in.read();if(value>=0 && remaining--<=0)throw new IOException("Installed level read bound");return value;}
        @Override public int read(byte[] bytes,int start,int length)throws IOException{if(length==0)return 0;int count=in.read(bytes,start,(int)Math.min(length,remaining+1));if(count>0 && (remaining-=count)<0)throw new IOException("Installed level read bound");return count;}
        @Override public long skip(long amount)throws IOException{long count=0;while(count<amount && read()>=0)count++;return count;}
    }
    private static String digest(CompoundTag tag,int maxBytes){
        BoundedOrdinarySaveNbt.validate(tag);new NodeBudget(maxBytes==MAX_PLAYER_BYTES?2048:8192).visit(tag,0);
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");
            OutputStream bounded=new OutputStream(){int count;public void write(int value){if(++count>maxBytes)throw new SurvivalRefusal("Installed native NBT byte bound");digest.update((byte)value);}public void write(byte[] data,int start,int length){if(length<0 || (long)count+length>maxBytes)throw new SurvivalRefusal("Installed native NBT byte bound");count+=length;digest.update(data,start,length);}};
            try(var output=new DataOutputStream(bounded)){NbtIo.write(tag,output);}return HexFormat.of().formatHex(digest.digest());
        }catch(IOException | NoSuchAlgorithmException failure){throw withCause("Cannot encode installed native save evidence",failure);}
    }
    private static final class NodeBudget {
        private final int maximum;private int nodes;NodeBudget(int maximum){this.maximum=maximum;}
        void visit(Tag tag,int depth){if(depth>=32 || ++nodes>maximum)throw new SurvivalRefusal("Installed NBT node/depth bound");
            if(tag instanceof CompoundTag compound){if(compound.size()>maximum-nodes)throw new SurvivalRefusal("Installed NBT compound bound");for(String key:compound.getAllKeys())visit(compound.get(key),depth+1);}
            else if(tag instanceof ListTag list){if(list.size()>maximum-nodes)throw new SurvivalRefusal("Installed NBT list bound");for(Tag child:list)visit(child,depth+1);}
        }
    }
    private static SurvivalRefusal withCause(String message,Throwable cause){var failure=new SurvivalRefusal(message);failure.initCause(cause);return failure;}
    private SurvivalRefusal refuse(String reason){var failure=new SurvivalRefusal(reason);poison(failure);return failure;}
    private static void pinSources(){
        // Installed-only originals; the private token was authenticated before this call.
        // Do not accept development resources or select a profile after a digest mismatch.
        pin(com.mojang.serialization.DataResult.class,"8e87bd250ba5f55a8f236b85630e1d5b84cfcc1e2126a1e609dc32d987d10f89");
        try{pin(Class.forName("com.mojang.serialization.DataResult$Success",false,com.mojang.serialization.DataResult.class.getClassLoader()),"fde43d4fddbb650edea7c36410ead89920b125ae27c0ce624aed27db345f24b8");}
        catch(ClassNotFoundException failure){throw withCause("Missing exact native DataResult.Success",failure);}
        pin(net.minecraft.server.players.PlayerList.class,"c7ed33824878b591e2c8440d392b1a2fbecb152e51c0873ae02bf148e43de430");
        pin(net.minecraft.client.server.IntegratedPlayerList.class,"0aaf4d99d16b08f670b1a541207224a57ba65215b944c64818bf621332473641");
        pin(net.minecraft.server.MinecraftServer.class,"3f1b7a260a249ae836d9d71bf0a0fe7575b62aff48f2b8fac10dd06aaa9efd2f");
        pin(net.minecraft.client.server.IntegratedServer.class,"26a16a6055bb148e1ffbe0d73bf74c38b710046a96f50600f3ae6b38a618bf5a");
        pin(net.minecraft.stats.StatsCounter.class,"e769405f3dd11b371e307efe0e55cb60f59a6190cb3f501fcd1eb50f6c48a449");
        pin(net.minecraft.stats.ServerStatsCounter.class,"8ef3ddbd1284d79be53c31301371df73c888d02b7e87c8ab7c6650ac8b0bd518");
        pin(net.minecraft.server.PlayerAdvancements.class,"ecf6ba63053dfed0581cad07cd383aa34a6dc8abc6209f2e695624f09590f03a");
        pin(net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess.class,"d6cc03db1b690e97f316388e0dc740c6300db856ecfeab5bacd161d7aacccd52");
        pin(net.minecraft.world.level.storage.PrimaryLevelData.class,"b5eac29d33c7536e1dfd5a66bfbf59f95bf92f8a2ce9f9d86eeddcd1f0feb36e");
        pin(net.neoforged.neoforge.common.CommonHooks.class,"86c8a56c77fff474bf4fe90f0354ad32698f018b5e19dc52a2725ee5ff96c73e");
    }
    private static void pin(Class<?> type,String expected){try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
        if(in==null)throw new IOException("Missing class");MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] chunk=new byte[8192];int total=0,n;
        while((n=in.read(chunk))!=-1){if((total+=n)>2*1024*1024)throw new IOException("Class bound");digest.update(chunk,0,n);}
        if(!expected.equals(HexFormat.of().formatHex(digest.digest())))throw new IOException("Class digest differs");
    }catch(IOException | NoSuchAlgorithmException failure){throw withCause("Installed save native source differs: "+type.getName(),failure);}}

    private static final class BoundedWriter extends BufferedWriter {
        private final InstalledOrdinarySaveEnvelope a;private final BufferedWriter actual;private final StringBuilder text=new StringBuilder();private boolean closed;
        BoundedWriter(InstalledOrdinarySaveEnvelope a,BufferedWriter actual){super(actual);this.a=a;this.actual=actual;}
        private void append(String value){a.check();if(closed || (long)text.length()+value.length()>MAX_TEXT_CHARS)throw a.refuse("Advancement writer closed or exceeds bound");text.append(value);}
        @Override public void write(int value)throws IOException{append(String.valueOf((char)value));try{actual.write(value);}catch(IOException failure){a.poison(failure);throw failure;}}
        @Override public void write(char[] value,int start,int length)throws IOException{Objects.checkFromIndexSize(start,length,value.length);if(length>MAX_TEXT_CHARS)throw a.refuse("Advancement writer bound");String slice=new String(value,start,length);append(slice);try{actual.write(value,start,length);}catch(IOException failure){a.poison(failure);throw failure;}}
        @Override public void write(String value,int start,int length)throws IOException{Objects.checkFromIndexSize(start,length,value.length());if(length>MAX_TEXT_CHARS)throw a.refuse("Advancement writer bound");append(value.substring(start,start+length));try{actual.write(value,start,length);}catch(IOException failure){a.poison(failure);throw failure;}}
        @Override public void flush()throws IOException{a.check();try{actual.flush();}catch(IOException failure){a.poison(failure);throw failure;}}
        @Override public void close()throws IOException{if(closed)return;try{actual.close();closed=true;a.advancementsBytes=text.toString().getBytes(StandardCharsets.UTF_8);requireText(a.advancementsFile,a.advancementsBytes);a.check();a.trace.advancementsWritten();}catch(IOException | RuntimeException failure){a.poison(failure);throw failure;}}
    }
}
