package io.earthplatform.minecraft.coregate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import net.minecraft.server.level.ServerPlayer;

/** Bounded diagnostic samples, never used as physics or terrain authority. */
final class CorePhysicsTrace {
    private static final int LIMIT=512;
    private final ArrayDeque<JsonObject> samples=new ArrayDeque<>();
    void record(ServerPlayer p,boolean ready,long revision,long generation) {
        var j=new JsonObject();j.addProperty("tick",p.tickCount);j.addProperty("x",p.getX());j.addProperty("feetY",p.getY());j.addProperty("z",p.getZ());
        j.addProperty("onGround",p.onGround());j.addProperty("verticalVelocity",p.getDeltaMovement().y);j.addProperty("collisionReady",ready);j.addProperty("revision",revision);j.addProperty("coverageGeneration",generation);
        j.addProperty("support",p.mainSupportingBlockPos.map(Object::toString).orElse("none"));
        if(samples.size()==LIMIT)samples.removeFirst();samples.addLast(j);
    }
    JsonArray snapshot(){var result=new JsonArray();for(var sample:samples)result.add(sample.deepCopy());return result;}
}
