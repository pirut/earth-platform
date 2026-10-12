package io.earthplatform.minecraft.survival.interaction.host;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Additional installed originals for MAIN-payload observation; never accepts development aliases. */
final class NativeLocalPayloadPins {
    private static volatile boolean checked;
    private static volatile int failed=-1;
    private static volatile String code="UNCHECKED",observed="NONE";
    private static final String[][] PINS={
        {"net/neoforged/neoforge/network/handling/MainThreadPayloadHandler.class","3a7313538c0834ab98ea0e14db431c062a158e598c850224f934f0b173d0a60e"},
        {"net/neoforged/neoforge/network/handling/ClientPayloadContext.class","7f6c5bfdb89bc1f139fdc1088c6c83eab21f7fa5407903473aa8528152de24b9"},
        {"net/neoforged/neoforge/network/handling/IPayloadContext.class","8812a7faba3bd2a66159d2b388054a7628868415f9227061603cee8f56494a59"},
        {"net/neoforged/neoforge/network/registration/NetworkRegistry.class","68d5cf8a94f4d15812e3bc2ec8a547a66b717eebb3ae0490b791eab2dedbdbb8"},
        {"net/minecraft/util/thread/ReentrantBlockableEventLoop.class","dd13ce106a52044e947e479a1c921a68ccc52aac9b2c75d955767e91c926fa1a"}
    };
    private NativeLocalPayloadPins(){}
    static boolean qualified(){return checked;}
    static boolean check(){
        try{
            byte[] scratch=new byte[4096];
            for(int i=0;i<PINS.length;i++){
                failed=i;observed="NONE";code="CHECKING";String[] pin=PINS[i];
                ClassLoader loader=pin[0].startsWith("net/minecraft/")?net.minecraft.network.Connection.class.getClassLoader()
                        :net.neoforged.neoforge.network.handling.MainThreadPayloadHandler.class.getClassLoader();
                try(var input=loader.getResourceAsStream(pin[0])){
                    if(input==null){code="MISSING";return false;}
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");int total=0;
                    for(int n;(n=input.read(scratch))>=0;){total=Math.addExact(total,n);if(total>1_048_576){code="OVERSIZE";return false;}digest.update(scratch,0,n);}
                    observed=HexFormat.of().formatHex(digest.digest());if(!pin[1].equals(observed)){code="HASH";return false;}
                }
            }
            checked=true;failed=-1;return true;
        }catch(Exception failure){code="EXCEPTION";return false;}
    }
    static String failureSummary(){
        if(checked)return "LOCAL_PAYLOAD_PINS checked=true";
        if(failed<0||failed>=PINS.length)return "LOCAL_PAYLOAD_PINS checked=false code="+code;
        return "LOCAL_PAYLOAD_PINS checked=false index="+failed+" resource="+PINS[failed][0]+" expected="+PINS[failed][1]+" observed="+observed+" code="+code;
    }
}
