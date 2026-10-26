#version 330

#moj_import <minecraft:globals.glsl>

// Lays the outline around the item over the frame, then sparkles twinkling on
// and near it. The overlay is drawn with the item itself (core/item_overlay).

uniform sampler2D MainSampler;
uniform sampler2D RingSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    ivec2 p = ivec2(texCoord * vec2(size));
    vec4 main = texture(MainSampler, texCoord);
    vec4 ring = texelFetch(RingSampler, p, 0);
    vec4 outline = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 config = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 extras = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    vec3 sparkleColor = texelFetch(ParamsSampler, ivec2(4, 0), 0).rgb;
    // A day is 1200 seconds of game time.
    float seconds = GameTime * 1200.0;
    vec3 result = main.rgb;

    // The glow goes under the outline, as light added around it.
    float glowStrength = texelFetch(ParamsSampler, ivec2(5, 0), 0).g;
    result += outline.rgb * ring.b * glowStrength * (1.0 - ring.r);
    result = mix(result, outline.rgb, ring.r * outline.a);

    float sparkles = config.b;
    if (sparkles > 0.0 && ring.g > 0.0) {
        float starSize = max(extras.b * 255.0, 1.0);
        float cellSize = max(starSize * 3.0, 6.0);
        vec2 cell = floor(vec2(p) / cellSize);
        float seed = hash(cell);
        if (seed < sparkles) {
            vec2 centre = (cell + vec2(hash(cell + 1.7), hash(cell + 4.1)) * 0.3 + 0.35) * cellSize;
            float speed = extras.a * 255.0 / 50.0;
            float life = fract(seconds * speed * 0.5 + hash(cell + 9.3));
            float bright = sin(life * 3.14159);
            bright *= bright;
            vec2 d = abs(vec2(p) + 0.5 - centre);
            float star = max(1.0 - (d.x + d.y * 3.0) / starSize, 1.0 - (d.y + d.x * 3.0) / starSize);
            star = clamp(star, 0.0, 1.0) * bright * ring.g;
            result = mix(result, mix(sparkleColor, vec3(1.0), 0.6), star);
        }
    }

    fragColor = vec4(clamp(result, 0.0, 1.0), main.a);
}
