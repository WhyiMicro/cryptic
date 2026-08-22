#version 330

// Vanilla's outline shader, with one change: the alpha an entity was given is
// kept instead of being replaced by the render type's own.
//
// Nothing reads that alpha in vanilla, which is why Cryptic can put the Better
// Glow fill strength in it and have the outline pass pick it up per entity. An
// entity nobody has touched still arrives fully opaque, so this behaves exactly
// as it always did until Cryptic writes something else.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = vec4(ColorModulator.rgb * vertexColor.rgb, ColorModulator.a * vertexColor.a);
}
