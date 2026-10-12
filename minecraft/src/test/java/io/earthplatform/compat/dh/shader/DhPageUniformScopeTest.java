package io.earthplatform.compat.dh.shader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhPageUniformScopeTest {
    static final class Gl implements DhPageUniformScope.Gl {
        int program=7,fbo=9,writes;boolean exists=true,fail;int failUse=-1;float[] value={1,2,3};
        public int currentProgram(){return program;}public int drawFramebuffer(){return fbo;}public boolean programExists(int p){return exists;}
        public float[] readOffset(int p,int l){return value;}public void useProgram(int p){if(p==failUse)throw new IllegalStateException("bind failure");program=p;}
        public void offset(int l,float x,float y,float z){value=new float[]{x,y,z};writes++;if(fail)throw new IllegalStateException("partial uniform");}
    }
    DhPageUniformScope scope(Gl gl,double x,double y,double z){return new DhPageUniformScope(gl,7,4,9,x,y,z,16384,32768);}
    @Test void signedPageAndLargeCoordinatesSubtractBeforeFloat(){var gl=new Gl();var s=scope(gl,9674936.25,-11000.5,-3112472.75);s.page(9674928,-12288,-3112480);assertArrayEquals(new float[]{-8.25f,-1287.5f,-7.25f},gl.value);s.close();assertArrayEquals(new float[]{1,2,3},gl.value);assertTrue(s.closed());}
    @Test void summitAndPageBoundaryDoNotDoubleApplyWorldYOffset(){var gl=new Gl();var s=scope(gl,0,8849.5,0);s.page(0,8192,0);assertEquals(-657.5f,gl.value[1]);s.page(0,10240,0);assertEquals(1390.5f,gl.value[1]);s.close();assertEquals(3,gl.writes);}
    @Test void wrongPassOrOutOfRangeRefusesBeforeMutation(){var gl=new Gl();gl.fbo=8;assertThrows(IllegalStateException.class,()->scope(gl,0,0,0));assertEquals(0,gl.writes);gl.fbo=9;var s=scope(gl,0,0,0);assertThrows(IllegalArgumentException.class,()->s.page(16385,0,0));s.close();assertEquals(0,gl.writes);}
    @Test void partialMutationAndFailedRestoreRemainRetryable(){var gl=new Gl();var s=scope(gl,0,0,0);gl.fail=true;assertThrows(IllegalStateException.class,()->s.page(0,2048,0));assertThrows(IllegalStateException.class,s::close);assertFalse(s.closed());gl.fail=false;s.close();assertTrue(s.closed());assertArrayEquals(new float[]{1,2,3},gl.value);int writes=gl.writes;s.close();assertEquals(writes,gl.writes);}
    @Test void failedCallerProgramRestoreKeepsOriginalTargetAcrossRetries(){var gl=new Gl();var s=scope(gl,0,0,0);s.page(0,2048,0);gl.program=12;gl.failUse=12;assertThrows(IllegalStateException.class,s::close);assertEquals(7,gl.program);assertFalse(s.closed());gl.failUse=-1;s.close();assertEquals(12,gl.program);assertArrayEquals(new float[]{1,2,3},gl.value);}
    @Test void changedCallerProgramIsPreservedAndDeletedBorrowIsNeverRecreated(){var gl=new Gl();var s=scope(gl,0,0,0);s.page(0,2048,0);gl.program=12;s.close();assertEquals(12,gl.program);assertArrayEquals(new float[]{1,2,3},gl.value);var gl2=new Gl();var old=scope(gl2,0,0,0);old.page(0,2048,0);gl2.exists=false;old.close();assertEquals(1,gl2.writes);assertTrue(old.closed());}
}
