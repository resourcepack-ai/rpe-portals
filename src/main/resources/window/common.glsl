// ---- Portals: the window maps ----
// A portal's window is a stack of maps in invisible item frames at the ring.
// Every item-frame map is drawn by the text pipeline with its own 128x128
// texture as Sampler0, so these shaders can read a map whole. Each map holds
// two horizontal layers of the place on the other side (64 across, 63 deep,
// side by side), a header row (which layers, where the portal is, how open it
// is, and a table of materials), and below that up to 32 of the real block
// textures those layers use. Every pixel is a SYMBOL 0..240, each a map colour
// of its own: a texel, a number in the header, or a cell's material and light.

const uint SPW_HASH[1024] = uint[](@HASH@);
const uint SPW_HASH_MUL = @HASH_MUL@u;
const int SPW_HASH_PROBES = @HASH_PROBES@;
const int SPW_SYM[256] = int[](@SYMS@);
const int SPW_RGB[256] = int[](@RGBS@);
const int SPW_MARK0 = @MARK0@;
const int SPW_MARK1 = @MARK1@;

const int SPW_W = 128;
const int SPW_D = 63;
const int SPW_LAYERS = 1;
const int SPW_TABLE = 16;
// The far side's dimension, its fog colour (three fields) and this side's dimension.
const int SPW_ENV = 122;
// The far side's block (x and z, each four digits, modulo 2^28) and its facing.
const int SPW_FAR = 111;
// How many of the material table's entries, from the first, are full blocks.
const int SPW_OCCLUDERS = 120;
// The cloud map's own fields: whether it holds clouds, where the far side is, the clouds' clock.
const int CLOUD_FIELDS = 16;
const int SPW_CLEAR = 240;
const int SPW_BASE = @BASE@;

// The map colour id of a texel, or -1 for one that is not a map colour: a
// hash table lookup, usually one read.
int spw_id(vec4 texel) {
    ivec3 c = ivec3(round(texel.rgb * 255.0));
    uint key = uint((c.r << 16) | (c.g << 8) | c.b);
    uint h = (key * SPW_HASH_MUL) >> 22;
    for (int i = 0; i < SPW_HASH_PROBES; i++) {
        uint e = SPW_HASH[h];
        if (e == 0xFFFFFFFFu) return -1;
        if ((e >> 8) == key) return int(e & 255u);
        h = (h + 1u) & 1023u;
    }
    return -1;
}

int spw_sym(ivec2 p) {
    int id = spw_id(texelFetch(Sampler0, p, 0));
    return id < 0 ? -1 : SPW_SYM[id];
}

// The header row, under the two layers.
int spw_field(int i) {
    return spw_sym(ivec2(i, SPW_D));
}

bool spw_is_window_map() {
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) return false;
    return spw_id(texelFetch(Sampler0, ivec2(0, SPW_D), 0)) == SPW_MARK0
        && spw_id(texelFetch(Sampler0, ivec2(1, SPW_D), 0)) == SPW_MARK1;
}

vec3 spw_rgb(int id) {
    int c = SPW_RGB[id];
    return vec3(float((c >> 16) & 255), float((c >> 8) & 255), float(c & 255)) / 255.0;
}
