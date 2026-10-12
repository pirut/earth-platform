package io.earthplatform.compat.dh.shader;

/** One borrowed program's page-offset mutation. No program/FBO/texture is created or owned.
 * The caller retains this scope before the first mutation, and retries close after a failure. */
public final class DhPageUniformScope implements AutoCloseable {
    public interface Gl {
        int currentProgram(); int drawFramebuffer(); boolean programExists(int program);
        float[] readOffset(int program,int location);
        void useProgram(int program); void offset(int location,float x,float y,float z);
        default void requireNoError(){}
        default void requireNoError(String phase){requireNoError();}
    }
    private final Gl gl;private final int program,location,framebuffer;private final float[] previous;
    private final double cameraX,cameraY,cameraZ,horizontalLimit,verticalLimit;
    private boolean mutated,closed;private Integer restoreProgram;
    public DhPageUniformScope(Gl gl,int program,int location,int framebuffer,double x,double y,double z,double horizontalLimit,double verticalLimit){
        if(gl==null||program<=0||location<0||framebuffer<=0||!finite(x,y,z,horizontalLimit,verticalLimit)||horizontalLimit<=0||verticalLimit<=0||horizontalLimit>32768||verticalLimit>32768)
            throw new IllegalArgumentException("Exact bounded borrowed DH pass required");
        this.gl=gl;this.program=program;this.location=location;this.framebuffer=framebuffer;cameraX=x;cameraY=y;cameraZ=z;this.horizontalLimit=horizontalLimit;this.verticalLimit=verticalLimit;
        if(gl.currentProgram()!=program||gl.drawFramebuffer()!=framebuffer)throw new IllegalStateException("Iris DH program/framebuffer is not the active borrowed pass");
        float[] read=gl.readOffset(program,location);
        if(read==null||read.length!=3||!Float.isFinite(read[0])||!Float.isFinite(read[1])||!Float.isFinite(read[2]))throw new IllegalStateException("Invalid borrowed page-offset state");
        gl.requireNoError("far.page.capture");previous=read.clone();
    }
    /** Origin subtraction is performed in double before the sole float conversion. */
    public void page(long originX,long originY,long originZ){
        if(closed)throw new IllegalStateException("Page scope closed");
        double x=originX-cameraX,y=originY-cameraY,z=originZ-cameraZ;
        if(!finite(x,y,z)||Math.abs(x)>horizontalLimit||Math.abs(y)>verticalLimit||Math.abs(z)>horizontalLimit)throw new IllegalArgumentException("Page is outside the admitted camera-relative extent");
        mutated=true; // a driver may mutate and then throw
        gl.offset(location,(float)x,(float)y,(float)z);
    }
    public boolean closed(){return closed;}
    @Override public void close(){
        if(closed)return;
        if(!mutated){closed=true;return;}
        // A shader reload may have deleted the borrowed program. No stale object may be recreated.
        if(!gl.programExists(program)){if(restoreProgram!=null&&gl.currentProgram()!=restoreProgram)gl.useProgram(restoreProgram);closed=true;return;}
        if(restoreProgram==null)restoreProgram=gl.currentProgram();
        int current=restoreProgram;Throwable failure=null;
        try{if(gl.currentProgram()!=program)gl.useProgram(program);gl.offset(location,previous[0],previous[1],previous[2]);}
        catch(RuntimeException|Error problem){failure=problem;}
        try{if(gl.currentProgram()!=current)gl.useProgram(current);}catch(RuntimeException|Error restore){if(failure==null)failure=restore;else if(failure!=restore)failure.addSuppressed(restore);}
        try{gl.requireNoError("far.page.restore");}catch(RuntimeException|Error error){if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error);}
        if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException error)throw error;
        closed=true;
    }
    private static boolean finite(double... values){for(double value:values)if(!Double.isFinite(value))return false;return true;}
}
