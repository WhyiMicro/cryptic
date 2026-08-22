#version 330

// Widens the glow along the other axis, then splits the result in two: the
// line, which is kept off the entity so its own colours stay visible, and a
// wash over the entity itself in the same colour.
//
// How solid that wash is comes from the alpha Cryptic wrote into the entity's
// outline colour, since a pass cannot be told anything after it is loaded. The
// lowest step means no wash at all, which is why presence is tested against a
// threshold below it rather than against zero.
//
// Adapted from Smoother Glowing (MIT), which fixes the same two problems in vanilla.

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GlowConfig {
    vec2 SpreadDir;
    float Radius;
};

uniform sampler2D InSampler;
uniform sampler2D MaskSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 texelStep = (1.0 / InSize) * SpreadDir;

    vec3 color = vec3(0.0);
    float weight = 0.0;
    float coverage = 0.0;

    for (float offset = -Radius; offset <= Radius; offset += 1.0) {
        vec4 sampled = texture(InSampler, texCoord + texelStep * offset);
        color += sampled.rgb * sampled.a;
        weight += sampled.a;
        coverage = max(coverage, sampled.a);
    }

    vec4 entity = texture(MaskSampler, texCoord);
    float present = step(0.002, entity.a);
    // One step of alpha is the off position, so the wash starts above it.
    float fill = max(entity.a * 255.0 - 1.0, 0.0) / 254.0;

    float lineAlpha = coverage * (1.0 - present);
    float fillAlpha = present * fill;
    float total = lineAlpha + fillAlpha;

    vec3 lineColor = weight > 0.0 ? color / weight : vec3(0.0);
    vec3 mixedColor = total > 0.0 ? (lineColor * lineAlpha + entity.rgb * fillAlpha) / total : vec3(0.0);
    fragColor = vec4(mixedColor, total);
}
