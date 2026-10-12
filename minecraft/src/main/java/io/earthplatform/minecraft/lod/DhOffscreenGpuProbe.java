package io.earthplatform.minecraft.lod;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Optional offscreen diagnostic, invoked only by an explicit development command.
 * Reads unchanged official DH shaders and actual-DH-generated fixture vertices.
 * Never loads DH classes/entrypoints, registers a DH level, or changes the live world.
 */
public final class DhOffscreenGpuProbe {
    private static final int SIZE = 512;
    private static final int PAGE_HEIGHT = 2048;
    private static final String JAR_SHA = "864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab";
    private static final String FIXTURE_SHA = "42052ba3c9dac62e67ec3297ae3d802695a97a5e092de66ea071f45cd8ff51b9";
    private static final String VERT_PATH = "assets/distanthorizons/shaders/terrain/gl/vert.vert";
    private static final String FRAG_PATH = "assets/distanthorizons/shaders/terrain/gl/frag.frag";
    private static final String VERT_SHA = "ebd4e079225b7827624cf5652de390c441314f72134084099940f4c44457663d";
    private static final String FRAG_SHA = "0b08a09258784e58fcd17f5cd46264ed2e42a962368519e667270183ef1df37e";
    private static boolean running;
    private record Quad(int pageY, int stripe, int bottom, int top, int argb, byte[] bytes) { }
    private record Fixture(String name, long originX, int bottom, int top, List<Quad> quads) { }
    private record Slot(String fixture, int pageY, int stripe) { }
    private record Stamp(long epoch, long leaseGeneration, long revision) { }
    private record Job(Slot slot, Stamp stamp, Quad quad) { }
    private static final class Counters {
        long drawCalls, vertexUploads, vertices, rejectedJobs, rgbaReadbacks, depthReadbacks, pixelsCompared;
    }
    private static final class Resources {
        int vertexShader, fragmentShader, program, vao, vbo, ibo, framebuffer, color, depth, light, atlas;
        void destroy() {
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            for (int id : new int[]{color, depth, light, atlas}) if (id != 0) glDeleteTextures(id);
            if (vbo != 0) glDeleteBuffers(vbo);
            if (ibo != 0) glDeleteBuffers(ibo);
            if (vao != 0) glDeleteVertexArrays(vao);
            if (program != 0) glDeleteProgram(program);
            if (vertexShader != 0) glDeleteShader(vertexShader);
            if (fragmentShader != 0) glDeleteShader(fragmentShader);
        }
        boolean deleted() {
            return (framebuffer == 0 || !glIsFramebuffer(framebuffer))
                    && (vbo == 0 || !glIsBuffer(vbo)) && (ibo == 0 || !glIsBuffer(ibo))
                    && (vao == 0 || !glIsVertexArray(vao)) && (program == 0 || !glIsProgram(program))
                    && (vertexShader == 0 || !glIsShader(vertexShader)) && (fragmentShader == 0 || !glIsShader(fragmentShader))
                    && Arrays.stream(new int[]{color, depth, light, atlas}).allMatch(id -> id == 0 || !glIsTexture(id));
        }
    }

    public static String run() throws Exception {
        if (!RenderSystem.isOnRenderThread()) throw new IllegalStateException("DH GPU fixture requires the actual render thread");
        if (running) throw new IllegalStateException("DH GPU fixture already running");
        running = true;
        Path output = Path.of("earthplatform-evidence", "dh-gpu", Long.toString(System.currentTimeMillis())).toAbsolutePath();
        Files.createDirectories(output);
        JsonObject report = new JsonObject();
        report.addProperty("scope", "Actual DH-generated VBO bytes and unchanged official DH terrain shaders; isolated offscreen GL fixture");
        report.addProperty("distantHorizons", "3.3.3-1.21.1");
        report.addProperty("jarSha256", JAR_SHA); report.addProperty("fixtureSha256", FIXTURE_SHA);
        report.addProperty("vertexShaderSha256", VERT_SHA); report.addProperty("fragmentShaderSha256", FRAG_SHA);
        report.addProperty("dhModLoadedByProbe", false); report.addProperty("nativeWorldIntegrated", false);
        report.addProperty("dropInCompatibility", false); report.addProperty("liveWorldChanged", false);
        report.addProperty("gpuVendor", glGetString(GL_VENDOR)); report.addProperty("gpuRenderer", glGetString(GL_RENDERER));
        report.addProperty("glVersion", glGetString(GL_VERSION));
        Resources resources = new Resources(); Counters counters = new Counters(); State state = null;
        ByteBuffer rgba = null; FloatBuffer depthReadback = null; Throwable failure = null;
        try {
            Path jar = configuredPath("earthplatform.lodProbeJar", "EARTH_DH_PROBE_JAR");
            Path fixturePath = configuredPath("earthplatform.lodProbeFixture", "EARTH_DH_PROBE_FIXTURE");
            require(Files.size(jar) == 28_289_212, "Wrong official DH artifact size");
            require(sha256(jar).equals(JAR_SHA), "Wrong official DH artifact hash");
            require(Files.size(fixturePath) == 16698 && sha256(fixturePath).equals(FIXTURE_SHA), "Wrong pinned fixture hash/size");
            List<Fixture> fixtures = readFixtures(fixturePath);
            String vertex; String fragment;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                vertex = shader(zip, VERT_PATH, VERT_SHA); fragment = shader(zip, FRAG_PATH, FRAG_SHA);
            }
            require(glGetError() == GL_NO_ERROR, "Preexisting OpenGL error; fixture declined to alter state");
            state = new State();
            resources.vertexShader = compile(GL_VERTEX_SHADER, vertex);
            resources.fragmentShader = compile(GL_FRAGMENT_SHADER, fragment);
            resources.program = glCreateProgram();
            glAttachShader(resources.program, resources.vertexShader); glAttachShader(resources.program, resources.fragmentShader);
            glBindAttribLocation(resources.program, 0, "vPosition"); glBindAttribLocation(resources.program, 1, "color");
            glBindAttribLocation(resources.program, 2, "irisData"); glLinkProgram(resources.program);
            require(glGetProgrami(resources.program, GL_LINK_STATUS) == GL_TRUE, "DH shader link: " + glGetProgramInfoLog(resources.program));
            report.addProperty("shaderCompileAndLink", true);
            initializeResources(resources);
            rgba = MemoryUtil.memAlloc(SIZE * SIZE * 4); depthReadback = MemoryUtil.memAllocFloat(SIZE * SIZE);
            JsonArray cases = new JsonArray();
            for (Fixture fixture : fixtures) cases.add(renderCase(fixture, resources, rgba, depthReadback, counters, output));
            report.add("cases", cases);
            require(counters.drawCalls == 184 && counters.vertices == 736, "Unexpected total actual-DH geometry draw count");
            require(counters.rejectedJobs == 52, "Missing stale generation rejection checks");
            require(glGetError() == GL_NO_ERROR, "OpenGL error after fixture draw/readback");
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            try {
                if (state != null) state.restore();
                resources.destroy();
                boolean cleanup = resources.deleted(); report.addProperty("ownedGlObjectsDeleted", cleanup);
                boolean restored = state == null || state.matches(); report.addProperty("touchedGlStateRestored", restored);
                if (!cleanup || !restored) throw new IllegalStateException("GPU fixture cleanup/state restoration failed");
            } catch (Throwable cleanupFailure) {
                if (failure == null) failure = cleanupFailure; else failure.addSuppressed(cleanupFailure);
            }
            if (rgba != null) MemoryUtil.memFree(rgba);
            if (depthReadback != null) MemoryUtil.memFree(depthReadback);
            report.addProperty("drawCalls", counters.drawCalls); report.addProperty("vertexUploads", counters.vertexUploads);
            report.addProperty("actualDhVertices", counters.vertices); report.addProperty("rejectedStaleJobs", counters.rejectedJobs);
            report.addProperty("rgbaReadbacks", counters.rgbaReadbacks); report.addProperty("depthReadbacks", counters.depthReadbacks);
            report.addProperty("pixelsCompared", counters.pixelsCompared);
            report.addProperty("status", failure == null ? "PASS" : "FAIL");
            if (failure != null) report.addProperty("failure", failure.toString());
            running = false;
            Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        }
        if (failure != null) throw new Exception("DH GPU fixture failed; report: " + output.resolve("report.json"), failure);
        return "DH GPU fixture PASS: 13 images, 184 DH quads, 52 stale jobs rejected. " + output.resolve("report.json");
    }

    private static JsonObject renderCase(Fixture fixture, Resources r, ByteBuffer rgba, FloatBuffer depth,
                                          Counters counters, Path output) throws IOException {
        glBindFramebuffer(GL_FRAMEBUFFER, r.framebuffer); glViewport(0, 0, SIZE, SIZE);
        glClearColor(0, 0, 0, 0); glClearDepth(1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        Map<Slot, Stamp> liveSlots = new HashMap<>(); Stamp stamp = new Stamp(1, 2, 1);
        for (Quad quad : fixture.quads) liveSlots.put(new Slot(fixture.name, quad.pageY, quad.stripe), stamp);
        Quad first = fixture.quads.getFirst(); Slot slot = new Slot(fixture.name, first.pageY, first.stripe);
        // These model apply-time rejection, including same-epoch remove/re-add ABA. No async race is claimed.
        for (Job stale : List.of(new Job(slot, new Stamp(0, 2, 1), first),
                new Job(slot, new Stamp(1, 1, 1), first), new Job(slot, new Stamp(1, 2, 0), first),
                new Job(new Slot(fixture.name, first.pageY + 20, first.stripe), stamp, first)))
            require(!draw(stale, liveSlots, fixture, r, counters), "Stale GPU job was accepted");
        readRgba(rgba, counters);
        for (int i = 0; i < SIZE * SIZE * 4; i++) require(rgba.get(i) == 0, "Stale job changed framebuffer");
        counters.pixelsCompared += SIZE * SIZE;
        for (Quad quad : fixture.quads)
            require(draw(new Job(new Slot(fixture.name, quad.pageY, quad.stripe), stamp, quad), liveSlots, fixture, r, counters),
                    "Live page draw rejected");
        readRgba(rgba, counters); depth.clear(); glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        counters.depthReadbacks++;
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        long colored = 0; float expectedDepth = 15.0f / 63.0f;
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int index = (y * SIZE + x) * 4;
            int red = Byte.toUnsignedInt(rgba.get(index)), green = Byte.toUnsignedInt(rgba.get(index + 1));
            int blue = Byte.toUnsignedInt(rgba.get(index + 2)), alpha = Byte.toUnsignedInt(rgba.get(index + 3));
            int expected = 0;
            if (x >= 128 && x < 384 && y >= 192 && y < 320) {
                int stripe = (x - 128) / 32;
                int absoluteY = fixture.bottom + (y - 192) / 32;
                int page = Math.floorDiv(absoluteY, PAGE_HEIGHT);
                expected = ((stripe + page) & 1) == 0 ? 0xff3366cc : 0xffe6aa33;
                colored++;
            }
            require(Math.abs(red - ((expected >>> 16) & 255)) <= 1
                            && Math.abs(green - ((expected >>> 8) & 255)) <= 1
                            && Math.abs(blue - (expected & 255)) <= 1 && alpha == (expected >>> 24),
                    "RGBA mismatch " + fixture.name + " at " + x + "," + y);
            float wantedDepth = expected == 0 ? 1.0f : expectedDepth;
            require(Math.abs(depth.get(y * SIZE + x) - wantedDepth) < 0.00002f, "Depth mismatch " + fixture.name);
            image.setRGB(x, SIZE - 1 - y, (alpha << 24) | (red << 16) | (green << 8) | blue);
        }
        counters.pixelsCompared += SIZE * SIZE;
        require(colored == 32768, "Missing cross-page area");
        Path png = output.resolve(fixture.name + ".png"); require(ImageIO.write(image, "PNG", png.toFile()), "PNG encoder unavailable");
        JsonObject result = new JsonObject(); result.addProperty("name", fixture.name);
        result.addProperty("originX", fixture.originX); result.addProperty("nativeBottomY", fixture.bottom);
        result.addProperty("nativeTopYExclusive", fixture.top); result.addProperty("quads", fixture.quads.size());
        result.addProperty("coloredPixels", colored); result.addProperty("allRgbaAndDepthPixelsVerified", true);
        result.addProperty("staleOnlyFramebufferUnchanged", true); result.addProperty("image", png.toString());
        result.addProperty("imageSha256", sha256(png)); return result;
    }

    private static boolean draw(Job job, Map<Slot, Stamp> liveSlots, Fixture fixture, Resources r, Counters counters) {
        if (!job.stamp.equals(liveSlots.get(job.slot)) || job.slot.pageY != job.quad.pageY
                || job.slot.stripe != job.quad.stripe || !job.slot.fixture.equals(fixture.name)) {
            counters.rejectedJobs++; return false;
        }
        glUseProgram(r.program); glBindVertexArray(r.vao); glBindBuffer(GL_ARRAY_BUFFER, r.vbo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer vertices = stack.malloc(job.quad.bytes.length); vertices.put(job.quad.bytes).flip();
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        }
        double cameraX = fixture.originX + 4.0, cameraY = fixture.bottom + 2.0;
        // Deliberately subtract in double before converting to float at Earth-scale X and full-range Y.
        glUniform3f(glGetUniformLocation(r.program, "uModelOffset"), (float) (fixture.originX + job.quad.stripe - cameraX),
                (float) ((long) job.quad.pageY * PAGE_HEIGHT - cameraY), -16.0f);
        glUniform1f(glGetUniformLocation(r.program, "uWorldYOffset"), job.quad.pageY * (float) PAGE_HEIGHT);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
        counters.vertexUploads++; counters.drawCalls++; counters.vertices += 4; return true;
    }

    private static void initializeResources(Resources r) {
        glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS); glDepthMask(true);
        for (int cap : new int[]{GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_FRAMEBUFFER_SRGB, GL_MULTISAMPLE, GL_DEPTH_CLAMP}) glDisable(cap);
        glColorMask(true, true, true, true); glPixelStorei(GL_PACK_ALIGNMENT, 1); glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0); glPixelStorei(GL_PACK_SKIP_PIXELS, 0); glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0); glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0); glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        r.framebuffer = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, r.framebuffer);
        glActiveTexture(GL_TEXTURE0); r.color = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, SIZE, SIZE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, r.color, 0);
        r.depth = texture(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_FLOAT, SIZE, SIZE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, r.depth, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0); glReadBuffer(GL_COLOR_ATTACHMENT0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "Incomplete diagnostic FBO");
        r.light = glGenTextures(); glBindTexture(GL_TEXTURE_2D, r.light);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer white = stack.malloc(16 * 16 * 4); while (white.hasRemaining()) white.put((byte) 255); white.flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
        }
        glActiveTexture(GL_TEXTURE1); r.atlas = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, 1, 1);
        glUseProgram(r.program); glUniform1i(glGetUniformLocation(r.program, "uLightMap"), 0);
        glUniform1i(glGetUniformLocation(r.program, "uBlockAtlas"), 1);
        for (String name : List.of("uIsWhiteWorld", "uNoiseEnabled", "uDitherDhRendering")) glUniform1i(glGetUniformLocation(r.program, name), 0);
        for (String name : List.of("uMircoOffset", "uEarthRadius", "uClipDistance")) glUniform1f(glGetUniformLocation(r.program, name), 0);
        glUniform1f(glGetUniformLocation(r.program, "uFrameMod8"), -1);
        glUniform1f(glGetUniformLocation(r.program, "uViewWidth"), SIZE); glUniform1f(glGetUniformLocation(r.program, "uViewHeight"), SIZE);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16); new Matrix4f().ortho(-8, 8, -8, 8, 1, 64).get(matrix);
            glUniformMatrix4fv(glGetUniformLocation(r.program, "uCombinedMatrix"), false, matrix);
            r.vao = glGenVertexArrays(); glBindVertexArray(r.vao); r.vbo = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, r.vbo);
            r.ibo = glGenBuffers(); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, r.ibo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, stack.ints(0, 1, 2, 2, 3, 0), GL_STATIC_DRAW);
        }
        glEnableVertexAttribArray(0); glVertexAttribIPointer(0, 4, GL_UNSIGNED_SHORT, 16, 0L);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 16, 8L);
        glEnableVertexAttribArray(2); glVertexAttribIPointer(2, 4, GL_UNSIGNED_BYTE, 16, 12L);
    }
    private static int texture(int internal, int format, int type, int width, int height) {
        int id = glGenTextures(); glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, (ByteBuffer) null); return id;
    }
    private static void readRgba(ByteBuffer rgba, Counters counters) {
        rgba.clear(); glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, rgba); counters.rgbaReadbacks++;
    }
    private static int compile(int kind, String source) {
        int shader = glCreateShader(kind); glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            String error = glGetShaderInfoLog(shader); glDeleteShader(shader); throw new IllegalStateException("Official DH shader compile: " + error);
        }
        return shader;
    }
    private static String shader(ZipFile zip, String path, String expected) throws IOException {
        var entry = zip.getEntry(path); require(entry != null && entry.getSize() > 0 && entry.getSize() < 65536, "Invalid shader entry");
        byte[] data; try (InputStream in = zip.getInputStream(entry)) { data = in.readNBytes(65536); }
        require(sha256(data).equals(expected), "Official shader hash mismatch");
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
    private static Path configuredPath(String property, String environment) {
        String value = System.getProperty(property); if (value == null || value.isBlank()) value = System.getenv(environment);
        if (value == null || value.isBlank()) throw new IllegalStateException("Optional probe input missing: -D" + property + " or " + environment);
        return Path.of(value).toAbsolutePath().normalize();
    }
    private static List<Fixture> readFixtures(Path path) throws IOException {
        try (var in = new DataInputStream(Files.newInputStream(path))) {
            require(in.readInt() == 0x454c4f44 && in.readInt() == 1, "Fixture schema");
            require(in.readInt() == (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 0 : 1), "Fixture native byte order");
            int count = in.readInt(); require(count == 13, "Fixture case budget"); List<Fixture> fixtures = new ArrayList<>();
            for (int f = 0; f < count; f++) {
                String name = in.readUTF(); require(name.matches("[a-z0-9-]{1,40}"), "Fixture label");
                long origin = in.readLong(); int bottom = in.readInt(), top = in.readInt(), quadCount = in.readInt();
                require(Math.abs(origin) < 30_000_000 && bottom >= -11008 && top <= 8864 && top - bottom == 4, "Fixture bounds");
                require(quadCount == 8 || quadCount == 16, "Fixture quad budget"); List<Quad> quads = new ArrayList<>();
                for (int q = 0; q < quadCount; q++) {
                    int page = in.readInt(), stripe = in.readInt(), low = in.readInt(), high = in.readInt(), argb = in.readInt(), length = in.readInt();
                    require(page >= -6 && page <= 4 && stripe >= 0 && stripe < 8 && low >= bottom && high <= top && high > low,
                            "Fixture page bounds");
                    require(Math.floorDiv(low, PAGE_HEIGHT) == page && Math.floorDiv(high - 1, PAGE_HEIGHT) == page && length == 64,
                            "Fixture page-local buffer");
                    byte[] bytes = in.readNBytes(length); require(bytes.length == length, "Truncated vertices");
                    quads.add(new Quad(page, stripe, low, high, argb, bytes));
                }
                fixtures.add(new Fixture(name, origin, bottom, top, List.copyOf(quads)));
            }
            require(in.read() == -1, "Unexpected fixture trailing bytes"); return List.copyOf(fixtures);
        }
    }
    private static String sha256(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    /** Save/restore only states touched by this fixture; no Minecraft renderer state-cache calls. */
    static final class State {
        final int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING), packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        final int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING), activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        final int depthFunction = glGetInteger(GL_DEPTH_FUNC);
        final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        final float clearDepth = glGetFloat(GL_DEPTH_CLEAR_VALUE);
        final double[] depthRange=new double[2];
        final int[] viewport = new int[4]; final float[] clearColor = new float[4]; final boolean[] colorMask = new boolean[4];
        final int[] caps = {GL_BLEND, GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_FRAMEBUFFER_SRGB, GL_MULTISAMPLE, GL_DEPTH_CLAMP};
        final boolean[] enabled = new boolean[caps.length];
        final int[] pixelNames = {GL_PACK_ALIGNMENT, GL_UNPACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_PIXELS,
                GL_PACK_SKIP_ROWS, GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS};
        final int[] pixels = new int[pixelNames.length]; final int[] textures = new int[2];
        State() {
            glGetDoublev(GL_DEPTH_RANGE,depthRange);glGetIntegerv(GL_VIEWPORT, viewport); glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
            }
            for (int i = 0; i < caps.length; i++) enabled[i] = glIsEnabled(caps[i]);
            for (int i = 0; i < pixelNames.length; i++) pixels[i] = glGetInteger(pixelNames[i]);
            for (int i = 0; i < 2; i++) { glActiveTexture(GL_TEXTURE0 + i); textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D); }
            glActiveTexture(activeTexture);
        }
        void restore() {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo); glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]); glUseProgram(program); glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer); glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            for (int i = 0; i < 2; i++) { glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, textures[i]); }
            glActiveTexture(activeTexture);glDepthRange(depthRange[0],depthRange[1]); glDepthFunc(depthFunction); glDepthMask(depthMask); glClearDepth(clearDepth);
            glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            for (int i = 0; i < caps.length; i++) { if (enabled[i]) glEnable(caps[i]); else glDisable(caps[i]); }
            for (int i = 0; i < pixelNames.length; i++) glPixelStorei(pixelNames[i], pixels[i]);
        }
        boolean matches() {
            State after = new State();
            return drawFbo == after.drawFbo && readFbo == after.readFbo && program == after.program && vao == after.vao
                    && arrayBuffer == after.arrayBuffer && packBuffer == after.packBuffer && unpackBuffer == after.unpackBuffer
                    && activeTexture == after.activeTexture && depthFunction == after.depthFunction && depthMask == after.depthMask
                    && clearDepth == after.clearDepth && Arrays.equals(depthRange,after.depthRange) && Arrays.equals(viewport, after.viewport) && Arrays.equals(clearColor, after.clearColor)
                    && Arrays.equals(colorMask, after.colorMask) && Arrays.equals(enabled, after.enabled)
                    && Arrays.equals(pixels, after.pixels) && Arrays.equals(textures, after.textures);
        }
    }
}
