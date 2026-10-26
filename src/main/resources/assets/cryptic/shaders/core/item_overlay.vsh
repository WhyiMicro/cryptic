#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec2 itemCoord;

// UV0 is the item's own model space, one unit across the item, so the pattern
// stays on the item however it's held or swung, as the enchantment glint does.
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    itemCoord = UV0;
}
