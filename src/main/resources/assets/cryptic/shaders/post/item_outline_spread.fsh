#version 330

// The first half of a distance transform over the held item. The hand pass is
// the only thing drawn since depth was cleared, so any depth at all is the item.
// Each pixel stores how far along its row the nearest item pixel is, up to the
// reach (the outline's width, or the sparkles' band); the ring pass finishes
// the job down the columns.

uniform sampler2D MainDepthSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;

out vec4 fragColor;

const int MAX_REACH = 32;

void main() {
    ivec2 size = textureSize(MainDepthSampler, 0);
    ivec2 p = ivec2(texCoord * vec2(size));
    int reach = int(texelFetch(ParamsSampler, ivec2(1, 0), 0).r * 255.0 + 0.5);

    float nearest = 255.0;
    for (int dx = -MAX_REACH; dx <= MAX_REACH; dx++) {
        if (abs(dx) > reach) continue;
        int x = p.x + dx;
        if (x < 0 || x >= size.x) continue;
        if (texelFetch(MainDepthSampler, ivec2(x, p.y), 0).r > 0.0) nearest = min(nearest, float(abs(dx)));
    }
    fragColor = vec4(nearest / 255.0, 0.0, 0.0, 1.0);
}
