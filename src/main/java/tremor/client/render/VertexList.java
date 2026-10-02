package tremor.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.Arrays;

/**
 * Recorded block-model vertices (block-local positions), replayed every frame at a new offset. Doubles as the
 * {@link VertexConsumer} that records them, so the vanilla model tesselator can bake straight into it.
 */
final class VertexList implements VertexConsumer {
    /** x, y, z, u, v, nx, ny, nz as floats + color, light as raw int bits. */
    static final int STRIDE = 10;

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
            out.addVertex(d[o] + dx, d[o + 1] + dy, d[o + 2] + dz, Float.floatToRawIntBits(d[o + 8]),
                    d[o + 3], d[o + 4], OverlayTexture.NO_OVERLAY, Float.floatToRawIntBits(d[o + 9]),
                    d[o + 5], d[o + 6], d[o + 7]);
        }
    }

    /** Emits vertex {@code i} at an explicit position (used by the warp style). */
    void emitAt(VertexConsumer out, int i, float x, float y, float z) {
        int o = i * STRIDE;
        float[] d = data;
        out.addVertex(x, y, z, Float.floatToRawIntBits(d[o + 8]), d[o + 3], d[o + 4], OverlayTexture.NO_OVERLAY,
                Float.floatToRawIntBits(d[o + 9]), d[o + 5], d[o + 6], d[o + 7]);
    }

    // ---- recording ----

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int packedOverlay, int packedLight,
                          float normalX, float normalY, float normalZ) {
        int o = grow();
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        data[o + 3] = u;
        data[o + 4] = v;
        data[o + 5] = normalX;
        data[o + 6] = normalY;
        data[o + 7] = normalZ;
        data[o + 8] = Float.intBitsToFloat(color);
        data[o + 9] = Float.intBitsToFloat(packedLight);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        int o = grow();
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        data[o + 8] = Float.intBitsToFloat(-1);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        data[last() + 8] = Float.intBitsToFloat(alpha << 24 | red << 16 | green << 8 | blue);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        int o = last();
        data[o + 3] = u;
        data[o + 4] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this; // overlay is not part of terrain vertices
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        data[last() + 9] = Float.intBitsToFloat(u & 0xFFFF | v << 16);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
        int o = last();
        data[o + 5] = normalX;
        data[o + 6] = normalY;
        data[o + 7] = normalZ;
        return this;
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
