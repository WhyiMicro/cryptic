#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <cryptic:round_rect.glsl>

out vec4 fragColor;

// A slice of a ring, for the menu's donut charts. halfSize carries the outer and
// inner radii; the slice runs clockwise from twelve o'clock, from and to a
// fraction of a turn sent as ten-thousandths in the radius bytes (round_rect's
// vertex shader splits each into two, so they are put back together here).
// Both the round edges and the cut ends are anti-aliased.
void main() {
    float r = length(localPos);
    float aa = max(fwidth(r), 1e-4);
    float ring = clamp(0.5 - (r - halfSize.x) / aa, 0.0, 1.0) * clamp(0.5 + (r - halfSize.y) / aa, 0.0, 1.0);
    if (ring <= 0.0) discard;

    float from = (radii.x + radii.y * 256.0) * 4.0 / 10000.0;
    float to = (radii.z + radii.w * 256.0) * 4.0 / 10000.0;
    float turn = atan(localPos.x, -localPos.y) / 6.28318530718;
    if (turn < 0.0) turn += 1.0;

    // How far inside the slice's ends this pixel is, along the arc, in pixels.
    float inside = min(turn - from, to - turn) * 6.28318530718 * r / aa;
    float ends = clamp(0.5 + inside, 0.0, 1.0);
    float coverage = ring * ends;
    if (coverage <= 0.0) discard;

    vec4 color = vertexColor * ColorModulator;
    fragColor = vec4(color.rgb, color.a * coverage);
}
