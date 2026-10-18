package io.earthplatform.minecraft.cubic.client;
import java.nio.*;
import java.util.*;
import org.joml.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

/** Disposable actual-GLSL raw-output oracle. Readback is for correctness, never an FPS window. */
public class ShadowGpuOracle {
 static boolean capture=true;
 static int texture(int w,int h,int format,int components,float[] values) {
  int t=GL11C.glGenTextures(); GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,t);
  FloatBuffer b=values==null?null:MemoryUtil.memAllocFloat(values.length);
  if(b!=null)b.put(values).flip();
  GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,format,w,h,0,components==2?GL30C.GL_RG:GL11C.GL_RED,GL11C.GL_FLOAT,b);
  if(b!=null)MemoryUtil.memFree(b);
  GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);
  GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_NEAREST);
  GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_WRAP_S,GL13C.GL_CLAMP_TO_EDGE);
  GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_WRAP_T,GL13C.GL_CLAMP_TO_EDGE);return t;
 }
 static void upload(int t,int w,int h,float[] data) {
  GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,t);FloatBuffer b=MemoryUtil.memAllocFloat(data.length);b.put(data).flip();
  GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D,0,0,0,w,h,GL11C.GL_RED,GL11C.GL_FLOAT,b);MemoryUtil.memFree(b);
 }
 static void matrix(int program,String name,Matrix4f m) {
  float[] a=new float[16];m.get(a);GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program,name),false,a);
 }
 static float[] draw(int program,int fbo,int out,int hintsOut,int probeOut,int depth,int hintsIn,int w,int h,Matrix4f p,Vector3f sun,boolean ready,boolean up) {
  GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);
  GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,out,0);
  GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT1,GL11C.GL_TEXTURE_2D,hintsOut,0);
  GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT2,GL11C.GL_TEXTURE_2D,probeOut,0);
  GL20C.glDrawBuffers(probeOut!=0?new int[]{GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_COLOR_ATTACHMENT1,GL30C.GL_COLOR_ATTACHMENT2}:hintsOut!=0?new int[]{GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_COLOR_ATTACHMENT1}:new int[]{GL30C.GL_COLOR_ATTACHMENT0});
  if(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO incomplete");
  if(hintsIn==hintsOut&&hintsIn!=0)throw new AssertionError("hint framebuffer feedback");
  GL20C.glUseProgram(program);GL13C.glActiveTexture(GL13C.GL_TEXTURE0);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,depth);
  GL13C.glActiveTexture(GL13C.GL_TEXTURE1);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,hintsIn);
  GL20C.glUniform1i(GL20C.glGetUniformLocation(program,"earth_Game"),0);GL20C.glUniform1i(GL20C.glGetUniformLocation(program,"earth_Hints"),1);
  GL20C.glUniform1i(GL20C.glGetUniformLocation(program,"earth_HintsReady"),ready?1:0);
  GL20C.glUniform1i(GL20C.glGetUniformLocation(program,"earth_SunUp"),up?1:0);
  GL20C.glUniform3f(GL20C.glGetUniformLocation(program,"earth_Sun"),sun.x,sun.y,sun.z);
  GL20C.glUniform3f(GL20C.glGetUniformLocation(program,"earth_Up"),0,1,0);
  GL20C.glUniform2f(GL20C.glGetUniformLocation(program,"earth_Focal"),p.m00()*0.5f*w,p.m11()*0.5f*h);
  matrix(program,"earth_Projection",p);matrix(program,"earth_FarInverse",new Matrix4f(p).invert());
  GL11C.glViewport(0,0,w,h);GL11C.glDrawArrays(GL11C.GL_TRIANGLES,0,3);
  GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);return capture?read(w,h,2):null;
 }
 static float[] read(int w,int h,int channels) {
  FloatBuffer b=MemoryUtil.memAllocFloat(w*h*channels);GL11C.glReadPixels(0,0,w,h,channels==2?GL30C.GL_RG:GL11C.GL_RED,GL11C.GL_FLOAT,b);
  float[] result=new float[b.remaining()];b.get(result);MemoryUtil.memFree(b);return result;
 }
 static void benchmark(int ref,int opt,int fbo) throws Exception {
  int w=1708,h=960;Matrix4f p=new Matrix4f().perspective((float)java.lang.Math.toRadians(75),w/(float)h,8,196608);
  float[] ds=new float[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
   double d=200+800*(1+java.lang.Math.sin(x*0.015))*0.5+600*(1+java.lang.Math.cos(y*0.018))*0.5;
   ds[y*w+x]=x>0.88*w?1:(-p.m22()+p.m32()/(float)d)*0.5f+0.5f;
  }
  GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
  int depth=texture(w,h,GL30C.GL_R32F,1,ds),out=texture(w,h,GL30C.GL_RG8,2,null);
  int[] hints={texture(w,h,GL30C.GL_R32F,1,new float[w*h]),texture(w,h,GL30C.GL_R32F,1,null)};
  Vector3f sun=new Vector3f(0.3f,0.12f,-0.3f).normalize();int read=0;capture=false;
  for(int i=0;i<20;i++) {draw(opt,fbo,out,hints[1-read],0,depth,hints[read],w,h,p,sun,i!=0,true);GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT1,GL11C.GL_TEXTURE_2D,0,0);read=1-read;}
  // One correctness read drains warm-up; no readback/finish occurs inside measured scopes.
  GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);read(w,h,2);
  java.util.List<GpuQueryRing.Sample> samples=new ArrayList<>();
  GpuQueryRing.Queries queries=new GpuQueryRing.Queries() {
   public int create(){return GL15C.glGenQueries();}public void stamp(int q){throw new AssertionError();}
   public boolean available(int q){return GL15C.glGetQueryObjecti(q,GL15C.GL_QUERY_RESULT_AVAILABLE)!=0;}
   public long result(int q){return GL33C.glGetQueryObjectui64(q,GL15C.GL_QUERY_RESULT);}
   public void delete(int q){GL15C.glDeleteQueries(q);}
   public boolean canBegin(){return GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED,GL15C.GL_CURRENT_QUERY)==0;}
   public void begin(int start,int end){GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED,start);}
   public void end(int start,int end){GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);}
   public boolean availablePair(int start,int end){return available(start);}
   public long elapsed(int start,int end){return result(start);}
  };
  try(var ring=new GpuQueryRing(queries,System::nanoTime,16,120,32)) {
   long frame=0;int pending=0;
   for(int block=0;block<8;block++) {
    boolean hinted=block%4==1||block%4==2;
    for(int i=0;i<24;i++) {
     long token=ring.begin((hinted?"hint":"ref")+block,++frame);if(token==0)throw new AssertionError("benchmark ring full");
     draw(hinted?opt:ref,fbo,out,hinted?hints[1-read]:0,0,depth,hinted?hints[read]:0,w,h,p,sun,true,true);
     ring.end(token,-1,-1);pending++;
     if(hinted){GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT1,GL11C.GL_TEXTURE_2D,0,0);read=1-read;}
     GL11C.glFlush();
     long deadline=System.nanoTime()+20_000_000_000L;
     do {
      ring.poll(frame+1);var got=ring.drain();samples.addAll(got);pending-=got.size();
      if(pending<8)break;if(System.nanoTime()>deadline)throw new AssertionError("GPU query timeout");Thread.sleep(1);
     }while(true);
    }
   }
   long deadline=System.nanoTime()+20_000_000_000L;
   while(pending>0){ring.poll(frame+1);var got=ring.drain();samples.addAll(got);pending-=got.size();if(System.nanoTime()>deadline)throw new AssertionError("GPU query timeout");Thread.sleep(1);}
   if(ring.dropped()!=0)throw new AssertionError("Dropped GPU samples "+ring.dropped());
  }
  for(int block=0;block<8;block++) {
   final String label=(block%4==1||block%4==2?"hint":"ref")+block;
   double[] values=samples.stream().filter(v->v.pass().equals(label)).mapToDouble(v->v.gpuNanos()/1e6).sorted().toArray();
   double mean=Arrays.stream(values).average().orElseThrow();
   System.out.printf(java.util.Locale.ROOT,"SHADOW_GPU_COMPONENT pass=%s n=%d meanMs=%.6f p95Ms=%.6f p99Ms=%.6f%n",label,values.length,mean,values[(int)java.lang.Math.ceil(values.length*.95)-1],values[(int)java.lang.Math.ceil(values.length*.99)-1]);
  }
  try(var csv=new java.io.PrintWriter(System.getProperty("earthplatform.oracle.csv", "shadow-gpu-component.csv"))) {
   csv.println("pass,frame,age,cpuNs,gpuNs");for(var v:samples)csv.println(v.pass()+","+v.frame()+","+v.age()+","+v.cpuNanos()+","+v.gpuNanos());
  }
  for(int t:new int[]{depth,out,hints[0],hints[1]})GL11C.glDeleteTextures(t);capture=true;
 }
 public static void main(String[] args) throws Exception {
  GLFWErrorCallback.createPrint(System.err).set();if(!GLFW.glfwInit())throw new AssertionError("GLFW init");
  long window=0;
  try {
   if(GLFW.glfwGetPrimaryMonitor()==0)throw new AssertionError("No primary monitor");
   GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,1);
   GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT,1);
   window=GLFW.glfwCreateWindow(64,64,"Earth shadow oracle",0,0);if(window==0)throw new AssertionError("GL context");
   GLFW.glfwMakeContextCurrent(window);var caps=GL.createCapabilities();
   System.out.println("GL "+GL11C.glGetString(GL11C.GL_VERSION)+" primary="+GLFW.glfwGetMonitorName(GLFW.glfwGetPrimaryMonitor())+" ARB_timer_query="+caps.GL_ARB_timer_query+" timestampBits="+GL15C.glGetQueryi(GL33C.GL_TIMESTAMP,GL15C.GL_QUERY_COUNTER_BITS)+" elapsedBits="+GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED,GL15C.GL_QUERY_COUNTER_BITS));
   int ref=GlShaders.link(FarLodShaders.FULL_SCREEN,FarLodShaders.SUN,"reference sun");
   int opt=GlShaders.link(FarLodShaders.FULL_SCREEN,FarLodShaders.SUN_HINTS,"hinted sun");
   String probeSource=FarLodShaders.SUN_HINTS.replace("layout(location = 1) out float earth_hint;","layout(location = 1) out float earth_hint;\nlayout(location = 2) out float earth_probe;")
    .replace("earth_hint = 0.0;","earth_hint = 0.0; earth_probe = 0.0;").replace("earth_hint = float(j + 1);","earth_probe = 1.0; earth_hint = float(j + 1);");
   int probeProgram=GlShaders.link(FarLodShaders.FULL_SCREEN,probeSource,"hint branch oracle");
   int vao=GL30C.glGenVertexArrays(),fbo=GL30C.glGenFramebuffers();GL30C.glBindVertexArray(vao);
   long compared=0,fastHits=0,shadowPixels=0;int cases=0;
   for(int[] size:new int[][]{{128,64},{257,129},{64,96},{128,64}}) {
    int w=size[0],h=size[1];GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    int depth=texture(w,h,GL30C.GL_R32F,1,null),a=texture(w,h,GL30C.GL_RG32F,2,null),b=texture(w,h,GL30C.GL_RG32F,2,null);
    int[] hints={texture(w,h,GL30C.GL_R32F,1,null),texture(w,h,GL30C.GL_R32F,1,null)};int probe=texture(w,h,GL30C.GL_R32F,1,null);
    float[] previous=new float[w*h];int read=0;
    for(int phase=0;phase<24;phase++) {
     Matrix4f p=new Matrix4f().perspective((float)java.lang.Math.toRadians(75+phase%3),w/(float)h,8,196608);
     if(phase%7==0)p.m20((phase%3-1)*0.001f).m21(0.0007f);
     float[] ds=new float[w*h];
     for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
      double hill=200+800*(1+java.lang.Math.sin((x+phase*0.2)*0.1))*0.5+600*(1+java.lang.Math.cos((y-phase*0.3)*0.12))*0.5;
      if(phase>=8&&phase<=12&&x>w/3&&x<w/2)hill=80+phase*5;
      ds[y*w+x]=(x>0.88*w||phase==18||phase%5==0&&(x+y)%9==0)?1.0f:(-p.m22()+p.m32()/(float)hill)*0.5f+0.5f;
     }
     if(phase%6==2)for(int i=0;i<previous.length;i+=19)previous[i]=switch(i%5){case 0->Float.NaN;case 1->Float.POSITIVE_INFINITY;case 2->33;case 3->4.5f;default->-1;};
     if(phase==3)for(int i=0;i<previous.length;i++)previous[i]=i%32+1;
     GL13C.glActiveTexture(GL13C.GL_TEXTURE0);upload(depth,w,h,ds);upload(hints[read],w,h,previous);
     Vector3f sun=new Vector3f(0.2f+(phase%4)*0.1f,0.12f,phase%8<4?-0.3f:0.3f).normalize();boolean up=phase!=17;
     float[] expected=draw(ref,fbo,a,0,0,depth,0,w,h,p,sun,false,up);
     float[] actual=draw(opt,fbo,b,hints[1-read],0,depth,hints[read],w,h,p,sun,phase!=0,up);
     for(int i=0;i<actual.length;i++){if(Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(actual[i]))throw new AssertionError("raw shadow/AO mismatch phase="+phase+" size="+w+"x"+h+" channel="+i+" "+expected[i]+" != "+actual[i]);if(i%2==0&&expected[i]>0)shadowPixels++;}
     GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT1);previous=read(w,h,1);
     float[] checked=draw(probeProgram,fbo,b,hints[1-read],probe,depth,hints[read],w,h,p,sun,phase!=0,up);
     for(int i=0;i<actual.length;i++)if(Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(checked[i]))throw new AssertionError("instrumented branch mismatch");
     GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT2);for(float f:read(w,h,1))if(f==1)fastHits++;
     // No attached output is sampled, including after a resize or changed sun/depth/noise projection.
     GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT1,GL11C.GL_TEXTURE_2D,0,0);read=1-read;
     compared+=actual.length;cases++;
    }
    for(int t:new int[]{depth,a,b,hints[0],hints[1],probe})GL11C.glDeleteTextures(t);
   }
   if(fastHits==0||shadowPixels==0)throw new AssertionError("No validated hint hits exercised");
   if(java.util.Arrays.asList(args).contains("bench")) benchmark(ref,opt,fbo);
   int error=GL11C.glGetError();if(error!=0)throw new AssertionError("GL error "+error);
   System.out.println("SHADOW_GPU_ORACLE PASS cases="+cases+" rawChannels="+compared+" validatedFastHits="+fastHits+" shadowPixels="+shadowPixels+" mismatches=0");
   GL20C.glDeleteProgram(ref);GL20C.glDeleteProgram(opt);GL20C.glDeleteProgram(probeProgram);GL30C.glDeleteFramebuffers(fbo);GL30C.glDeleteVertexArrays(vao);
  }finally{if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
