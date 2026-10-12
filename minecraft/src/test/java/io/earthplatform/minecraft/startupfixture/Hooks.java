package io.earthplatform.minecraft.startupfixture;

import java.util.ArrayList;
import java.util.List;

/** Observable marker calls only. No graphics, game construction or external mod classes. */
public final class Hooks {
    public static final List<String> EVENTS = new ArrayList<>();
    public static boolean FAIL_DRAW;
    public static void vanilla() { EVENTS.add("vanilla"); }
    public static void irisHead() { EVENTS.add("iris-head"); }
    public static void enterManagedCode() { EVENTS.add("managed-enter"); }
    public static void drawChunkLayer() { EVENTS.add("sodium-draw"); if (FAIL_DRAW) throw new IllegalStateException("fixture draw failure"); }
    public static void exitManagedCode() { EVENTS.add("managed-exit"); }
    public static void getInstance() { EVENTS.add("platform-hook"); }
    public static void dispatch() { EVENTS.add("native-dispatch"); }
    public static void irisTail() { EVENTS.add("iris-tail"); }
    private Hooks() {}
}
