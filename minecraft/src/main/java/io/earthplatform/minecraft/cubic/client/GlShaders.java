package io.earthplatform.minecraft.cubic.client;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

/** Compiles and links the far renderer's own GL programs ({@link FarGpu} and {@link FarLod}). */
final class GlShaders {
    private GlShaders() {}

    /**
     * Links a program from vertex and fragment source and returns its id. On a compile or link error
     * it frees whatever it made and throws an {@link IllegalStateException} naming {@code name}, with
     * the driver's log and, for a compile error, the numbered source.
     */
    static int link(String vertexSource, String fragmentSource, String name) {
        int vertex = compile(GL20C.GL_VERTEX_SHADER, vertexSource, name);
        int fragment;
        try { fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource, name); }
        catch (IllegalStateException failure) { GL20C.glDeleteShader(vertex); throw failure; }
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, vertex);
        GL20C.glDetachShader(program, fragment);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException("Could not link " + name + ": " + log);
        }
        return program;
    }

    private static int compile(int type, String source, String name) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile " + name + (type == GL20C.GL_VERTEX_SHADER ? " vertex" : " fragment")
                    + " shader: " + log + "\n" + numbered(source));
        }
        return shader;
    }

    private static String numbered(String source) {
        StringBuilder out = new StringBuilder();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) out.append(i + 1).append(": ").append(lines[i]).append('\n');
        return out.toString();
    }
}
