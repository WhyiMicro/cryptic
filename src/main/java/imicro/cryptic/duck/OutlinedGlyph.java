package imicro.cryptic.duck;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

/**
 * Lets an underline or strikethrough draw its outline with its sheet glyph's texture coordinates,
 * which are private to the glyph.
 */
public interface OutlinedGlyph {
    void cryptic$rect(Matrix4fc pose, VertexConsumer buffer, float x0, float y0, float x1, float y1, float z, int color, int light);
}
