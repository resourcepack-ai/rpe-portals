#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;

in vec3 spwPos;
flat in float spwOn;
flat in vec3 spwCentre;
flat in vec3 spwAcross;
flat in vec3 spwFront;
flat in float spwRadius;
flat in float spwSlab;
flat in float spwGridY;
flat in vec3 spwEye;
flat in float spwReversed;
flat in float spwFloorY;
flat in float spwEnv;
flat in float spwNearEnv;
flat in vec3 spwFog;
flat in ivec2 spwFarBlock;
flat in float spwFarFacing;

out vec4 fragColor;

@COMMON@

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)

// Shapes, as the plugin's BlockLooks numbers them.
const int SH_CUBE = 0;
const int SH_CUTOUT = 1;
const int SH_CROSS = 2;
const int SH_LIQUID = 3;
const int SH_SLAB_BOTTOM = 4;
const int SH_SLAB_TOP = 5;
const int SH_VINE = 6;
const int SH_THIN = 7;

// A texel of one of this map's textures: YCbCr, the chroma shared by each 2x2
// block and spelt by its four digits. Alpha 0 where the texture is see-through.
vec4 spw_texel(int tile, vec2 uv) {
    ivec2 t = ivec2(clamp(floor(uv * 16.0), 0.0, 15.0));
    ivec2 o = ivec2((tile % 8) * 16, 64 + (tile / 8) * 16);
    int s = spw_sym(o + t);
    // Luma 0 is a see-through texel.
    if (s < 8 || s >= SPW_CLEAR) return vec4(0.0);
    ivec2 b = o + ivec2(t.x - t.x % 2, t.y - t.y % 2);
    int d0 = spw_sym(b) % 8;
    int d1 = spw_sym(b + ivec2(1, 0)) % 8;
    int d2 = spw_sym(b + ivec2(0, 1)) % 8;
    int d3 = spw_sym(b + ivec2(1, 1)) % 8;
    float y = float(s / 8 - 1) / 28.0;
    float cb = float(d0 * 8 + d1) / 63.0 - 0.5;
    float cr = float(d2 * 8 + d3) / 63.0 - 0.5;
    vec3 rgb = vec3(y + 1.402 * cr, y - 0.344136 * cb - 0.714136 * cr, y + 1.772 * cb);
    return vec4(clamp(rgb, 0.0, 1.0), 1.0);
}

// The far side's own compass: its facing, and across it.
ivec2 spw_far_dir(int k) {
    ivec2 dirs[4] = ivec2[](ivec2(0, 1), ivec2(-1, 0), ivec2(0, -1), ivec2(1, 0));
    return dirs[((int(spwFarFacing + 0.5) + k) % 4 + 4) % 4];
}

// A direction in the window's grid (x across, z away) as the far world has it.
// The grid's x is the far side's across reversed, its z the far side's facing.
vec3 spw_far_world(vec3 g) {
    vec2 fn = vec2(spw_far_dir(0));
    vec2 fs = vec2(spw_far_dir(1));
    vec2 w = -g.x * fs + g.z * fn;
    return vec3(w.x, g.y, w.y);
}

// The far world's block a grid cell is, in x and z, modulo 2^28: exactly the
// block the plugin read for it.
ivec2 spw_far_block(ivec3 cell) {
    ivec2 n = spw_far_dir(0);
    ivec2 s = spw_far_dir(1);
    int ia = cell.x - SPW_W / 2;
    float c = float(cell.z) + 0.5;
    float fx = n.x != 0 ? 0.0 : 0.5;
    float fz = n.y != 0 ? 0.0 : 0.5;
    int x = spwFarBlock.x + int(floor(fx - float(ia * s.x) + c * float(n.x)));
    int z = spwFarBlock.y + int(floor(fz - float(ia * s.y) + c * float(n.y)));
    return ivec2(x, z);
}

// How far the game nudges a plant off the middle of its block, in the grid:
// the game's hash of the block's position (Mth.getSeed at y 0). Only bits 16 to
// 27 of its 64-bit product are used, and those depend only on the low 28 bits
// of everything, so 32-bit unsigned arithmetic gives exactly the game's answer.
vec3 spw_plant_offset(ivec3 cell, int code) {
    if (code == 0) return vec3(0.0);
    ivec2 b = spw_far_block(cell);
    uint l = (uint(b.x) * 3129871u) ^ (uint(b.y) * 116129781u);
    l = l * l * 42317861u + l * 11u;
    l = l >> 16;
    float maxH = code == 3 ? 0.125 : 0.25;
    float ox = clamp((float(l & 15u) / 15.0 - 0.5) * 0.5, -maxH, maxH);
    float oy = code == 2 ? (float((l >> 4) & 15u) / 15.0 - 1.0) * 0.2 : 0.0;
    float oz = clamp((float((l >> 8) & 15u) / 15.0 - 0.5) * 0.5, -maxH, maxH);
    vec2 fn = vec2(spw_far_dir(0));
    vec2 fs = vec2(spw_far_dir(1));
    return vec3(-dot(vec2(ox, oz), fs), oy, dot(vec2(ox, oz), fn));
}

// Whether a grid cell holds a full block, for ambient occlusion: in this map's
// two layers by its material's number (the full blocks come first), above and
// below them from the occlusion bits. One texel and one hash read either way.
bool spw_occ(ivec3 c, int slab, int occluders) {
    if (c.x < 0 || c.x >= SPW_W || c.z < 0 || c.z >= SPW_D) return false;
    int q = c.y - slab * SPW_LAYERS;
    if (q >= 0 && q < SPW_LAYERS) {
        vec4 texel = texelFetch(Sampler0, ivec2(c.x + q * SPW_W, c.z), 0);
        if (texel.a < 0.5) return false;
        int id = spw_id(texel);
        int s = id < 0 ? -1 : SPW_SYM[id];
        return s >= 0 && s < SPW_CLEAR && s / 8 < occluders;
    }
    if (q == -1 || q == SPW_LAYERS) {
        // The bits cover 80 across the middle and 40 deep, as far as occlusion is drawn.
        int ox = c.x - (SPW_W - 80) / 2;
        if (ox < 0 || ox >= 80 || c.z >= 40) return false;
        int idx = (q == -1 ? 0 : 80 * 40) + c.z * 80 + ox;
        int p = idx / 7;
        int s = spw_sym(ivec2(48 + p % 80, 112 + p / 80));
        if (s < 0 || s >= 128) return false;
        return ((s >> (idx - p * 7)) & 1) == 1;
    }
    return false;
}

// The game's smooth-lighting ambient occlusion on a face: each corner darkened
// by the full blocks beside it in the layer the face looks out on (a full
// block counts 0.2, open space 1.0, four averaged, the corner counting as
// blocked when both sides are), blended across the face.
float spw_ao(ivec3 cell, int axis, int sgn, vec3 hq, int slab, int occluders) {
    ivec3 outCell = cell;
    outCell[axis] += sgn;
    int ua = axis == 0 ? 1 : 0;
    int va = axis == 2 ? 1 : 2;
    ivec3 du = ivec3(0);
    du[ua] = 1;
    ivec3 dv = ivec3(0);
    dv[va] = 1;
    float sL = spw_occ(outCell - du, slab, occluders) ? 1.0 : 0.0;
    float sR = spw_occ(outCell + du, slab, occluders) ? 1.0 : 0.0;
    float sD = spw_occ(outCell - dv, slab, occluders) ? 1.0 : 0.0;
    float sU = spw_occ(outCell + dv, slab, occluders) ? 1.0 : 0.0;
    float cLD = (sL * sD > 0.5 || spw_occ(outCell - du - dv, slab, occluders)) ? 1.0 : 0.0;
    float cRD = (sR * sD > 0.5 || spw_occ(outCell + du - dv, slab, occluders)) ? 1.0 : 0.0;
    float cLU = (sL * sU > 0.5 || spw_occ(outCell - du + dv, slab, occluders)) ? 1.0 : 0.0;
    float cRU = (sR * sU > 0.5 || spw_occ(outCell + du + dv, slab, occluders)) ? 1.0 : 0.0;
    float v00 = (4.0 - 0.8 * (sL + sD + cLD)) / 4.0;
    float v10 = (4.0 - 0.8 * (sR + sD + cRD)) / 4.0;
    float v01 = (4.0 - 0.8 * (sL + sU + cLU)) / 4.0;
    float v11 = (4.0 - 0.8 * (sR + sU + cRU)) / 4.0;
    float fu = clamp(hq[ua], 0.0, 1.0);
    float fv = clamp(hq[va], 0.0, 1.0);
    return mix(mix(v00, v10, fu), mix(v01, v11, fu), fv);
}

// The face shading the game gives a face, by which way it faces.
float spw_face_light(vec3 n) {
    if (n.y > 0.5) return 1.0;
    if (n.y < -0.5) return 0.5;
    return abs(n.z) > 0.5 ? 0.8 : 0.6;
}

// The far side's dimension: 0 overworld, 1 Nether, 2 End.
int spw_env() {
    return int(spwEnv + 0.5);
}

// Whether the far side is lit and fogged the way the viewer's own world is:
// both in the overworld, so its daylight and sky are the viewer's.
bool spw_same_sky() {
    return spw_env() == 0 && spwNearEnv < 0.5;
}

// A light level 0..15 as the game brightens it. Under the Nether's and the
// End's roofs there is no sky light, but the game lights them with an ambient
// glow and the brightness setting lifts the dark: without that the Nether is
// black.
float spw_brightness(float level) {
    float b = level / 15.0;
    b = b / (4.0 - 3.0 * b);
    int env = spw_env();
    // The game's lightmap lifts its curve by the brightness setting, half way to
    // 1 - (1 - b)^4 at the default; without it shade under a tree reads as night.
    // Squared twice rather than pow(): pow of the tiny negative a level of 15
    // rounds to is not a number on some drivers, and draws black.
    float dark = clamp(1.0 - b, 0.0, 1.0);
    dark *= dark;
    if (env == 0) return mix(b, 1.0 - dark * dark, 0.5);
    float ambient = env == 1 ? 0.1 : 0.3;
    b = ambient + (1.0 - ambient) * b;
    if (env == 1) b = mix(b, 1.0, 0.25);
    float dim = clamp(1.0 - b, 0.0, 1.0);
    dim *= dim;
    float lifted = 1.0 - dim * dim;
    return mix(b, lifted, 0.5);
}

// The light where the portal stands, which dims the window at night: only when
// the far side shares the viewer's sky.
float spw_day() {
    if (!spw_same_sky()) return 1.0;
    return max(dot(vertexColor.rgb, vec3(0.299, 0.587, 0.114)), 0.15);
}

// The overworld's fog at noon, for a far overworld seen from somewhere else.
const vec3 SPW_DAY_FOG = vec3(0.753, 0.847, 1.0);

vec3 spw_fog_colour() {
    if (spw_same_sky()) return FogColor.rgb;
    return spw_env() == 0 ? SPW_DAY_FOG : spwFog;
}

// Fog as the far side's own world draws it: the Nether's is thick, close in and
// the colour of its biome.
vec3 spw_fog(vec3 c, float dist) {
    if (spw_same_sky()) {
        return apply_fog(vec4(c, 1.0), dist, dist, FogEnvironmentalStart, FogEnvironmentalEnd,
                FogRenderDistanceStart, FogRenderDistanceEnd, FogColor).rgb;
    }
    float reach = FogRenderDistanceEnd;
    float start = 1e6;
    float end = 1e6 + 1.0;
    if (spw_env() == 1) {
        start = reach * 0.05;
        end = min(reach, 192.0) * 0.5;
    }
    return apply_fog(vec4(c, 1.0), dist, dist, start, end,
            FogRenderDistanceStart, FogRenderDistanceEnd, vec4(spw_fog_colour(), 1.0)).rgb;
}

// The sky as the game paints it: a disc 16 blocks overhead, 512 across, in the
// biome's sky colour, fogged by how far each point of it is (sky.fsh with the
// distances its vertices carry, centre 16 and rim 512). Below the rim, the fog.
vec3 spw_sky(vec3 d) {
    vec3 fogc = spw_fog_colour();
    // No sky under a roof, or in the End's void: only the fog.
    if (spw_env() != 0) return fogc;
    if (d.y <= 1e-4) return fogc;
    // Daylight, read off the fog: the game scales its fog by the same daylight.
    float day = spw_same_sky() ? clamp((FogColor.b - 0.06) / 0.94, 0.0, 1.0) : 1.0;
    vec3 skyc = spwFog * day;
    float k = 16.0 * length(d.xz) / d.y / 512.0;
    if (k >= 1.0) return fogc;
    float skyEnd = spw_same_sky() ? max(FogSkyEnd, 1.0) : 192.0;
    float sph = mix(16.0, 512.25, k);
    float cyl = mix(16.0, 512.0, k);
    float f = max(clamp(sph / skyEnd, 0.0, 1.0), cyl >= skyEnd ? 1.0 : 0.0);
    return mix(skyc, fogc, f);
}

// Where a ray enters a box inside a cell, between t0 and t1, and through which
// axis: -1 when it misses.
float spw_box(vec3 o, vec3 rd, vec3 inv, vec3 lo, vec3 hi, float t0, float t1, out int axis) {
    vec3 ta = (lo - o) * inv;
    vec3 tb = (hi - o) * inv;
    vec3 tl = min(ta, tb);
    vec3 th = max(ta, tb);
    float tin = max(max(tl.x, tl.y), tl.z);
    float tout = min(min(th.x, th.y), th.z);
    axis = tin == tl.x ? 0 : (tin == tl.y ? 1 : 2);
    if (tout < tin || tout < t0 || tin > t1) return -1.0;
    return max(tin, t0);
}

// The texture coordinates on a face of a cell, at a point in the cell.
vec2 spw_face_uv(int axis, vec3 hp) {
    if (axis == 1) return hp.xz;
    if (axis == 0) return vec2(hp.z, 1.0 - hp.y);
    return vec2(hp.x, 1.0 - hp.y);
}

// How far in front of the plane a fragment is written, by how far away what it
// shows is: every map of the window draws at the plane, so this is what sorts
// them. Logarithmic, so the detailed part, the far land, the treetops and the
// clouds all keep their order out to the clouds' distance.
void spw_write_depth(float t) {
    float k = 4e-4 * (1.0 - clamp(log(1.0 + max(t, 0.0)) / log(4001.0), 0.0, 1.0));
    gl_FragDepth = spwReversed > 0.5 ? min(gl_FragCoord.z * (1.0 + k), 1.0) : gl_FragCoord.z * (1.0 - k);
}

// The land beyond the detailed part, as columns two blocks square, marched from
// where a ray leaves the detailed part. The horizon map holds the ground; the
// canopy map holds treetops, drawn as slabs three blocks thick floating over
// it, which is what a forest is from far off. A miss is w < 0.
const float H_CELL = 3.0;
const int H_ACROSS = 64;
const int H_DEPTH = 127;
const int H_BIAS = 60;
const float CANOPY_THICK = 3.0;

vec4 spw_columns(vec3 o, vec3 rd, vec3 inv, bool canopy) {
    // In blocks: X across from the horizon's edge, Y up from the grid's floor, Z away.
    vec3 h = vec3(o.x - float(SPW_W / 2) + float(H_ACROSS) * H_CELL * 0.5, o.y, o.z);
    // Leave the detailed part first: it draws itself.
    vec3 lo = vec3(float(H_ACROSS) * H_CELL * 0.5 - float(SPW_W / 2), 0.0, 0.0);
    vec3 hi = lo + vec3(float(SPW_W), float(spw_field(3) * SPW_LAYERS), float(SPW_D));
    vec3 ta = (lo - h) * inv;
    vec3 tb = (hi - h) * inv;
    float t = max(min(min(max(ta.x, tb.x), max(ta.y, tb.y)), max(ta.z, tb.z)), 0.0);
    float tEnd = 600.0;
    vec3 p = h + rd * (t + 1e-3);
    ivec2 cell = ivec2(floor(p.xz / H_CELL));
    ivec2 stp = ivec2(sign(rd.xz));
    vec2 tDelta = abs(inv.xz) * H_CELL;
    vec2 tNext = ((vec2(cell) + max(vec2(stp), vec2(0.0))) * H_CELL - h.xz) * inv.xz;
    int axis = 1;
    for (int i = 0; i < 200; i++) {
        if (t > tEnd) break;
        if (cell.x < 0 || cell.x >= H_ACROSS || cell.y < 0 || cell.y >= H_DEPTH) {
            // Off the sides: nothing more to find unless it is heading back in.
            if ((cell.x < 0 && stp.x <= 0) || (cell.x >= H_ACROSS && stp.x >= 0) || cell.y >= H_DEPTH || (cell.y < 0 && stp.y <= 0)) break;
        } else {
            int row = cell.y < SPW_D ? cell.y : cell.y + 1;
            int s = spw_sym(ivec2(cell.x, row));
            if (s >= 0 && s < 199) {
                float top = float(s - H_BIAS + 1);
                float bottom = canopy ? top - CANOPY_THICK : -1e4;
                float tOut = min(tNext.x, tNext.y);
                float yIn = h.y + rd.y * t;
                float yOut = h.y + rd.y * tOut;
                if (min(yIn, yOut) <= top && max(yIn, yOut) >= bottom) {
                    // In through a side, down through the top, or up through the underside.
                    float th = t;
                    int face = 0;
                    if (yIn > top) {
                        th = (top - h.y) / rd.y;
                        face = 1;
                    } else if (yIn < bottom) {
                        th = (bottom - h.y) / rd.y;
                        face = 2;
                    }
                    vec4 tc = texelFetch(Sampler0, ivec2(64 + cell.x, row), 0);
                    int cid = spw_id(tc);
                    // Leaves are the canopy map's; water is the water map colour (base 12).
                    int kind = canopy ? 1 : (cid >= 0 && cid / 4 == 12 ? 0 : 2);
                    vec3 c = tc.rgb;
                    // A side's shade is by the way it faces in the far world: east and west 0.6.
                    bool acrossIsX = spw_far_dir(1).x != 0;
                    float shade = face == 1 ? 1.0 : face == 2 ? 0.5 : ((axis == 0) == acrossIsX ? 0.6 : 0.8);
                    // Texture from afar: a sixteenth-of-a-block speckle on every block of
                    // the column, the way a block's own texture reads at a distance.
                    vec3 hp = h + rd * th;
                    vec3 px = floor(hp * 16.0);
                    float n = fract(sin(dot(px.xz + px.y * 3.1, vec2(12.9898, 78.233))) * 43758.5453);
                    float blockN = fract(sin(dot(floor(hp.xz) + floor(hp.y) * 7.0, vec2(4.898, 7.23))) * 23421.631);
                    if (kind == 1) {
                        // Leaves: darker, holed and mottled.
                        c *= (0.62 + 0.38 * n) * (0.9 + 0.2 * blockN);
                    } else if (kind == 0) {
                        // Water: flat and bright, a ripple across it.
                        c *= 0.92 + 0.08 * sin(hp.x * 1.7 + hp.z * 1.3 + GameTime * 24000.0 * 0.1);
                    } else {
                        c *= (0.84 + 0.16 * n) * (0.94 + 0.08 * blockN);
                    }
                    return vec4(c * shade, th);
                }
            }
        }
        if (tNext.x < tNext.y) {
            t = tNext.x;
            tNext.x += tDelta.x;
            cell.x += stp.x;
            axis = 0;
        } else {
            t = tNext.y;
            tNext.y += tDelta.y;
            cell.y += stp.y;
            axis = 2;
        }
    }
    return vec4(0.0, 0.0, 0.0, -1.0);
}

// The game's own clouds, from its own texture (one bit a 12-block cell, seven
// to a pixel of the cloud map), placed on the far world exactly as the game
// places them: drifting 0.03 blocks a tick east, nudged 3.96 blocks south, four
// blocks thick, each face shaded as the game shades it. Back with w < 0 for a
// ray that meets none.
const float CLOUD_BOTTOM = 192.33;
const float CLOUD_TOP = 196.33;
const float CLOUD_REACH = 2000.0;

bool spw_cloud_at(vec2 cell) {
    ivec2 tx = ivec2(mod(cell, 256.0));
    int idx = tx.y * 256 + tx.x;
    int px = idx / 7;
    int row = px / 128;
    if (row >= SPW_D) row += 1;
    int s = spw_sym(ivec2(px - (px / 128) * 128, row));
    if (s < 0 || s >= 128) return false;
    int bit = idx - px * 7;
    return ((s >> bit) & 1) == 1;
}

vec4 spw_clouds(vec3 o, vec3 rd) {
    if (spw_field(CLOUD_FIELDS) != 1) return vec4(0.0, 0.0, 0.0, -1.0);
    vec2 farXZ = vec2(float(spw_field(CLOUD_FIELDS + 1) * 200 + spw_field(CLOUD_FIELDS + 2)),
            float(spw_field(CLOUD_FIELDS + 3) * 200 + spw_field(CLOUD_FIELDS + 4)));
    int ff = spw_field(CLOUD_FIELDS + 5);
    float farFloor = float(spw_field(CLOUD_FIELDS + 6) * 200 + spw_field(CLOUD_FIELDS + 7) - 1000);
    float ticks = float(spw_field(CLOUD_FIELDS + 8)) * 40000.0 + float(spw_field(CLOUD_FIELDS + 9)) * 200.0
            + float(spw_field(CLOUD_FIELDS + 10));
    ticks = mod(ticks + GameTime * 24000.0, 102400.0);
    ivec2 dirs[4] = ivec2[](ivec2(0, 1), ivec2(-1, 0), ivec2(0, -1), ivec2(1, 0));
    vec2 n = vec2(dirs[ff % 4]);
    vec2 sx = vec2(dirs[(ff + 1) % 4]);
    vec2 mid = farXZ + vec2(n.x != 0.0 ? 0.0 : 0.5, n.y != 0.0 ? 0.0 : 0.5);
    // The ray in the far world: a grid point (a across, c out) is mid - a*s + c*n.
    float a0 = o.x - 0.5 - float(SPW_W / 2);
    vec3 wo = vec3(mid - a0 * sx + o.z * n, o.y + farFloor).xzy;
    vec3 wd = vec3(-rd.x * sx + rd.z * n, rd.y).xzy;
    if (abs(wd.y) < 1e-5) return vec4(0.0, 0.0, 0.0, -1.0);
    float t0 = (CLOUD_BOTTOM - wo.y) / wd.y;
    float t1 = (CLOUD_TOP - wo.y) / wd.y;
    float tIn = max(min(t0, t1), 0.0);
    float tOut = min(max(t0, t1), CLOUD_REACH);
    if (tOut <= tIn) return vec4(0.0, 0.0, 0.0, -1.0);
    // Cloud space: the texture's x runs east with the drift, its y south.
    vec2 shift = vec2(ticks * 0.030000001, 3.96);
    vec2 cp = wo.xz + wd.xz * tIn + shift;
    vec2 cell = floor(cp / 12.0);
    vec2 stp = sign(wd.xz);
    vec2 invd = 1.0 / vec2(abs(wd.x) < 1e-6 ? 1e-6 : wd.x, abs(wd.z) < 1e-6 ? 1e-6 : wd.z);
    vec2 tDelta = abs(invd) * 12.0;
    vec2 tNext = tIn + ((cell + max(stp, vec2(0.0))) * 12.0 - cp) * invd;
    float t = tIn;
    int face = wo.y < CLOUD_BOTTOM ? 0 : (wo.y > CLOUD_TOP ? 1 : 2);
    for (int i = 0; i < 96; i++) {
        if (t > tOut) break;
        if (spw_cloud_at(cell)) {
            // Below 0.7, above 1.0, east and west 0.9, north and south 0.8.
            float shade = face == 0 ? 0.7 : face == 1 ? 1.0 : face == 3 ? 0.9 : face == 4 ? 0.8 : 1.0;
            return vec4(vec3(shade), t);
        }
        if (tNext.x < tNext.y) {
            t = tNext.x;
            tNext.x += tDelta.x;
            cell.x += stp.x;
            face = 3;
        } else {
            t = tNext.y;
            tNext.y += tDelta.y;
            cell.y += stp.y;
            face = 4;
        }
    }
    return vec4(0.0, 0.0, 0.0, -1.0);
}

// The window: march this map's two layers of the far side, from the plane.
void spw_window() {
    vec3 E = spwEye;
    vec3 up = vec3(0.0, 1.0, 0.0);
    float eyeIn = dot(E - spwCentre, spwFront);
    if (eyeIn <= 0.0) discard;
    // The quad hangs a sliver in front of the portal's plane: where this
    // fragment's line of sight meets the plane itself.
    vec3 toQuad = spwPos - E;
    float along = dot(toQuad, spwFront);
    if (along >= 0.0) discard;
    vec3 P = E + toQuad * (eyeIn / -along);
    vec3 rel = P - spwCentre;
    float a = dot(rel, spwAcross);
    float b = rel.y;
    if (a * a + b * b > spwRadius * spwRadius) discard;
    vec3 d = normalize(P - E);
    float lead = length(P - E);

    // The far side as a grid: x across, y up from the grid's floor, z away into it.
    vec3 o = vec3(a + 0.5 + float(SPW_W / 2), P.y - spwGridY, 0.0);
    vec3 rd = vec3(dot(d, spwAcross), d.y, -dot(d, spwFront));
    int slab = int(spwSlab + 0.5);
    vec3 inv = 1.0 / vec3(abs(rd.x) < 1e-6 ? 1e-6 : rd.x, abs(rd.y) < 1e-6 ? 1e-6 : rd.y, abs(rd.z) < 1e-6 ? 1e-6 : rd.z);
    int voxelMaps = spw_field(3);
    if (slab >= voxelMaps) {
        if (slab == voxelMaps + 2) {
            // The clouds, over the sky: the game draws them at 0.8 opacity.
            if (spw_env() != 0) discard;
            vec4 cl = spw_clouds(o, rd);
            if (cl.w < 0.0) discard;
            float light = clamp(dot(spw_fog_colour(), vec3(0.299, 0.587, 0.114)) * 1.25, 0.2, 1.0);
            vec3 sky = spw_sky(d);
            vec3 c = mix(sky, cl.rgb * light, 0.8 * (1.0 - smoothstep(CLOUD_REACH * 0.6, CLOUD_REACH, cl.w)));
            fragColor = vec4(c, 1.0);
            spw_write_depth(cl.w);
            return;
        }
        bool canopy = slab == voxelMaps + 1;
        vec4 hz = spw_columns(o, rd, inv, canopy);
        if (hz.w < 0.0) {
            if (canopy) discard;
            // Nothing in the way: the sky, behind everything.
            fragColor = vec4(spw_sky(d), 1.0);
            spw_write_depth(1e6);
            return;
        }
        vec3 c = hz.rgb * spw_day() * (spw_env() == 0 ? 1.0 : spw_brightness(0.0));
        c = spw_fog(c, lead + hz.w);
        fragColor = vec4(c, 1.0);
        spw_write_depth(hz.w);
        return;
    }
    float y0 = float(slab * SPW_LAYERS);
    vec3 bmin = vec3(0.0, y0, 0.0);
    vec3 bmax = vec3(float(SPW_W), y0 + float(SPW_LAYERS), float(SPW_D));
    vec3 ta = (bmin - o) * inv;
    vec3 tb = (bmax - o) * inv;
    vec3 tlo = min(ta, tb);
    vec3 thi = max(ta, tb);
    float tmin = max(max(tlo.x, tlo.y), max(tlo.z, 0.0));
    float tmax = min(min(thi.x, thi.y), thi.z);

    bool hit = false;
    float tHit = 0.0;
    vec3 col = vec3(0.0);
    if (tmax > tmin) {
        vec3 p = o + rd * (tmin + 1e-4);
        ivec3 cell = ivec3(floor(p));
        cell = clamp(cell, ivec3(0, slab * SPW_LAYERS, 0), ivec3(SPW_W - 1, slab * SPW_LAYERS + SPW_LAYERS - 1, SPW_D - 1));
        ivec3 stp = ivec3(sign(rd));
        vec3 tDelta = abs(inv);
        vec3 tNext = (vec3(cell) + max(vec3(stp), vec3(0.0)) - o) * inv;
        float t = tmin;
        for (int i = 0; i < 200; i++) {
            if (cell.x < 0 || cell.x >= SPW_W || cell.z < 0 || cell.z >= SPW_D
                    || cell.y < slab * SPW_LAYERS || cell.y >= slab * SPW_LAYERS + SPW_LAYERS) break;
            int q = cell.y - slab * SPW_LAYERS;
            vec4 texel = texelFetch(Sampler0, ivec2(cell.x + q * SPW_W, cell.z), 0);
            float tOut = min(tNext.x, min(tNext.y, tNext.z));
            if (texel.a > 0.5) {
                int id = spw_id(texel);
                int s = id < 0 ? -1 : SPW_SYM[id];
                if (s >= 0 && s < SPW_CLEAR) {
                    int m = s / 8;
                    float level = float(s % 8) * 2.0 + 1.0;
                    int at = SPW_TABLE + m * 5;
                    int shape = spw_field(at);
                    int top = spw_field(at + 1);
                    int side = spw_field(at + 2);
                    int bottom = spw_field(at + 3);
                    int flags = spw_field(at + 4);
                    int logAxis = flags / 16;
                    int vine = flags % 16;
                    vec3 cellO = o - vec3(cell);

                    float tf = -1.0;
                    int fAxisOut = 1;
                    vec2 uv = vec2(0.0);
                    int tile = side;
                    vec3 nl = vec3(0.0, 1.0, 0.0);
                    if (shape == SH_CROSS) {
                        // Two planes corner to corner, as the game draws a plant, nudged off
                        // the middle of the block exactly as the game nudges it.
                        vec3 plantO = cellO - spw_plant_offset(cell, vine);
                        for (int pl = 0; pl < 2; pl++) {
                            float den = pl == 0 ? rd.x - rd.z : rd.x + rd.z;
                            if (abs(den) < 1e-5) continue;
                            float tp = pl == 0 ? -(plantO.x - plantO.z) / den : (1.0 - plantO.x - plantO.z) / den;
                            if (tp < max(t - 0.6, tmin) || tp > tOut + 0.6 || (tf >= 0.0 && tp > tf)) continue;
                            vec3 hq = plantO + rd * tp;
                            if (hq.y < 0.0 || hq.y > 1.0 || hq.x < 0.0 || hq.x > 1.0) continue;
                            vec2 puv = vec2(hq.x, 1.0 - hq.y);
                            if (spw_texel(side, puv).a < 0.5) continue;
                            tf = tp;
                            uv = puv;
                        }
                    } else if (shape == SH_VINE) {
                        // A sheet just inside each wall it climbs.
                        for (int f = 0; f < 4; f++) {
                            if ((vine & (1 << f)) == 0) continue;
                            int ax = f < 2 ? 0 : 2;
                            float plane = (f == 0 || f == 2) ? 0.95 : 0.05;
                            float den = rd[ax];
                            if (abs(den) < 1e-5) continue;
                            float tp = (plane - cellO[ax]) / den;
                            if (tp < t || tp > tOut || (tf >= 0.0 && tp > tf)) continue;
                            vec3 hq = cellO + rd * tp;
                            if (hq.y < 0.0 || hq.y > 1.0) continue;
                            vec2 puv = ax == 0 ? vec2(hq.z, 1.0 - hq.y) : vec2(hq.x, 1.0 - hq.y);
                            if (spw_texel(side, puv).a < 0.5) continue;
                            tf = tp;
                            uv = puv;
                            nl = vec3(0.0);
                            nl[ax] = -sign(den);
                        }
                    } else {
                        vec3 lo = vec3(0.0);
                        vec3 hi = vec3(1.0);
                        if (shape == SH_SLAB_BOTTOM) hi.y = 0.5;
                        if (shape == SH_SLAB_TOP) lo.y = 0.5;
                        if (shape == SH_THIN) hi.y = 0.0625;
                        if (shape == SH_LIQUID) hi.y = 0.875;
                        int fAxis = 1;
                        tf = spw_box(cellO, rd, inv, lo, hi, t, tOut, fAxis);
                        fAxisOut = fAxis;
                        if (tf >= 0.0) {
                            vec3 hq = cellO + rd * tf;
                            nl = vec3(0.0);
                            nl[fAxis] = -float(stp[fAxis]);
                            uv = spw_face_uv(fAxis, hq);
                            // Which texture: the ends of a log are its top texture.
                            int endAxis = logAxis == 1 ? 0 : logAxis == 2 ? 2 : 1;
                            tile = fAxis == endAxis ? (nl[fAxis] > 0.0 ? top : bottom) : side;
                            if (shape == SH_CUTOUT && spw_texel(tile, uv).a < 0.5) {
                                // Through a gap in the near face to the inside of the far one, as
                                // the game draws leaves: that is most of what makes a canopy dense.
                                vec3 tlo2 = (lo - cellO) * inv;
                                vec3 thi2 = (hi - cellO) * inv;
                                vec3 tx2 = max(tlo2, thi2);
                                float tExit = min(min(tx2.x, tx2.y), tx2.z);
                                int bAxis = tExit == tx2.x ? 0 : (tExit == tx2.y ? 1 : 2);
                                vec3 hb = cellO + rd * tExit;
                                vec2 buv = spw_face_uv(bAxis, hb);
                                if (spw_texel(tile, buv).a >= 0.5) {
                                    tf = tExit;
                                    fAxisOut = bAxis;
                                    uv = buv;
                                    nl = vec3(0.0);
                                    nl[bAxis] = float(stp[bAxis]);
                                } else {
                                    tf = -1.0;
                                }
                            }
                        }
                    }

                    if (tf >= 0.0) {
                        // Shaded by the way the face looks in the far world, as it is there.
                        vec3 n = spw_far_world(nl);
                        vec4 tx = spw_texel(tile, uv);
                        float shade = (shape == SH_CROSS || shape == SH_VINE) ? 0.9 : spw_face_light(n);
                        // Leaves sit in each other's shade.
                        if (shape == SH_CUTOUT) shade *= 0.8;
                        // Ambient occlusion on full and part blocks near enough to see it.
                        float ao = 1.0;
                        if (tf < 40.0 && (shape == SH_CUBE || shape == SH_CUTOUT || shape == SH_SLAB_BOTTOM || shape == SH_SLAB_TOP || shape == SH_THIN)) {
                            vec3 hq = cellO + rd * tf;
                            float onFace = hq[fAxisOut];
                            if (onFace < 0.01 || onFace > 0.99) {
                                ao = mix(spw_ao(cell, fAxisOut, int(nl[fAxisOut]), hq, slab, spw_field(SPW_OCCLUDERS)), 1.0, smoothstep(28.0, 40.0, tf));
                            }
                        }
                        vec3 c = tx.rgb * shade * ao * (shape == SH_LIQUID ? 1.0 : spw_brightness(level));
                        if (shape == SH_LIQUID) {
                            // A water surface: the bottom seen through it, as deep as the
                            // cell says, darker and bluer the deeper it is.
                            float depth = float(s % 8);
                            vec3 water = tx.rgb * shade;
                            vec3 seen = water;
                            if (fAxisOut == 1 && rd.y < 0.0) {
                                float floorY = float(cell.y) - depth;
                                float tb = (floorY - o.y) / rd.y;
                                vec3 hb = o + rd * tb;
                                vec3 bottomCol = spw_texel(bottom, fract(hb.xz)).rgb;
                                float through = exp(-(depth + 0.875) * 0.45);
                                seen = mix(water * 0.85, bottomCol * vec3(0.8, 0.9, 1.0), through * 0.85);
                            }
                            c = seen;
                        }
                        hit = true;
                        tHit = tf;
                        col = c;
                        break;
                    }
                }
            }
            if (tNext.x < tNext.y && tNext.x < tNext.z) {
                t = tNext.x;
                tNext.x += tDelta.x;
                cell.x += stp.x;
            } else if (tNext.y < tNext.z) {
                t = tNext.y;
                tNext.y += tDelta.y;
                cell.y += stp.y;
            } else {
                t = tNext.z;
                tNext.z += tDelta.z;
                cell.z += stp.z;
            }
        }
    }

    if (!hit) {
        // The horizon map paints what is beyond, and the sky.
        discard;
    } else {
        // How light it is where the portal stands: day, night, the shade of a roof.
        col *= spw_day();
        col = spw_fog(col, lead + tHit);
    }
    fragColor = vec4(col, 1.0);
    // Just in front of the plane, so nothing behind the portal shows through it;
    // nearer for nearer things, so the maps sort among themselves.
    spw_write_depth(tHit);
}
#endif

void main() {
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    if (spwOn > 0.5) {
        spw_window();
        return;
    }
#endif
#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

#ifdef IS_SEE_THROUGH
    vec4 color = texColor * vertexColor;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif
    if (color.a < 0.1) {
        discard;
    }

#ifdef IS_SEE_THROUGH
    fragColor = color * ColorModulator;
#elif defined(IS_GUI)
    fragColor = color;
#else
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
    gl_FragDepth = gl_FragCoord.z;
}
