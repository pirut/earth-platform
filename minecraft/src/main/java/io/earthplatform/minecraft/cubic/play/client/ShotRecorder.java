package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Saves a recorded shot's frames (see {@link EarthCinema}). With FFmpeg on the computer the frames
 * go straight into it and come out as one MP4; without it they are saved as numbered PNG images in
 * a folder, which any video editor can open as an image sequence. Both land in {@code earth-shots}
 * in the game folder. The shot waits for the writer, never the other way round, so a slow disk
 * costs time but no frames.
 */
final class ShotRecorder {
    /** Where FFmpeg is, when it is not somewhere we look anyway. */
    private static final String FFMPEG = System.getProperty("earthplatform.shot.ffmpeg", "");
    /** x264's quality: lower is better and bigger; 16 looks lossless at a glance. */
    private static final int CRF = Math.max(0, Math.min(40, Integer.getInteger("earthplatform.shot.crf", 16)));
    /** Frames read off the graphics card but not yet written; each is the whole picture, 8 MB at 1080p. */
    private static final int WAITING = 3;

    private final int width, height;
    private final Path output;
    @Nullable private final Process ffmpeg;
    @Nullable private final WritableByteChannel pipe;
    @Nullable private final Path log;
    private final ExecutorService writers;
    private final Semaphore room = new Semaphore(WAITING);
    private volatile String failure;
    private int frames;

    private ShotRecorder(int width, int height, Path output, @Nullable Process ffmpeg, @Nullable Path log) {
        this.width = width;
        this.height = height;
        this.output = output;
        this.ffmpeg = ffmpeg;
        this.log = log;
        pipe = ffmpeg == null ? null : Channels.newChannel(ffmpeg.getOutputStream());
        // One writer keeps a video's frames in order; images are named, so two may work at once.
        writers = Executors.newFixedThreadPool(ffmpeg == null ? 2 : 1, task -> {
            Thread thread = new Thread(task, "Earth shot writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts a recording of the game's picture as it is now, named for the shot. */
    static ShotRecorder open(String name, int fps) throws IOException {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        Path folder = Minecraft.getInstance().gameDirectory.toPath().resolve("earth-shots");
        Files.createDirectories(folder);
        String stamp = name + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"));
        String program = findFfmpeg();
        if (program != null) {
            Path video = folder.resolve(stamp + ".mp4"), log = folder.resolve(stamp + ".log");
            try {
                Process process = new ProcessBuilder(command(program, target.width, target.height, fps, video))
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                return new ShotRecorder(target.width, target.height, video, process, log);
            } catch (IOException e) {
                EarthPlatformMod.LOGGER.warn("Could not start FFmpeg at {} ({}); saving images instead", program, e.toString());
            }
        }
        Path frames = folder.resolve(stamp);
        Files.createDirectories(frames);
        return new ShotRecorder(target.width, target.height, frames, null, null);
    }

    /** FFmpeg's arguments: raw frames in, bottom row first as the graphics card hands them over, an MP4 that plays everywhere out. */
    static List<String> command(String program, int width, int height, int fps, Path video) {
        return List.of(program, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height,
                "-r", Integer.toString(fps), "-i", "-",
                // H.264 in the usual pixel format needs even sides.
                "-vf", "vflip,crop=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-preset", "medium", "-crf", Integer.toString(CRF),
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", video.toString());
    }

    /**
     * FFmpeg, where one is: the path given with {@code -Dearthplatform.shot.ffmpeg}, in the game
     * folder (where Replay Mod's users keep theirs), on the PATH, or where the usual installers on
     * a Mac put it, which a launcher started from the Dock does not have on its PATH.
     */
    @Nullable static String findFfmpeg() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "ffmpeg.exe" : "ffmpeg";
        List<Path> places = new ArrayList<>();
        if (!FFMPEG.isEmpty()) places.add(Path.of(FFMPEG));
        Path game = Minecraft.getInstance().gameDirectory.toPath();
        places.add(game.resolve(exe));
        places.add(game.resolve("ffmpeg").resolve(exe));
        places.add(game.resolve("ffmpeg").resolve("bin").resolve(exe));
        String path = System.getenv("PATH");
        if (path != null) for (String folder : path.split(File.pathSeparator)) {
            if (folder.isBlank()) continue;
            try { places.add(Path.of(folder).resolve(exe)); } catch (RuntimeException notAPath) { /* skip it */ }
        }
        if (!windows) for (String folder : List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/opt/local/bin")) places.add(Path.of(folder, exe));
        for (Path place : places) if (Files.isRegularFile(place) && Files.isExecutable(place)) return place.toString();
        return null;
    }

    boolean video() { return ffmpeg != null; }
    Path output() { return output; }
    int frames() { return frames; }

    /** Why the recording broke, or null while all is well. */
    @Nullable String failure() { return failure; }

    /**
     * Takes the picture just drawn. Waits while {@link #WAITING} earlier frames are still being
     * written. False when the recording cannot go on: the window changed size or the writer failed.
     */
    boolean capture() {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width != width || target.height != height) failure = "the window changed size";
        if (failure != null) return false;
        try {
            while (!room.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                if (failure != null) return false;
                if (ffmpeg != null && !ffmpeg.isAlive()) { failure = "FFmpeg stopped" + logTail(); return false; }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        RenderSystem.bindTexture(target.getColorTextureId());
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int number = ++frames;
        writers.execute(() -> {
            try {
                if (failure == null) write(pixels, number);
            } catch (IOException | RuntimeException e) {
                failure = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                MemoryUtil.memFree(pixels);
                room.release();
            }
        });
        return true;
    }

    private void write(ByteBuffer pixels, int number) throws IOException {
        if (pipe != null) {
            while (pixels.hasRemaining()) pipe.write(pixels);
            return;
        }
        // The card's rows run bottom to top and an image's top to bottom; its bytes are already in NativeImage's order.
        pixels.order(ByteOrder.LITTLE_ENDIAN);
        try (NativeImage image = new NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) {
                int row = (height - 1 - y) * width * 4;
                for (int x = 0; x < width; x++) image.setPixelRGBA(x, y, pixels.getInt(row + x * 4) | 0xFF000000);
            }
            image.writeToFile(output.resolve(String.format(Locale.ROOT, "%06d.png", number)));
        }
    }

    /**
     * Finishes the file once every frame is written; call from any thread but the writers'. Returns
     * why it failed, or null when the video or the images are whole.
     */
    @Nullable String finish() {
        writers.shutdown();
        try {
            if (!writers.awaitTermination(10, TimeUnit.MINUTES)) failure = "the frames took too long to write";
            if (ffmpeg != null) {
                try (OutputStream in = ffmpeg.getOutputStream()) { in.flush(); } catch (IOException e) { if (failure == null) failure = "FFmpeg stopped" + logTail(); }
                if (!ffmpeg.waitFor(10, TimeUnit.MINUTES)) { ffmpeg.destroyForcibly(); failure = "FFmpeg did not finish"; }
                else if (ffmpeg.exitValue() != 0 && failure == null) failure = "FFmpeg failed" + logTail();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "interrupted";
        }
        // FFmpeg's notes matter only when something went wrong.
        if (failure == null && log != null) try { Files.deleteIfExists(log); } catch (IOException e) { /* it can stay */ }
        return failure;
    }

    private String logTail() {
        if (log == null) return "";
        try {
            List<String> lines = Files.readAllLines(log);
            return lines.isEmpty() ? "" : ": " + lines.get(lines.size() - 1);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
