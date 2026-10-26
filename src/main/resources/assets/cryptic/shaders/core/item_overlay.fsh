#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

// Item Outline's overlay, drawn over the held item where it already is (depth
// equal), the way the enchantment glint is. Sampler0 is the module's params
// texture: pixel 2 holds the strength (red) and effect (green), pixel 3 the
// colour with the speed in alpha. Sampler1 is the vanilla glint texture.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 itemCoord;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

void main() {
    vec4 extras = texelFetch(Sampler0, ivec2(2, 0), 0);
    vec4 params = texelFetch(Sampler0, ivec2(3, 0), 0);
    float amount = extras.r;
    if (amount <= 0.0) {
        discard;
    }
    int effect = int(extras.g * 255.0 + 0.5);
    vec3 color = params.rgb;
    // A day is 1200 seconds of game time.
    float t = GameTime * 1200.0 * params.a * 255.0 / 50.0;
    // In the item's pixels: sixteen across.
    vec2 q = itemCoord * 16.0;

    vec3 rgb = color;
    float alpha;
    if (effect == 0) {
        alpha = 0.65;
    } else if (effect == 1) {
        alpha = 0.65 * (0.55 + 0.45 * sin(t * 3.0));
    } else if (effect == 2) {
        // Diagonal bands of light sweeping over the item.
        float phase = fract((q.x + q.y) / 16.0 - t * 0.25);
        float light = smoothstep(0.07, 0.0, abs(phase - 0.5));
        rgb = mix(color, vec3(1.0), 0.5 * light);
        alpha = 0.08 + 0.92 * light;
    } else if (effect == 3) {
        // Soft blobs drifting slowly over the surface.
        float blobs = noise(q / 4.0 + vec2(t * 0.18, t * 0.11)) * 0.6 + noise(q / 2.35 - vec2(t * 0.13, -t * 0.16)) * 0.4;
        alpha = smoothstep(0.52, 0.85, blobs);
    } else if (effect == 4) {
        // Bands of colour rolling along.
        alpha = 0.7 * (0.5 + 0.5 * sin((q.x * 0.7 + q.y) * 0.8 - t * 2.5));
    } else {
        // Minecraft's glint: its texture, tilted 20 degrees and drifting on two clocks, here in
        // the overlay's colour.
        float seconds = t * 8.0;
        vec2 drift = vec2(-fract(seconds / 110.0), fract(seconds / 30.0));
        float angle = 3.14159265 / 9.0;
        vec2 uv = itemCoord * 0.25;
        uv = vec2(uv.x * cos(angle) - uv.y * sin(angle), uv.x * sin(angle) + uv.y * cos(angle)) + drift;
        vec3 glint = texture(Sampler1, fract(uv)).rgb;
        float strength = max(glint.r, max(glint.g, glint.b));
        rgb = color * strength * strength * 0.8;
        alpha = 1.0;
    }

    float fade = 1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    fragColor = vec4(rgb, clamp(alpha * amount, 0.0, 1.0) * fade);
}
