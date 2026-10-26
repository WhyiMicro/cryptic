package imicro.cryptic.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Gives each vertex of an item the UV of where it sits on the item's flat plane (its model x and
 * y, one unit across the item), whatever face it's on. Front, back and the thin edges then share
 * one surface, so a pattern runs over an edge instead of breaking at it, as it does with vanilla's
 * per-face decal projection.
 */
public final class ItemSpaceUv implements VertexConsumer {
    private final VertexConsumer delegate;
    private final Matrix4f inversePose;
    private final Vector3f model = new Vector3f();
    private float x;
    private float y;
    private float z;

    public ItemSpaceUv(VertexConsumer delegate, PoseStack.Pose pose) {
        this.delegate = delegate;
        this.inversePose = new Matrix4f(pose.pose()).invert();
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        this.delegate.setColor(-1);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        this.delegate.setColor(-1);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        Vector3f position = this.inversePose.transformPosition(this.x, this.y, this.z, this.model);
        this.delegate.setUv(position.x(), position.y());
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
