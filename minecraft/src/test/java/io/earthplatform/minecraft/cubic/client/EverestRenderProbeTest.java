package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.geography.globe.*;
import io.earthplatform.minecraft.cubic.earth.*;
import io.earthplatform.minecraft.cubic.gen.*;
import io.earthplatform.minecraft.cubic.far.Far;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Offline investigation using a COPY of a local world's settings and cached Everest data. */
class EverestRenderProbeTest {
    @Test @EnabledIfEnvironmentVariable(named="EARTH_EVEREST_REPRO", matches=".+")
    void inspectCachedGlacierColumns() throws Exception {
        Path root=Path.of(System.getenv("EARTH_EVEREST_REPRO")), cache=root.resolve("cache");
        Properties pins=new Properties();
        try(var reader=Files.newBufferedReader(root.resolve("generator.properties"))) {pins.load(reader);}
        CompoundTag data=NbtIo.readCompressed(root.resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompound("Data");
        long seed=data.getCompound("WorldGenSettings").getLong("seed");
        var player=data.getCompound("Player");
        ListTag position=player.getList("Pos",Tag.TAG_DOUBLE), rotation=player.getList("Rotation",Tag.TAG_FLOAT);
        double cameraX=position.getDouble(0),cameraY=position.getDouble(1),cameraZ=position.getDouble(2);
        float yaw=rotation.getFloat(0),pitch=rotation.getFloat(1);
        System.out.printf(Locale.ROOT,"EVEREST_CAMERA %.3f %.3f %.3f yaw=%.3f pitch=%.3f seed=%d%n",cameraX,cameraY,cameraZ,yaw,pitch,seed);
        if(Files.isDirectory(root.resolve("entities"))) {
            Map<String,Integer> entityCounts=new TreeMap<>();int falling=0;
            try(var files=Files.list(root.resolve("entities"))) {for(var file:files.toList()) {
                byte[] bytes=Files.readAllBytes(file);if(bytes.length<8192)continue;
                var offsets=java.nio.ByteBuffer.wrap(bytes);
                for(int i=0;i<1024;i++) {
                    int offset=(offsets.getInt(i*4)>>>8)*4096;if(offset==0||offset+5>=bytes.length)continue;
                    int length=offsets.getInt(offset);if(length<2||offset+4+length>bytes.length)continue;
                    var stream=new java.io.ByteArrayInputStream(bytes,offset+5,length-1);
                    java.io.InputStream decoded=bytes[offset+4]==1?new java.util.zip.GZIPInputStream(stream):new java.util.zip.InflaterInputStream(stream);
                    CompoundTag tag=NbtIo.read(new java.io.DataInputStream(decoded),NbtAccounter.unlimitedHeap());
                    for(Tag entry:tag.getList("Entities",Tag.TAG_COMPOUND)) {
                        var entity=(CompoundTag)entry;String id=entity.getString("id");entityCounts.merge(id,1,Integer::sum);
                        if(id.equals("minecraft:falling_block")) {
                            falling++;if(falling<=25)System.out.println("EVEREST_FALLING pos="+entity.getList("Pos",Tag.TAG_DOUBLE)+" state="+entity.getCompound("BlockState")+" time="+entity.getInt("Time"));
                        }
                    }
                }
            }}
            System.out.println("EVEREST_ENTITIES "+entityCounts);
        }
        var biomes=SurfacePacketFixtures.biomes();
        // Supply the mod biome in the same registry used for real surface generation.
        var registry=new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(net.minecraft.core.registries.Registries.BIOME,com.mojang.serialization.Lifecycle.stable());
        biomes.entrySet().forEach(h->registry.register(h.getKey(),h.getValue(),net.minecraft.core.RegistrationInfo.BUILT_IN));
        try(var in=getClass().getResourceAsStream("/data/earthplatform/worldgen/biome/glacial_lake.json")) {
            var json=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
            var lookup=net.minecraft.data.registries.VanillaRegistries.createLookup();
            var biome=net.minecraft.world.level.biome.Biome.DIRECT_CODEC.parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,lookup),json).getOrThrow();
            registry.register(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform","glacial_lake")),biome,net.minecraft.core.RegistrationInfo.BUILT_IN);
        }
        registry.freeze();
        Duration timeout=Duration.ofSeconds(2);
        var copernicus=new CopernicusSource(CopernicusSource.AWS_COPERNICUS,cache.resolve(CopernicusSource.DATASET_ID),false,12,2,timeout);
        TerrariumTileSource[] made=new TerrariumTileSource[1];
        var tiles=new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES,cache.resolve(TerrariumTileSource.DATASET_ID),false,512,2,timeout,
                List.of(new CopernicusFill(copernicus,(z,x,y)->made[0].cleaned(z,x,y),512)));
        made[0]=tiles;
        var cover=new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER,cache.resolve(WorldCoverSource.DATASET_ID),false,64,2,timeout);
        var climate=new ClimateSource(ClimateSource.CHELSA,cache.resolve(ClimateSource.DATASET_ID),false,320,2,timeout);
        var water=new SurfaceWaterSource(SurfaceWaterSource.JRC_GSW,cache.resolve(SurfaceWaterSource.DATASET_ID),false,96,2,timeout);
        var drainageTiles=new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES,cache.resolve(TerrariumTileSource.DATASET_ID),false,64,2,timeout);
        var rivers=new RiverMap(water,new Drainage(drainageTiles,climate::precipitation,24),256);
        var settings=new EarthSettings(Double.parseDouble(pins.getProperty("earth.latitude")),Double.parseDouble(pins.getProperty("earth.longitude")),Double.parseDouble(pins.getProperty("earth.metresPerBlock")));
        var earth=new EarthGenerator(seed,registry,settings,tiles,cover,climate,null,rivers,new BareEarth(tiles,cover,cache.resolve(BareEarth.ID)));
        var storage=new io.earthplatform.minecraft.cubic.CubeStorage(root.resolve("cubes"));
        var server=new io.earthplatform.minecraft.cubic.far.FarServer(earth,storage,new io.earthplatform.minecraft.cubic.CubeCodec(registry),registry);
        var fold=server.getClass().getDeclaredMethod("foldSavedCubes",int.class,int.class,int.class,FarColumn.class); fold.setAccessible(true);
        if("1".equals(System.getenv("EARTH_EVEREST_SCAN"))) {
            Set<Long> edited=new TreeSet<>();
            storage.forEachSaved((key,tag)->{if(!tag.getBoolean(io.earthplatform.minecraft.cubic.CubeCodec.UNTOUCHED))edited.add(Far.key(4,io.earthplatform.minecraft.cubic.CubeKey.x(key)>>4,io.earthplatform.minecraft.cubic.CubeKey.z(key)>>4));});
            int changed=0,raised=0;
            for(long key:edited) {
                FarColumn column=new FarColumn();earth.farColumn(4,Far.x(key),Far.z(key),column);
                double[] before=heights(column,4);fold.invoke(server,4,Far.x(key),Far.z(key),column);double[] after=heights(column,4);
                for(int i=0;i<256;i++)if(before[i]!=after[i]) {
                    changed++;if(after[i]>before[i])raised++;
                    if(changed<=40)System.out.println("EVEREST_SAVED_DELTA x="+(Far.x(key)*256+(i&15)*16)+" z="+(Far.z(key)*256+(i>>4)*16)+" before="+before[i]+" after="+after[i]);
                }
            }
            System.out.println("EVEREST_SAVED_SCAN columns="+edited.size()+" changed="+changed+" raised="+raised);
        }
        double angle=Math.toRadians(yaw);
        double distance=256;
        for(;distance<8000;distance+=64) {
            int x=(int)(cameraX-Math.sin(angle)*distance),z=(int)(cameraZ+Math.cos(angle)*distance);
            if(cameraY+1.62-Math.tan(Math.toRadians(pitch))*distance<=earth.surfaceY(x,z))break;
        }
        int targetX=(int)(cameraX-Math.sin(angle)*distance),targetZ=(int)(cameraZ+Math.cos(angle)*distance);
        System.out.println("EVEREST_TARGET x="+targetX+" z="+targetZ+" horizontal="+distance);
        for(int level:new int[]{3,4,5}) {
            var jobs=new ArrayList<FarMesher.Job>(); var looks=new FarLooks();var faces=EverestProbeImage.faces();
            int v=1<<level,width=Far.width(level),radius=2,side=(radius*2+1)*16;
            int startX=Math.floorDiv(targetX,width)-radius,startZ=Math.floorDiv(targetZ,width)-radius;
            double[] heights=new double[side*side]; String[] states=new String[side*side];
            Map<String,Integer> counts=new TreeMap<>();
            for(int cz=0;cz<=2*radius;cz++)for(int cx=0;cx<=2*radius;cx++) {
                FarColumn column=new FarColumn(); earth.farColumn(level,startX+cx,startZ+cz,column);
                if(level<=Far.EDIT_LEVELS) fold.invoke(server,level,startX+cx,startZ+cz,column);
                var sections=column.sections.toArray(net.minecraft.world.level.chunk.LevelChunkSection[]::new);
                char[] drops=new char[256];for(int i=0;i<256;i++)drops[i]=(char)(column.drops[i]&65535);
                jobs.add(new FarMesher.Job(1,1,level,jobs.size(),(startX+cx)*width,(startZ+cz)*width,column.bottom,sections,drops,null,null,null,false,
                        looks,faces,null,new float[]{.5f,1,.8f,.8f,.6f,.6f},new float[]{0,0,1,1}));
                for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                    int row=FarMesher.top(sections,x,z,false),at=(cz*16+z)*side+cx*16+x;
                    BlockState state=FarMesher.at(sections,row,x,z);
                    String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    states[at]=name; counts.merge(name,1,Integer::sum);
                    heights[at]=(column.bottom*16+row+1)*(double)v-(column.drops[z*16+x]&65535)/(double)FarMesher.UNIT;
                }
            }
            List<String> csv=new ArrayList<>(List.of("x,z,top,state,localRise")); int spikes=0;
            for(int z=1;z<side-1;z++)for(int x=1;x<side-1;x++) {
                int at=z*side+x; double highest=Double.NEGATIVE_INFINITY;
                for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)if(dx!=0||dz!=0)highest=Math.max(highest,heights[at+dz*side+dx]);
                double rise=heights[at]-highest;
                if(rise>v*.5) {spikes++;System.out.printf(Locale.ROOT,"EVEREST_SPIKE level=%d x=%d z=%d top=%.3f rise=%.3f state=%s%n",level,(startX*16+x)*v,(startZ*16+z)*v,heights[at],rise,states[at]);}
                csv.add(String.format(Locale.ROOT,"%d,%d,%.3f,%s,%.3f",(startX*16+x)*v,(startZ*16+z)*v,heights[at],states[at],rise));
            }
            Files.write(root.resolve("saved-surface-level-"+level+".csv"),csv);
            System.out.println("EVEREST_MATERIALS level="+level+" isolated="+spikes+" "+counts);
            if("1".equals(System.getenv("EARTH_GL_TESTS")))
                EverestProbeImage.render(root.resolve("packed-level-"+level+".png"),jobs,cameraX,cameraY+1.62,cameraZ,yaw,pitch,10);
        }
    }
    private static double[] heights(FarColumn column,int level) {
        double[] result=new double[256];var sections=column.sections.toArray(net.minecraft.world.level.chunk.LevelChunkSection[]::new);
        for(int i=0;i<256;i++)result[i]=(column.bottom*16+FarMesher.top(sections,i&15,i>>4,false)+1)*(double)(1<<level)-(column.drops[i]&65535)/(double)FarMesher.UNIT;
        return result;
    }
}
