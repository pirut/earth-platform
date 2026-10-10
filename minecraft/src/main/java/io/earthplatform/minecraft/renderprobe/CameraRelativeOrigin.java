package io.earthplatform.minecraft.renderprobe;

import io.earthplatform.core.CubePos;

/** Absolute integer origins stay exact; subtract the double camera before any float conversion. */
public record CameraRelativeOrigin(float x,float y,float z) {
    public static CameraRelativeOrigin of(CubePos cube,double cameraX,double cameraY,double cameraZ) {
        return new CameraRelativeOrigin(relative(cube.minBlockX(),cameraX),relative(cube.minBlockY(),cameraY),relative(cube.minBlockZ(),cameraZ));
    }
    private static float relative(long origin,double camera) {
        if(!Double.isFinite(camera))throw new IllegalArgumentException("Camera must be finite");
        double difference=(double)origin-camera;
        if(Math.abs(difference)>4096)throw new IllegalArgumentException("Diagnostic camera must stay near its admitted cube");
        return (float)difference;
    }
}
