package io.earthplatform.minecraft.coregate;

/** Each action needs a release before another press can trigger, including across a rebuilt view. */
final class CoreInputLatch {
    private boolean attackHeld,useHeld;
    CoreInputLatch(boolean attackAlreadyDown,boolean useAlreadyDown){attackHeld=attackAlreadyDown;useHeld=useAlreadyDown;}
    void observe(boolean attackDown,boolean useDown){if(!attackDown)attackHeld=false;if(!useDown)useHeld=false;}
    boolean claim(boolean place){if(place){if(useHeld)return false;useHeld=true;}else{if(attackHeld)return false;attackHeld=true;}return true;}
}
