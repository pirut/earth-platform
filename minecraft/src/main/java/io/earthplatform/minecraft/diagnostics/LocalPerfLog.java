package io.earthplatform.minecraft.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded local-only JSONL sink. File IO never runs on the render/server threads. */
public final class LocalPerfLog implements AutoCloseable {
    private final ArrayBlockingQueue<String> queue=new ArrayBlockingQueue<>(64);
    private final AtomicLong dropped=new AtomicLong();
    private final Path directory;
    private final int maxBytes,files;
    private final Thread worker;
    private final String header;
    private final int headerBytes;
    private volatile boolean closed,failed;
    public LocalPerfLog(Path directory) { this(directory,4<<20,3,null); }
    public LocalPerfLog(Path directory,String header) { this(directory,4<<20,3,header); }
    public LocalPerfLog(Path directory,int maxBytes,int files) { this(directory,maxBytes,files,null); }
    /** Repeats the immutable session identity/capabilities in each retained file. */
    public LocalPerfLog(Path directory,int maxBytes,int files,String header) {
        if(maxBytes<64||files<1||files>8)throw new IllegalArgumentException("Invalid log limits");
        this.directory=directory;this.maxBytes=maxBytes;this.files=files;
        this.header=header;headerBytes=header==null?0:header.getBytes(StandardCharsets.UTF_8).length+1;
        if(headerBytes>Math.min(16384,maxBytes/4))throw new IllegalArgumentException("Log header exceeds bound");
        worker=new Thread(this::writeLoop,"Earth performance log");worker.setDaemon(true);worker.start();
    }
    public boolean offer(String json) {
        if(closed||failed||json.length()>Math.min(16384,maxBytes/4)||!queue.offer(json)){dropped.incrementAndGet();return false;}
        return true;
    }
    public long dropped() { return dropped.get(); }
    public boolean failed() { return failed; }
    private Path file(int n) { return directory.resolve(n==0?"latest.jsonl":"latest."+n+".jsonl"); }
    private void rotate() throws IOException {
        if(files==1)Files.deleteIfExists(file(0));
        for(int i=files-1;i>=1;i--)if(Files.exists(file(i-1)))Files.move(file(i-1),file(i),StandardCopyOption.REPLACE_EXISTING);
    }
    private void writeHeader(BufferedWriter writer) throws IOException {
        if(header!=null){writer.write(header);writer.write('\n');writer.flush();}
    }
    private void writeLoop() {
        BufferedWriter writer=null;
        try {
            Files.createDirectories(directory);
            long bytes=Files.exists(file(0))?Files.size(file(0)):0;
            if(bytes+headerBytes>maxBytes){rotate();bytes=0;}
            writer=Files.newBufferedWriter(file(0),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            writeHeader(writer);bytes+=headerBytes;
            while(!closed||!queue.isEmpty()) {
                String line;
                try{line=queue.poll(250,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){continue;}
                if(line==null)continue;
                int size=line.getBytes(StandardCharsets.UTF_8).length+1;
                if(size>maxBytes-headerBytes){dropped.incrementAndGet();continue;}
                if(bytes+size>maxBytes) {
                    writer.close();
                    rotate();
                    writer=Files.newBufferedWriter(file(0),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
                    writeHeader(writer);bytes=headerBytes;
                }
                writer.write(line);writer.write('\n');writer.flush();bytes+=size;
            }
        }catch(IOException failure){failed=true;queue.clear();}
        finally {if(writer!=null)try{writer.close();}catch(IOException ignored){}}
    }
    /** Best-effort bounded shutdown; a slow disk never prevents the game from exiting. Not interrupted,
     *  since an interrupt during a write closes the channel and loses the last line; the poll wakes in 250 ms. */
    @Override public void close() {
        closed=true;
        try{worker.join(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
