package io.earthplatform.minecraft.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Versioned migration for the vanilla section's Y tag. This is not a cubic region format.
 * Default writes preserve vanilla bytes; wide writes require an explicit experimental opt-in.
 */
public final class SectionYCodec {
    public static final String OPT_IN = "earthplatform.experimental.wideSectionY";
    public static final String FORMAT_TAG = "earthplatform_section_y_format";
    public static final int FORMAT_VERSION = 1;
    private SectionYCodec() {}

    /** Signature is deliberately suitable for the narrowly checked ChunkSerializer hook. */
    public static int read(CompoundTag section, String name) {
        requireY(name);
        if (!section.contains(FORMAT_TAG)) {
            if (Boolean.getBoolean(OPT_IN) && !section.contains(name, Tag.TAG_BYTE)) throw new SectionYIntegrityError("Unversioned section Y must be a legacy signed byte in wide mode");
            return section.getByte(name);
        }
        if (!section.contains(FORMAT_TAG, Tag.TAG_INT) || section.getInt(FORMAT_TAG) != FORMAT_VERSION) {
            throw new SectionYIntegrityError("Unsupported Earth section-Y format; refusing coordinate corruption");
        }
        if (!Boolean.getBoolean(OPT_IN)) throw new SectionYIntegrityError("This save contains wide section Y; enable " + OPT_IN + " to read it safely");
        if (!section.contains(name, Tag.TAG_INT)) throw new SectionYIntegrityError("Versioned section Y must be a signed int");
        return section.getInt(name);
    }

    /** The hook removes the original I2B before entering here, preserving the full integer. */
    public static void write(CompoundTag section, String name, int sectionY) {
        requireY(name);
        if (Boolean.getBoolean(OPT_IN)) {
            section.putInt(FORMAT_TAG, FORMAT_VERSION);
            section.putInt(name, sectionY);
        } else {
            if (sectionY < Byte.MIN_VALUE || sectionY > Byte.MAX_VALUE) {
                throw new SectionYIntegrityError("Section Y " + sectionY + " would truncate in vanilla storage; wide-section format is disabled");
            }
            section.remove(FORMAT_TAG);
            section.putByte(name, (byte) sectionY);
        }
    }
    private static void requireY(String name) {
        if (!"Y".equals(name)) throw new IllegalArgumentException("Section-Y hook received an unexpected key");
    }
}
