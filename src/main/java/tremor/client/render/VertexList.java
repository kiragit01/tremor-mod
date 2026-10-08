package tremor.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;

import java.util.Arrays;

/**
 * Recorded block-model vertices (block-local positions), replayed every frame at a new offset. Doubles as the
 * {@link VertexConsumer} that records them, so the vanilla model tesselator can bake straight into it.
 */
final class VertexList implements VertexConsumer {
    /** x, y, z, u, v, nx, ny, nz as floats + color, light as raw int bits. */
    static final int STRIDE = 10;
    /** How far (blocks) a face turned inside out ({@link #emitInside}) lies inside the face of the voxel. */
    static final float INSET_HALF = 0.002f;

    private float[] data = new float[STRIDE * 24];
    private int size; // in vertices

    int size() {
        return size;
    }

    float x(int i) {
        return data[i * STRIDE];
    }

    float y(int i) {
        return data[i * STRIDE + 1];
    }

    float z(int i) {
        return data[i * STRIDE + 2];
    }

    /** Emits every vertex translated by {@code (dx, dy, dz)}. */
    void emit(VertexConsumer out, float dx, float dy, float dz) {
        float[] d = data;
        for (int i = 0, o = 0; i < size; i++, o += STRIDE) {
            put(out, d[o] + dx, d[o + 1] + dy, d[o + 2] + dz, Float.floatToRawIntBits(d[o + 8]), d[o + 3],
                    d[o + 4], Float.floatToRawIntBits(d[o + 9]), d[o + 5], d[o + 6], d[o + 7]);
        }
    }

    /**
     * Emits every quad turned inside out, translated by {@code (dx, dy, dz)}: its corners in the opposite order and
     * its normal reversed, so that it faces into the voxel and is seen from inside it (a camera inside a raised copy);
     * pulled {@value #INSET_HALF} towards the middle of the voxel, so that it lies in front of the faces of whatever
     * touches the voxel there, and lit as a face turned the other way would be: its colour (which carries the
     * shading of its own direction) times {@code shade[direction]}, by the {@link Direction} ordinal of the face's
     * outward normal. Quads only (4 vertices each, as the chunk layers are).
     */
    void emitInside(VertexConsumer out, float dx, float dy, float dz, float[] shade) {
        float[] d = data;
        float pull = 2 * INSET_HALF;
        for (int q = 0; q + 4 <= size; q += 4) {
            int first = q * STRIDE;
            float factor = shade[Direction.getNearest(d[first + 5], d[first + 6], d[first + 7]).ordinal()];
            for (int j = 3; j >= 0; j--) {
                int o = (q + j) * STRIDE;
                float x = d[o] + (0.5f - d[o]) * pull;
                float y = d[o + 1] + (0.5f - d[o + 1]) * pull;
                float z = d[o + 2] + (0.5f - d[o + 2]) * pull;
                put(out, x + dx, y + dy, z + dz, scale(Float.floatToRawIntBits(d[o + 8]), factor), d[o + 3],
                        d[o + 4], Float.floatToRawIntBits(d[o + 9]), -d[o + 5], -d[o + 6], -d[o + 7]);
            }
        }
    }

    /** An ARGB colour with red, green and blue times {@code factor} (clamped), alpha kept. */
    private static int scale(int argb, float factor) {
        int r = Math.min(255, Math.round((argb >> 16 & 0xFF) * factor));
        int g = Math.min(255, Math.round((argb >> 8 & 0xFF) * factor));
        int b = Math.min(255, Math.round((argb & 0xFF) * factor));
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Emits vertex {@code i} at an explicit position (used by the warp style). */
    void emitAt(VertexConsumer out, int i, float x, float y, float z) {
        int o = i * STRIDE;
        float[] d = data;
        put(out, x, y, z, Float.floatToRawIntBits(d[o + 8]), d[o + 3], d[o + 4], Float.floatToRawIntBits(d[o + 9]),
                d[o + 5], d[o + 6], d[o + 7]);
    }

    /** One whole vertex through the bulk method (the fast path of a {@code BufferBuilder} in the block format). */
    private static void put(VertexConsumer out, float x, float y, float z, int argb, float u, float v, int light,
                            float normalX, float normalY, float normalZ) {
        out.vertex(x, y, z, (argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f,
                (argb >>> 24) / 255f, u, v, OverlayTexture.NO_OVERLAY, light, normalX, normalY, normalZ);
    }

    // ---- recording ----

    @Override
    public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v,
                       int packedOverlay, int packedLight, float normalX, float normalY, float normalZ) {
        int o = grow();
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        data[o + 3] = u;
        data[o + 4] = v;
        data[o + 5] = normalX;
        data[o + 6] = normalY;
        data[o + 7] = normalZ;
        data[o + 8] = Float.intBitsToFloat((int) (alpha * 255) << 24 | (int) (red * 255) << 16
                | (int) (green * 255) << 8 | (int) (blue * 255));
        data[o + 9] = Float.intBitsToFloat(packedLight);
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        int o = grow();
        data[o] = (float) x;
        data[o + 1] = (float) y;
        data[o + 2] = (float) z;
        data[o + 8] = Float.intBitsToFloat(-1);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        data[last() + 8] = Float.intBitsToFloat(alpha << 24 | red << 16 | green << 8 | blue);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        int o = last();
        data[o + 3] = u;
        data[o + 4] = v;
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        return this; // overlay is not part of terrain vertices
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        data[last() + 9] = Float.intBitsToFloat(u & 0xFFFF | v << 16);
        return this;
    }

    @Override
    public VertexConsumer normal(float normalX, float normalY, float normalZ) {
        int o = last();
        data[o + 5] = normalX;
        data[o + 6] = normalY;
        data[o + 7] = normalZ;
        return this;
    }

    @Override
    public void endVertex() {
        // vertex(x, y, z) already made room for it
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        // block models always give their colour
    }

    @Override
    public void unsetDefaultColor() {
    }

    private int grow() {
        int o = size * STRIDE;
        if (o + STRIDE > data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        size++;
        return o;
    }

    private int last() {
        return (size - 1) * STRIDE;
    }
}
