package io.earthplatform.compat.dh.shader;

/** Bounded two-owner cleanup before upstream may destroy borrowed state. Completed
 * branches are not replayed; failed branches remain owned and must succeed on retry. */
final class ShaderLifecycleBarrier {
    private boolean hostDone,farDone,running;
    void restore(Runnable host,Runnable far){
        if(running)throw new IllegalStateException("Reentrant shader lifecycle mutation");
        running=true;Throwable failure=null;
        try{
            if(!hostDone)try{host.run();hostDone=true;}catch(RuntimeException|Error problem){failure=problem;}
            if(!farDone)try{far.run();farDone=true;}catch(RuntimeException|Error problem){failure=append(failure,problem);}
        }finally{running=false;}
        propagate(failure);
    }
    boolean complete(){return hostDone&&farDone;}
    static Throwable append(Throwable primary,Throwable next){if(primary==null)return next;if(primary!=next)primary.addSuppressed(next);return primary;}
    static void propagate(Throwable failure){if(failure instanceof Error e)throw e;if(failure instanceof RuntimeException e)throw e;}
}
