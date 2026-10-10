package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;

/** Bounded render/halo coordinates only; this does not change any Minecraft Level height. */
public enum SceneRenderEnvelope {
    NATIVE(-11008,8864), PLAYER_DIAGNOSTIC_PADDING(-11040,8896);
    private final int minY,maxY;
    SceneRenderEnvelope(int minY,int maxY){this.minY=minY;this.maxY=maxY;}
    public int minY(){return minY;}
    public int maxYExclusive(){return maxY;}
    public int height(){return maxY-minY;}
    public boolean contains(CubePos position){return position.minBlockY()>=minY&&position.minBlockY()+16<=maxY;}
}
