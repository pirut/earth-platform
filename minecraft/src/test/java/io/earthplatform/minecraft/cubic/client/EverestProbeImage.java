package io.earthplatform.minecraft.cubic.client;

import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Diagnostic: real packed terrain and plain shaders, constant representative material colours. */
final class EverestProbeImage {
    @SuppressWarnings("unchecked") static FarFaces faces() throws Exception {
        FarFaces faces=new FarFaces(); var field=FarFaces.class.getDeclaredField("faces");field.setAccessible(true);
        var cache=(Map<BlockState,FarFaces.Face[]>)field.get(faces);
        for(var block:net.minecraft.core.registries.BuiltInRegistries.BLOCK) for(var state:block.getStateDefinition().getPossibleStates()) {
            String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
            int rgb=id.contains("snow")?0xf1f2f2:id.equals("blackstone")?0x34323b:id.equals("smooth_basalt")?0x535354:id.contains("ice")?0x98b9dc:0x96928a;
            FarFaces.Face[] six=new FarFaces.Face[6];Arrays.fill(six,new FarFaces.Face(0,0,1,1,0xff000000|rgb,-1));cache.put(state,six);
        }
        return faces;
    }
    static void render(Path output,List<FarMesher.Job> jobs,double cameraX,double cameraY,double cameraZ,float yaw,float pitch,float fov) throws Exception {
        if(!GLFW.glfwInit())throw new IllegalStateException("GLFW");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(16,16,"Everest offline diagnostic",0,0);
        GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        try {
            int width=1280,height=720;
            var all=new it.unimi.dsi.fastutil.ints.IntArrayList();var columns=new it.unimi.dsi.fastutil.ints.IntArrayList();
            var starts=new it.unimi.dsi.fastutil.ints.IntArrayList();var counts=new it.unimi.dsi.fastutil.ints.IntArrayList();
            for(var job:jobs) {
                var mesh=FarMesher.build(job);
                try {
                    int start=all.size()/4,faces=mesh.quads().length/4;starts.add(start*6);counts.add(faces*6);
                    int[] quads=mesh.quads().clone();
                    if(mesh.grid()!=null)for(int i=0;i<quads.length;i+=4)if(((quads[i]>>>10)&7)>=6)quads[i+1]=start+faces;
                    all.addElements(all.size(),quads);if(mesh.grid()!=null)all.addElements(all.size(),mesh.grid());
                    columns.add(job.x0());columns.add(job.bottom()*16*(1<<job.level()));columns.add(job.z0());columns.add(job.level());
                }finally{mesh.free();}
            }
            int program=GlShaders.link("#version 330 core\n"+FarShaders.VERTEX,"#version 330 core\n"+FarShaders.FRAGMENT,"Everest diagnostic");
            GL20C.glUseProgram(program);
            bindBuffer(program,"earth_Quads",0,GL30C.GL_RGBA32UI,all.toIntArray());
            bindBuffer(program,"earth_Columns",1,GL30C.GL_RGBA32I,columns.toIntArray());
            var field=FarLooks.class.getDeclaredField("data");field.setAccessible(true);
            bindBuffer(program,"earth_Looks",2,GL30C.GL_RGBA32UI,(int[])field.get(jobs.getFirst().looks()));
            GL13C.glActiveTexture(GL13C.GL_TEXTURE3);int light=GL11C.glGenTextures();GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,light);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA8,1,1,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,org.lwjgl.BufferUtils.createByteBuffer(4).putInt(-1).flip());
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_NEAREST);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program,"earth_Lightmap"),3);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(program,"earth_Atlas"),3);
            var projection=new Matrix4f().perspective((float)Math.toRadians(fov),width/(float)height,8,128000);
            var view=new Matrix4f().rotateX((float)Math.toRadians(pitch)).rotateY((float)Math.toRadians(yaw+180));
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program,"earth_Projection"),false,projection.get(new float[16]));
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program,"earth_ModelView"),false,view.get(new float[16]));
            int bx=(int)Math.floor(cameraX),by=(int)Math.floor(cameraY),bz=(int)Math.floor(cameraZ);
            GL20C.glUniform3i(GL20C.glGetUniformLocation(program,"earth_CameraBlock"),bx,by,bz);
            GL20C.glUniform3f(GL20C.glGetUniformLocation(program,"earth_CameraFraction"),(float)(cameraX-bx),(float)(cameraY-by),(float)(cameraZ-bz));
            GL20C.glUniform1f(GL20C.glGetUniformLocation(program,"earth_HazeHeight"),1000000);
            GL20C.glUniform1f(GL20C.glGetUniformLocation(program,"earth_Edge"),1000000);
            GL20C.glUniform1f(GL20C.glGetUniformLocation(program,"earth_FogEnd"),1000000);
            GL20C.glUniform1f(GL20C.glGetUniformLocation(program,"earth_FogStart"),1000000);
            GL20C.glUniform4f(GL20C.glGetUniformLocation(program,"earth_ColorModulator"),1,1,1,1);
            GL20C.glUniform1fv(GL20C.glGetUniformLocation(program,"earth_Shade"),new float[]{.5f,1,.8f,.8f,.6f,.6f});
            int target=GL30C.glGenFramebuffers();GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,target);
            int colour=GL11C.glGenTextures();GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,colour);GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA8,width,height,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,colour,0);
            int depth=GL30C.glGenRenderbuffers();GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL30C.GL_DEPTH_COMPONENT32F,width,height);GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_ATTACHMENT,GL30C.GL_RENDERBUFFER,depth);
            GL11C.glViewport(0,0,width,height);GL11C.glClearColor(.45f,.65f,.85f,1);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,light);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);GL11C.glDisable(GL11C.GL_CULL_FACE);GL30C.glBindVertexArray(GL30C.glGenVertexArrays());
            for(int i=0;i<starts.size();i++)GL11C.glDrawArrays(GL11C.GL_TRIANGLES,starts.getInt(i),counts.getInt(i));
            var pixels=org.lwjgl.BufferUtils.createByteBuffer(width*height*4);GL11C.glReadPixels(0,0,width,height,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,pixels);
            var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){int i=(y*width+x)*4;image.setRGB(x,height-1-y,(pixels.get(i)&255)<<16|(pixels.get(i+1)&255)<<8|(pixels.get(i+2)&255));}
            javax.imageio.ImageIO.write(image,"png",output.toFile());
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new IllegalStateException("Everest probe GL error");
        }finally{GL.setCapabilities(null);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
    private static void bindBuffer(int program,String name,int unit,int format,int[] values) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);int texture=GL11C.glGenTextures(),buffer=GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER,buffer);GL15C.glBufferData(GL31C.GL_TEXTURE_BUFFER,values,GL15C.GL_STATIC_DRAW);
        GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER,texture);GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER,format,buffer);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program,name),unit);
    }
}
