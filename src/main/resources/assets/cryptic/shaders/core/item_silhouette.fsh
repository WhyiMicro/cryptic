#version 330

#moj_import <minecraft:fog.glsl>

// The held item's shape in one flat colour: its texture only decides where the
// item is, as the cutout does for the item itself. Item Outline draws a few
// shifted copies of it behind the item to make the Sharp outline.

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (texture(Sampler0, texCoord0).a < 0.1) {
        discard;
    }
    float fade = 1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    fragColor = vec4(vertexColor.rgb, vertexColor.a * fade);
}
