package imicro.cryptic.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Passes vertices through with every colour replaced by one, for drawing a model's shape. */
public final class FlatColor implements VertexConsumer {
    private final VertexConsumer delegate;
    private final int argb;

    public FlatColor(VertexConsumer delegate, int argb) {
        this.delegate = delegate;
        this.argb = argb;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        this.delegate.setColor(this.argb);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        this.delegate.setColor(this.argb);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        this.delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.delegate.setNormal(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        this.delegate.setLineWidth(width);
        return this;
    }
}
