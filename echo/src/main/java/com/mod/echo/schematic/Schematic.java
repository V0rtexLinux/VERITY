package com.mod.echo.schematic;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A minimal reader for the Sponge Schematic Format (.schem) — an open, versioned
 * NBT structure documented at github.com/SpongePowered/Schematic-Specification.
 *
 * <p>Only what ECHO actually needs is read: dimensions, the block palette, and the
 * block-data array. Block entities (chests with contents, signs with text, etc.)
 * and entities are intentionally not parsed — the builder places plain blocks,
 * which covers the vast majority of what makes a structure "look right".
 *
 * <p>This does not support the Litematica {@code .litematic} format, which uses a
 * different, compressed bit-packed layout — a file in that format is rejected
 * with a clear message rather than mis-parsed.
 */
public final class Schematic {

    public final int width;
    public final int height;
    public final int length;
    /** Palette id for every position, in Sponge's YZX order (x fastest, then z, then y). */
    public final int[] blocks;
    /** Palette id -> block state string, e.g. "minecraft:oak_stairs[facing=north,half=bottom]". */
    public final Map<Integer, String> palette;

    private Schematic(int width, int height, int length, int[] blocks, Map<Integer, String> palette) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.blocks = blocks;
        this.palette = palette;
    }

    public long blockCount() { return (long) width * height * length; }

    /** @throws IOException with a human-readable reason on any format problem. */
    public static Schematic parse(byte[] rawFile) throws IOException {
        byte[] data = looksGzipped(rawFile) ? gunzip(rawFile) : rawFile;
        NbtTag root = NbtTag.readNamed(new DataInputStream(new ByteArrayInputStream(data)));
        if (root.type != NbtTag.COMPOUND) {
            throw new IOException("Not a schematic file (no root NBT compound found).");
        }
        Map<String, NbtTag> fields = root.asCompound();

        // Real Sponge schematics nest everything one level under "Schematic" in some
        // exporters (notably older WorldEdit versions) and at the top level in others.
        if (fields.containsKey("Schematic") && fields.get("Schematic").type == NbtTag.COMPOUND) {
            fields = fields.get("Schematic").asCompound();
        }

        Integer width = intField(fields, "Width");
        Integer height = intField(fields, "Height");
        Integer length = intField(fields, "Length");
        if (width == null || height == null || length == null) {
            throw new IOException("Missing Width/Height/Length — not a recognised .schem file.");
        }

        NbtTag paletteTag = fields.get("Palette");
        if (paletteTag == null || paletteTag.type != NbtTag.COMPOUND) {
            throw new IOException("Missing block Palette — not a recognised .schem file.");
        }
        Map<Integer, String> palette = new HashMap<>();
        for (Map.Entry<String, NbtTag> e : paletteTag.asCompound().entrySet()) {
            if (e.getValue().type == NbtTag.INT) {
                palette.put(e.getValue().asInt(), e.getKey());
            }
        }

        NbtTag blockData = fields.get("BlockData");
        if (blockData == null || blockData.type != NbtTag.BYTE_ARRAY) {
            throw new IOException("Missing BlockData — not a recognised .schem file.");
        }
        int[] blocks = decodeVarIntArray(blockData.asByteArray(), (long) width * height * length);

        return new Schematic(width, height, length, blocks, palette);
    }

    /** Sponge packs the block array as a stream of unsigned LEB128 varints, one per block. */
    private static int[] decodeVarIntArray(byte[] raw, long expectedCount) throws IOException {
        int[] out = new int[(int) expectedCount];
        int index = 0;
        int i = 0;
        while (i < raw.length && index < out.length) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (i >= raw.length) throw new IOException("BlockData ended mid-value.");
                byte b = raw[i++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) throw new IOException("BlockData varint too long.");
            }
            out[index++] = value;
        }
        if (index != out.length) {
            throw new IOException("BlockData had " + index + " values, expected " + out.length + ".");
        }
        return out;
    }

    private static Integer intField(Map<String, NbtTag> fields, String name) {
        NbtTag tag = fields.get(name);
        if (tag == null) return null;
        return switch (tag.type) {
            case NbtTag.SHORT, NbtTag.INT -> tag.asInt();
            default -> null;
        };
    }

    private static boolean looksGzipped(byte[] data) {
        return data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ //
    //  A tiny, generic NBT reader — just enough tag types to walk any     //
    //  compound safely (unsupported nested values are skipped correctly  //
    //  rather than corrupting the rest of the stream).                   //
    // ------------------------------------------------------------------ //

    private static final class NbtTag {
        static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5,
                DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

        final int type;
        final Object value;

        NbtTag(int type, Object value) { this.type = type; this.value = value; }

        int asInt() { return ((Number) value).intValue(); }
        byte[] asByteArray() { return (byte[]) value; }
        @SuppressWarnings("unchecked")
        Map<String, NbtTag> asCompound() { return (Map<String, NbtTag>) value; }

        /** Reads a top-level named tag: type byte, name, then the payload. */
        static NbtTag readNamed(DataInputStream in) throws IOException {
            int type = in.readUnsignedByte();
            if (type == END) return new NbtTag(END, null);
            in.readUTF(); // name — irrelevant at the root
            return readPayload(in, type);
        }

        private static NbtTag readPayload(DataInputStream in, int type) throws IOException {
            return switch (type) {
                case BYTE -> new NbtTag(BYTE, in.readByte());
                case SHORT -> new NbtTag(SHORT, in.readShort());
                case INT -> new NbtTag(INT, in.readInt());
                case LONG -> new NbtTag(LONG, in.readLong());
                case FLOAT -> new NbtTag(FLOAT, in.readFloat());
                case DOUBLE -> new NbtTag(DOUBLE, in.readDouble());
                case BYTE_ARRAY -> {
                    int len = in.readInt();
                    byte[] b = new byte[Math.max(0, len)];
                    in.readFully(b);
                    yield new NbtTag(BYTE_ARRAY, b);
                }
                case STRING -> new NbtTag(STRING, in.readUTF());
                case LIST -> {
                    int elemType = in.readUnsignedByte();
                    int len = in.readInt();
                    Object[] items = new Object[Math.max(0, len)];
                    for (int i = 0; i < items.length; i++) {
                        items[i] = elemType == END ? null : readPayload(in, elemType).value;
                    }
                    yield new NbtTag(LIST, items);
                }
                case COMPOUND -> {
                    Map<String, NbtTag> map = new HashMap<>();
                    while (true) {
                        int childType = in.readUnsignedByte();
                        if (childType == END) break;
                        String name = in.readUTF();
                        map.put(name, readPayload(in, childType));
                    }
                    yield new NbtTag(COMPOUND, map);
                }
                case INT_ARRAY -> {
                    int len = in.readInt();
                    int[] arr = new int[Math.max(0, len)];
                    for (int i = 0; i < arr.length; i++) arr[i] = in.readInt();
                    yield new NbtTag(INT_ARRAY, arr);
                }
                case LONG_ARRAY -> {
                    int len = in.readInt();
                    long[] arr = new long[Math.max(0, len)];
                    for (int i = 0; i < arr.length; i++) arr[i] = in.readLong();
                    yield new NbtTag(LONG_ARRAY, arr);
                }
                default -> throw new IOException("Unknown NBT tag type " + type + " — the file may be corrupt.");
            };
        }
    }
}
