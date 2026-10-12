package io.earthplatform.compat.dh;

/** String-based linkage allows the engine to load safely when the optional DH mod is absent. */
final class InstalledDhApiProbe {
    private InstalledDhApiProbe(){}
    static InstalledDhResolver.Capabilities inspect(ClassLoader loader)throws ReflectiveOperationException{
        Class<?> api=Class.forName("com.seibel.distanthorizons.api.DhApi",true,loader);
        String version=(String)api.getMethod("getModVersion").invoke(null);
        String apiVersion=api.getMethod("getApiMajorVersion").invoke(null)+"."+api.getMethod("getApiMinorVersion").invoke(null)+"."+api.getMethod("getApiPatchVersion").invoke(null);
        Class<?> delayed=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed",true,loader);
        Object render=delayed.getField("renderProxy").get(null),config=delayed.getField("configs").get(null);
        if(render==null||config==null)return new InstalledDhResolver.Capabilities(api,version,apiVersion,false,"","","",false);
        Object graphics=call(loader,"com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig",config,"graphics");
        Object mode=call(loader,"com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig",graphics,"renderingMode");
        Object enabled=call(loader,"com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig",graphics,"renderingEnabled");
        try{return new InstalledDhResolver.Capabilities(api,version,apiVersion,true,
                String.valueOf(call(loader,"com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy",render,"getRenderingApi")),
                String.valueOf(call(loader,"com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy",render,"getRenderingEngine")),
                String.valueOf(call(loader,"com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue",mode,"getValue")),
                Boolean.TRUE.equals(call(loader,"com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue",enabled,"getValue")));
        }catch(java.lang.reflect.InvocationTargetException notReady){
            if(notReady.getCause() instanceof IllegalStateException)
                return new InstalledDhResolver.Capabilities(api,version,apiVersion,false,"","","",false);
            throw notReady;
        }
    }
    private static Object call(ClassLoader loader,String type,Object receiver,String method)throws ReflectiveOperationException{
        return Class.forName(type,false,loader).getMethod(method).invoke(receiver);
    }
}
