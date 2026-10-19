package eptest;

import com.google.gson.*;
import java.io.*;
import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.objects.*;

/** Local native corpus and exact current-thread hit allocation counters; never packaged. */
public final class FaceCacheProbe {
 private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
 private static Object faces;
 private static MethodHandle faceCall,tintCall;
 private static final List<BlockState> states=List.of(Blocks.STONE.defaultBlockState(),Blocks.DIRT.defaultBlockState(),Blocks.GRASS_BLOCK.defaultBlockState(),Blocks.OAK_LEAVES.defaultBlockState(),Blocks.BIRCH_LEAVES.defaultBlockState(),Blocks.SPRUCE_LEAVES.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.Z),Blocks.SAND.defaultBlockState(),Blocks.GRAVEL.defaultBlockState(),Blocks.CLAY.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,7),Blocks.LAVA.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED,true),Blocks.GLASS.defaultBlockState(),Blocks.RED_MUSHROOM_BLOCK.defaultBlockState(),Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,7),Blocks.GLOWSTONE.defaultBlockState());
 private static final List<Holder<Biome>> biomes=new ArrayList<>();
 private static final List<BlockState> faceStates=new ArrayList<>(),tintStates=new ArrayList<>();
 private static final List<Direction> faceDirections=new ArrayList<>();
 private static final List<Object> faceExpected=new ArrayList<>(),tintFaces=new ArrayList<>();
 private static final List<Holder<Biome>> tintBiomes=new ArrayList<>();
 private static int[] tintExpected;
 private static long sink;
 private static MethodHandle method(Class<?> c,String name,Class<?>...args)throws Exception{var m=c.getDeclaredMethod(name,args);m.setAccessible(true);return MethodHandles.lookup().unreflect(m);}
 private static Object construct(Class<?> c)throws Exception{var ctor=c.getDeclaredConstructor();ctor.setAccessible(true);return ctor.newInstance();}
 public static void oracle(Minecraft mc,String mode,Path out,Path reference)throws Exception{
  try{
   Class<?> f=Class.forName("io.earthplatform.minecraft.cubic.client.FarFaces"),fc=Class.forName(f.getName()+"$Face"),l=Class.forName("io.earthplatform.minecraft.cubic.client.FarLooks");
   faces=construct(f);Object looks=construct(l);faceCall=method(f,"face",BlockState.class,Direction.class).asType(MethodType.methodType(Object.class,Object.class,BlockState.class,Direction.class));tintCall=method(f,"tint",BlockState.class,Holder.class,fc).asType(MethodType.methodType(int.class,Object.class,BlockState.class,Holder.class,Object.class));
   var id=method(l,"id",f,BlockState.class,Direction.class,Holder.class,boolean.class,Object2IntMap.class).asType(MethodType.methodType(int.class,Object.class,Object.class,BlockState.class,Direction.class,Holder.class,boolean.class,Object2IntMap.class));
   var tintIndex=method(fc,"tint").asType(MethodType.methodType(int.class,Object.class));
   var count=method(l,"count").asType(MethodType.methodType(int.class,Object.class));var copy=method(l,"copy",int.class,int.class).asType(MethodType.methodType(int[].class,Object.class,int.class,int.class));
   var registry=mc.level.registryAccess().registryOrThrow(Registries.BIOME);biomes.clear();for(var key:List.of(Biomes.PLAINS,Biomes.SWAMP,Biomes.BADLANDS,Biomes.SNOWY_PLAINS))biomes.add(registry.getHolderOrThrow(key));biomes.add(null);
   Object2IntOpenHashMap<BlockState> ids=new Object2IntOpenHashMap<>(),otherIds=new Object2IntOpenHashMap<>();for(int i=0;i<states.size();i++){ids.put(states.get(i),i*17-5);otherIds.put(states.get(i),i*19+3);}
   var maps=Arrays.<Object2IntMap<BlockState>>asList(null,ids,otherIds);var hashes=new ArrayList<String>();int calls=0;
   faceStates.clear();faceDirections.clear();faceExpected.clear();tintStates.clear();tintFaces.clear();tintBiomes.clear();var expectedTint=new ArrayList<Integer>();
   for(int repeat=0;repeat<2;repeat++){
    var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
    for(BlockState state:states)for(Direction direction:Direction.values()){
     Object face=(Object)faceCall.invokeExact(faces,state,direction);
     if(repeat==0){faceStates.add(state);faceDirections.add(direction);faceExpected.add(face);}
     for(Holder<Biome> biome:biomes){
      int tint=(int)tintCall.invokeExact(faces,state,biome,face);data.writeInt(tint);
      if(repeat==0&&(int)tintIndex.invokeExact(face)!=-1){tintStates.add(state);tintFaces.add(face);tintBiomes.add(biome);expectedTint.add(tint);}
      for(boolean textured:new boolean[]{false,true})for(Object2IntMap<BlockState> map:maps){data.writeInt((int)id.invokeExact(looks,faces,state,direction,biome,textured,map));calls++;}
     }
    }
    int size=(int)count.invokeExact(looks);data.writeInt(size);int[] packed=(int[])copy.invokeExact(looks,0,size);for(int n:packed)data.writeInt(n);hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
   }
   if(!hashes.get(0).equals(hashes.get(1)))throw new AssertionError("cold/warm look IDs, colors or packed values differ");
   tintExpected=expectedTint.stream().mapToInt(Integer::intValue).toArray();var result=new LinkedHashMap<String,Object>();result.put("hashes",hashes);result.put("states",states.size());result.put("directions",6);result.put("biomes_including_null",biomes.size());result.put("shader_id_maps",3);result.put("look_calls",calls);result.put("look_count",(int)count.invokeExact(looks));result.put("exact_cold_warm",true);result.put("matches_reference",!mode.equals("baseline"));
   if(!mode.equals("baseline")){var expected=JSON.fromJson(Files.readString(reference),JsonObject.class);if(!expected.getAsJsonArray("hashes").equals(JSON.toJsonTree(hashes)))throw new AssertionError("face/tint/look corpus differs from original");}
   Files.writeString(out.resolve("face-oracle-"+mode+".json"),JSON.toJson(result));
  }catch(Throwable t){if(t instanceof Exception e)throw e;throw new RuntimeException(t);}
 }
 private static void faceLoop(int n)throws Throwable{long value=0;for(int i=0;i<n;i++){int j=i%faceStates.size();Object face=(Object)faceCall.invokeExact(faces,faceStates.get(j),faceDirections.get(j));if(face!=faceExpected.get(j))throw new AssertionError("face cache changed identity");value+=System.identityHashCode(face);}sink=value;}
 private static void tintLoop(int n)throws Throwable{long value=0;for(int i=0;i<n;i++){int j=i%tintStates.size();int tint=(int)tintCall.invokeExact(faces,tintStates.get(j),tintBiomes.get(j),tintFaces.get(j));if(tint!=tintExpected[j])throw new AssertionError("tint changed");value+=tint;}sink=value;}
 public static void bench(Path out,String label)throws Exception{
  try{
   if(faces==null)throw new IllegalStateException("corpus first");var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();if(!bean.isThreadAllocatedMemorySupported())throw new IllegalStateException("allocation counters unavailable");bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId();faceLoop(300000);tintLoop(300000);var result=new LinkedHashMap<String,Object>();int n=1000000;
   for(String kind:List.of("face","tint")){long allocated=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();if(kind.equals("face"))faceLoop(n);else tintLoop(n);long end=System.nanoTime(),bytes=bean.getThreadAllocatedBytes(thread)-allocated;result.put(kind,Map.of("hits",n,"allocated_bytes",bytes,"bytes_per_hit",(double)bytes/n,"elapsed_ms",(end-start)/1e6,"checksum",sink));}
   result.put("tint_cases_cached_and_tinted",tintStates.size());result.put("component_only_not_frame_gain",true);Files.writeString(out.resolve("face-bench-"+label+".json"),JSON.toJson(result));
  }catch(Throwable t){if(t instanceof Exception e)throw e;throw new RuntimeException(t);}
 }
}
