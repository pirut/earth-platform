package io.earthplatform.minecraft.lod;

import com.google.gson.JsonObject;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import java.util.List;
import org.joml.Matrix4f;

/** Owner-thread near/far handshake. A pending change never changes current coverage. */
public interface SceneLodRenderer extends AutoCloseable {
    default void setCpuProfiler(RenderCpuProfiler profiler){}
    void initialize();
    boolean replaceCoverage(long generation,List<SceneReadyCube> ready);
    boolean coverageReady(long generation);
    default boolean pendingCoverage(long generation){return false;}
    default void cancelPendingCoverage(){}
    default void pump(double cameraX,double cameraY,double cameraZ){}
    boolean hasPublishedGeometry();
    void draw(Matrix4f matrix,double x,double y,double z,long resourceGeneration);
    void requestInvalidation();
    JsonObject diagnostics();
    void close();
}
