#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#ifdef IS_GUI
#include <minecraft:globals.glsl>
#endif
#ifdef VULKAN
#define gl_VertexID gl_VertexIndex
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    vec3 pos = Position;
#ifdef IS_GUI
    vec2 ui = round(2.0 / vec2(ProjMat[0][0], -ProjMat[1][1]));
    // Only our encoded bitmap ascents in the GUI projection are moved.
    if (pos.y >= ui.y && ProjMat[3].x == -1.0) {
        int id = (int(pos.y) >> 13) - 1024;
        if (id >= 300 && id <= 305) {
            float guiScale = max(1.0, ScreenSize.y / ui.y);
            float factor = min(clamp(ScreenSize.y / 720.0, 1.0, 1.5) / guiScale,
                max(0.0, min((ui.x - 16.0) / 360.0, (ui.y - 108.0) / 84.0)));
            float corner = float(((gl_VertexID + 1) % 4) / 2);
            pos.x = ui.x * 0.5 + (pos.x - ui.x * 0.5) * factor;
            pos.y = ui.y - 78.0 - (float(id - 300) * 14.0 + (1.0 - corner) * 12.0) * factor;
        }
    }
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;
}
