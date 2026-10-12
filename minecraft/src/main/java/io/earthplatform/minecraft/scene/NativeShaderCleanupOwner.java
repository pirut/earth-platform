package io.earthplatform.minecraft.scene;

import java.util.Objects;
import java.util.function.Supplier;

/** One retained restoration, never a queue. Failure keeps ownership until exact retry succeeds. */
final class NativeShaderCleanupOwner {
    private static final class Entry {
        final Runnable cleanup;
        final Thread thread=Thread.currentThread();
        boolean complete;
        Entry(Runnable cleanup){this.cleanup=Objects.requireNonNull(cleanup);}
    }
    private Entry pending;
    private boolean capturing,restoring;
    Runnable capture(Supplier<Runnable> readOnlyCapture) {
        if(pending!=null||capturing||restoring)throw new IllegalStateException("Prior native shader cleanup remains owned");
        capturing=true;
        try {Entry entry=new Entry(readOnlyCapture.get());pending=entry;return ()->retry(entry);}
        finally {capturing=false;}
    }
    boolean retained(){return pending!=null||capturing||restoring;}
    void retry(){if(pending!=null)retry(pending);}
    private void retry(Entry entry) {
        if(Thread.currentThread()!=entry.thread)throw new IllegalStateException("Native shader cleanup requires its capture thread");
        if(entry.complete)return;
        if(pending!=entry||restoring)throw new IllegalStateException("Native shader cleanup ownership changed");
        restoring=true;
        try {entry.cleanup.run();entry.complete=true;pending=null;}
        finally {restoring=false;}
    }
}
