package io.earthplatform.minecraft.lod;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.coregate.RollingDepthComposition;
import io.earthplatform.minecraft.coregate.RollingGateClient;
import io.earthplatform.minecraft.scene.SceneNativeDepthFixture;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Explicit actual-shader depth24 probe; no live-world ownership or gameplay authority. */
public final class DhLongDistanceGpuProbe {
    private static final int SIZE=256;
    private static Resources retained;
    private static boolean running;
    private DhLongDistanceGpuProbe(){}
    public static boolean hasRetainedResources(){return retained!=null||running;}
    private record Case(String name,int dhDistance,int nativeDistance,double cameraZOffset,float yaw,boolean seam){}
    private static final class Resources {
        final DhGeographicLodView.OpenGlDriver dh=new DhGeographicLodView.OpenGlDriver(16384,32768);
        final RollingDepthComposition composition=new RollingDepthComposition();
        boolean compositionClosed;
        final List<DhGeographicLodView.Mesh> dhMeshes=new ArrayList<>();
        final List<SceneNativeDepthFixture> nativeMeshes=new ArrayList<>();
        final List<Integer> textures=new ArrayList<>();
        int fbo,color,depth,white,light;
        ByteBuffer rgba;FloatBuffer depthValues;
        DhOffscreenGpuProbe.State raw;
        SceneNativeDepthFixture.DrawState nativeState;
        void close(){
            Throwable failure=null;
            // A failed production restore remains owned, with its framebuffer alive for retry.
            var beforeComposition=new DhOffscreenGpuProbe.State();
            try{if(fbo!=0)glBindFramebuffer(GL_FRAMEBUFFER,fbo);composition.close();compositionClosed=true;}
            catch(RuntimeException|Error e){failure=append(failure,e);}
            finally{try{beforeComposition.restore();}catch(RuntimeException|Error e){failure=append(failure,e);}}
            for(var it=nativeMeshes.iterator();it.hasNext();)try{var m=it.next();m.close();require(m.deleted(),"Native fixture owner remains");it.remove();}catch(RuntimeException|Error e){failure=append(failure,e);}
            for(var it=dhMeshes.iterator();it.hasNext();)try{var m=it.next();m.close();require(m.deleted(),"DH fixture owner remains");it.remove();}catch(RuntimeException|Error e){failure=append(failure,e);}
            try{dh.close();require(dh.deleted(),"DH driver owner remains");}catch(RuntimeException|Error e){failure=append(failure,e);}
            if(compositionClosed&&fbo!=0)try{glDeleteFramebuffers(fbo);require(!glIsFramebuffer(fbo),"Framebuffer owner remains");fbo=0;}catch(RuntimeException|Error e){failure=append(failure,e);}
            for(var it=textures.iterator();compositionClosed&&it.hasNext();)try{int id=it.next();glDeleteTextures(id);require(!glIsTexture(id),"Texture owner remains");it.remove();}catch(RuntimeException|Error e){failure=append(failure,e);}
            if(rgba!=null){MemoryUtil.memFree(rgba);rgba=null;}if(depthValues!=null){MemoryUtil.memFree(depthValues);depthValues=null;}
            if(nativeState!=null)try{nativeState.restore();nativeState=null;}catch(RuntimeException|Error e){failure=append(failure,e);}
            if(raw!=null)try{raw.restore();require(raw.matches(),"Probe GL state restoration incomplete");raw=null;}catch(RuntimeException|Error e){failure=append(failure,e);}
            if(failure!=null)throw propagate(failure);
        }
        boolean deleted(){return compositionClosed&&nativeMeshes.isEmpty()&&dhMeshes.isEmpty()&&dh.deleted()&&textures.isEmpty()&&fbo==0&&rgba==null&&depthValues==null&&raw==null&&nativeState==null;}
    }
    public static String run() throws Exception {
        RenderSystem.assertOnRenderThread();require(!running,"Depth probe already running");
        require(!RollingGateClient.hasRetainedSession(),"Leave the retained gameplay session before the isolated depth probe");
        Path out=Path.of("earthplatform-evidence","long-distance-depth",Long.toString(System.currentTimeMillis())).toAbsolutePath();Files.createDirectories(out);
        var report=new JsonObject();var cases=new JsonArray();report.add("cases",cases);
        report.addProperty("scope","Actual official DH terrain vertices/shaders and actual native BLOCK VertexBuffer/solid shader; manually authored diagnostic quads, not baked models");
        report.addProperty("layout",DhLodLayout.LONG_4096.identity());report.addProperty("depthFormat","GL_DEPTH_COMPONENT24");report.addProperty("gpuRenderer",glGetString(GL_RENDERER));report.addProperty("glVersion",glGetString(GL_VERSION));
        report.addProperty("upstreamConstructorFailureLimit","An upstream VertexBuffer constructor failure before returning its owner may leave internal partial GL allocations; this diagnostic does not claim fault coverage for that upstream constructor path.");
        report.addProperty("nativeCleanupVerification","Native wrapper invalidation plus explicit VAO/VBO deletion checks; hidden upstream index-buffer cleanup follows VertexBuffer.close semantics.");
        report.addProperty("hardwareGpuPerformanceClaim",false);report.addProperty("stockDhModLoaded",false);report.addProperty("changesLiveWorld",false);
        Throwable failure=null;Resources r=null;running=true;
        try{
            if(retained!=null){retained.close();require(retained.deleted(),"Prior probe cleanup incomplete");retained=null;}
            require(glGetError()==GL_NO_ERROR,"Preexisting GL error; declining probe");
            r=new Resources();retained=r;r.raw=new DhOffscreenGpuProbe.State();r.nativeState=new SceneNativeDepthFixture.DrawState();
            initialize(r);
            // Exercise unusual caller depth state, then prove each explicit partition can restore it.
            glDepthRange(.2,.8);glClearDepth(.75);glDepthMask(false);glEnable(GL_SCISSOR_TEST);glEnable(GL_DEPTH_CLAMP);
            var unusual=new DhOffscreenGpuProbe.State();
            var scenarios=List.of(new Case("dh-near-native-far",1,4096,0,0,false),new Case("native-near-dh-far",4096,1,0,0,false),
                new Case("dh-far-nearer",4096,4097,0,0,false),new Case("native-far-nearer",4097,4096,0,0,false),
                new Case("dh-split-minus-ulp",16,0,-Math.ulp(16f),0,true),new Case("dh-split-plus-ulp",16,0,Math.ulp(16f),0,true),
                new Case("dh-split-minus-page-ulp",16,0,-Math.ulp(4096f),0,true),new Case("dh-split-plus-page-ulp",16,0,Math.ulp(4096f),0,true),
                new Case("dh-oblique-split",16,0,0,.25f,true),new Case("native-oblique-split",0,16,0,-.25f,true));
            for(int bottom:new int[]{-11008,8608})for(var test:scenarios){
                renderCase(r,test,-9674936,-3112472,bottom+128,out,cases);
                require(unusual.matches(),"Unusual range/mask/scissor state must round-trip after every case");
            }
            report.addProperty("caseCount",cases.size());require(cases.size()==20,"Complete bounded case matrix");
            report.add("productionDepthComposition",r.composition.diagnostics());report.addProperty("actualProductionHelperInvoked",true);
            report.addProperty("allCasesPassed",true);report.addProperty("unusualStateRestoredBetweenCases",true);
        }catch(Throwable e){failure=e;report.addProperty("failure",e.toString());}
        finally{
            if(r!=null)try{r.close();report.addProperty("allOwnedGlObjectsDeleted",r.deleted());if(r.deleted())retained=null;}catch(Throwable cleanup){failure=append(failure,cleanup);report.addProperty("cleanupFailure",cleanup.toString());}
            report.addProperty("cleanupRetainedForRetry",retained!=null);report.addProperty("status",failure==null?"PASS":"FAIL");
            running=false;
            Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
        if(failure!=null)throw propagate(failure);
        return "Long-distance DH/native depth probe PASS: "+out.resolve("report.json");
    }
    private static void initialize(Resources r){
        // Native cached bindings remain consistent while the raw GL fixture initializes.
        var state=new DhOffscreenGpuProbe.State();
        try{
            r.dh.initialize();glActiveTexture(GL_TEXTURE0);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
            glPixelStorei(GL_UNPACK_ALIGNMENT,1);glPixelStorei(GL_UNPACK_ROW_LENGTH,0);glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);glPixelStorei(GL_UNPACK_SKIP_ROWS,0);
            r.color=texture(r,GL_RGBA8,GL_RGBA,GL_UNSIGNED_BYTE,SIZE,false);
            r.depth=texture(r,GL_DEPTH_COMPONENT24,GL_DEPTH_COMPONENT,GL_FLOAT,SIZE,false);
            r.white=texture(r,GL_RGBA8,GL_RGBA,GL_UNSIGNED_BYTE,1,true);r.light=texture(r,GL_RGBA8,GL_RGBA,GL_UNSIGNED_BYTE,16,true);
            r.fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,r.fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,r.color,0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,r.depth,0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);glReadBuffer(GL_COLOR_ATTACHMENT0);require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"Depth24 framebuffer incomplete");
            r.rgba=MemoryUtil.memAlloc(SIZE*SIZE*4);r.depthValues=MemoryUtil.memAllocFloat(SIZE*SIZE);
        }finally{state.restore();}
    }
    private static int texture(Resources r,int internal,int format,int type,int size,boolean white){
        int id=glGenTextures();require(id!=0,"Texture allocation");r.textures.add(id);glBindTexture(GL_TEXTURE_2D,id);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        if(white)try(var stack=MemoryStack.stackPush()){var data=stack.malloc(size*size*4);while(data.hasRemaining())data.put((byte)255);data.flip();glTexImage2D(GL_TEXTURE_2D,0,internal,size,size,0,format,type,data);}
        else glTexImage2D(GL_TEXTURE_2D,0,internal,size,size,0,format,type,(ByteBuffer)null);
        return id;
    }
    private static void renderCase(Resources r,Case test,long originX,long originZ,int cameraBaseY,Path out,JsonArray reports)throws Exception{
        String name=(cameraBaseY<0?"abyss-":"summit-")+test.name();
        int distance=Math.max(test.dhDistance(),test.nativeDistance());int half=distance>100?128:16;
        Map<DhGeographicLodData.TileKey,DhGeographicLodView.Mesh> dhMeshes=new LinkedHashMap<>();SceneNativeDepthFixture nativeMesh=null;
        var raw=new DhOffscreenGpuProbe.State();var nativeState=new SceneNativeDepthFixture.DrawState();
        Throwable primary=null;
        try{
            if(test.dhDistance()>0){
                long x=originX-half,z=originZ-test.dhDistance();var face=new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.SOUTH,x,cameraBaseY-half,z,half*2,half*2,0xffff0000);
                long tileX=Math.floorDiv(x,4096)*4096,tileZ=Math.floorDiv(z,4096)*4096;
                var byPage=new LinkedHashMap<DhGeographicLodData.TileKey,List<DhTerrainGeometry.PageFace>>();
                for(var page:DhTerrainGeometry.splitPages(face,tileX,tileZ))byPage.computeIfAbsent(new DhGeographicLodData.TileKey(tileX,tileZ,page.pageY2048()),ignored->new ArrayList<>()).add(page);
                for(var e:byPage.entrySet()){var before=new DhOffscreenGpuProbe.State();try{var mesh=r.dh.upload(e.getKey(),e.getValue());r.dhMeshes.add(mesh);dhMeshes.put(e.getKey(),mesh);}finally{before.restore();}}
            }
            if(test.nativeDistance()>0){nativeMesh=new SceneNativeDepthFixture();r.nativeMeshes.add(nativeMesh);nativeMesh.initialize(half,half,-test.nativeDistance());nativeState.restore();}
            glBindFramebuffer(GL_FRAMEBUFFER,r.fbo);glViewport(0,0,SIZE,SIZE);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_DEPTH_CLAMP);glDisable(GL_FRAMEBUFFER_SRGB);glDisable(GL_MULTISAMPLE);glDisable(GL_STENCIL_TEST);
            glColorMask(true,true,true,true);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
            // Production composition must clear through this deliberately unusual caller state.
            glDepthRange(.2,.8);glClearDepth(.75);glDepthMask(false);glEnable(GL_SCISSOR_TEST);glEnable(GL_DEPTH_CLAMP);
            var helperCaller=new DhOffscreenGpuProbe.State();
            var original=new Matrix4f().perspective((float)Math.toRadians(60),1,.05f,2048);var view=new Matrix4f().rotateY(test.yaw());
            double cameraX=originX+.25,cameraY=cameraBaseY,cameraZ=originZ+test.cameraZOffset();
            var nativeModel=new Matrix4f(view).translate((float)(originX-cameraX),(float)(cameraBaseY-cameraY),(float)(originZ-cameraZ));
            long[] draws={0};final var submittedNative=nativeMesh;
            require(r.composition.draw(original,DhLodLayout.LONG_4096,pass->{
                if(!dhMeshes.isEmpty()){var before=new DhOffscreenGpuProbe.State();try{draws[0]+=r.dh.draw(dhMeshes,new Matrix4f(pass.projection()).mul(view),cameraX,cameraY,cameraZ).drawCalls();}finally{before.restore();}}
                if(submittedNative!=null){submittedNative.draw(nativeModel,pass.projection(),r.white,r.light);draws[0]++;}
                return true;
            }),"Production depth composition aborted");
            require(helperCaller.matches(),"Production helper did not restore unusual caller state");
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ALIGNMENT,1);glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);
            r.rgba.clear();r.depthValues.clear();glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,r.rgba);glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,r.depthValues);require(glGetError()==GL_NO_ERROR,"Depth probe GL error");
            int expected=test.dhDistance()>0&&(test.nativeDistance()==0||test.dhDistance()<test.nativeDistance())?0:1;
            int checked=0,near=0,far=0;double maxError=0;
            int first=test.seam()?SIZE/4:SIZE/2-2,last=test.seam()?SIZE*3/4:SIZE/2+2;
            for(int x=first;x<last;x++)for(int y=SIZE/2-2;y<SIZE/2+2;y++){
                int offset=(y*SIZE+x)*4,red=r.rgba.get(offset)&255,green=r.rgba.get(offset+1)&255,blue=r.rgba.get(offset+2)&255;
                require(expected==0?red>128&&green<16&&blue<16:green>128&&red<16&&blue<16,"Wrong nearest surface or split gap "+name+" pixel="+x+","+y+" rgb="+red+","+green+","+blue);
                double depth=r.depthValues.get(y*SIZE+x);require(depth>0&&depth<1,"Finite populated depth "+name);
                if(depth<.5)near++;else far++;
                int nearest=test.dhDistance()==0?test.nativeDistance():test.nativeDistance()==0?test.dhDistance():Math.min(test.dhDistance(),test.nativeDistance());
                double rayX=((x+.5)/SIZE*2-1)/original.m00();double t=(nearest+test.cameraZOffset())/(Math.cos(test.yaw())-Math.sin(test.yaw())*rayX);
                var pass=RollingDepthComposition.passes(original,DhLodLayout.LONG_4096).stream().filter(p->t<16?p.nearPlane()<1:p.nearPlane()>=16).findFirst().orElseThrow();
                var matrix=pass.projection();double expectedDepth=pass.depthMin()+((matrix.m22()*(-t)+matrix.m32())/t+1)*.5*(pass.depthMax()-pass.depthMin());
                double error=Math.abs(depth-expectedDepth);maxError=Math.max(maxError,error);require(error<.000004,"Depth projection mismatch "+name+" error="+error);checked++;
            }
            if(test.yaw()!=0)require(near>0&&far>0,"Oblique plane must continuously cross both depth partitions");
            if(distance>100&&Math.min(test.dhDistance(),test.nativeDistance())>100)require(near==0,"Distant surface must occupy far depth range");
            var receipt=new JsonObject();receipt.addProperty("name",name);receipt.addProperty("nativeOriginX",originX);receipt.addProperty("nativeOriginZ",originZ);receipt.addProperty("nativeCameraY",cameraY);receipt.addProperty("dhDistance",test.dhDistance());receipt.addProperty("nativeDistance",test.nativeDistance());receipt.addProperty("cameraZOffset",test.cameraZOffset());receipt.addProperty("yawRadians",test.yaw());receipt.addProperty("drawCalls",draws[0]);receipt.addProperty("minimumVertexY",cameraBaseY-half);receipt.addProperty("maximumVertexY",cameraBaseY+half);receipt.addProperty("productionHelperRestored",true);receipt.addProperty("checkedPixels",checked);receipt.addProperty("nearRangePixels",near);receipt.addProperty("farRangePixels",far);receipt.addProperty("maximumDepthError",maxError);receipt.addProperty("status","PASS");
            var png=out.resolve(name+".png");saveImage(r.rgba,png);receipt.addProperty("image",png.getFileName().toString());reports.add(receipt);
        }catch(Exception|Error e){primary=e;throw e;}finally{
            Throwable failure=null;
            if(nativeMesh!=null)try{nativeMesh.close();if(nativeMesh.deleted())r.nativeMeshes.remove(nativeMesh);}catch(RuntimeException|Error e){failure=e;}
            for(var mesh:dhMeshes.values())try{mesh.close();if(mesh.deleted())r.dhMeshes.remove(mesh);}catch(RuntimeException|Error e){failure=append(failure,e);}
            try{nativeState.restore();}catch(RuntimeException|Error e){failure=append(failure,e);}
            try{raw.restore();require(raw.matches(),"Case GL state restoration");}catch(RuntimeException|Error e){failure=append(failure,e);}
            if(failure!=null){if(primary!=null)append(primary,failure);else throw propagate(failure);}
        }
    }
    private static void saveImage(ByteBuffer rgba,Path path)throws Exception{
        var image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){int i=(y*SIZE+x)*4;image.setRGB(x,SIZE-y-1,((rgba.get(i+3)&255)<<24)|((rgba.get(i)&255)<<16)|((rgba.get(i+1)&255)<<8)|(rgba.get(i+2)&255));}
        require(ImageIO.write(image,"PNG",path.toFile()),"PNG writer unavailable");
    }
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static Throwable append(Throwable a,Throwable b){if(a==null)return b;if(a!=b)a.addSuppressed(b);return a;}
    private static RuntimeException propagate(Throwable e){if(e instanceof Error error)throw error;if(e instanceof RuntimeException runtime)return runtime;return new IllegalStateException(e);}
}
