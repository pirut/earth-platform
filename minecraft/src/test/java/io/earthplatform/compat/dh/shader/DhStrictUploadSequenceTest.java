package io.earthplatform.compat.dh.shader;

import io.earthplatform.minecraft.lod.DhGlUpload;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL33C.*;

class DhStrictUploadSequenceTest {
    static final class Owner implements DhGlUpload.Owner {
        int vao,vbo,ibo;
        public int vao(){return vao;}public int vbo(){return vbo;}public int ibo(){return ibo;}
        public void vao(int n){vao=n;}public void vbo(int n){vbo=n;}public void ibo(int n){ibo=n;}
    }
    static final class Gl implements DhGlUpload.Gl {
        final List<String> calls=new ArrayList<>();int name=20;
        public int genVertexArray(){calls.add("genVAO");return ++name;}
        public int genBuffer(){calls.add("genBuffer");return ++name;}
        public void bindVertexArray(int n){calls.add("vao:"+n);}
        public void bindBuffer(int t,int n){calls.add("buffer:"+t+":"+n);}
        public void vertexData(ByteBuffer v){calls.add("vertices:"+v.remaining());}
        public void indexData(IntBuffer v){calls.add("indices:"+v.remaining());}
        public void enableAttribute(int i){calls.add("enable:"+i);}
        public void integerAttribute(int i,int c,int t,int s,long o){calls.add("integer:"+i+":"+c+":"+t+":"+s+":"+o);}
        public void attribute(int i,int c,int t,boolean n,int s,long o){calls.add("float:"+i+":"+c+":"+t+":"+n+":"+s+":"+o);}
        public void check(String p,DhGlUpload.Owner o){calls.add(p+":"+o.vao()+":"+o.vbo()+":"+o.ibo());}
    }
    @Test void pinnedOfficialLayoutAndExactOwnerHandoffOrderRemainUnchanged(){
        var gl=new Gl();var o=new Owner();DhGlUpload.upload(gl,o,ByteBuffer.allocate(64),IntBuffer.allocate(6));
        assertEquals(List.of("far.upload.entry:0:0:0","genVAO","far.upload.vao.allocate:21:0:0","vao:21","far.upload.vao.bind:21:0:0",
                "genBuffer","far.upload.vbo.allocate:21:22:0","buffer:"+GL_ARRAY_BUFFER+":22","far.upload.vbo.bind:21:22:0","vertices:64","far.upload.vertex.data:21:22:0",
                "genBuffer","far.upload.ibo.allocate:21:22:23","buffer:"+GL_ELEMENT_ARRAY_BUFFER+":23","far.upload.ibo.bind:21:22:23","indices:6","far.upload.index.data:21:22:23",
                "enable:0","far.upload.attribute0.enable:21:22:23","integer:0:4:"+GL_UNSIGNED_SHORT+":16:0","far.upload.attribute0.pointer:21:22:23",
                "enable:1","far.upload.attribute1.enable:21:22:23","float:1:4:"+GL_UNSIGNED_BYTE+":true:16:8","far.upload.attribute1.pointer:21:22:23",
                "enable:2","far.upload.attribute2.enable:21:22:23","integer:2:4:"+GL_UNSIGNED_BYTE+":16:12","far.upload.attribute2.pointer:21:22:23"),gl.calls);
        assertEquals(21,o.vao);assertEquals(22,o.vbo);assertEquals(23,o.ibo);
    }
    @Test void exactMaximumPackingBudgetIsUnchanged(){var gl=new Gl();var o=new Owner();DhGlUpload.upload(gl,o,ByteBuffer.allocate(4096*64),IntBuffer.allocate(4096*6));assertTrue(gl.calls.contains("vertices:262144"));}
    @Test void malformedAndOverBudgetPayloadsRefuseBeforeAnyGlCall(){
        for(int bytes:new int[]{0,1,63,65,4096*64+64}){var gl=new Gl();assertThrows(IllegalArgumentException.class,()->DhGlUpload.upload(gl,new Owner(),ByteBuffer.allocate(bytes),IntBuffer.allocate(6)));assertTrue(gl.calls.isEmpty());}
        var gl=new Gl();assertThrows(IllegalArgumentException.class,()->DhGlUpload.upload(gl,new Owner(),ByteBuffer.allocate(64),IntBuffer.allocate(12)));assertTrue(gl.calls.isEmpty());
    }
    @Test void existingOwnerCannotBeOverwrittenByRetry(){var gl=new Gl();var o=new Owner();o.vbo=99;assertThrows(IllegalStateException.class,()->DhGlUpload.upload(gl,o,ByteBuffer.allocate(64),IntBuffer.allocate(6)));assertEquals(99,o.vbo);assertTrue(gl.calls.isEmpty());}
    @Test void pageCaptureAndRestoreUseDistinctStrictLabels(){
        var labels=new ArrayList<String>();
        var gl=new DhPageUniformScope.Gl(){
            public int currentProgram(){return 7;}public int drawFramebuffer(){return 9;}public boolean programExists(int p){return true;}
            public float[] readOffset(int p,int l){return new float[]{1,2,3};}public void useProgram(int p){}public void offset(int l,float x,float y,float z){}
            public void requireNoError(String phase){labels.add(phase);}
        };
        var scope=new DhPageUniformScope(gl,7,0,9,0,0,0,16384,32768);scope.page(0,-12288,0);scope.close();
        assertEquals(List.of("far.page.capture","far.page.restore"),labels);
    }
}
