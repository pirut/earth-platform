package io.earthplatform.minecraft.survival.interaction.host;

import net.minecraft.network.protocol.Packet;

/** Exact original PacketEncoder generic matcher, source-pinned through TypeParameterMatcher.
 * This predicate adds no virtual/native callback and is used only after exact codec admission. */
final class NativeOriginalPacketWriteShape {
    private NativeOriginalPacketWriteShape(){}
    static boolean accepts(Object message){return message instanceof Packet<?>;}
    /** Read only final matcher operands after the exact original PacketEncoder constructor returns. */
    static boolean exactMatcher(Object encoder){
        if(encoder==null||encoder.getClass()!=net.minecraft.network.PacketEncoder.class)return false;
        try{
            var field=io.netty.handler.codec.MessageToByteEncoder.class.getDeclaredField("matcher");field.setAccessible(true);
            Object matcher=field.get(encoder);if(matcher==null)return false;
            Class<?> type=matcher.getClass();
            if(type.getClassLoader()!=io.netty.util.internal.TypeParameterMatcher.class.getClassLoader()
                    ||!type.getName().equals("io.netty.util.internal.TypeParameterMatcher$ReflectiveMatcher"))return false;
            var operand=type.getDeclaredField("type");operand.setAccessible(true);
            return operand.get(matcher)==Packet.class;
        }catch(ReflectiveOperationException|RuntimeException failure){return false;}
    }
}
