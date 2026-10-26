#version 330

// Finishes the distance to the item down each column. Red is the outline's
// strength, never over the item itself; green is how close the pixel is to the
// item within the reach, where sparkles may appear (1 on the item); blue is the
// glow fading out beyond the outline.
//
// Square measures distance the way the pixels step (the larger of the two axes),
// so the band keeps the item's square corners and has a hard edge. Glow fades
// out over the width; Smooth rounds the corners and softens the last pixel.
//
// Params is pixels the mod rewrites every frame, since a pass can't be handed
// values after it loads; see ItemOutline for the layout.

uniform sampler2D SpreadSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;

out vec4 fragColor;

const int MAX_REACH = 32;

void main() {
    ivec2 size = textureSize(SpreadSampler, 0);
    ivec2 p = ivec2(texCoord * vec2(size));
    vec4 config = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    float reach = floor(config.r * 255.0 + 0.5);
    int style = int(config.g * 255.0 + 0.5);
    float width = floor(config.a * 255.0 + 0.5);

    if (texelFetch(MainDepthSampler, p, 0).r > 0.0) {
        fragColor = vec4(0.0, 1.0, 0.0, 1.0);
        return;
    }

    float nearest = 1000.0;
    float nearestSquare = 1000.0;
    for (int dy = -MAX_REACH; dy <= MAX_REACH; dy++) {
        if (float(abs(dy)) > reach) continue;
        int y = p.y + dy;
        if (y < 0 || y >= size.y) continue;
        float dx = texelFetch(SpreadSampler, ivec2(p.x, y), 0).r * 255.0;
        if (dx > reach) continue;
        nearest = min(nearest, length(vec2(dx, float(dy))));
        nearestSquare = min(nearestSquare, max(dx, float(abs(dy))));
    }

    float strength = 0.0;
    if (width > 0.0) {
        if (style == 0) {
            strength = nearestSquare <= width ? 1.0 : 0.0;
        } else if (style == 1) {
            float t = clamp((nearest - 1.0) / width, 0.0, 1.0);
            strength = (1.0 - t) * (1.0 - t);
        } else {
            strength = clamp(width + 0.5 - nearest, 0.0, 1.0);
        }
    }
    float glowSize = floor(texelFetch(ParamsSampler, ivec2(5, 0), 0).r * 255.0 + 0.5);
    float glow = 0.0;
    if (glowSize > 0.0 && nearest < 1000.0) {
        float g = clamp(1.0 - (nearest - width - 0.5) / glowSize, 0.0, 1.0);
        glow = smoothstep(0.0, 1.0, g);
    }
    float near = nearest > reach ? 0.0 : 1.0 - nearest / (reach + 1.0);
    fragColor = vec4(strength, near, glow, 1.0);
}
