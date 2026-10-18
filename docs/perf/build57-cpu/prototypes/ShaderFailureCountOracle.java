package io.earthplatform.minecraft.cubic.client;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import io.earthplatform.minecraft.diagnostics.PerfFailures;
/** Local context-backed counter test; intentionally invalid shader, no game/assets/worlds. */
public final class ShaderFailureCountOracle {
 public static void main(String[] ignored) {
  GLFWErrorCallback.createPrint(System.err).set();
  if(!GLFW.glfwInit()||GLFW.glfwGetPrimaryMonitor()==0)throw new AssertionError("Display unavailable");
  GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,1);
  GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT,GLFW.GLFW_TRUE);
  long window=GLFW.glfwCreateWindow(64,64,"Earth isolated shader failure counter",0,0);
  if(window==0)throw new AssertionError("Context unavailable");
  try {
   GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
   long before=PerfFailures.count(PerfFailures.Stage.FAR_SHADER);
   for(int i=1;i<=3;i++) {
    try{GlShaders.link("#version 330 core\nvoid main(){gl_Position=vec4(0);}","#version 330 core\nthis must fail;","isolated counter fixture");throw new AssertionError("Invalid shader accepted");}
    catch(IllegalStateException expected){}
    if(PerfFailures.count(PerfFailures.Stage.FAR_SHADER)!=before+i)throw new AssertionError("Shader failure count differs");
   }
   long terrain=PerfFailures.count(PerfFailures.Stage.FAR_GENERATION);
   for(int i=1;i<=5;i++)PerfFailures.record(PerfFailures.Stage.FAR_GENERATION,new IllegalStateException("private text never recorded"));
   if(PerfFailures.count(PerfFailures.Stage.FAR_GENERATION)!=terrain+5)throw new AssertionError("Generation event counter differs");
   if(!"java.lang.IllegalStateException".equals(PerfFailures.type(PerfFailures.Stage.FAR_SHADER)))throw new AssertionError("Unsafe exception type");
   System.out.println("FAILURE_COUNTER_ORACLE PASS invalidShaderCompiles=3 shaderEvents=3 immediateGenerationEvents=5 sanitizedTypes=true");
  }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
