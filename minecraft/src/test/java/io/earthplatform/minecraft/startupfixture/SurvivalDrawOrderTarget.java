package io.earthplatform.minecraft.startupfixture;

/** Synthetic no-game target, used only by the real transformer ordering test. */
public final class SurvivalDrawOrderTarget {
    public void renderLevel(Object layer,double x,double y,double z,Object view,Object projection) {
        renderSectionLayer(layer,x,y,z,view,projection);
        SurvivalDrawOrderHooks.event("level-tail");
    }
    public void renderSectionLayer(Object layer,double x,double y,double z,Object view,Object projection) {
        SurvivalDrawOrderHooks.event("vanilla");
    }
}
