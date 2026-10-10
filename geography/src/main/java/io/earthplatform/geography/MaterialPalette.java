package io.earthplatform.geography;

/** The Minecraft adapter supplies registry state IDs; the pure-Java core assigns no Minecraft meaning. */
public record MaterialPalette(int air,int stone,int dirt,int grass,int water,int gravel) {
    public static final MaterialPalette DEFAULT = new MaterialPalette(0,1,2,3,4,5);
    public MaterialPalette { if ((air|stone|dirt|grass|water|gravel)<0) throw new IllegalArgumentException("Negative state ID"); }
}
