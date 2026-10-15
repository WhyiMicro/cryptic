#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <cryptic:round_rect.glsl>

out vec4 fragColor;

// A rounded rectangle filled with the light-and-dark checkerboard a colour with
// some transparency is shown over. The cell size rides in on LineWidth, and the
// cells are counted from the top-left corner, so the pattern sits the same way
// in every swatch. The vertex colour tints both tones (white leaves them as
// they are). Edges are anti-aliased the same way as round_rect's.
void main() {
    float dist = shapeDistance();
    float aa = max(fwidth(dist), 1e-4);
    float coverage = clamp(0.5 - dist / aa, 0.0, 1.0);
    if (coverage <= 0.0) discard;

    vec2 p = localPos + halfSize;
    float cell = max(edgeWidth, 1.0);
    float parity = mod(floor(p.x / cell) + floor(p.y / cell), 2.0);
    vec3 rgb = mix(vec3(0.8), vec3(0.533), parity);
    vec4 color = vec4(rgb * vertexColor.rgb, vertexColor.a) * ColorModulator;

    fragColor = vec4(color.rgb, color.a * coverage);
}
