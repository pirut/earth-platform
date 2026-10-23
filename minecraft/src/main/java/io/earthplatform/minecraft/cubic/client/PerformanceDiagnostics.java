package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.Cubic;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.earthplatform.minecraft.cubic.Timings;
import io.earthplatform.minecraft.cubic.diagnostics.*;
import java.time.Instant;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.*;

/** Current build. Fixed-cost frame capture, bounded async local-only JSONL. */
@EventBusSubscriber(modid=Cubic.MOD_ID,value=Dist.CLIENT)
public final class PerformanceDiagnostics {
    private static State state;
    private PerformanceDiagnostics() {}
    private static final class State {
        final Gson gson=new Gson();
        final FrameWindow frames=new FrameWindow();
        final LocalPerfLog log;
        long lastFrame,nextLog,windowStart;
        boolean warned;
        final Map<Timings,Timings.Total> previous=new IdentityHashMap<>();
        State(Minecraft mc,long now) {
            nextLog=now+5_000_000_000L;windowStart=now;
            JsonObject record=base("build");
            record.addProperty("commit",BuildInfo.commit());record.addProperty("version",BuildInfo.version());
            record.addProperty("flavor","current");record.addProperty("java",clean(System.getProperty("java.version")));
            record.addProperty("os",clean(System.getProperty("os.name")));record.addProperty("os_version",clean(System.getProperty("os.version")));
            record.addProperty("architecture",clean(System.getProperty("os.arch")));record.addProperty("hardware_threads",Runtime.getRuntime().availableProcessors());
            record.addProperty("max_heap_mib",Runtime.getRuntime().maxMemory()>>20);
            record.addProperty("gpu_vendor",clean(GL11C.glGetString(GL11C.GL_VENDOR)));
            record.addProperty("gpu_renderer",clean(GL11C.glGetString(GL11C.GL_RENDERER)));
            record.addProperty("gl_version",clean(GL11C.glGetString(GL11C.GL_VERSION)));
            var caps=GL.getCapabilities();
            boolean queries=caps.OpenGL33||caps.GL_ARB_timer_query;
            record.addProperty("timer_query",queries);
            record.addProperty("timestamp_bits",queries&&caps.glQueryCounter!=0?GL15C.glGetQueryi(GL33C.GL_TIMESTAMP,GL15C.GL_QUERY_COUNTER_BITS):0);
            record.addProperty("elapsed_bits",queries?GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED,GL15C.GL_QUERY_COUNTER_BITS):0);
            record.addProperty("max_draw_buffers",GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS));
            record.addProperty("gpu_sample_every_frames",FarGpuTimers.sampleEvery());record.addProperty("frame_quantile_precision_ms",1);
            record.addProperty("frame_quantile_cap_ms",2000);record.addProperty("local_only",true);
            record.addProperty("log_max_files",3);record.addProperty("log_max_file_bytes",4<<20);
            log=new LocalPerfLog(mc.gameDirectory.toPath().resolve(Cubic.MOD_ID).resolve("perf"),record.toString());
            Runtime.getRuntime().addShutdownHook(new Thread(log::close,"Performance log close"));
            Cubic.LOGGER.info("Local performance logs: {}/perf/latest.jsonl (local only)", Cubic.MOD_ID);
        }
        JsonObject base(String type) {JsonObject record=new JsonObject();record.addProperty("type",type);record.addProperty("utc",Instant.now().toString());return record;}
        void emit(Minecraft mc) {
            JsonObject record=base("window");record.addProperty("commit",BuildInfo.commit());record.addProperty("version",BuildInfo.version());long now=System.nanoTime();record.addProperty("window_seconds",(now-windowStart)/1e9);windowStart=now;record.add("frames",gson.toJsonTree(frames.take()));
            JsonObject settings=new JsonObject();
            settings.addProperty("framebuffer_width",mc.getMainRenderTarget().width);settings.addProperty("framebuffer_height",mc.getMainRenderTarget().height);
            settings.addProperty("render_chunks",mc.options.renderDistance().get());settings.addProperty("simulation_chunks",mc.options.simulationDistance().get());
            settings.addProperty("fps_limit",mc.options.framerateLimit().get());settings.addProperty("vsync",mc.options.enableVsync().get());
            settings.addProperty("shader_pack_active",ShaderBlocks.packInUse());settings.addProperty("own_far_depth",FarLod.active());
            settings.addProperty("integrated_server",mc.getSingleplayerServer()!=null);settings.addProperty("screen_open",mc.screen!=null);
            settings.addProperty("automatic_run_mesher",FarMesher.automaticRuns());record.add("settings",settings);
            Runtime r=Runtime.getRuntime();record.addProperty("heap_used_mib",(r.totalMemory()-r.freeMemory())>>20);record.addProperty("heap_committed_mib",r.totalMemory()>>20);
            ClientCubeWorld world=ClientCubeWorld.current();record.addProperty("cubic_world_loaded",world!=null);
            if(world!=null) {
                record.add("far",gson.toJsonTree(world.far().perfState()));
                JsonObject cubes=new JsonObject();cubes.addProperty("loaded",world.loadedCubes());cubes.addProperty("meshes",world.renderer().meshCount());cubes.addProperty("pending_meshes",world.renderer().pendingCount());record.add("cubes",cubes);
            }
            record.add("run_mesher",gson.toJsonTree(FarMesher.runStats()));
            JsonObject stages=new JsonObject();
            Timings[] timers={Timings.FRAME,Timings.FAR_MAKE,Timings.FAR_MESH,Timings.FAR_UPLOAD,Timings.FAR_DRAW,Timings.FAR_WATER,Timings.CUBE_MESH,Timings.CUBE_UPLOAD,Timings.CUBE_DRAW,Timings.SERVER_TICK,Timings.CUBE_UNLOAD,Timings.CUBE_FLUSH};
            String[] names={"frame","far_generation","far_mesh","far_upload","far_draw","far_water","cube_mesh","cube_upload","cube_draw","server_cubes","cube_unload","cube_flush"};
            for(int i=0;i<timers.length;i++) {
                var total=timers[i].total();var before=previous.put(timers[i],total);
                long count=before==null?total.calls():Math.max(0,total.calls()-before.calls());
                long nanos=before==null?total.nanos():Math.max(0,total.nanos()-before.nanos());
                JsonObject stage=new JsonObject();stage.addProperty("calls",count);stage.addProperty("cpu_ms_total",nanos/1e6);stage.addProperty("cpu_ms_mean",count==0?0:nanos/1e6/count);stages.add(names[i],stage);
            }
            record.add("stages",stages);
            Map<String,long[]> gpu=new LinkedHashMap<>();
            for(var sample:FarGpuTimers.drain()) {
                long[] aggregate=gpu.computeIfAbsent(sample.pass(),key->new long[]{0,0,0,0,0,-1});
                aggregate[0]++;aggregate[1]+=sample.gpuNanos();aggregate[2]+=sample.cpuNanos();aggregate[3]=Math.max(aggregate[3],sample.gpuNanos());aggregate[4]=Math.max(aggregate[4],sample.age());aggregate[5]=Math.max(aggregate[5],sample.ranges());
            }
            JsonObject passes=new JsonObject();gpu.forEach((name,a)->{JsonObject pass=new JsonObject();pass.addProperty("samples",a[0]);pass.addProperty("gpu_ms_mean",a[1]/1e6/a[0]);pass.addProperty("cpu_ms_mean",a[2]/1e6/a[0]);pass.addProperty("gpu_ms_max",a[3]/1e6);pass.addProperty("max_age_frames",a[4]);pass.addProperty("max_ranges",a[5]);passes.add(name,pass);});
            record.add("sampled_gpu_passes",passes);record.addProperty("gpu_query_mode",FarGpuTimers.mode());record.addProperty("gpu_query_dropped",FarGpuTimers.dropped());
            JsonObject errors=new JsonObject();for(var stage:PerfFailures.Stage.values())if(PerfFailures.count(stage)>0){JsonObject error=new JsonObject();error.addProperty("count",PerfFailures.count(stage));error.addProperty("exception_type",PerfFailures.type(stage));errors.add(stage.name(),error);}record.add("errors",errors);
            record.addProperty("log_dropped",log.dropped());log.offer(record.toString());
            if(log.failed()&&!warned){warned=true;Cubic.LOGGER.warn("Local performance log could not be written");}
        }
    }
    static String clean(String value) {return value==null?"unknown":value.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(256,value.length()));}
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if(!BuildInfo.diagnostics())return;
        long now=System.nanoTime();Minecraft mc=Minecraft.getInstance();
        if(state==null)state=new State(mc,now);
        if(state.lastFrame!=0)state.frames.add(now-state.lastFrame);state.lastFrame=now;
        if(now-state.nextLog>=0){state.nextLog=now+5_000_000_000L;state.emit(mc);}
    }
}
