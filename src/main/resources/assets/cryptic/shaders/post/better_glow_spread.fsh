#version 330

// Widens the glow along one axis, keeping the colour it was drawn in.
//
// Vanilla averages the colours of every sample it takes, lit or not, so an
// edge pixel with one lit neighbour comes back at a fifth of its brightness.
// That is the dark fringe around a vanilla glow. Weighting each sample by
// whether it is lit at all, and dividing by that count instead, keeps the
// colour whole however little of the pixel the entity covers.
//
// Presence is deliberately not the alpha itself: Cryptic writes the fill
// strength into that alpha, and the line has to stay solid whatever it says.
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

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 texelStep = (1.0 / InSize) * SpreadDir;

    vec3 color = vec3(0.0);
    float weight = 0.0;
    float coverage = 0.0;

    for (float offset = -Radius; offset <= Radius; offset += 1.0) {
        vec4 sampled = texture(InSampler, texCoord + texelStep * offset);
        float present = step(0.002, sampled.a);
        color += sampled.rgb * present;
        weight += present;
        coverage = max(coverage, present);
    }

    fragColor = vec4(weight > 0.0 ? color / weight : vec3(0.0), coverage);
}
